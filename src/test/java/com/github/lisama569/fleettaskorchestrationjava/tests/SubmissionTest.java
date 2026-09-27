package com.github.lisama569.fleettaskorchestrationjava.tests;

import com.github.lisama569.fleettaskorchestrationjava.model.RejectReason;
import com.github.lisama569.fleettaskorchestrationjava.model.Task;
import com.github.lisama569.fleettaskorchestrationjava.model.TaskStatus;
import com.github.lisama569.fleettaskorchestrationjava.service.TaskRejectedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 提交校验：各类拒绝原因可区分；重复提交幂等。
 */
class SubmissionTest {
    private static final Logger log = LoggerFactory.getLogger(SubmissionTest.class);

    @TempDir
    Path tempDir;

    private FleetTestFixture fixture() {
        return FleetTestFixture.freshStart(tempDir.resolve("state.json"), 1000);
    }

    @Test
    void rejectWhenNoRobotOnline() {
        FleetTestFixture f = fixture();
        log.info("[input] submit task=T1 capability=lift load=1, onlineRobots=0");
        TaskRejectedException e = assertThrows(TaskRejectedException.class,
                () -> f.taskService.submit("T1", 1, null, "lift", 1, 60_000));
        log.info("[decision] rejectReason={}", e.getReason());
        assertEquals(RejectReason.NO_ROBOT_ONLINE, e.getReason());
    }

    @Test
    void rejectWhenNoCapableRobot() {
        FleetTestFixture f = fixture();
        f.robotService.register("R1", Set.of("carry"), 10);
        log.info("[input] submit task=T1 capability=lift, robots=[R1(carry)]");
        TaskRejectedException e = assertThrows(TaskRejectedException.class,
                () -> f.taskService.submit("T1", 1, null, "lift", 1, 60_000));
        log.info("[decision] rejectReason={} (capability mismatch)", e.getReason());
        assertEquals(RejectReason.NO_CAPABLE_ROBOT, e.getReason());
    }

    @Test
    void rejectWhenLoadExceedsEveryCapableRobot() {
        FleetTestFixture f = fixture();
        f.robotService.register("R1", Set.of("lift"), 5);
        log.info("[input] submit task=T1 capability=lift load=99, R1.maxLoad=5");
        TaskRejectedException e = assertThrows(TaskRejectedException.class,
                () -> f.taskService.submit("T1", 1, null, "lift", 99, 60_000));
        log.info("[decision] rejectReason={} (requiredLoad=99 > maxLoad=5)", e.getReason());
        assertEquals(RejectReason.ROBOT_LOAD_INSUFFICIENT, e.getReason());
    }

    @Test
    void duplicateSubmissionIsIdempotentAndTerminalResubmitRejected() {
        FleetTestFixture f = fixture();
        f.robotService.register("R1", Set.of("lift"), 10);

        Task first = f.taskService.submit("T1", 1, null, "lift", 1, 60_000);
        log.info("[input] submit T1 twice; firstStatus={}", first.getStatus());
        Task second = f.taskService.submit("T1", 9, null, "lift", 1, 60_000);
        log.info("[decision] duplicate returned existing task, status={} assignments={}",
                second.getStatus(), f.scheduler.assignmentsFor("T1").size());
        assertSame(first, second);
        assertEquals(1, f.scheduler.assignmentsFor("T1").size(), "重复提交不能产生重复分配");

        f.taskService.reportResult("T1", "R1", first.getAttemptId(), true, "done");
        log.info("[input] resubmit T1 after terminal status={}", first.getStatus());
        TaskRejectedException e = assertThrows(TaskRejectedException.class,
                () -> f.taskService.submit("T1", 1, null, "lift", 1, 60_000));
        log.info("[decision] rejectReason={}", e.getReason());
        assertEquals(RejectReason.TASK_ALREADY_TERMINAL, e.getReason());
        assertEquals(TaskStatus.SUCCEEDED, first.getStatus());
    }

    @Test
    void busyRobotDoesNotRejectButQueues() {
        FleetTestFixture f = fixture();
        f.robotService.register("R1", Set.of("lift"), 10);
        f.taskService.submit("T1", 1, null, "lift", 1, 60_000);
        log.info("[input] R1 busy with T1; submit T2 same capability");
        Task t2 = f.taskService.submit("T2", 1, null, "lift", 1, 60_000);
        log.info("[decision] T2 status={} (机器人忙时排队而非拒绝)", t2.getStatus());
        assertEquals(TaskStatus.PENDING, t2.getStatus());
    }
}
