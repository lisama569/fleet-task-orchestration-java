package com.github.lisama569.fleettaskorchestrationjava.fleet.model;

/**
 * 任务状态机：
 * <pre>
 *   PENDING ──分配──▶ ALLOCATED ──首个进度──▶ IN_PROGRESS ──成功──▶ COMPLETED
 *      │                  │                        │
 *      │                  ├──取消/超时/失败────────┤
 *      │                  ▼                        ▼
 *      └──────────────▶ CANCELLED / TIMED_OUT / FAILED （均为终态）
 *   ALLOCATED/IN_PROGRESS 在机器人掉线或重启恢复时回退到 PENDING（可重新调度）
 * </pre>
 */
public enum TaskStatus {
    PENDING,
    ALLOCATED,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    CANCELLED,
    TIMED_OUT;

    /** 是否已进入终态（终态不允许再推进，重复回报一律幂等忽略）。 */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED || this == TIMED_OUT;
    }

    /** 是否已分配给机器人且仍在执行（ALLOCATED 或 IN_PROGRESS）。 */
    public boolean isActive() {
        return this == ALLOCATED || this == IN_PROGRESS;
    }
}
