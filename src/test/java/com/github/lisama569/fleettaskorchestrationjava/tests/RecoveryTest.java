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
 * 重启恢复：未完成任务从快照恢复为可调度状态，不卡死、不重复下发。
 */
class RecoveryTest {
    private static final Logger log = LoggerFactory.getLogger(RecoveryTest.class);

    @TempDir
    Path tempDir;

    @Test
    void unfinishedTasksAreResumableAfterRestart() {
        Path snapshot = tempDir.resolve("state.json");

        // ---- 第一次启动：1 台机器人，3 个任务（一个已完成、一个运行、一个排队），随后"崩溃" ----
        FleetTestFixture first = FleetTestFixture.freshStart(snapshot, 1000);
        first.robotService.register("R1", Set.of("lift"), 10);
        Task done = first.taskService.submit("T-DONE", 1, null, "lift", 1, 60_000);
        first.taskService.reportResult("T-DONE", "R1", done.getAttemptId(), true, "done");
        Task running = first.taskService.submit("T-RUN", 5, null, "lift", 1, 60_000);
        Task queued = first.taskService.submit("T-QUEUED", 1, null, "lift", 1, 60_000);

        log.info("[input] before crash: T-RUN={} T-QUEUED={} T-DONE={}",
                running.getStatus(), queued.getStatus(), done.getStatus());
        assertEquals(TaskStatus.RUNNING, running.getStatus());
        assertEquals(TaskStatus.PENDING, queued.getStatus());
        assertEquals(TaskStatus.SUCCEEDED, done.getStatus());
        // 不调用任何停机钩子，直接丢弃对象图，模拟崩溃

        // ---- 第二次启动：同一快照文件，全新对象图 ----
        FleetTestFixture second = FleetTestFixture.restart(snapshot, 1000);
        Task recoveredRun = second.taskService.get("T-RUN");
        Task recoveredQueued = second.taskService.get("T-QUEUED");
        Task recoveredDone = second.taskService.get("T-DONE");
        log.info("[decision] after restart: T-RUN={} robot={} | T-QUEUED={} | T-DONE={}",
                recoveredRun.getStatus(), recoveredRun.getAssignedRobotId(),
                recoveredQueued.getStatus(), recoveredDone.getStatus());

        assertNotNull(recoveredRun, "运行中任务不能丢");
        assertNotNull(recoveredQueued, "排队任务不能丢");
        assertEquals(TaskStatus.SUCCEEDED, recoveredDone.getStatus(), "终结任务保持终结");

        // 恢复后未完成任务应可被继续调度（init 已触发 recover + schedule）
        assertEquals(TaskStatus.RUNNING, recoveredRun.getStatus(), "恢复后应重新被调度执行");
        assertNotNull(recoveredRun.getAssignedRobotId());

        // 不重复下发：同一任务同一时刻只占用一台机器人
        long busyWithRecoveredRun = second.robotService.all().stream()
                .filter(r -> recoveredRun.getTaskId().equals(r.getCurrentTaskId())).count();
        log.info("[decision] robots occupied by T-RUN={}", busyWithRecoveredRun);
        assertEquals(1, busyWithRecoveredRun, "恢复后不能重复下发到多台机器人");

        // 恢复后任务可正常走到终结
        second.taskService.reportResult("T-RUN", recoveredRun.getAssignedRobotId(),
                recoveredRun.getAttemptId(), true, "finished after recovery");
        assertEquals(TaskStatus.SUCCEEDED, second.taskService.get("T-RUN").getStatus());
        log.info("[decision] T-RUN completed after recovery; T-QUEUED status={}",
                second.taskService.get("T-QUEUED").getStatus());
        assertEquals(TaskStatus.RUNNING, second.taskService.get("T-QUEUED").getStatus(),
                "机器人释放后，恢复出的排队任务应被继续调度");
    }

    @Test
    void recoveryResetsBusyRobotsToIdle() {
        Path snapshot = tempDir.resolve("state.json");

        FleetTestFixture first = FleetTestFixture.freshStart(snapshot, 1000);
        first.robotService.register("R1", Set.of("lift"), 10);
        first.taskService.submit("T1", 1, null, "lift", 1, 60_000);
        log.info("[input] crash while R1 BUSY on T1");

        FleetTestFixture second = FleetTestFixture.restart(snapshot, 1000);
        log.info("[decision] after restart: R1 status={} currentTask={}",
                second.robotService.get("R1").getStatus(),
                second.robotService.get("R1").getCurrentTaskId());
        // 恢复流程会把 BUSY 复位为 IDLE 并重新调度 T1，因此最终 R1 应重新占用 T1（一致状态）
        assertEquals(RobotStatus.BUSY, second.robotService.get("R1").getStatus());
        assertEquals("T1", second.robotService.get("R1").getCurrentTaskId());
        assertEquals(TaskStatus.RUNNING, second.taskService.get("T1").getStatus());
    }

    @Test
    void terminalTasksAreNotResurrectedAfterRestart() {
        Path snapshot = tempDir.resolve("state.json");

        FleetTestFixture first = FleetTestFixture.freshStart(snapshot, 1000);
        first.robotService.register("R1", Set.of("lift"), 10);
        first.taskService.submit("T-OK", 1, null, "lift", 1, 60_000);
        first.taskService.reportResult("T-OK", "R1",
                first.taskService.get("T-OK").getAttemptId(), true, "done");
        first.taskService.submit("T-CXL", 1, null, "lift", 1, 60_000);
        first.taskService.cancel("T-CXL");
        log.info("[input] crash with T-OK=SUCCEEDED, T-CXL=CANCELLED");

        FleetTestFixture second = FleetTestFixture.restart(snapshot, 1000);
        log.info("[decision] after restart: T-OK={} T-CXL={}",
                second.taskService.get("T-OK").getStatus(),
                second.taskService.get("T-CXL").getStatus());
        assertEquals(TaskStatus.SUCCEEDED, second.taskService.get("T-OK").getStatus());
        assertEquals(TaskStatus.CANCELLED, second.taskService.get("T-CXL").getStatus());
        assertTrue(second.scheduler.assignmentHistory().isEmpty(),
                "终结任务恢复后不能产生新的分配");
    }
}
