package com.github.lisama569.fleettaskorchestrationjava.fleet.model;

/**
 * 回报处理结果。changed=true 表示本次真正发生了状态推进/副作用。
 */
public record ReportResult(ReportOutcome outcome, boolean changed, String message, Task task) {

    public static ReportResult of(ReportOutcome outcome, boolean changed, String message, Task task) {
        return new ReportResult(outcome, changed, message, task);
    }
}
