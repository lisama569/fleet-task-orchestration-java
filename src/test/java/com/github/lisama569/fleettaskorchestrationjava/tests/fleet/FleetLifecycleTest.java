package com.github.lisama569.fleettaskorchestrationjava.tests.fleet;

import com.github.lisama569.fleettaskorchestrationjava.fleet.config.FleetProperties;
import com.github.lisama569.fleettaskorchestrationjava.fleet.model.CancelOutcome;
import com.github.lisama569.fleettaskorchestrationjava.fleet.model.CancelResult;
import com.github.lisama569.fleettaskorchestrationjava.fleet.model.ReportOutcome;
import com.github.lisama569.fleettaskorchestrationjava.fleet.model.ReportResult;
import com.github.lisama569.fleettaskorchestrationjava.fleet.model.RobotStatus;
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

/** 取消/超时/失败/幂等回报/掉线与替换 的状态流转测试。 */
class FleetLifecycleTest {

    private static final Logger log = LoggerFactory.getLogger(FleetLifecycleTest.class);

    private TaskStore store;
    private RobotRegistry registry;
    private TestSupport.NoopEngine engine;
    private FleetService fleet;
    private final AtomicLong clock = new AtomicLong(1_000_000L);

    private TaskRequest active(String id, long timeoutMs) {
        return new TaskRequest(id, "LIFT", 50, 0, 2, 1000, false, timeoutMs);
    }

    @BeforeEach
    void setUp() {
        store = TestSupport.tempStore();
        registry = new InMemoryRobotRegistry();
        engine = new TestSupport.NoopEngine();
        FleetProperties props = TestSupport.props();
        fleet = TestSupport.newService(store, registry, engine, props, clock);
        fleet.registerRobot("R1", Set.of("LIFT"), 5);
        fleet.registerRobot("R2", Set.of("LIFT"), 8);
    }

