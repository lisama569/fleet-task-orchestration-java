package com.github.lisama569.fleettaskorchestrationjava.service;

import com.github.lisama569.fleettaskorchestrationjava.model.RejectReason;

/**
 * 任务提交被拒绝，携带可区分的拒绝原因。
 */
public class TaskRejectedException extends RuntimeException {
    private final RejectReason reason;

    public TaskRejectedException(RejectReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RejectReason getReason() {
        return reason;
    }
}
