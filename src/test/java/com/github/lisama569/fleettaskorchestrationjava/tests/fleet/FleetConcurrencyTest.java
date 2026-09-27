package com.github.lisama569.fleettaskorchestrationjava.tests.fleet;

import com.github.lisama569.fleettaskorchestrationjava.fleet.config.FleetProperties;
import com.github.lisama569.fleettaskorchestrationjava.fleet.model.Robot;
import com.github.lisama569.fleettaskorchestrationjava.fleet.model.RobotStatus;
import com.github.lisama569.fleettaskorchestrationjava.fleet.model.SubmitResult;
import com.github.lisama569.fleettaskorchestrationjava.fleet.model.Task;
import com.github.lisama569.fleettaskorchestrationjava.fleet.model.TaskRequest;
import com.github.lisama569.fleettaskorchestrationjava.fleet.registry.InMemoryRobotRegistry;
import com.github.lisama569.fleettaskorchestrationjava.fleet.registry.RobotRegistry;
import com.github.lisama569.fleettaskorchestrationjava.fleet.service.FleetService;
import com.github.lisama569.fleettaskorchestrationjava.fleet.store.TaskStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** 并发：重复提交去重、并发分配互斥、提交/取消/回报混合下的归属一致性。 */
class FleetConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(FleetConcurrencyTest.class);

    private TaskStore store;
    private RobotRegistry registry;
    private TestSupport.NoopEngine engine;
    private FleetService fleet;
    private final AtomicLong clock = new AtomicLong(5_000L);

    @BeforeEach
    void setUp() {
        store = TestSupport.tempStore();
        registry = new InMemoryRobotRegistry();
        engine = new TestSupport.NoopEngine();
        FleetProperties props = TestSupport.props();
        fleet = TestSupport.newService(store, registry, engine, props(new FleetProperties()), clock);
    }

    private FleetProperties props(FleetProperties p) {
        p.setSchedulerEnabled(false);
        return p;
    }

    private TaskRequest task(String id) {
        return new TaskRequest(id, "LIFT", 50, 0, 2, 1000, false, 0);
    }

    @Test
    void concurrentDuplicateSubmissionProducesSingleTaskAndSingleDispatch() throws Exception {
        fleet.registerRobot("R1", Set.of("LIFT"), 5);
        int threads = 32;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger allocated = new AtomicInteger();
        AtomicInteger duplicates = new AtomicInteger();
        List<SubmitResult> results = new ArrayList<>(); // 加锁收集，避免列表本身干扰
        Object collectLock = new Object();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    SubmitResult r = fleet.submit(task("DUP-T"));
                    synchronized (collectLock) {
                        results.add(r);
                    }
                    if (r.allocated() && !r.duplicate()) {
                        allocated.incrementAndGet();
                    }
                    if (r.duplicate()) {
                        duplicates.incrementAndGet();
                    }
                } catch (Exception ignored) { }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        log.info("[用例输入] {} 个线程同时提交同一个 taskId=DUP-T；[判定] 任务总数={} 真正分配次数={} 幂等返回次数={} 引擎下发次数={}",
                threads, fleet.listTasks().size(), allocated.get(), duplicates.get(), engine.dispatchCount());
        assertThat(fleet.listTasks()).hasSize(1);
        assertThat(allocated.get()).isEqualTo(1);
        assertThat(duplicates.get()).isEqualTo(threads - 1);
        assertThat(engine.dispatchCount()).isEqualTo(1);
    }

    @Test
    void concurrentDistinctTasksNeverDoubleAssignRobot() throws Exception {
        // 4 台机器人，64 个并发任务，互斥约束下任何时刻 BUSY 机器人只持有一个任务，且活动任务归属唯一
        fleet.registerRobot("R1", Set.of("LIFT"), 5);
        fleet.registerRobot("R2", Set.of("LIFT"), 5);
        fleet.registerRobot("R3", Set.of("LIFT"), 5);
        fleet.registerRobot("R4", Set.of("LIFT"), 5);

        int n = 64;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        for (int i = 0; i < n; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    fleet.submit(task("CT-" + idx));
                } catch (Exception ignored) { }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        long active = fleet.listTasks().stream().filter(t -> t.getStatus().isActive()).count();
        long pending = fleet.listTasks().stream().filter(t -> t.getStatus().name().equals("PENDING")).count();
        log.info("[用例输入] {} 个并发不同任务抢 4 台机器人；[判定] 活动任务={} 排队={} 引擎下发次数={}",
                active, pending, engine.dispatchCount());

        // 不变量 1：活动任务数 == 被占用机器人数 == 引擎下发数
        long busyRobots = registry.all().stream().filter(r -> r.getStatus() == RobotStatus.BUSY).count();
        assertThat(active).isEqualTo(4);
        assertThat(busyRobots).isEqualTo(4);
        assertThat(engine.dispatchCount()).isEqualTo(4);
        assertThat(active + pending).isEqualTo(n);

        // 不变量 2：每个 BUSY 机器人的 currentTask 唯一，且该任务的 owner 指回它；任务不共享机器人
        assertInvariants();
    }

    @Test
    void mixedSubmitCancelReportKeepsOwnershipConsistent() throws Exception {
        fleet.registerRobot("R1", Set.of("LIFT"), 5);
        // 先占住 R1
        fleet.submit(task("HOLDER"));
        int threads = 24;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        java.util.Map<String, AtomicInteger> outcomes = new ConcurrentHashMap<>();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    String id = "MIX-" + idx;
                    SubmitResult r = fleet.submit(task(id));
                    outcomes.computeIfAbsent("submit:" + (r.duplicate() ? "dup" : r.allocated() ? "allocated" : "queued"),
                            k -> new AtomicInteger()).incrementAndGet();
                    // 一半线程立刻取消自己的任务
                    if (idx % 2 == 0) {
                        var c = fleet.cancel(id);
                        outcomes.computeIfAbsent("cancel:" + c.outcome(), k -> new AtomicInteger()).incrementAndGet();
                    } else {
                        // 另一半乱序回报进度（大多数会因 PENDING/未归属 被拒，不能错误推进）
                        var pr = fleet.reportProgress(id, "R1", 50);
                        outcomes.computeIfAbsent("report:" + pr.outcome(), k -> new AtomicInteger()).incrementAndGet();
                    }
                } catch (Exception ignored) { }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        log.info("[用例输入] {} 线程并发 提交+取消/回报 混合操作（R1 被 HOLDER 占用）；[判定] 结果分布={}", outcomes);
        // HOLDER 始终在 R1 上，未受任何混乱操作影响
        Robot r1 = registry.all().stream().filter(r -> r.getId().equals("R1")).findFirst().orElseThrow();
        assertThat(r1.getCurrentTaskId()).isEqualTo("HOLDER");
        // 取消偶数编号任务后，不应有任何 MIX 任务错误占到 R1
        fleet.listTasks().stream()
                .filter(t -> t.getId().startsWith("MIX-") && t.getStatus().isActive())
                .forEach(t -> assertThat(t.getAssignedRobotId()).isNotEqualTo("R1"));
        assertInvariants();
    }

    /** 全量不变量：BUSY 机器人与活动任务一一对应，归属双向一致；不存在两个任务共享一台机器人。 */
    private void assertInvariants() {
        java.util.Map<String, String> robotToTask = new java.util.HashMap<>();
        for (Robot r : registry.all()) {
            if (r.getStatus() == RobotStatus.BUSY) {
                assertThat(r.getCurrentTaskId()).asString().isNotNull();
                robotToTask.put(r.getId(), r.getCurrentTaskId());
            } else {
                assertThat(r.getCurrentTaskId()).isNull();
            }
        }
        java.util.Map<String, String> taskToRobot = new java.util.HashMap<>();
        for (Task t : fleet.listTasks()) {
            if (t.getStatus().isActive()) {
                assertThat(t.getAssignedRobotId()).isNotNull();
                taskToRobot.put(t.getId(), t.getAssignedRobotId());
            } else {
                assertThat(t.getAssignedRobotId())
                        .as("非活动任务 %s 不应残留归属机器人", t.getId()).isNull();
            }
        }
        // 双向一一对应
        assertThat(robotToTask).hasSameSizeAs(taskToRobot);
        robotToTask.forEach((robot, taskId) ->
                assertThat(taskToRobot.get(taskId)).isEqualTo(robot));
        taskToRobot.forEach((taskId, robot) ->
                assertThat(robotToTask.get(robot)).isEqualTo(taskId));
    }
}
