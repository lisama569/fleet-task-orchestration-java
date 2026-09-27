package com.github.lisama569.fleettaskorchestrationjava.service;

import com.github.lisama569.fleettaskorchestrationjava.model.RejectReason;
import com.github.lisama569.fleettaskorchestrationjava.model.Robot;
import com.github.lisama569.fleettaskorchestrationjava.model.RobotStatus;
import com.github.lisama569.fleettaskorchestrationjava.model.Task;
import com.github.lisama569.fleettaskorchestrationjava.model.TaskStatus;
import com.github.lisama569.fleettaskorchestrationjava.store.RobotStore;
import com.github.lisama569.fleettaskorchestrationjava.store.TaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * 任务提交、取消、进度与结果上报。
 * 幂等约定：
 * - 重复提交同一 taskId：任务未终结则直接返回已存在任务（不产生重复分配）；已终结则拒绝。
 * - 重复取消/重复回报同一结果：返回当前状态，不产生重复副作用。
 */
@Service
public class TaskService {
    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    private final TaskStore taskStore;
    private final RobotStore robotStore;
    private final FleetLock fleetLock;
    private final Scheduler scheduler;

    public TaskService(TaskStore taskStore, RobotStore robotStore, FleetLock fleetLock, Scheduler scheduler) {
        this.taskStore = taskStore;
        this.robotStore = robotStore;
        this.fleetLock = fleetLock;
        this.scheduler = scheduler;
    }

    /**
     * 提交任务。校验能力/负载是否可能被满足，不满足则带原因拒绝；
     * 通过则入队并立即触发一轮调度。
     */
    public Task submit(String taskId, int priority, Instant deadline, String requiredCapability,
                       int requiredLoad, long timeoutMillis) {
        return fleetLock.inLock(() -> {
            log.info("[task.submit] input taskId={} priority={} deadline={} capability={} load={} timeoutMs={}",
                    taskId, priority, deadline, requiredCapability, requiredLoad, timeoutMillis);

            Task existing = taskStore.get(taskId);
            if (existing != null) {
                if (existing.getStatus().isTerminal()) {
                    throw new TaskRejectedException(RejectReason.TASK_ALREADY_TERMINAL,
                            "task " + taskId + " already terminal: " + existing.getStatus());
                }
                log.info("[task.submit] duplicate submission ignored, taskId={} status={}",
                        taskId, existing.getStatus());
                return existing; // 幂等去重：不产生重复分配
            }

            List<Robot> online = robotStore.all().stream()
                    .filter(r -> r.getStatus() != RobotStatus.OFFLINE)
                    .toList();
            if (online.isEmpty()) {
                throw new TaskRejectedException(RejectReason.NO_ROBOT_ONLINE,
                        "no online robot registered");
            }
            boolean anyCapable = online.stream()
                    .anyMatch(r -> r.getCapabilities().contains(requiredCapability));
            if (!anyCapable) {
                throw new TaskRejectedException(RejectReason.NO_CAPABLE_ROBOT,
                        "no online robot has capability: " + requiredCapability);
            }
            boolean anyLoadFit = online.stream()
                    .filter(r -> r.getCapabilities().contains(requiredCapability))
                    .anyMatch(r -> r.getMaxLoad() >= requiredLoad);
            if (!anyLoadFit) {
                throw new TaskRejectedException(RejectReason.ROBOT_LOAD_INSUFFICIENT,
                        "required load " + requiredLoad + " exceeds every capable robot's maxLoad");
            }

            Task task = new Task(taskId, priority, deadline, requiredCapability, requiredLoad,
                    timeoutMillis, Instant.now());
            taskStore.putIfAbsent(task);
            log.info("[task.submit] accepted taskId={} -> PENDING", taskId);
            scheduler.scheduleOnce();
            return task;
        });
    }

    /** 取消任务。PENDING 直接取消；RUNNING 取消并释放机器人；终结态幂等返回。 */
    public Task cancel(String taskId) {
        return fleetLock.inLock(() -> {
            Task task = mustGet(taskId);
            log.info("[task.cancel] input taskId={} currentStatus={}", taskId, task.getStatus());
            if (task.getStatus().isTerminal()) {
                log.info("[task.cancel] idempotent, already terminal status={}", task.getStatus());
                return task;
            }
            String robotId = task.getAssignedRobotId();
            task.setStatus(TaskStatus.CANCELLED);
            task.setFinishedAt(Instant.now());
            task.setStatusReason("CANCELLED_BY_USER");
            if (robotId != null) {
                scheduler.releaseRobot(robotId, taskId);
            }
            log.info("[task.cancel] taskId={} -> CANCELLED releasedRobot={}", taskId, robotId);
            scheduler.scheduleOnce();
            return task;
        });
    }

    /** 进度上报。仅 RUNNING 且机器人与尝试号匹配时接受；否则幂等忽略。 */
    public Task reportProgress(String taskId, String robotId, long attemptId, int percent) {
        return fleetLock.inLock(() -> {
            Task task = mustGet(taskId);
            boolean valid = task.getStatus() == TaskStatus.RUNNING
                    && robotId.equals(task.getAssignedRobotId())
                    && attemptId == task.getAttemptId();
            log.info("[task.progress] input taskId={} robot={} attempt={} percent={} -> accepted={} (status={})",
                    taskId, robotId, attemptId, percent, valid, task.getStatus());
            // 进度不改变状态机，重复上报天然无副作用
            return task;
        });
    }

    /**
     * 结果回报。仅 RUNNING 且机器人与尝试号匹配时生效；
     * 重复回报同一结果（或终结后迟到回报）幂等忽略，不产生重复副作用。
     */
    public Task reportResult(String taskId, String robotId, long attemptId, boolean success, String detail) {
        return fleetLock.inLock(() -> {
            Task task = mustGet(taskId);
            log.info("[task.result] input taskId={} robot={} attempt={} success={} detail={} currentStatus={}",
                    taskId, robotId, attemptId, success, detail, task.getStatus());

            if (task.getStatus().isTerminal()) {
                log.info("[task.result] idempotent ignore, already terminal status={}", task.getStatus());
                return task;
            }
            if (task.getStatus() != TaskStatus.RUNNING
                    || !robotId.equals(task.getAssignedRobotId())
                    || attemptId != task.getAttemptId()) {
                log.info("[task.result] stale report ignored (status={}, assignedRobot={}, attempt={})",
                        task.getStatus(), task.getAssignedRobotId(), task.getAttemptId());
                return task;
            }
            task.setStatus(success ? TaskStatus.SUCCEEDED : TaskStatus.FAILED);
            task.setFinishedAt(Instant.now());
            task.setStatusReason(success ? "REPORTED_SUCCESS" : "REPORTED_FAILURE: " + detail);
            scheduler.releaseRobot(robotId, taskId);
            log.info("[task.result] taskId={} -> {} robotReleased={}", taskId, task.getStatus(), robotId);
            scheduler.scheduleOnce();
            return task;
        });
    }

    public Task get(String taskId) {
        return taskStore.get(taskId);
    }

    public Collection<Task> all() {
        return taskStore.all();
    }

    private Task mustGet(String taskId) {
        Task task = taskStore.get(taskId);
        if (task == null) {
            throw new IllegalArgumentException("task not found: " + taskId);
        }
        return task;
    }
}
