package com.github.lisama569.fleettaskorchestrationjava.tests;

import com.github.lisama569.fleettaskorchestrationjava.model.Assignment;
import com.github.lisama569.fleettaskorchestrationjava.model.Robot;
import com.github.lisama569.fleettaskorchestrationjava.model.RobotStatus;
import com.github.lisama569.fleettaskorchestrationjava.model.Task;
import com.github.lisama569.fleettaskorchestrationjava.model.TaskStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 并发场景：重复提交去重、机器人互斥占用、提交/取消/回报混杂时状态一致。
 */
class ConcurrencyTest {
    private static final Logger log = LoggerFactory.getLogger(ConcurrencyTest.class);

    @TempDir
    Path tempDir;

    @Test
    void concurrentDuplicateSubmissionYieldsSingleTaskAndSingleAssignment() throws Exception {
        FleetTestFixture f = FleetTestFixture.freshStart(tempDir.resolve("state.json"), 1000);
        f.robotService.register("R1", Set.of("lift"), 10);

        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        List<Throwable> errors = new ArrayList<>();
        log.info("[input] {} threads submit the SAME taskId=T-SAME concurrently", threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    Task t = f.taskService.submit("T-SAME", 5, null, "lift", 1, 60_000);
                    if (t != null) {
                        accepted.incrementAndGet();
                    }
                } catch (Throwable e) {
                    synchronized (errors) {
                        errors.add(e);
                    }
                }
            });
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        log.info("[decision] accepted={} errors={} taskCount={} assignments={}",
                accepted.get(), errors.size(), f.taskService.all().size(),
                f.scheduler.assignmentsFor("T-SAME").size());
        assertTrue(errors.isEmpty(), "重复提交不应报错: " + errors);
        assertEquals(threads, accepted.get(), "所有提交方都应拿到同一个任务句柄");
        assertEquals(1, f.taskService.all().size(), "只能存在一个任务记录");
        assertEquals(1, f.scheduler.assignmentsFor("T-SAME").size(), "只能产生一次分配");
    }

    @Test
    void concurrentDistinctTasksNeverDoubleBookARobot() throws Exception {
        FleetTestFixture f = FleetTestFixture.freshStart(tempDir.resolve("state.json"), 1000);
        int robotCount = 4;
        int taskCount = 40;
        for (int i = 0; i < robotCount; i++) {
            f.robotService.register("R" + i, Set.of("lift"), 10);
        }

        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch go = new CountDownLatch(1);
        log.info("[input] {} tasks x {} robots, submit all concurrently", taskCount, robotCount);
        for (int i = 0; i < taskCount; i++) {
            String taskId = "T" + i;
            pool.submit(() -> {
                try {
                    go.await();
                    f.taskService.submit(taskId, 1, null, "lift", 1, 60_000);
                } catch (Exception ignored) {
                }
            });
        }
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));

        // 一致性断言：任何机器人最多占用一个任务；占用与归属必须互相印证
        Map<String, String> robotToTask = new ConcurrentHashMap<>();
        for (Robot r : f.robotService.all()) {
            if (r.getStatus() == RobotStatus.BUSY) {
                assertNotNull(r.getCurrentTaskId());
                assertNull(robotToTask.put(r.getRobotId(), r.getCurrentTaskId()),
                        "同一机器人被重复占用");
            } else {
                assertNull(r.getCurrentTaskId(), "非 BUSY 机器人不应持有任务");
            }
        }
        long running = f.taskService.all().stream()
                .filter(t -> t.getStatus() == TaskStatus.RUNNING).count();
        log.info("[decision] running={} busyRobots={} assignments={} (robotCount={})",
                running, robotToTask.size(), f.scheduler.assignmentHistory().size(), robotCount);
        assertEquals(robotCount, running, "运行中的任务数应等于机器人数");
        assertEquals(robotCount, f.scheduler.assignmentHistory().size());
        for (Task t : f.taskService.all()) {
            if (t.getStatus() == TaskStatus.RUNNING) {
                assertEquals(t.getTaskId(),
                        robotToTask.get(t.getAssignedRobotId()),
                        "任务归属与机器人占用互相矛盾: " + t.getTaskId());
            }
        }
    }

    @Test
    void mixedSubmitCancelReportKeepsStateConsistent() throws Exception {
        FleetTestFixture f = FleetTestFixture.freshStart(tempDir.resolve("state.json"), 1000);
        int robotCount = 3;
        int taskCount = 30;
        for (int i = 0; i < robotCount; i++) {
            f.robotService.register("R" + i, Set.of("lift"), 10);
        }

        ExecutorService pool = Executors.newFixedThreadPool(12);
        CountDownLatch go = new CountDownLatch(1);
        log.info("[input] {} tasks; concurrent submit + random cancel/report on {} robots",
                taskCount, robotCount);
        for (int i = 0; i < taskCount; i++) {
            String taskId = "T" + i;
            int idx = i;
            pool.submit(() -> {
                try {
                    go.await();
                    f.taskService.submit(taskId, idx % 5, null, "lift", 1, 60_000);
                } catch (Exception ignored) {
                }
            });
            // 并发的取消与回报（可能打在 PENDING/RUNNING/终结态上，都必须幂等安全）
            pool.submit(() -> {
                try {
                    go.await();
                    Thread.sleep((long) (Math.random() * 50));
                    if (idx % 3 == 0) {
                        f.taskService.cancel(taskId);
                    } else {
                        Task t = f.taskService.get(taskId);
                        if (t != null && t.getAssignedRobotId() != null) {
                            f.taskService.reportResult(taskId, t.getAssignedRobotId(),
                                    t.getAttemptId(), true, "done");
                            // 故意重复回报
                            f.taskService.reportResult(taskId, t.getAssignedRobotId(),
                                    t.getAttemptId(), true, "done");
                        }
                    }
                } catch (Exception ignored) {
                }
            });
        }
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));

        // 全局一致性：每个 BUSY 机器人对应且仅对应一个 RUNNING 任务，反之亦然
        Map<String, String> robotToTask = new ConcurrentHashMap<>();
        for (Robot r : f.robotService.all()) {
            if (r.getStatus() == RobotStatus.BUSY) {
                assertNull(robotToTask.put(r.getRobotId(), r.getCurrentTaskId()));
            }
        }
        int inconsistent = 0;
        for (Task t : f.taskService.all()) {
            if (t.getStatus() == TaskStatus.RUNNING) {
                String holder = t.getAssignedRobotId();
                if (holder == null || !t.getTaskId().equals(robotToTask.get(holder))) {
                    inconsistent++;
                }
            } else if (t.getStatus().isTerminal() || t.getStatus() == TaskStatus.PENDING) {
                // 非运行态任务不应仍被机器人占用
                if (t.getAssignedRobotId() != null
                        && t.getTaskId().equals(robotToTask.get(t.getAssignedRobotId()))) {
                    inconsistent++;
                }
            }
        }
        long terminal = f.taskService.all().stream().filter(t -> t.getStatus().isTerminal()).count();
        log.info("[decision] tasks={} terminal={} running={} busyRobots={} inconsistent={}",
                f.taskService.all().size(), terminal,
                f.taskService.all().stream().filter(t -> t.getStatus() == TaskStatus.RUNNING).count(),
                robotToTask.size(), inconsistent);
        assertEquals(0, inconsistent, "机器人占用与任务归属互相矛盾");
    }
}
