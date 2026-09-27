package com.github.lisama569.fleettaskorchestrationjava.fleet.model;

/**
 * 任务聚合（可变，访问由 FleetService 内的统一锁保护）。
 * revision 单调递增：任何真正发生的状态推进都会 +1，重复上报因不产生副作用而保持不变。
 */
public class Task {
    private final String id;
    private final String capability;
    private final int priority;
    private final long deadlineEpochMs;
    private final int load;
    private final long durationMs;
    private final boolean failBeforeComplete;
    private final long timeoutMs;
    private final long createdAtMs;

    private TaskStatus status = TaskStatus.PENDING;
    private String assignedRobotId;
    private long allocatedAtMs;
    private long startedAtMs;
    private long finishedAtMs;
    private int progressPercent;
    private int priorityBoost;
    private String lastDecisionReason;
    private String errorCode;
    private long revision;

    public Task(TaskRequest req, long nowMs) {
        this.id = req.taskId();
        this.capability = req.capability();
        this.priority = req.priority();
        this.deadlineEpochMs = req.deadlineEpochMs();
        this.load = req.load();
        this.durationMs = req.durationMs();
        this.failBeforeComplete = req.failBeforeComplete();
        this.timeoutMs = req.timeoutMs();
        this.createdAtMs = nowMs;
    }

    /** 供存储层反序列化使用的全参构造。 */
    public Task(String id, String capability, int priority, long deadlineEpochMs, int load, long durationMs,
                boolean failBeforeComplete, long timeoutMs, long createdAtMs, TaskStatus status,
                String assignedRobotId, long allocatedAtMs, long startedAtMs, long finishedAtMs,
                int progressPercent, int priorityBoost, String lastDecisionReason, String errorCode,
                long revision) {
        this.id = id;
        this.capability = capability;
        this.priority = priority;
        this.deadlineEpochMs = deadlineEpochMs;
        this.load = load;
        this.durationMs = durationMs;
        this.failBeforeComplete = failBeforeComplete;
        this.timeoutMs = timeoutMs;
        this.createdAtMs = createdAtMs;
        this.status = status;
        this.assignedRobotId = assignedRobotId;
        this.allocatedAtMs = allocatedAtMs;
        this.startedAtMs = startedAtMs;
        this.finishedAtMs = finishedAtMs;
        this.progressPercent = progressPercent;
        this.priorityBoost = priorityBoost;
        this.lastDecisionReason = lastDecisionReason;
        this.errorCode = errorCode;
        this.revision = revision;
    }

    /** 有效优先级 = 基础优先级 + 长期排队提升量。 */
    public int effectivePriority() {
        return priority + priorityBoost;
    }

    /** 是否仍可被调度（未到终态且当前没有占用机器人）。 */
    public boolean schedulable() {
        return status == TaskStatus.PENDING;
    }

    public void markAllocated(String robotId, long nowMs, String decisionReason) {
        this.status = TaskStatus.ALLOCATED;
        this.assignedRobotId = robotId;
        this.allocatedAtMs = nowMs;
        this.startedAtMs = 0L;
        this.progressPercent = 0;
        this.lastDecisionReason = decisionReason;
        this.revision++;
    }

    public void markStarted(long nowMs) {
        if (this.status == TaskStatus.ALLOCATED) {
            this.status = TaskStatus.IN_PROGRESS;
            this.startedAtMs = nowMs;
            this.revision++;
        }
    }

    public void updateProgress(int percent) {
        int clamped = Math.max(0, Math.min(100, percent));
        if (this.status.isActive() && clamped > this.progressPercent) {
            this.progressPercent = clamped;
            this.revision++;
        }
    }

    public void markCompleted(long nowMs) {
        this.status = TaskStatus.COMPLETED;
        this.progressPercent = 100;
        this.finishedAtMs = nowMs;
        this.revision++;
    }

    public void markFailed(long nowMs, String errorCode) {
        this.status = TaskStatus.FAILED;
        this.finishedAtMs = nowMs;
        this.errorCode = errorCode;
        this.revision++;
    }

    public void markCancelled(long nowMs, String errorCode) {
        this.status = TaskStatus.CANCELLED;
        this.finishedAtMs = nowMs;
        this.errorCode = errorCode;
        this.revision++;
    }

    public void markTimedOut(long nowMs, String errorCode) {
        this.status = TaskStatus.TIMED_OUT;
        this.finishedAtMs = nowMs;
        this.errorCode = errorCode;
        this.revision++;
    }

    /** 任务被退回排队（机器人掉线/替换、重启恢复、调度中断），释放归属。 */
    public void requeue(String why) {
        this.status = TaskStatus.PENDING;
        this.assignedRobotId = null;
        this.allocatedAtMs = 0L;
        this.startedAtMs = 0L;
        this.progressPercent = 0;
        this.lastDecisionReason = why;
        this.revision++;
    }

    public void addPriorityBoost(int delta) {
        if (delta > 0) {
            this.priorityBoost += delta;
            this.revision++;
        }
    }

    public String getId() { return id; }
    public String getCapability() { return capability; }
    public int getPriority() { return priority; }
    public long getDeadlineEpochMs() { return deadlineEpochMs; }
    public int getLoad() { return load; }
    public long getDurationMs() { return durationMs; }
    public boolean isFailBeforeComplete() { return failBeforeComplete; }
    public long getTimeoutMs() { return timeoutMs; }
    public long getCreatedAtMs() { return createdAtMs; }
    public TaskStatus getStatus() { return status; }
    public String getAssignedRobotId() { return assignedRobotId; }
    public long getAllocatedAtMs() { return allocatedAtMs; }
    public long getStartedAtMs() { return startedAtMs; }
    public long getFinishedAtMs() { return finishedAtMs; }
    public int getProgressPercent() { return progressPercent; }
    public int getPriorityBoost() { return priorityBoost; }
    public String getLastDecisionReason() { return lastDecisionReason; }
    public String getErrorCode() { return errorCode; }
    public long getRevision() { return revision; }

    public void setLastDecisionReason(String lastDecisionReason) {
        this.lastDecisionReason = lastDecisionReason;
    }

    @Override
    public String toString() {
        return "Task{" + id + ", " + status
                + (assignedRobotId != null ? ", robot=" + assignedRobotId : "")
                + ", effPri=" + effectivePriority()
                + ", progress=" + progressPercent + "%}";
    }
}
