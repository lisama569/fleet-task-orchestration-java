package com.github.lisama569.fleettaskorchestrationjava.fleet.demo;

import com.github.lisama569.fleettaskorchestrationjava.fleet.config.FleetProperties;
import com.github.lisama569.fleettaskorchestrationjava.fleet.model.CancelResult;
import com.github.lisama569.fleettaskorchestrationjava.fleet.model.Robot;
import com.github.lisama569.fleettaskorchestrationjava.fleet.model.RobotStatus;
import com.github.lisama569.fleettaskorchestrationjava.fleet.model.SubmitResult;
import com.github.lisama569.fleettaskorchestrationjava.fleet.model.Task;
import com.github.lisama569.fleettaskorchestrationjava.fleet.model.TaskRequest;
import com.github.lisama569.fleettaskorchestrationjava.fleet.model.TaskStatus;
import com.github.lisama569.fleettaskorchestrationjava.fleet.service.FleetService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;

import java.util.List;
import java.util.Set;

/**
 * 启动即跑的本地自检演示（fleet.demo-enabled=true 时生效）。
 * 纯内存 + 本地 JSON，跑起来后在日志里即可验证：分配依据、可区分的拒绝原因、
 * 取消/超时/失败的状态推进、幂等回报、掉线释放、老化提升与重启恢复。
 */
