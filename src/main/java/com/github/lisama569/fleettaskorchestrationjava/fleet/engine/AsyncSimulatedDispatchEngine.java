package com.github.lisama569.fleettaskorchestrationjava.fleet.engine;

import com.github.lisama569.fleettaskorchestrationjava.fleet.model.Task;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 异步模拟派发引擎：不接真实设备，用定时任务模拟任务在机器人上的执行。
 * <ul>
 *   <li>dispatch 幂等：同一 taskId 已在跑时直接忽略，绝不重复下发。</li>
 *   <li>执行期间按固定节奏回调进度；正常完成回调 onSucceeded，failBeforeComplete 时回调 onFailed。</li>
 *   <li>timeoutMs 到时回调 onTimedOut；完成/失败/超时谁先触发谁生效，之后的事件被 CAS 挡掉。</li>
 *   <li>cancel 幂等：未在跑/已结束都无副作用。</li>
 *   <li>回调里不再访问 Task 的可变状态，只传 taskId，归属/状态判定全部交给 FleetService。</li>
 * </ul>
 */
public class AsyncSimulatedDispatchEngine implements DispatchEngine {

    private static final Logger log = LoggerFactory.getLogger(AsyncSimulatedDispatchEngine.class);
    private static final long PROGRESS_TICK_MS = 100L;

    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "fleet-sim-engine");
                t.setDaemon(true);
                return t;
            });

    private record Execution(AtomicBoolean finished,
                             ScheduledFuture<?> progressFuture,
                             ScheduledFuture<?> lifecycleFuture) { }

    private final Map<String, Execution> running = new ConcurrentHashMap<>();
    private volatile EngineCallbacks callbacks = new EngineCallbacks() {
        @Override public void onStarted(String taskId) { }
        @Override public void onProgress(String taskId, int percent) { }
        @Override public void onSucceeded(String taskId) { }
        @Override public void onFailed(String taskId, String errorCode) { }
        @Override public void onTimedOut(String taskId, String errorCode) { }
    };

    @Override
    public void attach(EngineCallbacks callbacks) {
        this.callbacks = callbacks;
    }

    @Override
    public void dispatch(Task task) {
        if (task == null) {
            return;
        }
        String taskId = task.getId();
        AtomicBoolean finished = new AtomicBoolean(false);
        Execution exec = new Execution(finished, null, null);
        Execution existing = running.putIfAbsent(taskId, exec);
        if (existing != null) {
            log.info("[engine] 任务 {} 已在执行中，重复 dispatch 幂等忽略", taskId);
            return;
        }

        long durationMs = task.getDurationMs();
        long timeoutMs = task.getTimeoutMs();
        log.info("[engine] 下发任务 {} 到机器人 {}（duration={}ms, timeout={}ms, fail={}）",
                taskId, task.getAssignedRobotId(), durationMs, timeoutMs, task.isFailBeforeComplete());

        ScheduledFuture<?>[] progressHolder = new ScheduledFuture<?>[1];
        ScheduledFuture<?>[] lifecycleHolder = new ScheduledFuture<?>[1];

        // 进度定时器
        ScheduledFuture<?> progressFuture = executor.scheduleAtFixedRate(() -> {
            Execution cur = running.get(taskId);
            if (cur == null || cur.finished().get()) {
                return;
            }
            long elapsed = System.currentTimeMillis() - task.getAllocatedAtMs();
            int percent = (int) Math.min(99, elapsed * 100 / Math.max(1, durationMs));
            safeCallback(() -> callbacks.onProgress(taskId, percent));
        }, PROGRESS_TICK_MS, PROGRESS_TICK_MS, TimeUnit.MILLISECONDS);

        // 完成/失败定时器
        long ticks = Math.max(1, durationMs / PROGRESS_TICK_MS);
        ScheduledFuture<?> lifecycleFuture = executor.schedule(() -> {
            if (!finishIfFirst(taskId)) {
                return;
            }
            progressFuture.cancel(false);
            if (task.isFailBeforeComplete()) {
                log.info("[engine] 任务 {} 模拟执行失败", taskId);
                safeCallback(() -> callbacks.onFailed(taskId, "SIMULATED_FAILURE"));
            } else {
                log.info("[engine] 任务 {} 模拟执行成功", taskId);
                safeCallback(() -> callbacks.onSucceeded(taskId));
            }
        }, durationMs, TimeUnit.MILLISECONDS);

        // 超时定时器
        if (timeoutMs > 0) {
            executor.schedule(() -> {
                if (!finishIfFirst(taskId)) {
                    return;
                }
                progressFuture.cancel(false);
                lifecycleFuture.cancel(false);
                log.warn("[engine] 任务 {} 执行超过 {}ms，判定超时", taskId, timeoutMs);
                safeCallback(() -> callbacks.onTimedOut(taskId, "EXECUTION_TIMEOUT"));
            }, timeoutMs, TimeUnit.MILLISECONDS);
        }

        // 记录 future 以便 cancel 时一并取消
        running.put(taskId, new Execution(finished, progressFuture, lifecycleFuture));
    }

    @Override
    public void cancel(String taskId) {
        Execution exec = running.remove(taskId);
        if (exec == null) {
            log.info("[engine] 取消任务 {}：未在执行（可能已结束），幂等无操作", taskId);
            return;
        }
        if (exec.progressFuture() != null) {
            exec.progressFuture().cancel(false);
        }
        if (exec.lifecycleFuture() != null) {
            exec.lifecycleFuture().cancel(false);
        }
        exec.finished().set(true);
        log.info("[engine] 任务 {} 已在引擎侧取消", taskId);
    }

    @Override
    public boolean isRunning(String taskId) {
        Execution exec = running.get(taskId);
        return exec != null && !exec.finished().get();
    }

    /** 引擎内部在任务正常结束/超时时移除执行记录（取消由 cancel 负责）。 */
    private boolean finishIfFirst(String taskId) {
        Execution exec = running.get(taskId);
        if (exec == null) {
            return false;
        }
        if (exec.finished().compareAndSet(false, true)) {
            running.remove(taskId);
            return true;
        }
        return false;
    }

    private void safeCallback(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.warn("[engine] 回调执行异常 taskId={}", e.getMessage(), e);
        }
    }

    @PreDestroy
    public void shutdown() {
        running.clear();
        executor.shutdownNow();
        log.info("[engine] 模拟引擎已关闭");
    }
}
