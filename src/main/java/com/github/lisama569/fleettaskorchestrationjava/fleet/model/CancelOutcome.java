package com.github.lisama569.fleettaskorchestrationjava.fleet.model;

/**
 * 取消请求的处理结论。
 */
public enum CancelOutcome {
    /** 任务原本排队中，取消成功。 */
    CANCELLED_PENDING,
    /** 任务执行中，取消成功并释放机器人。 */
    CANCELLED_ACTIVE,
    /** 任务已终态，幂等忽略。 */
    ALREADY_TERMINAL,
    /** 任务不存在。 */
    NOT_FOUND
}
