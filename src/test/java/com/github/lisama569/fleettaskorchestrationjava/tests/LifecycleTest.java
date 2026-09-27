package com.github.lisama569.fleettaskorchestrationjava.tests;

import com.github.lisama569.fleettaskorchestrationjava.model.RobotStatus;
import com.github.lisama569.fleettaskorchestrationjava.model.Task;
import com.github.lisama569.fleettaskorchestrationjava.model.TaskStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 执行期状态流转：超时、取消、重复回报幂等、老化提优。
 */
class LifecycleTest {
    private static final Logger log = LoggerFactory.getLogger(LifecycleTest.class);

    @TempDir
    Path tempDir;

    private FleetTestFixture fixture(long agingThresholdMillis) {
        return FleetTestFixture.freshStart(tempDir.resolve("state.json"), agingThresholdMillis);
    }

    @Test
    void timeoutTerminatesTaskAndReleasesRobot() throws InterruptedException {
        FleetTestFixture f = fixture(1000);
        f.robotService.register("R1", Set.of("lift"), 10);
        Task t = f.taskService.submit("T1", 1, null, "lift", 1, 120);
        log.info("[input] task=T1 timeoutMs=120 status={} robot=R1", t.getStatus());
        assertEquals(TaskStatus.RUNNING, t.getStatus());

        Thread.sleep(200);
        f.scheduler.scanTimeouts();
        log.info("[decision] after scan: status={} reason={} robotStatus={}",
                t.getStatus(), t.getStatusReason(), f.robotService.get("R1").getStatus());
        assertEquals(TaskStatus.TIMED_OUT, t.getStatus());
        assertEquals(RobotStatus.IDLE, f.robotService.get("R1").getStatus());
        assertNull(f.robotService.get("R1").getCurrentTaskId());

        // 幂等：再次扫描不产生二次副作用
        f.scheduler.scanTimeouts();
        assertEquals(TaskStatus.TIMED_OUT, t.getStatus());
    }

    @Test
    void cancelRunningTaskReleasesRobotAndIsIdempotent() {
        FleetTestFixture f = fixture(1000);
        f.robotService.register("R1", Set.of("lift"), 10);
        f.taskService.submit("T1", 1, null, "lift", 1, 60_000);
        f.taskService.submit("T2", 1, null, "lift", 1, 60_000);
        log.info("[input] cancel running T1; T2 queued behind");

        f.taskService.cancel("T1");
        Task t1 = f.taskService.get("T1");
        log.info("[decision] T1 status={} robotStatus={}", t1.getStatus(),
                f.robotService.get("R1").getStatus());
        assertEquals(TaskStatus.CANCELLED, t1.getStatus());

        // 机器人释放后，排队的 T2 应被立即调度上去
        Task t2 = f.taskService.get("T2");
        log.info("[decision] queued T2 rescheduled: status={} robot={}", t2.getStatus(),
                t2.getAssignedRobotId());
        assertEquals(TaskStatus.RUNNING, t2.getStatus());
        assertEquals("R1", t2.getAssignedRobotId());

        // 重复取消幂等
        f.taskService.cancel("T1");
        assertEquals(TaskStatus.CANCELLED, f.taskService.get("T1").getStatus());
    }

    @Test
    void cancelPendingTaskIsImmediate() {
        FleetTestFixture f = fixture(1000);
        f.robotService.register("R1", Set.of("lift"), 10);
        f.taskService.submit("T1", 1, null, "lift", 1, 60_000); // 占住 R1
        f.taskService.submit("T2", 1, null, "lift", 1, 60_000); // 排队
        log.info("[input] cancel PENDING T2");
        f.taskService.cancel("T2");
        log.info("[decision] T2 status={}", f.taskService.get("T2").getStatus());
        assertEquals(TaskStatus.CANCELLED, f.taskService.get("T2").getStatus());
    }

