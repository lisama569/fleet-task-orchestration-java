package com.github.lisama569.fleettaskorchestrationjava.fleet.model;

/**
 * 取消处理结果。cancelled=true 表示本次真正发生了取消并释放占用。
 */
public record CancelResult(CancelOutcome outcome, boolean cancelled, String message, Task task) {

    public static CancelResult of(CancelOutcome outcome, boolean cancelled, String message, Task task) {
        return new CancelResult(outcome, cancelled, message, task);
    }
}
