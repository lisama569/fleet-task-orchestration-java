package com.github.lisama569.fleettaskorchestrationjava.fleet.model;

/**
 * 进度/结果回报的处理结论。changed=false 的均为幂等忽略，不产生副作用。
 */
public enum ReportOutcome {
    /** 进度已更新（产生副作用）。 */
    PROGRESS_ACCEPTED,
    /** 首次回报成功，任务进入 COMPLETED。 */
    SUCCEEDED,
    /** 首次回报失败，任务进入 FAILED。 */
    FAILED_ACCEPTED,
    /** 任务不存在。 */
    NOT_FOUND,
    /** 回报方机器人不是任务当前归属者（可能已被重新分配），拒绝。 */
    WRONG_OWNER,
    /** 重复回报同一个终态结果，幂等忽略。 */
    DUPLICATE_IGNORED,
    /** 任务尚未分配，不能上报。 */
    NOT_ACTIVE
}
