package com.github.lisama569.fleettaskorchestrationjava.model;

import java.util.Set;

/**
 * 机器人实体。同一时刻最多执行一个互斥任务（currentTaskId 唯一占用）。
 */
public class Robot {
    private final String robotId;
    private final Set<String> capabilities;
    private final int maxLoad;

    private volatile RobotStatus status = RobotStatus.IDLE;
    private volatile String currentTaskId;

    public Robot(String robotId, Set<String> capabilities, int maxLoad) {
        this.robotId = robotId;
        this.capabilities = Set.copyOf(capabilities);
        this.maxLoad = maxLoad;
    }

    public String getRobotId() { return robotId; }
    public Set<String> getCapabilities() { return capabilities; }
    public int getMaxLoad() { return maxLoad; }
    public RobotStatus getStatus() { return status; }
    public void setStatus(RobotStatus status) { this.status = status; }
    public String getCurrentTaskId() { return currentTaskId; }
    public void setCurrentTaskId(String currentTaskId) { this.currentTaskId = currentTaskId; }

    public boolean canServe(Task task) {
        return status == RobotStatus.IDLE
                && capabilities.contains(task.getRequiredCapability())
                && maxLoad >= task.getRequiredLoad();
    }
}
