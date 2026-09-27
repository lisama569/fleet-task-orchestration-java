package com.github.lisama569.fleettaskorchestrationjava.service;

import com.github.lisama569.fleettaskorchestrationjava.model.Assignment;
import com.github.lisama569.fleettaskorchestrationjava.model.Robot;
import com.github.lisama569.fleettaskorchestrationjava.model.RobotStatus;
import com.github.lisama569.fleettaskorchestrationjava.model.Task;
import com.github.lisama569.fleettaskorchestrationjava.model.TaskStatus;
import com.github.lisama569.fleettaskorchestrationjava.store.RobotStore;
import com.github.lisama569.fleettaskorchestrationjava.store.TaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 调度器：按有效优先级与截止时间排队，把任务分配给合适的空闲机器人。
 * 所有状态推进都在 FleetLock 内完成；synchronized 可重入，服务层持锁调用安全。
 */
@Service
public class Scheduler {
    private static final Logger log = LoggerFactory.getLogger(Scheduler.class);

    /** 排队超过该时长未调度，触发一次老化提优 */
    private final long agingThresholdMillis;
    /** 老化提优上限，防止无限膨胀 */
    private static final int MAX_BOOSTS = 10;

    private final TaskStore taskStore;
    private final RobotStore robotStore;
    private final FleetLock fleetLock;
    private final AtomicLong attemptSequence = new AtomicLong(0);
    private final List<Assignment> assignmentHistory = new CopyOnWriteArrayList<>();

    public Scheduler(TaskStore taskStore, RobotStore robotStore, FleetLock fleetLock,
                     @Value("${fleet.aging-threshold-millis:5000}") long agingThresholdMillis) {
        this.taskStore = taskStore;
        this.robotStore = robotStore;
        this.fleetLock = fleetLock;
        this.agingThresholdMillis = agingThresholdMillis;
    }

    /**
     * 尝试把排队任务分配给空闲机器人。
     * 排队规则：有效优先级降序 -> 截止时间升序（无截止排最后）-> 提交时间升序。
     * 选机器人规则：能力匹配且负载足够的空闲机器人中，maxLoad 最小者（best-fit），并列取 robotId 字典序小者。
     */
    public List<Assignment> scheduleOnce() {
        return fleetLock.inLock(() -> {
            List<Assignment> made = new ArrayList<>();
            List<Task> pending = taskStore.all().stream()
                    .filter(t -> t.getStatus() == TaskStatus.PENDING)
                    .sorted(Comparator
                            .comparingInt(Task::effectivePriority).reversed()
                            .thenComparing(t -> t.getDeadline() == null ? Instant.MAX : t.getDeadline())
                            .thenComparing(Task::getSubmittedAt))
                    .toList();

            for (Task task : pending) {
                List<Robot> candidates = robotStore.all().stream()
                        .filter(r -> r.canServe(task))
                        .sorted(Comparator.comparingInt(Robot::getMaxLoad)
                                .thenComparing(Robot::getRobotId))
                        .toList();
                if (candidates.isEmpty()) {
                    log.info("[schedule] task={} no idle capable robot, stays PENDING " +
                                    "(effectivePriority={}, deadline={}, capability={}, requiredLoad={})",
                            task.getTaskId(), task.effectivePriority(), task.getDeadline(),
                            task.getRequiredCapability(), task.getRequiredLoad());
                    continue;
                }
                Robot chosen = candidates.get(0);
                String explanation = String.format(
                        "chosen=%s among candidates=%s by best-fit(smallest maxLoad>=%d, tie-break robotId); " +
                        "capability=%s matched; effectivePriority=%d(base=%d+boost=%d); deadline=%s",
                        chosen.getRobotId(),
                        candidates.stream().map(r -> r.getRobotId() + "(maxLoad=" + r.getMaxLoad() + ")").toList(),
                        task.getRequiredLoad(), task.getRequiredCapability(),
                        task.effectivePriority(), task.getBasePriority(), task.getPriorityBoosts(),
                        task.getDeadline());

                long attempt = attemptSequence.incrementAndGet();
                task.setAttemptId(attempt);
                task.setAssignedRobotId(chosen.getRobotId());
                task.setStatus(TaskStatus.ASSIGNED);
                chosen.setStatus(RobotStatus.BUSY);
                chosen.setCurrentTaskId(task.getTaskId());
                // 模拟设备即时确认：ASSIGNED -> RUNNING
                task.setStatus(TaskStatus.RUNNING);
                task.setStartedAt(Instant.now());
                task.setStatusReason("ASSIGNED");

                Assignment assignment = new Assignment(task.getTaskId(), chosen.getRobotId(), attempt,
                        task.effectivePriority(), Instant.now(), explanation);
                assignmentHistory.add(assignment);
                made.add(assignment);
                log.info("[schedule] assigned task={} robot={} attempt={} | {}", task.getTaskId(),
                        chosen.getRobotId(), attempt, explanation);
            }
            return made;
        });
    }