    @Test
    void shouldTransitionAllocatedToInProgressToCompleted() {
        fleet.submit(active("T1", 0));
        fleet.handleStarted("T1");
        log.info("[用例输入] onStarted(T1)；[判定] 状态={}（ALLOCATED -> IN_PROGRESS）", fleet.getTask("T1").getStatus());
        assertThat(fleet.getTask("T1").getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);

        ReportResult progress = fleet.reportProgress("T1", "R1", 40);
        assertThat(progress.changed()).isTrue();
        assertThat(progress.outcome()).isEqualTo(ReportOutcome.PROGRESS_ACCEPTED);

        ReportResult success = fleet.reportSuccess("T1", "R1");
        log.info("[用例输入] 40%进度后回报成功；[判定] outcome={} changed={} 状态={}",
                success.outcome(), success.changed(), fleet.getTask("T1").getStatus());
        assertThat(success.changed()).isTrue();
        assertThat(success.outcome()).isEqualTo(ReportOutcome.SUCCEEDED);
        assertThat(fleet.getTask("T1").getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(registry.get("R1").getStatus()).isEqualTo(RobotStatus.IDLE);
        assertThat(registry.get("R1").getCompletedCount()).isEqualTo(1);
    }

    @Test
    void shouldIgnoreDuplicateSuccessWithoutSideEffect() {
        fleet.submit(active("T1", 0));
        ReportResult first = fleet.reportSuccess("T1", "R1");
        long revisionAfterFirst = fleet.getTask("T1").getRevision();
        int countAfterFirst = registry.get("R1").getCompletedCount();

        ReportResult dup = fleet.reportSuccess("T1", "R1");
        log.info("[用例输入] 连续两次 reportSuccess(T1,R1)；[判定] 第二次 outcome={} changed={} rev{}->rev{} 完成计数{}->{}",
                dup.outcome(), dup.changed(), revisionAfterFirst, fleet.getTask("T1").getRevision(),
                countAfterFirst, registry.get("R1").getCompletedCount());
        assertThat(first.changed()).isTrue();
        assertThat(dup.changed()).isFalse();
        assertThat(dup.outcome()).isEqualTo(ReportOutcome.DUPLICATE_IGNORED);
        assertThat(fleet.getTask("T1").getRevision()).isEqualTo(revisionAfterFirst);
        assertThat(registry.get("R1").getCompletedCount()).isEqualTo(countAfterFirst);
    }

    @Test
    void shouldIgnoreFailureAfterSuccess() {
        fleet.submit(active("T1", 0));
        fleet.reportSuccess("T1", "R1");
        ReportResult late = fleet.reportFailure("T1", "R1", "LATE_FAILURE");
        log.info("[用例输入] 先成功后回报失败；[判定] outcome={} changed={} 终态仍为={}",
                late.outcome(), late.changed(), fleet.getTask("T1").getStatus());
        assertThat(late.changed()).isFalse();
        assertThat(late.outcome()).isEqualTo(ReportOutcome.DUPLICATE_IGNORED);
        assertThat(fleet.getTask("T1").getStatus()).isEqualTo(TaskStatus.COMPLETED);
    }

    @Test
    void shouldRejectProgressFromWrongOwner() {
        // T1 在 R1 上；R2 冒名回报
        fleet.submit(active("T1", 0));
        ReportResult r = fleet.reportProgress("T1", "R2", 50);
        log.info("[用例输入] T1 归属 R1，R2 冒名上报 50%；[判定] outcome={} 任务进度保持={}%",
                r.outcome(), fleet.getTask("T1").getProgressPercent());
        assertThat(r.outcome()).isEqualTo(ReportOutcome.WRONG_OWNER);
        assertThat(r.changed()).isFalse();
        assertThat(fleet.getTask("T1").getProgressPercent()).isZero();
    }

    @Test
    void shouldRejectStaleProgressLowerPercent() {
        fleet.submit(active("T1", 0));
        fleet.reportProgress("T1", "R1", 80);
        ReportResult stale = fleet.reportProgress("T1", "R1", 30);
        log.info("[用例输入] 先报 80% 再报 30%；[判定] outcome={} changed={} 进度保持={}%",
                stale.outcome(), stale.changed(), fleet.getTask("T1").getProgressPercent());
        assertThat(stale.changed()).isFalse();
        assertThat(fleet.getTask("T1").getProgressPercent()).isEqualTo(80);
    }

    @Test
    void shouldCancelQueuedAndActiveTask() {
        fleet.submit(active("T1", 0)); // -> R1 BUSY
        fleet.submit(active("T2", 0)); // -> R2 BUSY
        fleet.submit(active("T3", 0)); // 排队（两台忙）

        CancelResult queuedCancel = fleet.cancel("T3");
        assertThat(queuedCancel.cancelled()).isTrue();
        assertThat(queuedCancel.outcome()).isEqualTo(CancelOutcome.CANCELLED_PENDING);

        CancelResult activeCancel = fleet.cancel("T1");
        log.info("[用例输入] 取消排队 T3 与执行中 T1；[判定] T1.outcome={} T1状态={} R1状态={} 引擎取消调用={}",
                activeCancel.outcome(), fleet.getTask("T1").getStatus(), registry.get("R1").getStatus(),
                engine.cancelCount());
        assertThat(activeCancel.cancelled()).isTrue();
        assertThat(activeCancel.outcome()).isEqualTo(CancelOutcome.CANCELLED_ACTIVE);
        assertThat(fleet.getTask("T1").getStatus()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(registry.get("R1").getStatus()).isEqualTo(RobotStatus.IDLE);
        assertThat(engine.cancelCount()).isEqualTo(1);
    }

    @Test
    void shouldCancelBeIdempotent() {
        fleet.submit(active("T1", 0));
        fleet.cancel("T1");
        CancelResult again = fleet.cancel("T1");
        log.info("[用例输入] 连续两次 cancel(T1)；[判定] 第二次 outcome={} cancelled={} 引擎取消总次数={}",
                again.outcome(), again.cancelled(), engine.cancelCount());
        assertThat(again.cancelled()).isFalse();
        assertThat(again.outcome()).isEqualTo(CancelOutcome.ALREADY_TERMINAL);
        assertThat(engine.cancelCount()).isEqualTo(1);
    }

    @Test
    void shouldTimeoutBySchedulerScanAndFreeRobot() {
        fleet.submit(active("T1", 500));
        clock.addAndGet(499);
        fleet.runScheduleTick();
        log.info("[用例输入] timeout=500，推进时钟到499ms；[判定] 状态={}（不应超时）", fleet.getTask("T1").getStatus());
        assertThat(fleet.getTask("T1").getStatus()).isIn(TaskStatus.ALLOCATED, TaskStatus.IN_PROGRESS);

        clock.addAndGet(2);
        fleet.runScheduleTick();
        log.info("[用例输入] 推进时钟到 501ms 再 tick；[判定] 状态={} R1={} errorCode={}",
                fleet.getTask("T1").getStatus(), registry.get("R1").getStatus(),
                fleet.getTask("T1").getErrorCode());
        assertThat(fleet.getTask("T1").getStatus()).isEqualTo(TaskStatus.TIMED_OUT);
        assertThat(registry.get("R1").getStatus()).isEqualTo(RobotStatus.IDLE);
        assertThat(fleet.getTask("T1").getErrorCode()).isEqualTo("SCHEDULER_TIMEOUT_SCAN");
    }

    @Test
    void shouldMarkFailureAndFreeRobot() {
        fleet.submit(active("T1", 0));
        ReportResult r = fleet.reportFailure("T1", "R1", "ARM_JAM");
        log.info("[用例输入] reportFailure(T1,ARM_JAM)；[判定] outcome={} 状态={} R1={}",
                r.outcome(), fleet.getTask("T1").getStatus(), registry.get("R1").getStatus());
        assertThat(r.outcome()).isEqualTo(ReportOutcome.FAILED_ACCEPTED);
        assertThat(fleet.getTask("T1").getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(registry.get("R1").getStatus()).isEqualTo(RobotStatus.IDLE);
    }

    @Test
    void shouldRequeueTaskWhenRobotOffline() {
        fleet.submit(active("T1", 0)); // R1<-T1
        fleet.reportProgress("T1", "R1", 60);
        // 先让 R2 不可服务，制造“退回排队后暂时无资源”的中间态
        fleet.robotOffline("R2");
        fleet.robotOffline("R1");
        log.info("[用例输入] R1 执行 T1(60%) 时掉线，R2 也离线；[判定] T1={} T1.robot={} 进度={}% R1={}",
                fleet.getTask("T1").getStatus(), fleet.getTask("T1").getAssignedRobotId(),
                fleet.getTask("T1").getProgressPercent(), registry.get("R1").getStatus());
        assertThat(fleet.getTask("T1").getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(fleet.getTask("T1").getAssignedRobotId()).isNull();
        assertThat(fleet.getTask("T1").getProgressPercent()).isZero(); // 退回排队后进度清零
        assertThat(registry.get("R1").getStatus()).isEqualTo(RobotStatus.OFFLINE);

        // R2 恢复在线，tick 后 T1 重新分配给 R2，且只下发一次新执行
        fleet.robotOnline("R2");
        fleet.runScheduleTick();
        log.info("[用例输入] R2 恢复在线并 tick；[判定] T1 归属={} 状态={} 累计下发={}",
                fleet.getTask("T1").getAssignedRobotId(), fleet.getTask("T1").getStatus(),
                engine.dispatchCount());
        assertThat(fleet.getTask("T1").getAssignedRobotId()).isEqualTo("R2");
        assertThat(fleet.getTask("T1").getStatus()).isEqualTo(TaskStatus.ALLOCATED);
        assertThat(engine.dispatchCount()).isEqualTo(2);
    }

    @Test
    void shouldRequeueWhenRobotReplaced() {
        fleet.submit(active("T1", 0)); // R1<-T1
        // R2 先占用，使替换后的新机器人 R3 成为任务唯一可行归属
        fleet.submit(active("T2", 0));
        fleet.reportProgress("T1", "R1", 40);
        fleet.replaceRobot("R1", "R3", Set.of("LIFT"), 9);
        fleet.runScheduleTick();
        log.info("[用例输入] R1 被 R3(LIFT,cap9) 替换（R2 忙）；[判定] T1 状态={} 归属={} 进度={}% R1={} R3={}",
                fleet.getTask("T1").getStatus(), fleet.getTask("T1").getAssignedRobotId(),
                fleet.getTask("T1").getProgressPercent(),
                registry.get("R1").getStatus(), registry.get("R3").getStatus());
        assertThat(registry.get("R1").getStatus()).isEqualTo(RobotStatus.OFFLINE);
        assertThat(registry.get("R3").getStatus()).isEqualTo(RobotStatus.BUSY);
        assertThat(registry.get("R3").getCurrentTaskId()).isEqualTo("T1");
        assertThat(fleet.getTask("T1").getAssignedRobotId()).isEqualTo("R3");
        assertThat(fleet.getTask("T1").getProgressPercent()).isZero();
    }
}
