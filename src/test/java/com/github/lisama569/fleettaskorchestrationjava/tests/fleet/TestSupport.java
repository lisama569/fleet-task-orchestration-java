package com.github.lisama569.fleettaskorchestrationjava.tests.fleet;

import com.github.lisama569.fleettaskorchestrationjava.fleet.config.FleetProperties;
import com.github.lisama569.fleettaskorchestrationjava.fleet.engine.DispatchEngine;
import com.github.lisama569.fleettaskorchestrationjava.fleet.engine.EngineCallbacks;
import com.github.lisama569.fleettaskorchestrationjava.fleet.model.Task;
import com.github.lisama569.fleettaskorchestrationjava.fleet.registry.InMemoryRobotRegistry;
import com.github.lisama569.fleettaskorchestrationjava.fleet.registry.RobotRegistry;
import com.github.lisama569.fleettaskorchestrationjava.fleet.scheduler.CapabilityLoadPolicy;
import com.github.lisama569.fleettaskorchestrationjava.fleet.service.FleetService;
import com.github.lisama569.fleettaskorchestrationjava.fleet.store.JsonFileTaskStore;
import com.github.lisama569.fleettaskorchestrationjava.fleet.store.TaskStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** 测试公共构造工具。 */
public final class TestSupport {

    private TestSupport() { }

    /** 每个用例独立的临时 JSON 存储，互不干扰。 */
    public static TaskStore tempStore() {
        try {
            Path dir = Files.createTempDirectory("fleet-test-");
            dir.toFile().deleteOnExit();
            Path file = dir.resolve("tasks.json");
            return new JsonFileTaskStore(file.toString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static FleetProperties props() {
        FleetProperties p = new FleetProperties();
        p.setSchedulerEnabled(false);
        p.setAgingAfterMs(0L);
        return p;
    }

    public static FleetService newService(TaskStore store, RobotRegistry registry,
                                    DispatchEngine engine, FleetProperties props, AtomicLong clock) {
        return new FleetService(store, null, registry, engine, new CapabilityLoadPolicy(), props, clock::get);
    }

    /** 不做任何异步动作的引擎：下发不执行，由测试手动回报结果，保证确定性。 */
    public static final class NoopEngine implements DispatchEngine {
        private final AtomicInteger dispatchCount = new AtomicInteger();
        private final AtomicInteger cancelCount = new AtomicInteger();
        private EngineCallbacks callbacks = new EngineCallbacks() {
            @Override public void onStarted(String t) { }
            @Override public void onProgress(String t, int p) { }
            @Override public void onSucceeded(String t) { }
            @Override public void onFailed(String t, String e) { }
            @Override public void onTimedOut(String t, String e) { }
        };

        @Override public void dispatch(Task task) { dispatchCount.incrementAndGet(); }
        @Override public void cancel(String taskId) { cancelCount.incrementAndGet(); }
        @Override public void attach(EngineCallbacks c) { this.callbacks = c; }
        @Override public boolean isRunning(String taskId) { return false; }
        public int dispatchCount() { return dispatchCount.get(); }
        public int cancelCount() { return cancelCount.get(); }
        public EngineCallbacks callbacks() { return callbacks; }
    }

    /** 可记录每个任务下发次数的异步模拟引擎包装（用于并发下断言每任务只下发一次）。 */
    public static void spinAwait(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
