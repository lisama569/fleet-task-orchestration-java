package com.github.lisama569.fleettaskorchestrationjava.fleet.service;

import com.github.lisama569.fleettaskorchestrationjava.fleet.config.FleetProperties;
import com.github.lisama569.fleettaskorchestrationjava.fleet.engine.DispatchEngine;
import com.github.lisama569.fleettaskorchestrationjava.fleet.engine.EngineCallbacks;
import com.github.lisama569.fleettaskorchestrationjava.fleet.model.AllocationDecision;
import com.github.lisama569.fleettaskorchestrationjava.fleet.model.CancelOutcome;
import com.github.lisama569.fleettaskorchestrationjava.fleet.model.CancelResult;
import com.github.lisama569.fleettaskorchestrationjava.fleet.model.RejectReason;
import com.github.lisama569.fleettaskorchestrationjava.fleet.model.ReportOutcome;
import com.github.lisama569.fleettaskorchestrationjava.fleet.model.ReportResult;
import com.github.lisama569.fleettaskorchestrationjava.fleet.model.Robot;
import com.github.lisama569.fleettaskorchestrationjava.fleet.model.RobotStatus;
import com.github.lisama569.fleettaskorchestrationjava.fleet.model.SubmitResult;
import com.github.lisama569.fleettaskorchestrationjava.fleet.model.Task;
import com.github.lisama569.fleettaskorchestrationjava.fleet.model.TaskRequest;
import com.github.lisama569.fleettaskorchestrationjava.fleet.model.TaskStatus;
import com.github.lisama569.fleettaskorchestrationjava.fleet.registry.RobotRegistry;
import com.github.lisama569.fleettaskorchestrationjava.fleet.scheduler.AllocationPolicy;
import com.github.lisama569.fleettaskorchestrationjava.fleet.store.RobotStore;
import com.github.lisama569.fleettaskorchestrationjava.fleet.store.TaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * 车队任务编排核心服务。
 *
 * 一致性设计：
 * <ul>
 *   <li>单一 {@link ReentrantLock} 串行化所有任务/机器人状态变更，杜绝并发下的重复分配与归属矛盾。</li>
 *   <li>状态推进全部为 CAS 式幂等：终态任务的重复结果回报、重复取消、重复引擎事件都不产生副作用。</li>
 *   <li>每次变更后同步落盘；{@link #recoverOnStartup()} 把未完成任务退回 PENDING，重启可继续调度且不重复下发。</li>
 * </ul>
 */
public class FleetService {

    private static final Logger log = LoggerFactory.getLogger(FleetService.class);

    private final TaskStore taskStore;
    private final RobotStore robotStore;
    private final RobotRegistry robotRegistry;
    private final DispatchEngine engine;
    private final AllocationPolicy policy;
    private final FleetProperties props;
    private final LongSupplier clock;

    /** 任务表，保持插入顺序便于观测；所有访问必须持有 lock。 */
    private final Map<String, Task> tasks = new LinkedHashMap<>();
    /** 记录每个任务上次老化提升的时间。 */
    private final Map<String, Long> lastBoostAt = new HashMap<>();

    private final ReentrantLock lock = new ReentrantLock(true);
    private volatile boolean recovered;

    public FleetService(TaskStore taskStore, RobotRegistry robotRegistry, DispatchEngine engine,
                        AllocationPolicy policy, FleetProperties props) {
        this(taskStore, null, robotRegistry, engine, policy, props, System::currentTimeMillis);
    }

    public FleetService(TaskStore taskStore, RobotStore robotStore, RobotRegistry robotRegistry,
                        DispatchEngine engine, AllocationPolicy policy, FleetProperties props) {
        this(taskStore, robotStore, robotRegistry, engine, policy, props, System::currentTimeMillis);
    }

    public FleetService(TaskStore taskStore, RobotStore robotStore, RobotRegistry robotRegistry,
                        DispatchEngine engine, AllocationPolicy policy, FleetProperties props, LongSupplier clock) {
        this.taskStore = taskStore;
        this.robotStore = robotStore;
        this.robotRegistry = robotRegistry;
        this.engine = engine;
        this.policy = policy;
        this.props = props;
        this.clock = clock;
        this.engine.attach(new EngineCallbacks() {
            @Override public void onStarted(String taskId) { handleStarted(taskId); }
            @Override public void onProgress(String taskId, int percent) { handleEngineProgress(taskId, percent); }
            @Override public void onSucceeded(String taskId) { handleSucceeded(taskId); }
            @Override public void onFailed(String taskId, String errorCode) { handleFailed(taskId, errorCode); }
            @Override public void onTimedOut(String taskId, String errorCode) { handleTimedOut(taskId, errorCode); }
        });
    }

    private long now() {
        return clock.getAsLong();
    }

    private void persist() {
        taskStore.saveAll(tasks.values());
    }

    /** 调用方必须已持有锁。 */
    private void persistRobots() {
        if (robotStore != null) {
            robotStore.saveAll(robotRegistry.all());
        }
    }

    // ------------------------- 机器人管理 -------------------------

    public Robot registerRobot(String robotId, Set<String> capabilities, int capacity) {
        lock.lock();
        try {
            Robot robot = new Robot(robotId, capabilities, capacity);
            robotRegistry.register(robot);
            persistRobots();
            log.info("[fleet] 注册机器人 {} caps={} capacity={}", robotId, capabilities, capacity);
            int allocated = schedulePending();
            if (allocated > 0) {
                persist();
            }
            return robot;
        } finally {
            lock.unlock();
        }
    }

    public List<Robot> listRobots() {
        lock.lock();
        try {
            return robotRegistry.all();
        } finally {
            lock.unlock();
        }
    }

    // ------------------------- 任务查询 -------------------------

    public List<Task> listTasks() {
        lock.lock();
        try {
            return new ArrayList<>(tasks.values());
        } finally {
            lock.unlock();
        }
    }

    public Task getTask(String taskId) {
        lock.lock();
        try {
            return tasks.get(taskId);
        } finally {
            lock.unlock();
        }
    }

    // ------------------------- 提交（幂等） -------------------------

    public SubmitResult submit(TaskRequest request) {
        if (request == null) {
            return SubmitResult.invalid("请求为空");
        }
        TaskRequest req;
        try {
            // record 紧凑构造器已做校验，这里再触发一次，非法参数给可区分原因
            req = new TaskRequest(request.taskId(), request.capability(), request.priority(),
                    request.deadlineEpochMs(), request.load(), request.durationMs(),
                    request.failBeforeComplete(), request.timeoutMs());
        } catch (IllegalArgumentException e) {
            log.warn("[fleet] 非法提交 taskId={} 原因={}", request.taskId(), e.getMessage());
            return SubmitResult.invalid(e.getMessage());
        }

        lock.lock();
        try {
            ensureRecovered();
            Task existing = tasks.get(req.taskId());
            if (existing != null) {
                log.info("[fleet] 重复提交任务 {}（当前状态 {}），幂等返回，不产生重复分配",
                        req.taskId(), existing.getStatus());
                return SubmitResult.duplicate(existing);
            }

            Task task = new Task(req, now());
            tasks.put(task.getId(), task);
            log.info("[fleet] 接收任务 {} cap={} pri={} deadline={} load={} timeout={}ms fail={}",
                    task.getId(), task.getCapability(), task.getPriority(),
                    task.getDeadlineEpochMs(), task.getLoad(), task.getTimeoutMs(), task.isFailBeforeComplete());

            AllocationDecision decision = tryAllocate(task);
            persist();
            if (decision.feasible()) {
                return SubmitResult.allocated(task, decision.reason());
            }
            return SubmitResult.queued(task, decision.rejectReason(), decision.reason());
        } finally {
            lock.unlock();
        }
    }

    // ------------------------- 回报（幂等） -------------------------

    public ReportResult reportProgress(String taskId, String robotId, int percent) {
        lock.lock();
        try {
            Task task = tasks.get(taskId);
            if (task == null) {
                return ReportResult.of(ReportOutcome.NOT_FOUND, false, "任务不存在: " + taskId, null);
            }
            if (!task.getStatus().isActive()) {
                return ReportResult.of(ReportOutcome.NOT_ACTIVE, false,
                        "任务不在执行中: " + task.getStatus(), task);
            }
            if (!robotId.equals(task.getAssignedRobotId())) {
                log.warn("[fleet] 进度归属拒绝 task={} reporter={} owner={}",
                        taskId, robotId, task.getAssignedRobotId());
                return ReportResult.of(ReportOutcome.WRONG_OWNER, false,
                        "机器人 " + robotId + " 不是任务当前归属者", task);
            }
            int before = task.getProgressPercent();
            task.updateProgress(percent);
            boolean changed = task.getProgressPercent() > before;
            if (changed) {
                persist();
                log.info("[fleet] 进度更新 task={} robot={} {}% (rev={})",
                        taskId, robotId, task.getProgressPercent(), task.getRevision());
                return ReportResult.of(ReportOutcome.PROGRESS_ACCEPTED, true,
                        "进度已更新到 " + task.getProgressPercent() + "%", task);
            }
            return ReportResult.of(ReportOutcome.DUPLICATE_IGNORED, false,
                    "进度未前进（重复/过期回报），幂等忽略", task);
        } finally {
            lock.unlock();
        }
    }

    public ReportResult reportSuccess(String taskId, String robotId) {
        return reportTerminal(taskId, robotId, true, null);
    }

    public ReportResult reportFailure(String taskId, String robotId, String errorCode) {
        return reportTerminal(taskId, robotId, false, errorCode);
    }

    private ReportResult reportTerminal(String taskId, String robotId, boolean success, String errorCode) {
        lock.lock();
        try {
            Task task = tasks.get(taskId);
            if (task == null) {
                return ReportResult.of(ReportOutcome.NOT_FOUND, false, "任务不存在: " + taskId, null);
            }
            if (task.getStatus().isTerminal()) {
                // 重复回报同一个/另一个终态结果：幂等忽略，不释放机器人、不计数、不重复副作用
                log.info("[fleet] 任务 {} 已处于终态 {}，重复结果回报({}) 幂等忽略",
                        taskId, task.getStatus(), success ? "SUCCESS" : "FAIL:" + errorCode);
                return ReportResult.of(ReportOutcome.DUPLICATE_IGNORED, false,
                        "任务已终态 " + task.getStatus() + "，重复回报被忽略", task);
            }
            if (!task.getStatus().isActive()) {
                return ReportResult.of(ReportOutcome.NOT_ACTIVE, false,
                        "任务不在执行中: " + task.getStatus(), task);
            }
            if (!robotId.equals(task.getAssignedRobotId())) {
                log.warn("[fleet] 结果归属拒绝 task={} reporter={} owner={}",
                        taskId, robotId, task.getAssignedRobotId());
                return ReportResult.of(ReportOutcome.WRONG_OWNER, false,
                        "机器人 " + robotId + " 不是任务当前归属者", task);
            }

            Robot robot = robotRegistry.get(robotId);
            if (success) {
                task.markCompleted(now());
                if (robot != null) {
                    robot.release(now());
                }
                persist();
                log.info("[fleet] 任务 {} 成功完成，机器人 {} 释放（已完成 {} 单）",
                        taskId, robotId, robot == null ? "-" : robot.getCompletedCount());
                schedulePending();
                persist();
                return ReportResult.of(ReportOutcome.SUCCEEDED, true, "任务成功完成", task);
            } else {
                task.markFailed(now(), errorCode);
                if (robot != null) {
                    robot.release(now());
                }
                persist();
                log.warn("[fleet] 任务 {} 失败 reason={}，机器人 {} 释放", taskId, errorCode, robotId);
                schedulePending();
                persist();
                return ReportResult.of(ReportOutcome.FAILED_ACCEPTED, true,
                        "任务失败: " + errorCode, task);
            }
        } finally {
            lock.unlock();
        }
    }

    // ------------------------- 取消（幂等） -------------------------

    public CancelResult cancel(String taskId) {
        lock.lock();
        try {
            Task task = tasks.get(taskId);
            if (task == null) {
                return CancelResult.of(CancelOutcome.NOT_FOUND, false, "任务不存在: " + taskId, null);
            }
            TaskStatus st = task.getStatus();
            if (st.isTerminal()) {
                log.info("[fleet] 取消任务 {}：已是终态 {}，幂等忽略", taskId, st);
                return CancelResult.of(CancelOutcome.ALREADY_TERMINAL, false,
                        "任务已终态 " + st + "，取消被忽略", task);
            }

            String robotId = task.getAssignedRobotId();
            boolean wasActive = st.isActive();
            if (wasActive) {
                // 先停引擎（幂等），再推进状态，避免取消后引擎回调又推进
                engine.cancel(taskId);
            }
            task.markCancelled(now(), "USER_CANCELLED");
            if (wasActive) {
                Robot robot = robotRegistry.get(robotId);
                if (robot != null && taskId.equals(robot.getCurrentTaskId())) {
                    robot.release(now());
                }
            }
            persist();
            log.info("[fleet] 任务 {} 取消成功（原状态 {}），{}",
                    taskId, st, wasActive ? "机器人 " + robotId + " 已释放" : "排队中，无机器人占用");
            if (wasActive) {
                schedulePending();
                persist();
            }
            return CancelResult.of(wasActive ? CancelOutcome.CANCELLED_ACTIVE : CancelOutcome.CANCELLED_PENDING,
                    true, wasActive ? "执行中任务已取消并释放机器人" : "排队任务已取消", task);
        } finally {
            lock.unlock();
        }
    }

    // ------------------------- 机器人掉线 / 恢复 / 替换 -------------------------

    public void robotOffline(String robotId) {
        lock.lock();
        try {
            Robot robot = robotRegistry.get(robotId);
            if (robot == null) {
                log.warn("[fleet] 掉线请求：机器人 {} 不存在", robotId);
                return;
            }
            // 必须先取出当前任务，再 markOffline（后者会清空 currentTaskId）
            String taskId = robot.getCurrentTaskId();
            Task heldTask = taskId == null ? null : tasks.get(taskId);
            boolean taskActive = heldTask != null && heldTask.getStatus().isActive();
            if (taskActive) {
                engine.cancel(taskId);
                heldTask.requeue("机器人 " + robotId + " 掉线，退回排队等待重新分配");
            }
            robot.markOffline();
            persistRobots();
            log.warn("[fleet] 机器人 {} 掉线{}", robotId,
                    taskActive ? "，任务 " + taskId + " 退回排队" : "");
            persist();
            if (taskActive) {
                schedulePending();
                persist();
            }
        } finally {
            lock.unlock();
        }
    }

    public void robotOnline(String robotId) {
        lock.lock();
        try {
            Robot robot = robotRegistry.get(robotId);
            if (robot == null) {
                log.warn("[fleet] 上线请求：机器人 {} 不存在", robotId);
                return;
            }
            robot.markOnline();
            persistRobots();
            log.info("[fleet] 机器人 {} 恢复在线（{}）", robotId, robot.getStatus());
            int allocated = schedulePending();
            if (allocated > 0) {
                persist();
            }
        } finally {
            lock.unlock();
        }
    }

    public Robot replaceRobot(String oldRobotId, String newRobotId, Set<String> capabilities, int capacity) {
        lock.lock();
        try {
            Robot old = robotRegistry.get(oldRobotId);
            String taskId = old == null ? null : old.getCurrentTaskId();
            Task heldTask = taskId == null ? null : tasks.get(taskId);
            boolean taskActive = heldTask != null && heldTask.getStatus().isActive();
            if (old != null) {
                if (taskActive) {
                    engine.cancel(taskId);
                    heldTask.requeue("机器人 " + oldRobotId + " 被替换，退回排队");
                }
                old.markOffline();
            }
            Robot replacement = new Robot(newRobotId, capabilities, capacity);
            robotRegistry.register(replacement);
            persistRobots();
            log.info("[fleet] 机器人 {} 被 {} 替换{}", oldRobotId, newRobotId,
                    taskActive ? "，原任务 " + taskId + " 退回排队" : "");
            persist();
            if (taskActive) {
                schedulePending();
                persist();
            }
            return replacement;
        } finally {
            lock.unlock();
        }
    }

    // ------------------------- 引擎事件回调（均在锁内做幂等推进） -------------------------

    public void handleStarted(String taskId) {
        lock.lock();
        try {
            Task task = tasks.get(taskId);
            if (task == null) {
                return;
            }
            if (task.getStatus() == TaskStatus.ALLOCATED) {
                task.markStarted(now());
                persist();
                log.info("[fleet] 任务 {} 在机器人 {} 上开始执行 (rev={})",
                        taskId, task.getAssignedRobotId(), task.getRevision());
            }
            // IN_PROGRESS/终态：过期或重复事件，忽略
        } finally {
            lock.unlock();
        }
    }

    private void handleEngineProgress(String taskId, int percent) {
        // 引擎自报进度，归属天然可信，走通用校验
        Task task = getTask(taskId);
        if (task != null && task.getStatus().isActive()) {
            reportProgress(taskId, task.getAssignedRobotId(), percent);
        }
    }

    private void handleSucceeded(String taskId) {
        Task task = getTask(taskId);
        if (task != null && task.getStatus().isActive()) {
            reportSuccess(taskId, task.getAssignedRobotId());
        } else if (task != null) {
            log.info("[fleet] 引擎成功事件到达但任务 {} 已为 {}，幂等忽略", taskId, task.getStatus());
        }
    }

    private void handleFailed(String taskId, String errorCode) {
        Task task = getTask(taskId);
        if (task != null && task.getStatus().isActive()) {
            reportFailure(taskId, task.getAssignedRobotId(), errorCode);
        } else if (task != null) {
            log.info("[fleet] 引擎失败事件到达但任务 {} 已为 {}，幂等忽略", taskId, task.getStatus());
        }
    }

    private void handleTimedOut(String taskId, String errorCode) {
        lock.lock();
        try {
            Task task = tasks.get(taskId);
            if (task == null || !task.getStatus().isActive()) {
                log.info("[fleet] 超时事件 task={} 已不活动，幂等忽略", taskId);
                return;
            }
            String robotId = task.getAssignedRobotId();
            task.markTimedOut(now(), errorCode);
            Robot robot = robotRegistry.get(robotId);
            if (robot != null && taskId.equals(robot.getCurrentTaskId())) {
                robot.release(now());
            }
            persist();
            log.warn("[fleet] 任务 {} 执行超时({})，机器人 {} 已释放", taskId, errorCode, robotId);
            schedulePending();
            persist();
        } finally {
            lock.unlock();
        }
    }

    // ------------------------- 调度：分配 + 超时扫描 + 老化提升 -------------------------

    /**
     * 执行一轮调度：超时扫描 -> 老化提升 -> 按优先级/截止时间排队分配。
     * 返回本轮新分配的任务数。
     */
    public int runScheduleTick() {
        lock.lock();
        try {
            ensureRecovered();
            boolean changed = detectTimeouts();
            changed |= applyAging();
            int allocated = schedulePending();
            if (changed || allocated > 0) {
                persist();
            }
            return allocated;
        } finally {
            lock.unlock();
        }
    }

    private boolean detectTimeouts() {
        boolean changed = false;
        long t = now();
        for (Task task : tasks.values()) {
            if (!task.getStatus().isActive() || task.getTimeoutMs() <= 0 || task.getAllocatedAtMs() <= 0) {
                continue;
            }
            long elapsed = t - task.getAllocatedAtMs();
            if (elapsed > task.getTimeoutMs()) {
                String robotId = task.getAssignedRobotId();
                task.markTimedOut(t, "SCHEDULER_TIMEOUT_SCAN");
                Robot robot = robotRegistry.get(robotId);
                if (robot != null && task.getId().equals(robot.getCurrentTaskId())) {
                    robot.release(t);
                }
                engine.cancel(task.getId());
                changed = true;
                log.warn("[fleet][tick] 任务 {} 超时 {}ms>{}ms，机器人 {} 释放",
                        task.getId(), elapsed, task.getTimeoutMs(), robotId);
            }
        }
        return changed;
    }

    private boolean applyAging() {
        long agingAfter = props.getAgingAfterMs();
        if (agingAfter <= 0) {
            return false;
        }
        boolean changed = false;
        long t = now();
        for (Task task : tasks.values()) {
            if (!task.schedulable()) {
                continue;
            }
            long age = t - task.getCreatedAtMs();
            if (age < agingAfter) {
                continue;
            }
            // 语义：排队超过 agingAfter 之后，每再过一个 agingIntervalMs 就提升一次。
            // 应有的提升次数 = 1 + (超出阈值的时间 / 间隔)；据此幂等地补到目标次数，
            // 漏掉 tick / tick 不均匀也不会少提升或重复提升。
            long overtime = age - agingAfter;
            long targetBoosts = 1 + overtime / props.getAgingIntervalMs();
            long applied = task.getPriorityBoost() / Math.max(1, props.getAgingBoost());
            if (applied >= targetBoosts) {
                continue;
            }
            task.addPriorityBoost((int) ((targetBoosts - applied) * props.getAgingBoost()));
            lastBoostAt.put(task.getId(), t);
            changed = true;
            log.info("[fleet][tick] 任务 {} 排队 {}ms 未被调度，老化提升到 +{} -> 有效优先级 {}",
                    task.getId(), age, task.getPriorityBoost(), task.effectivePriority());
        }
        return changed;
    }

    /**
     * 把 PENDING 任务按（有效优先级降序、截止时间升序、提交时间升序）逐个尝试分配，
     * 直到没有可分配任务或没有可行机器人。返回新分配数。
     * 调用方必须已持有锁。
     */
    private int schedulePending() {
        int allocated = 0;
        List<Task> pending = tasks.values().stream()
                .filter(Task::schedulable)
                .sorted(pendingOrder())
                .toList();
        for (Task task : pending) {
            AllocationDecision decision = tryAllocate(task);
            if (!decision.feasible()) {
                // 排在前面的任务分不上，后面低优先级任务也可能被其他机器人服务；继续尝试
                log.debug("[fleet][tick] 任务 {} 暂无法分配: {} ({})",
                        task.getId(), decision.rejectReason(), decision.reason());
            } else {
                allocated++;
            }
        }
        return allocated;
    }

    private Comparator<Task> pendingOrder() {
        return Comparator.comparingInt(Task::effectivePriority).reversed()
                .thenComparing(t -> t.getDeadlineEpochMs() == 0 ? Long.MAX_VALUE : t.getDeadlineEpochMs())
                .thenComparing(Task::getCreatedAtMs)
                .thenComparing(Task::getId);
    }

    /**
     * 尝试为单个任务做一次分配判定并下发。调用方必须已持有锁。
     */
    private AllocationDecision tryAllocate(Task task) {
        AllocationDecision decision = policy.decide(task, robotRegistry.all());
        if (!decision.feasible()) {
            task.setLastDecisionReason(decision.rejectReason() + ": " + decision.reason());
            log.info("[fleet] 任务 {} 排队等待，原因 {} | {}",
                    task.getId(), decision.rejectReason(), decision.reason());
            return decision;
        }
        Robot robot = robotRegistry.get(decision.robotId());
        // 二次校验：策略判定与占用变更之间必须仍满足互斥
        if (robot == null || robot.getStatus() != RobotStatus.IDLE) {
            AllocationDecision retry = AllocationDecision.rejected(RejectReason.ALL_ROBOTS_BUSY,
                    "选中机器人状态在分配瞬间已变化", decision.candidateEvals());
            task.setLastDecisionReason(retry.reason());
            return retry;
        }
        robot.assign(task.getId(), now());
        task.markAllocated(robot.getId(), now(), decision.reason());
        log.info("[fleet] 任务 {} 分配给机器人 {} | 依据: {}",
                task.getId(), robot.getId(), decision.reason());
        engine.dispatch(task);
        return decision;
    }

    // ------------------------- 重启恢复 -------------------------

    public void recoverOnStartup() {
        lock.lock();
        try {
            if (robotStore != null) {
                for (Robot r : robotStore.loadAll()) {
                    robotRegistry.register(r);
                }
            }
            List<Task> loaded = taskStore.loadAll();
            tasks.clear();
            long t = now();
            int recoveredCount = 0;
            for (Task task : loaded) {
                if (task.getStatus().isActive()) {
                    String robotId = task.getAssignedRobotId();
                    if (robotId != null) {
                        Robot robot = robotRegistry.get(robotId);
                        if (robot != null && task.getId().equals(robot.getCurrentTaskId())) {
                            robot.release(t);
                        }
                    }
                    task.requeue("重启恢复：执行归属已失效，退回排队等待重新调度");
                    recoveredCount++;
                }
                tasks.put(task.getId(), task);
                lastBoostAt.put(task.getId(), t);
            }
            // 注册表中残留的 BUSY 机器人（内存态可能来自上一进程）也一并复位
            for (Robot robot : robotRegistry.all()) {
                robot.recoverIdle();
            }
            recovered = true;
            persist();
            if (!loaded.isEmpty()) {
                log.info("[fleet] 启动恢复：载入 {} 个任务，其中 {} 个未完成任务退回 PENDING 可继续调度",
                        loaded.size(), recoveredCount);
            }
            int allocated = schedulePending();
            if (allocated > 0) {
                persist();
                log.info("[fleet] 启动恢复后立即重新分配 {} 个任务（不重复下发，每任务仅一条新执行）", allocated);
            }
        } finally {
            lock.unlock();
        }
    }

    private void ensureRecovered() {
        if (!recovered) {
            // 未显式调用 recoverOnStartup 时（如纯单测直接 new 服务），懒加载一次
            if (robotStore != null) {
                for (Robot r : robotStore.loadAll()) {
                    robotRegistry.register(r);
                }
            }
            List<Task> loaded = taskStore.loadAll();
            if (loaded.isEmpty()) {
                recovered = true;
                return;
            }
            for (Task task : loaded) {
                if (task.getStatus().isActive()) {
                    task.requeue("懒恢复：执行归属失效，退回排队");
                }
                tasks.put(task.getId(), task);
                lastBoostAt.put(task.getId(), now());
            }
            for (Robot robot : robotRegistry.all()) {
                robot.recoverIdle();
            }
            recovered = true;
            persist();
            schedulePending();
            persist();
        }
    }
}
