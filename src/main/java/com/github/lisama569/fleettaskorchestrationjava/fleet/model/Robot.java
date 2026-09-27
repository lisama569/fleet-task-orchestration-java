package com.github.lisama569.fleettaskorchestrationjava.fleet.model;

import java.util.Set;

/**
 * 机器人聚合（可变，访问由 FleetService 内的统一锁保护）。
 * BUSY 状态下 currentTaskId 记录其持有的唯一任务，保证互斥。
 */
public class Robot {
    private final String id;
    private final Set<String> capabilities;
    private final int capacity;
    private RobotStatus status = RobotStatus.IDLE;
    private String currentTaskId;
    private int completedCount;
    private long lastFreedAtMs;

    public Robot(String id, Set<String> capabilities, int capacity) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("robotId 不能为空");
        }
        if (capabilities == null || capabilities.isEmpty()) {
            throw new IllegalArgumentException("机器人至少需要一种能力: " + id);
        }
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity 必须为正数: " + capacity);
        }
        this.id = id;
        this.capabilities = Set.copyOf(capabilities);
        this.capacity = capacity;
    }

    public boolean canServe(Task task) {
        return capabilities.contains(task.getCapability()) && capacity >= task.getLoad();
    }

    public void assign(String taskId, long nowMs) {
        if (this.status != RobotStatus.IDLE) {
            throw new IllegalStateException("机器人非 IDLE，不能派单: " + id + " -> " + taskId);
        }
        this.status = RobotStatus.BUSY;
        this.currentTaskId = taskId;
        this.lastFreedAtMs = nowMs;
    }

    /** 完成/取消/超时/掉线后释放，机器人重新成为 IDLE 并参与分配。 */
    public void release(long nowMs) {
        this.status = RobotStatus.IDLE;
        if (this.currentTaskId != null) {
            this.completedCount++;
        }
        this.currentTaskId = null;
        this.lastFreedAtMs = nowMs;
    }

    public void markOffline() {
        this.status = RobotStatus.OFFLINE;
        this.currentTaskId = null;
    }

    public void markOnline() {
        if (this.status == RobotStatus.OFFLINE) {
            this.status = RobotStatus.IDLE;
            this.currentTaskId = null;
        }
    }

    /** 重启恢复：BUSY 归属已失效，重置为 IDLE。 */
    public void recoverIdle() {
        if (this.status == RobotStatus.BUSY) {
            this.status = RobotStatus.IDLE;
            this.currentTaskId = null;
        }
    }

    public String getId() { return id; }
    public Set<String> getCapabilities() { return capabilities; }
    public int getCapacity() { return capacity; }
    public RobotStatus getStatus() { return status; }
    public String getCurrentTaskId() { return currentTaskId; }
    public int getCompletedCount() { return completedCount; }
    public long getLastFreedAtMs() { return lastFreedAtMs; }

    @Override
    public String toString() {
        return "Robot{" + id + ", " + status
                + (currentTaskId != null ? ", task=" + currentTaskId : "")
                + ", caps=" + capabilities + ", cap=" + capacity + "}";
    }
}