@Order(0)
public class FleetDemoRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger("FLEET-DEMO");

    private final FleetService fleet;
    private final FleetProperties props;
    private final boolean demoEnabled;

    public FleetDemoRunner(FleetService fleet, FleetProperties props) {
        this(fleet, props, true);
    }

    public FleetDemoRunner(FleetService fleet, FleetProperties props, boolean demoEnabled) {
        this.fleet = fleet;
        this.props = props;
        this.demoEnabled = demoEnabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!demoEnabled) {
            log.info("[demo] 已跳过（fleet.demo-enabled=false）");
            return;
        }
        try {
            runScenario();
        } catch (Exception e) {
            log.warn("[demo] 演示执行异常: {}", e.getMessage(), e);
        }
    }

    private void runScenario() throws Exception {
        log.info("[demo] ============ 车队任务编排自检开始 ============");

        // 两台机器人：R1 能力 LIFT 容量 5；R2 能力 LIFT+SCAN 容量 10
        fleet.registerRobot("R1", Set.of("LIFT"), 5);
        fleet.registerRobot("R2", Set.of("LIFT", "SCAN"), 10);

        // 1) 正常任务 -> 应分给 R1（容量 5 最贴近负载 3）
        SubmitResult r1 = fleet.submit(new TaskRequest("T-OK", "LIFT", 50, 0, 3, 400, false, 0));
        log.info("[demo] 输入 T-OK(LIFT,pri50,load3) -> allocated={}, msg={}", r1.allocated(), r1.message());

        // 2) 第二个 LIFT 任务 -> R1 忙，分给 R2
        SubmitResult r2 = fleet.submit(new TaskRequest("T-OK2", "LIFT", 40, 0, 4, 500, false, 0));
        log.info("[demo] 输入 T-OK2(LIFT,pri40,load4) -> allocated={}, robot={}",
                r2.allocated(), robotOf(r2.task()));

        // 3) 再来一个 -> 两台都忙，排队 ALL_ROBOTS_BUSY
        SubmitResult r3 = fleet.submit(new TaskRequest("T-QUEUE", "LIFT", 30, 0, 2, 300, false, 0));
        log.info("[demo] 输入 T-QUEUE -> allocated={}, reason={}, msg={}",
                r3.allocated(), r3.reason(), r3.message());

        // 4) 重复提交 T-OK -> DUPLICATE_SUBMISSION，不产生重复分配
        SubmitResult dup = fleet.submit(new TaskRequest("T-OK", "LIFT", 50, 0, 3, 400, false, 0));
        log.info("[demo] 重复提交 T-OK -> duplicate={}, reason={}", dup.duplicate(), dup.reason());

        // 等两台都空闲，专门展示能力与负载两类拒绝原因
        awaitUntil(2000, () -> fleet.getTask("T-OK2").getStatus() == TaskStatus.COMPLETED);

        // 5) 能力不匹配（此时机器人全空闲，原因明确为 CAPABILITY_MISMATCH）
        SubmitResult r4 = fleet.submit(new TaskRequest("T-WRONGCAP", "WELD", 60, 0, 1, 300, false, 0));
        log.info("[demo] 输入 T-WRONGCAP(WELD)（机器人空闲）-> allocated={}, reason={}",
                r4.allocated(), r4.reason());

        // 6) 负载不支持（同上，明确为 LOAD_UNSUPPORTED）
        SubmitResult r5 = fleet.submit(new TaskRequest("T-HEAVY", "LIFT", 70, 0, 20, 300, false, 0));
        log.info("[demo] 输入 T-HEAVY(load20,最大容量10) -> allocated={}, reason={}", r5.allocated(), r5.reason());

        // 7) 取消排队中的 T-HEAVY
        CancelResult cancel = fleet.cancel("T-HEAVY");
        log.info("[demo] 取消排队 T-HEAVY -> cancelled={}, outcome={}", cancel.cancelled(), cancel.outcome());

        // 8) 取消执行中的任务（SCAN 只有 R2 能做）
        SubmitResult running = fleet.submit(new TaskRequest("T-CANCEL", "SCAN", 50, 0, 2, 2000, false, 0));
        log.info("[demo] 输入 T-CANCEL(SCAN) -> allocated={}, robot={}",
                running.allocated(), robotOf(running.task()));
        TestSupportSleep(150);
        CancelResult cancelActive = fleet.cancel("T-CANCEL");
        log.info("[demo] 取消执行中 T-CANCEL -> cancelled={}, outcome={}, 当前状态={}",
                cancelActive.cancelled(), cancelActive.outcome(), status("T-CANCEL"));

        // 9) 超时任务：timeout 小于执行时长
        SubmitResult timeoutTask = fleet.submit(new TaskRequest("T-TIMEOUT", "LIFT", 50, 0, 2, 2000, false, 300));
        log.info("[demo] 输入 T-TIMEOUT(timeout300,dur2000) -> allocated={}", timeoutTask.allocated());
        awaitUntil(1500, () -> fleet.getTask("T-TIMEOUT").getStatus() == TaskStatus.TIMED_OUT);
        log.info("[demo] T-TIMEOUT 最终状态={}", status("T-TIMEOUT"));

        // 10) 模拟失败任务
        SubmitResult failTask = fleet.submit(new TaskRequest("T-FAIL", "LIFT", 50, 0, 2, 300, true, 0));
        log.info("[demo] 输入 T-FAIL(fail=true) -> allocated={}", failTask.allocated());
        awaitUntil(1500, () -> fleet.getTask("T-FAIL").getStatus() == TaskStatus.FAILED);
        log.info("[demo] T-FAIL 最终状态={} errorCode={}", status("T-FAIL"),
                fleet.getTask("T-FAIL").getErrorCode());

        // 11) 老化提升演示：先把两台机器人用长任务占满，再提交一个 SCAN(load<=10) 低优先级任务。
        // 它会先排队（ALL_ROBOTS_BUSY）；R2 是唯一 SCAN 机且被占，直到老化提升可被观察到。
        fleet.submit(new TaskRequest("OCC-1", "LIFT", 5, 0, 2, 30_000, false, 0));
        fleet.submit(new TaskRequest("OCC-2", "SCAN", 5, 0, 2, 30_000, false, 0));
        SubmitResult aging = fleet.submit(new TaskRequest("T-AGING", "SCAN", 10, 0, 9, 300, false, 0));
        log.info("[demo] 输入 T-AGING(SCAN,load9,pri10，两台均被长任务占用) -> allocated={}, reason={}",
                aging.allocated(), aging.reason());
        long wait = props.getAgingAfterMs() + props.getAgingIntervalMs() + 300;
        TestSupportSleep(wait);
        Task agingTask = fleet.getTask("T-AGING");
        log.info("[demo] 老化后 T-AGING 状态={} 有效优先级={}（基础10 + 老化提升，任务仍因无空闲机器人保持排队）",
                agingTask.getStatus(), agingTask.effectivePriority());
        // 释放唯一的 SCAN 机器人 R2 -> 提升后的 T-AGING 立即得到调度
        fleet.cancel("OCC-2");
        awaitUntil(1000, () -> fleet.getTask("T-AGING").getStatus().isActive());
        log.info("[demo] 取消 OCC-2 释放 R2 后：T-AGING 状态={} 归属={}",
                fleet.getTask("T-AGING").getStatus(), fleet.getTask("T-AGING").getAssignedRobotId());

        printFinalState();
        log.info("[demo] ============ 车队任务编排自检结束（任务已持久化到 {}，重启会恢复未完成项）============",
                props.getTaskStorePath());
    }

    private void printFinalState() {
        List<Robot> robots = fleet.listRobots();
        for (Robot r : robots) {
            log.info("[demo] 机器人最终状态: {}", r);
        }
        for (Task t : fleet.listTasks()) {
            log.info("[demo] 任务最终状态: {} rev={} decision={}", t, t.getRevision(),
                    t.getLastDecisionReason());
        }
        // 不变量断言：BUSY 机器人必须持有处于 ALLOCATED/IN_PROGRESS 的同一任务；反之活动任务必须归属 BUSY 机器人
        for (Robot r : robots) {
            if (r.getStatus() == RobotStatus.BUSY) {
                Task t = fleet.getTask(r.getCurrentTaskId());
                if (t == null || !t.getStatus().isActive()
                        || !r.getId().equals(t.getAssignedRobotId())) {
                    throw new IllegalStateException("不变量破坏: BUSY 机器人 " + r.getId() + " 持有非法任务");
                }
            }
        }
        for (Task t : fleet.listTasks()) {
            if (t.getStatus().isActive()) {
                Robot owner = fleet.listRobots().stream()
                        .filter(r -> r.getId().equals(t.getAssignedRobotId())).findFirst().orElse(null);
                if (owner == null || owner.getStatus() != RobotStatus.BUSY
                        || !t.getId().equals(owner.getCurrentTaskId())) {
                    throw new IllegalStateException("不变量破坏: 活动任务 " + t.getId() + " 无 BUSY 归属机器人");
                }
            }
        }
    }

    private static void TestSupportSleep(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }

    private String status(String taskId) {
        Task t = fleet.getTask(taskId);
        return t == null ? "null" : String.valueOf(t.getStatus());
    }

    private String robotOf(Task t) {
        return t == null || t.getAssignedRobotId() == null ? "-" : t.getAssignedRobotId();
    }

    private interface Condition { boolean check(); }

    private void awaitUntil(long maxWaitMs, Condition c) throws InterruptedException {
        long deadline = System.currentTimeMillis() + maxWaitMs;
        while (System.currentTimeMillis() < deadline) {
            if (c.check()) {
                return;
            }
            Thread.sleep(25);
        }
        if (!c.check()) {
            throw new IllegalStateException("等待条件超时未满足");
        }
    }
}
