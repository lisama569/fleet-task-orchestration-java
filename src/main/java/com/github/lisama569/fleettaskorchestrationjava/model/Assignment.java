package com.github.lisama569.fleettaskorchestrationjava.model;

import java.time.Instant;

/**
 * 一次分配决策的不可变记录，explanation 说明为什么选了这个机器人。
 */
public record Assignment(String taskId, String robotId, long attemptId,
                         int effectivePriority, Instant assignedAt, String explanation) {
}
