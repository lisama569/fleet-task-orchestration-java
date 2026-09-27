package com.github.lisama569.fleettaskorchestrationjava.service;

import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 全局调度锁：所有涉及"机器人占用"与"任务归属"的状态推进都在同一把锁内完成，
 * 保证并发提交、取消、回报混在一起时两者不会互相矛盾。
 */
@Component
public class FleetLock {
    private final Object monitor = new Object();
    /** 每次持锁变更后的回调（如快照落盘），由 SnapshotStore 注册 */
    private volatile Runnable afterMutation = () -> { };

    public void setAfterMutation(Runnable afterMutation) {
        this.afterMutation = afterMutation;
    }

    public <T> T inLock(Supplier<T> action) {
        synchronized (monitor) {
            T result = action.get();
            afterMutation.run();
            return result;
        }
    }

    public void inLock(Runnable action) {
        synchronized (monitor) {
            action.run();
            afterMutation.run();
        }
    }
}
