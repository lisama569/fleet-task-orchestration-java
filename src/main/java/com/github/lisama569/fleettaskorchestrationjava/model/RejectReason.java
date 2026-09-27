package com.github.lisama569.fleettaskorchestrationjava.model;

/**
 * 任务提交被拒绝的可区分原因。
 */
public enum RejectReason {
    /** 同一个 taskId 已存在且未终结（幂等去重时不算错误，由调用方判断） */
    DUPLICATE_TASK,
    /** 已终结的任务被重复提交 */
    TASK_ALREADY_TERMINAL,
    /** 没有任何在线机器人具备任务要求的能力 */
    NO_CAPABLE_ROBOT,
    /** 有能力匹配的机器人，但负载能力均不满足任务要求 */
    ROBOT_LOAD_INSUFFICIENT,
    /** 当前没有任何在线机器人 */
    NO_ROBOT_ONLINE
}
