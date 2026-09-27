package com.github.lisama569.fleettaskorchestrationjava.model;

/**
 * 任务状态机：
 * PENDING -> ASSIGNED -> RUNNING -> SUCCEEDED / FAILED / TIMED_OUT / CANCELLED
 * PENDING -> CANCELLED
 * ASSIGNED/RUNNING 在服务重启恢复时回退为 PENDING。
 */
public enum TaskStatus {
    PENDING,
    ASSIGNED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == TIMED_OUT || this == CANCELLED;
    }
}