    @Test
    void duplicateAndStaleResultReportsAreIdempotent() {
        FleetTestFixture f = fixture(1000);
        f.robotService.register("R1", Set.of("lift"), 10);
        f.robotService.register("R2", Set.of("lift"), 10);
        Task t = f.taskService.submit("T1", 1, null, "lift", 1, 60_000);
        long attempt = t.getAttemptId();
        log.info("[input] T1 assigned robot={} attempt={}", t.getAssignedRobotId(), attempt);

        // 来自错误机器人的过期回报：忽略
        String other = "R1".equals(t.getAssignedRobotId()) ? "R2" : "R1";
        f.taskService.reportResult("T1", other, attempt, false, "stale");
        log.info("[decision] stale report from {} ignored, status={}", other, t.getStatus());
        assertEquals(TaskStatus.RUNNING, t.getStatus());

        // 正确回报成功
        f.taskService.reportResult("T1", t.getAssignedRobotId(), attempt, true, "done");
        assertEquals(TaskStatus.SUCCEEDED, t.getStatus());
        assertEquals(RobotStatus.IDLE, f.robotService.get(t.getAssignedRobotId()).getStatus());

        // 重复回报同一结果：无副作用
        f.taskService.reportResult("T1", t.getAssignedRobotId(), attempt, true, "done");
        f.taskService.reportResult("T1", t.getAssignedRobotId(), attempt, false, "late-failure");
        log.info("[decision] duplicate/late reports ignored, status={} assignments={}",
                t.getStatus(), f.scheduler.assignmentsFor("T1").size());
        assertEquals(TaskStatus.SUCCEEDED, t.getStatus());
        assertEquals(1, f.scheduler.assignmentsFor("T1").size());
    }

    @Test
    void duplicateProgressReportsHaveNoSideEffects() {
        FleetTestFixture f = fixture(1000);
        f.robotService.register("R1", Set.of("lift"), 10);
        Task t = f.taskService.submit("T1", 1, null, "lift", 1, 60_000);
        log.info("[input] report progress 50%% three times, attempt={}", t.getAttemptId());
        f.taskService.reportProgress("T1", "R1", t.getAttemptId(), 50);
        f.taskService.reportProgress("T1", "R1", t.getAttemptId(), 50);
        f.taskService.reportProgress("T1", "R1", t.getAttemptId(), 50);
        log.info("[decision] status unchanged={}", t.getStatus());
        assertEquals(TaskStatus.RUNNING, t.getStatus());
    }

    @Test
    void agedTaskGetsPriorityBoostAndWinsScheduling() {
        FleetTestFixture f = fixture(50); // 50ms 即触发一次提优
        f.robotService.register("R1", Set.of("lift"), 10);
        f.taskService.submit("OCCUPIER", 1, null, "lift", 1, 60_000); // 占住唯一机器人

        Task low = f.taskService.submit("LOW", 1, null, "lift", 1, 60_000);
        log.info("[input] LOW priority=1 queued; wait for aging threshold=50ms");
        assertEquals(TaskStatus.PENDING, low.getStatus());

        // 等待老化生效
        long deadline = System.currentTimeMillis() + 3000;
        while (low.getPriorityBoosts() == 0 && System.currentTimeMillis() < deadline) {
            f.scheduler.boostAgedTasks();
        }
        log.info("[decision] LOW boosts={} effectivePriority={}", low.getPriorityBoosts(),
                low.effectivePriority());
        assertTrue(low.getPriorityBoosts() > 0, "长期排队应被提优");

        // 此时插入一个基础优先级更高的任务，但 LOW 提优后应不低于它
        Task high = f.taskService.submit("HIGH", 2, null, "lift", 1, 60_000);
        // 释放占坑任务，触发调度
        f.taskService.cancel("OCCUPIER");
        log.info("[decision] after release: LOW status={} robot={} | HIGH status={}",
                low.getStatus(), low.getAssignedRobotId(), high.getStatus());
        assertEquals(TaskStatus.RUNNING, low.getStatus(),
                "提优后的老任务应优先于新提交的高优先级任务");
        assertEquals(TaskStatus.PENDING, high.getStatus());
    }

    @Test
    void robotOfflineRequeuesTaskAndReplacementPicksItUp() {
        FleetTestFixture f = fixture(1000);
        f.robotService.register("R1", Set.of("lift"), 10);
        Task t = f.taskService.submit("T1", 1, null, "lift", 1, 60_000);
        log.info("[input] T1 running on R1; mark R1 offline");
        f.robotService.markOffline("R1");
        log.info("[decision] T1 status={} reason={} R1 status={}", t.getStatus(),
                t.getStatusReason(), f.robotService.get("R1").getStatus());
        assertEquals(TaskStatus.PENDING, t.getStatus());
        assertEquals(RobotStatus.OFFLINE, f.robotService.get("R1").getStatus());

        // 幂等掉线
        f.robotService.markOffline("R1");
        assertEquals(RobotStatus.OFFLINE, f.robotService.get("R1").getStatus());

        // 替换机器人重新上线，任务被重新调度
        f.robotService.replace("R1", Set.of("lift"), 10);
        log.info("[decision] after replace: T1 status={} robot={} R1 status={}",
                t.getStatus(), t.getAssignedRobotId(), f.robotService.get("R1").getStatus());
        assertEquals(TaskStatus.RUNNING, t.getStatus());
        assertEquals(RobotStatus.BUSY, f.robotService.get("R1").getStatus());
    }
}
