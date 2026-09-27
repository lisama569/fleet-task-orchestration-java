package com.github.lisama569.fleettaskorchestrationjava.fleet.model;

/**
 * 机器人状态：IDLE（在线空闲）/ BUSY（在线占用中）/ OFFLINE（掉线）。
 * 互斥约束：BUSY 机器人同一时刻只能持有一个任务。
 */
public enum RobotStatus {
    IDLE,
    BUSY,
    OFFLINE
}
