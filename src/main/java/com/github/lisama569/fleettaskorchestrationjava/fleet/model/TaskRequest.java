package com.github.lisama569.fleettaskorchestrationjava.fleet.model;

/**
 * 任务提交请求（不可变值对象）。
 *
 * @param taskId               调用方提供的幂等键；重复提交相同 taskId 不会产生重复任务/重复分配
 * @param capability           任务要求的机器人能力标识
 * @param priority             基础优先级，取值 0..100，越大越紧急
 * @param deadlineEpochMs      截止时间（epoch ms），0 表示无截止时间
 * @param load                 负载要求（单位），机器人 capacity 必须不小于它
 * @param durationMs           模拟执行时长，仅被模拟派发引擎使用
 * @param failBeforeComplete   true 时模拟引擎会把任务执行到失败，用于演示失败处理
 * @param timeoutMs            分配后执行超时阈值，0 表示不启用超时
 */
public record TaskRequest(String taskId,
                          String capability,
                          int priority,
                          long deadlineEpochMs,
                          int load,
                          long durationMs,
                          boolean failBeforeComplete,
                          long timeoutMs) {

    public TaskRequest {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId 不能为空");
        }
        if (capability == null || capability.isBlank()) {
            throw new IllegalArgumentException("capability 不能为空");
        }
        if (priority < 0 || priority > 100) {
            throw new IllegalArgumentException("priority 必须在 0..100 之间: " + priority);
        }
        if (load <= 0) {
            throw new IllegalArgumentException("load 必须为正数: " + load);
        }
        if (durationMs <= 0) {
            throw new IllegalArgumentException("durationMs 必须为正数: " + durationMs);
        }
        if (timeoutMs < 0) {
            throw new IllegalArgumentException("timeoutMs 不能为负: " + timeoutMs);
        }
    }
}
