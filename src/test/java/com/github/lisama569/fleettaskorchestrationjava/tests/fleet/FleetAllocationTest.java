package com.github.lisama569.fleettaskorchestrationjava.tests.fleet;

import com.github.lisama569.fleettaskorchestrationjava.fleet.config.FleetProperties;
import com.github.lisama569.fleettaskorchestrationjava.fleet.model.RejectReason;
import com.github.lisama569.fleettaskorchestrationjava.fleet.model.Robot;
import com.github.lisama569.fleettaskorchestrationjava.fleet.model.RobotStatus;
import com.github.lisama569.fleettaskorchestrationjava.fleet.model.SubmitResult;
import com.github.lisama569.fleettaskorchestrationjava.fleet.model.Task;
import com.github.lisama569.fleettaskorchestrationjava.fleet.model.TaskRequest;
import com.github.lisama569.fleettaskorchestrationjava.fleet.model.TaskStatus;
import com.github.lisama569.fleettaskorchestrationjava.fleet.registry.InMemoryRobotRegistry;
import com.github.lisama569.fleettaskorchestrationjava.fleet.registry.RobotRegistry;
import com.github.lisama569.fleettaskorchestrationjava.fleet.service.FleetService;
import com.github.lisama569.fleettaskorchestrationjava.fleet.store.TaskStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** 分配规则与可区分拒绝原因测试。 */
class FleetAllocationTest {

    private static final Logger log = LoggerFactory.getLogger(FleetAllocationTest.class);

    private TaskStore store;
    private RobotRegistry registry;
    private TestSupport.NoopEngine engine;
    private FleetService fleet;
    private final AtomicLong clock = new AtomicLong(1_000_000L);

    private TaskRequest req(String id, String cap, int pri, int load) {
        return new TaskRequest(id, cap, pri, 0, load, 1000, false, 0);
    }

    @BeforeEach
    void setUp() {
        store = TestSupport.tempStore();
        registry = new InMemoryRobotRegistry();
        engine = new TestSupport.NoopEngine();
        FleetProperties props = TestSupport.props();
        fleet = TestSupport.newService(store, registry, engine, props, clock);
    }

    @Test
    void shouldRejectWhenNoRobots() {
        SubmitResult r = fleet.submit(req("T1", "LIFT", 50, 2));
        log.info("[用例输入] task=T1 cap=LIFT load=2，无任何机器人；[判定] allocated={} reason={} 依据={}",
                r.allocated(), r.reason(), r.message());
        assertThat(r.allocated()).isFalse();
        assertThat(r.reason()).isEqualTo(RejectReason.ALL_ROBOTS_OFFLINE);
    }

    @Test
    void shouldRejectWhenAllOffline() {
        fleet.registerRobot("R1", Set.of("LIFT"), 5);
        fleet.robotOffline("R1");
        SubmitResult r = fleet.submit(req("T1", "LIFT", 50, 2));
        log.info("[用例输入] task=T1，机器人 R1 已 OFFLINE；[判定] reason={} 依据={}", r.reason(), r.message());
        assertThat(r.reason()).isEqualTo(RejectReason.ALL_ROBOTS_OFFLINE);
    }

