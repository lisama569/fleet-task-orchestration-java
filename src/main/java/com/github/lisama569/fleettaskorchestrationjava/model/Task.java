package com.github.lisama569.fleettaskorchestrationjava.model;

import java.time.Instant;

/**
 * 任务实体。所有字段的可变操作都必须由 TaskService 在调度锁内完成。
 */
public class Task {
    private final String taskId;
    private final int basePriority;
    private final Instant deadline;
    private final String requiredCapability;
    private final int requiredLoad;
    private final long timeoutMillis;
    private final Instant submittedAt;

    private volatile TaskStatus status = TaskStatus.PENDING;
    private volatile String assignedRobotId;
    private volatile long attemptId;
    private volatile Instant startedAt;
    private volatile Instant finishedAt;
    private volatile String statusReason = "SUBMITTED";
    /** 已生效的优先级提升次数（排队老化） */
    private volatile int priorityBoosts;

    public Task(String taskId, int basePriority, Instant deadline, String requiredCapability,
                int requiredLoad, long timeoutMillis, Instant submittedAt) {
        this.taskId = taskId;
        this.basePriority = basePriority;
        this.deadline = deadline;
        this.requiredCapability = requiredCapability;
        this.requiredLoad = requiredLoad;
        this.timeoutMillis = timeoutMillis;
        this.submittedAt = submittedAt;
    }

    public String getTaskId() { return taskId; }
    public int getBasePriority() { return basePriority; }
    public Instant getDeadline() { return deadline; }
    public String getRequiredCapability() { return requiredCapability; }
    public int getRequiredLoad() { return requiredLoad; }
    public long getTimeoutMillis() { return timeoutMillis; }
    public Instant getSubmittedAt() { return submittedAt; }
    public TaskStatus getStatus() { return status; }
    public void setStatus(TaskStatus status) { this.status = status; }
    public String getAssignedRobotId() { return assignedRobotId; }
    public void setAssignedRobotId(String assignedRobotId) { this.assignedRobotId = assignedRobotId; }
    public long getAttemptId() { return attemptId; }
    public void setAttemptId(long attemptId) { this.attemptId = attemptId; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public String getStatusReason() { return statusReason; }
    public void setStatusReason(String statusReason) { this.statusReason = statusReason; }
    public int getPriorityBoosts() { return priorityBoosts; }
    public void setPriorityBoosts(int priorityBoosts) { this.priorityBoosts = priorityBoosts; }

    /** 有效优先级 = 基础优先级 + 老化提升 */
    public int effectivePriority() {
        return basePriority + priorityBoosts;
    }
}
