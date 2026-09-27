package com.github.lisama569.fleettaskorchestrationjava.fleet.model;

/**
 * 提交结果：区分“幂等重复提交 / 立即分配成功 / 进入排队（带可区分原因） / 非法请求”。
 */
public record SubmitResult(boolean duplicate,
                           boolean allocated,
                           RejectReason reason,
                           String message,
                           Task task) {

    public static SubmitResult duplicate(Task existing) {
        return new SubmitResult(true,
                existing.getStatus().isActive(),
                RejectReason.DUPLICATE_SUBMISSION,
                "任务已存在，幂等返回，不产生重复分配", existing);
    }

    public static SubmitResult allocated(Task task, String message) {
        return new SubmitResult(false, true, RejectReason.NONE, message, task);
    }

    public static SubmitResult queued(Task task, RejectReason code, String message) {
        return new SubmitResult(false, false, code, message, task);
    }

    public static SubmitResult invalid(String message) {
        return new SubmitResult(false, false, RejectReason.INVALID_REQUEST, message, null);
    }
}