    @Test
    void shouldRejectWhenAllBusyAndQueueTask() {
        fleet.registerRobot("R1", Set.of("LIFT"), 5);
        fleet.submit(req("T1", "LIFT", 50, 2));
        SubmitResult r = fleet.submit(req("T2", "LIFT", 50, 2));
        log.info("[用例输入] task=T2，唯一机器人 R1 正 BUSY 于 T1；[判定] reason={} 状态=PENDING 依据={}",
                r.reason(), r.message());
        assertThat(r.allocated()).isFalse();
        assertThat(r.reason()).isEqualTo(RejectReason.ALL_ROBOTS_BUSY);
        assertThat(r.task().getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(registry.get("R1").getStatus()).isEqualTo(RobotStatus.BUSY);
    }

    @Test
    void shouldRejectCapabilityMismatch() {
        fleet.registerRobot("R1", Set.of("SCAN"), 5);
        SubmitResult r = fleet.submit(req("T1", "WELD", 50, 2));
        log.info("[用例输入] task=T1 cap=WELD，机器人能力 SCAN；[判定] reason={} 依据={}", r.reason(), r.message());
        assertThat(r.reason()).isEqualTo(RejectReason.CAPABILITY_MISMATCH);
    }

    @Test
    void shouldRejectLoadUnsupported() {
        fleet.registerRobot("R1", Set.of("LIFT"), 3);
        SubmitResult r = fleet.submit(req("T1", "LIFT", 50, 8));
        log.info("[用例输入] task=T1 load=8，机器人容量=3；[判定] reason={} 依据={}", r.reason(), r.message());
        assertThat(r.reason()).isEqualTo(RejectReason.LOAD_UNSUPPORTED);
    }

    @Test
    void shouldChooseClosestFitRobot() {
        fleet.registerRobot("R-BIG", Set.of("LIFT"), 10);
        fleet.registerRobot("R-SMALL", Set.of("LIFT"), 4);
        SubmitResult r = fleet.submit(req("T1", "LIFT", 50, 3));
        log.info("[用例输入] task=T1 load=3，候选 R-BIG(cap10)/R-SMALL(cap4)；[判定] 选中机器人={} 依据={}",
                r.task().getAssignedRobotId(), r.message());
        assertThat(r.allocated()).isTrue();
        // 剩余容量：R-SMALL=1 < R-BIG=7，选最贴近负载的
        assertThat(r.task().getAssignedRobotId()).isEqualTo("R-SMALL");
    }

    @Test
    void shouldEnforceMutexOneTaskPerBusyRobot() {
        fleet.registerRobot("R1", Set.of("LIFT"), 5);
        fleet.submit(req("T1", "LIFT", 50, 2));
        fleet.submit(req("T2", "LIFT", 50, 2));
        Robot r1 = registry.get("R1");
        log.info("[用例输入] 两任务抢同一机器人 R1；[判定] R1.currentTask={} T2 状态={}",
                r1.getCurrentTaskId(), fleet.getTask("T2").getStatus());
        assertThat(r1.getStatus()).isEqualTo(RobotStatus.BUSY);
        assertThat(r1.getCurrentTaskId()).isEqualTo("T1");
        assertThat(fleet.getTask("T2").getStatus()).isEqualTo(TaskStatus.PENDING);
    }

    @Test
    void shouldOrderByPriorityThenDeadline() {
        fleet.registerRobot("R1", Set.of("LIFT"), 5);
        // R1 被低优先级但先到的任务占住，两个任务排队
        fleet.submit(req("HOLDER", "LIFT", 10, 1));
        TaskRequest low = new TaskRequest("LOW", "LIFT", 20, 0, 1, 1000, false, 0);
        TaskRequest high = new TaskRequest("HIGH", "LIFT", 90, 0, 1, 1000, false, 0);
        fleet.submit(low);
        clock.addAndGet(10);
        fleet.submit(high);
        // 释放后 tick：HIGH 先得到机器人
        fleet.reportSuccess("HOLDER", "R1");
        log.info("[用例输入] 排队 LOW(pri20)/HIGH(pri90)，HOLDER 完成后调度；[判定] HIGH.robot={} LOW.状态={}",
                fleet.getTask("HIGH").getAssignedRobotId(), fleet.getTask("LOW").getStatus());
        assertThat(fleet.getTask("HIGH").getAssignedRobotId()).isEqualTo("R1");
        assertThat(fleet.getTask("LOW").getStatus()).isEqualTo(TaskStatus.PENDING);
    }

    @Test
    void shouldDedupDuplicateSubmission() {
        fleet.registerRobot("R1", Set.of("LIFT"), 5);
        SubmitResult first = fleet.submit(req("T1", "LIFT", 50, 2));
        SubmitResult dup = fleet.submit(req("T1", "LIFT", 50, 2));
        log.info("[用例输入] 同一 taskId=T1 提交两次；[判定] duplicate={} 引擎下发次数={} 任务总数={}",
                dup.duplicate(), engine.dispatchCount(), fleet.listTasks().size());
        assertThat(first.duplicate()).isFalse();
        assertThat(dup.duplicate()).isTrue();
        assertThat(dup.reason()).isEqualTo(RejectReason.DUPLICATE_SUBMISSION);
        assertThat(engine.dispatchCount()).isEqualTo(1);
        assertThat(fleet.listTasks()).hasSize(1);
    }

    @Test
    void shouldInvalidRequestBeRejectedWithReason() {
        // 非法优先级在值对象构造期就会被拒绝，服务层对 null 请求也给出 INVALID_REQUEST
        SubmitResult nullResult = fleet.submit(null);
        log.info("[用例输入] submit(null)；[判定] reason={} message={}", nullResult.reason(), nullResult.message());
        assertThat(nullResult.reason()).isEqualTo(RejectReason.INVALID_REQUEST);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new TaskRequest(" ", "LIFT", 50, 0, 1, 100, false, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("taskId");
        log.info("[用例输入] taskId 为空白构造值对象；[判定] 直接抛出 IllegalArgumentException，非法输入无法进入系统");
    }

    @Test
    void shouldFreeRobotAfterCompletionAndReassignQueued() {
        fleet.registerRobot("R1", Set.of("LIFT"), 5);
        fleet.submit(req("T1", "LIFT", 50, 2));
        fleet.submit(req("T2", "LIFT", 50, 2));
        fleet.reportSuccess("T1", "R1");
        Task t2 = fleet.getTask("T2");
        log.info("[用例输入] T1 完成后回报；[判定] R1 状态={} R1.currentTask={} T2 状态={} T2.robot={}",
                registry.get("R1").getStatus(), registry.get("R1").getCurrentTaskId(),
                t2.getStatus(), t2.getAssignedRobotId());
        // T2 在同一次回报里立即被重新分配，机器人先释放后再次 BUSY，新归属必须是 T2
        assertThat(t2.getStatus().isActive()).isTrue();
        assertThat(t2.getAssignedRobotId()).isEqualTo("R1");
        assertThat(registry.get("R1").getStatus()).isEqualTo(RobotStatus.BUSY);
        assertThat(registry.get("R1").getCurrentTaskId()).isEqualTo("T2");
    }
}