    /** 把任务交还调度队列（机器人掉线/替换/执行失败可重试时调用）。幂等：仅对非终结任务生效。 */
    public void requeueTask(String taskId, String reason) {
        fleetLock.inLock(() -> {
            Task task = taskStore.get(taskId);
            if (task == null || task.getStatus().isTerminal() || task.getStatus() == TaskStatus.PENDING) {
                log.info("[requeue] task={} skipped (status={})", taskId,
                        task == null ? "MISSING" : task.getStatus());
                return;
            }
            task.setStatus(TaskStatus.PENDING);
            task.setAssignedRobotId(null);
            task.setStartedAt(null);
            task.setStatusReason(reason);
            log.info("[requeue] task={} back to PENDING, reason={}", taskId, reason);
        });
    }

    /** 释放机器人占用（任务终结时调用）。幂等。 */
    public void releaseRobot(String robotId, String taskId) {
        fleetLock.inLock(() -> {
            Robot robot = robotStore.get(robotId);
            if (robot == null) {
                return;
            }
            if (taskId.equals(robot.getCurrentTaskId())) {
                robot.setCurrentTaskId(null);
                if (robot.getStatus() == RobotStatus.BUSY) {
                    robot.setStatus(RobotStatus.IDLE);
                }
                log.info("[release] robot={} released from task={}", robotId, taskId);
            }
        });
    }

    /** 扫描超时任务：RUNNING 且超过 timeoutMillis 未终结 -> TIMED_OUT，释放机器人。幂等。 */
    public void scanTimeouts() {
        fleetLock.inLock(() -> {
            Instant now = Instant.now();
            boolean changed = false;
            for (Task task : taskStore.all()) {
                if (task.getStatus() == TaskStatus.RUNNING && task.getTimeoutMillis() > 0
                        && task.getStartedAt() != null
                        && task.getStartedAt().plusMillis(task.getTimeoutMillis()).isBefore(now)) {
                    task.setStatus(TaskStatus.TIMED_OUT);
                    task.setFinishedAt(now);
                    task.setStatusReason("TIMEOUT_AFTER_" + task.getTimeoutMillis() + "MS");
                    String robotId = task.getAssignedRobotId();
                    log.info("[timeout] task={} robot={} startedAt={} timeoutMillis={}",
                            task.getTaskId(), robotId, task.getStartedAt(), task.getTimeoutMillis());
                    if (robotId != null) {
                        releaseRobot(robotId, task.getTaskId());
                    }
                    changed = true;
                }
            }
            if (changed) {
                scheduleOnce();
            }
        });
    }

    /** 老化提优：排队超过阈值的 PENDING 任务提升有效优先级。幂等且单调。 */
    public void boostAgedTasks() {
        fleetLock.inLock(() -> {
            Instant now = Instant.now();
            for (Task task : taskStore.all()) {
                if (task.getStatus() != TaskStatus.PENDING || task.getPriorityBoosts() >= MAX_BOOSTS) {
                    continue;
                }
                long waitedMillis = now.toEpochMilli() - task.getSubmittedAt().toEpochMilli();
                int deserved = (int) Math.min(MAX_BOOSTS, waitedMillis / agingThresholdMillis);
                if (deserved > task.getPriorityBoosts()) {
                    task.setPriorityBoosts(deserved);
                    log.info("[aging] task={} waited={}ms boosts={} effectivePriority={}",
                            task.getTaskId(), waitedMillis, deserved, task.effectivePriority());
                }
            }
        });
    }

    /**
     * 重启恢复：ASSIGNED/RUNNING 任务回退为 PENDING 重新排队（不重复下发，因为占用全部释放）；
     * BUSY 机器人复位为 IDLE。恢复后调用方应触发一次 scheduleOnce。
     */
    public void recoverOnStartup() {
        fleetLock.inLock(() -> {
            for (Task task : taskStore.all()) {
                if (task.getStatus() == TaskStatus.ASSIGNED || task.getStatus() == TaskStatus.RUNNING) {
                    task.setStatus(TaskStatus.PENDING);
                    task.setAssignedRobotId(null);
                    task.setStartedAt(null);
                    task.setStatusReason("RECOVERED_AFTER_RESTART");
                    log.info("[recover] task={} reset to PENDING", task.getTaskId());
                }
            }
            for (Robot robot : robotStore.all()) {
                if (robot.getStatus() == RobotStatus.BUSY) {
                    robot.setStatus(RobotStatus.IDLE);
                    robot.setCurrentTaskId(null);
                    log.info("[recover] robot={} reset to IDLE", robot.getRobotId());
                }
            }
        });
    }

    /** 周期性后台维护：超时扫描 + 老化提优 + 补调度。 */
    @Scheduled(fixedDelayString = "${fleet.housekeeping-interval-millis:1000}")
    public void housekeeping() {
        scanTimeouts();
        boostAgedTasks();
        scheduleOnce();
    }

    public List<Assignment> assignmentHistory() {
        return List.copyOf(assignmentHistory);
    }

    public List<Assignment> assignmentsFor(String taskId) {
        return assignmentHistory.stream().filter(a -> a.taskId().equals(taskId)).toList();
    }
}
