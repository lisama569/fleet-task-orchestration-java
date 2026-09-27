package com.github.lisama569.fleettaskorchestrationjava.fleet.model;

/**
 * 任务本次未能立即分配时，可区分的拒绝/延后原因码。
 */
public enum RejectReason {
    /** 分配成功，无拒绝原因。 */
    NONE,
    /** 幂等拦截：相同 taskId 已提交过，直接返回原任务，不产生重复分配。 */
    DUPLICATE_SUBMISSION,
    /** 没有注册机器人，或所有机器人都离线。 */
    ALL_ROBOTS_OFFLINE,
    /** 在线机器人全部占用中（互斥约束导致暂时无空闲机器人）。 */
    ALL_ROBOTS_BUSY,
    /** 空闲机器人存在，但没有一个具备任务要求的能力。 */
    CAPABILITY_MISMATCH,
    /** 具备能力的空闲机器人存在，但负载能力都不满足。 */
    LOAD_UNSUPPORTED,
    /** 请求参数非法。 */
    INVALID_REQUEST
}
