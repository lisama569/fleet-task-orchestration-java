package com.github.lisama569.fleettaskorchestrationjava.fleet.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 车队编排本地配置。 */
@ConfigurationProperties(prefix = "fleet")
public class FleetProperties {
    /** 任务持久化文件路径（本地 JSON）。 */
    private String taskStorePath = "data/fleet-tasks.json";
    /** 是否启动后台调度循环。 */
    private boolean schedulerEnabled = true;
    /** 调度循环间隔（ms）。 */
    private long scheduleIntervalMs = 200;
    /** 任务排队超过该时长（ms）后触发优先级提升，0 表示禁用。 */
    private long agingAfterMs = 5000;
    /** 每次提升的优先级幅度。 */
    private int agingBoost = 10;
    /** 两次提升之间的最小间隔（ms）。 */
    private long agingIntervalMs = 2000;
    /** 是否在启动时运行本地自检演示。 */
    private boolean demoEnabled = true;

    public String getTaskStorePath() { return taskStorePath; }
    public void setTaskStorePath(String taskStorePath) { this.taskStorePath = taskStorePath; }
    public boolean isSchedulerEnabled() { return schedulerEnabled; }
    public void setSchedulerEnabled(boolean schedulerEnabled) { this.schedulerEnabled = schedulerEnabled; }
    public long getScheduleIntervalMs() { return scheduleIntervalMs; }
    public void setScheduleIntervalMs(long scheduleIntervalMs) { this.scheduleIntervalMs = scheduleIntervalMs; }
    public long getAgingAfterMs() { return agingAfterMs; }
    public void setAgingAfterMs(long agingAfterMs) { this.agingAfterMs = agingAfterMs; }
    public int getAgingBoost() { return agingBoost; }
    public void setAgingBoost(int agingBoost) { this.agingBoost = agingBoost; }
    public long getAgingIntervalMs() { return agingIntervalMs; }
    public void setAgingIntervalMs(long agingIntervalMs) { this.agingIntervalMs = agingIntervalMs; }
    public boolean isDemoEnabled() { return demoEnabled; }
    public void setDemoEnabled(boolean demoEnabled) { this.demoEnabled = demoEnabled; }
}
