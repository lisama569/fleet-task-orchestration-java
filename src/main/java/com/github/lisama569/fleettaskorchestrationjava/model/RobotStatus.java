package com.github.lisama569.fleettaskorchestrationjava.model;

/**
 * 机器人状态：IDLE 可分配，BUSY 执行互斥任务中，OFFLINE 掉线不参与分配。
 */
public enum RobotStatus {
    IDLE,
    BUSY,
    OFFLINE
}
