package com.github.lisama569.fleettaskorchestrationjava.tests.fleet;

import com.github.lisama569.fleettaskorchestrationjava.fleet.config.FleetProperties;
import com.github.lisama569.fleettaskorchestrationjava.fleet.model.TaskRequest;
import com.github.lisama569.fleettaskorchestrationjava.fleet.model.TaskStatus;
import com.github.lisama569.fleettaskorchestrationjava.fleet.registry.InMemoryRobotRegistry;
import com.github.lisama569.fleettaskorchestrationjava.fleet.registry.RobotRegistry;
import com.github.lisama569.fleettaskorchestrationjava.fleet.service.FleetService;
import com.github.lisama569.fleettaskorchestrationjava.fleet.store.JsonFileRobotStore;
import com.github.lisama569.fleettaskorchestrationjava.fleet.store.JsonFileTaskStore;
import com.github.lisama569.fleettaskorchestrationjava.fleet.store.RobotStore;
import com.github.lisama569.fleettaskorchestrationjava.fleet.store.TaskStore;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** 重启恢复：未完成任务回到可继续调度状态，不卡死、不重复下发；终态保持终态。 */
class RecoveryTest {

    private static final Logger log = LoggerFactory.getLogger(RecoveryTest.class);

    private TaskRequest task(String id, long duration) {
        return new TaskRequest(id, "LIFT", 50, 0, 2, duration, false, 0);
    }

    private FleetService buildService(TaskStore store, RobotRegistry registry,
                                   TestSupport.NoopEngine engine, AtomicLong clock) {
        FleetProperties props = TestSupport.props();
        return TestSupport.newService(store, registry, engine, props, clock);
    }

    private FleetService buildServiceWithRobotStore(TaskStore taskStore, RobotStore robotStore,
                                                RobotRegistry registry, TestSupport.NoopEngine engine,
                                                AtomicLong clock) {
        FleetProperties props = TestSupport.props();
        return new FleetService(taskStore, robotStore, registry, engine,
                new com.github.lisama569.fleettaskorchestrationjava.fleet.scheduler.CapabilityLoadPolicy(),
                props, clock::get);
    }

    @Test
    void activeTasksRecoverToPendingAndRedispatchOnce() throws Exception {
        Path dir = Files.createTempDirectory("fleet-recovery-");
        dir.toFile().deleteOnExit();
        Path file = dir.resolve("tasks.json");
        TaskStore store = new JsonFileTaskStore(file.toString());
        AtomicLong clock = new AtomicLong(1_000L);

        // —— 第一个“进程”：注册机器人并产生两个活动任务、一个已完成任务、一个排队任务
        RobotRegistry registry1 = new InMemoryRobotRegistry();
        TestSupport.NoopEngine engine1 = new TestSupport.NoopEngine();
        FleetService svc1 = buildService(store, registry1, engine1, clock);
        svc1.registerRobot("R1", Set.of("LIFT"), 5);
        svc1.registerRobot("R2", Set.of("LIFT"), 5);
        svc1.submit(task("DONE", 1000));       // R1
        svc1.reportSuccess("DONE", "R1");       // 先完成，R1 释放
        svc1.submit(task("ACTIVE-1", 1000)); // R1
        svc1.submit(task("ACTIVE-2", 1000)); // R2
        svc1.submit(task("QUEUED", 1000)); // 两台都忙 -> PENDING
        log.info("[重启前] ACTIVE-1={} ACTIVE-2={} DONE={} QUEUED={} 引擎1下发={}",
                svc1.getTask("ACTIVE-1").getStatus(), svc1.getTask("ACTIVE-2").getStatus(),
                svc1.getTask("DONE").getStatus(), svc1.getTask("QUEUED").getStatus(),
                engine1.dispatchCount());

        // —— 模拟进程重启：全新内存注册表、全新引擎、同一个文件存储
        RobotRegistry registry2 = new InMemoryRobotRegistry();
        TestSupport.NoopEngine engine2 = new TestSupport.NoopEngine();
        FleetService svc2 = buildService(store, registry2, engine2, clock);
        // 机器人需要重新注册（本地模拟设备名录为空），再显式执行恢复
        svc2.registerRobot("R1", Set.of("LIFT"), 5);
        svc2.registerRobot("R2", Set.of("LIFT"), 5);
        svc2.recoverOnStartup();

        log.info("[重启恢复后] ACTIVE-1={} 归属={} | ACTIVE-2={} 归属={} | DONE={} | QUEUED={} 归属={} | 引擎2下发={}",
                svc2.getTask("ACTIVE-1").getStatus(), svc2.getTask("ACTIVE-1").getAssignedRobotId(),
                svc2.getTask("ACTIVE-2").getStatus(), svc2.getTask("ACTIVE-2").getAssignedRobotId(),
                svc2.getTask("DONE").getStatus(),
                svc2.getTask("QUEUED").getStatus(), svc2.getTask("QUEUED").getAssignedRobotId(),
                engine2.dispatchCount());

        // 两个原活动任务 + 原排队任务，共三个未完成任务被重新调度到两台机器人；无遗漏、无卡死
        assertThat(svc2.getTask("ACTIVE-1").getStatus()).isIn(TaskStatus.ALLOCATED, TaskStatus.IN_PROGRESS);
        assertThat(svc2.getTask("ACTIVE-2").getStatus()).isIn(TaskStatus.ALLOCATED, TaskStatus.IN_PROGRESS);
        assertThat(svc2.getTask("DONE").getStatus()).isEqualTo(TaskStatus.COMPLETED);
        // 终态任务保留“最后归属”作为历史信息，但该机器人不再因此被占用
        assertThat(svc2.getTask("DONE").getAssignedRobotId()).isEqualTo("R1");

        long active = svc2.listTasks().stream().filter(t -> t.getStatus().isActive()).count();
        long pending = svc2.listTasks().stream().filter(t -> t.getStatus().name().equals("PENDING")).count();
        assertThat(active).isEqualTo(2);
        assertThat(pending).isEqualTo(1);
        // 重启后每个未完成任务只重新下发一次：3 个可调度任务，2 台机器人 -> 2 次下发
        assertThat(engine2.dispatchCount()).isEqualTo(2);

        // 重启后幂等：重复提交旧任务不重新下发
        var dup = svc2.submit(task("ACTIVE-1", 1000));
        assertThat(dup.duplicate()).isTrue();
        assertThat(engine2.dispatchCount()).isEqualTo(2);
    }

    @Test
    void recoveredQueuedTaskEventuallyScheduledWithoutDoubleDispatch() throws Exception {
        Path dir = Files.createTempDirectory("fleet-recovery2-");
        dir.toFile().deleteOnExit();
        Path file = dir.resolve("tasks.json");
        TaskStore store = new JsonFileTaskStore(file.toString());
        AtomicLong clock = new AtomicLong(1_000L);

        RobotRegistry registry1 = new InMemoryRobotRegistry();
        TestSupport.NoopEngine e1 = new TestSupport.NoopEngine();
        FleetService svc1 = buildService(store, registry1, e1, clock);
        // 只有一台机器人，两个任务：一活动一排队
        svc1.registerRobot("R1", Set.of("LIFT"), 5);
        svc1.submit(task("RUN", 1000));
        svc1.submit(task("WAIT", 1000));
        log.info("[重启前] RUN={} WAIT={}", svc1.getTask("RUN").getStatus(),
                svc1.getTask("WAIT").getStatus());

        // 重启：先注册机器人并恢复，RUN 立即重占 R1，WAIT 仍 PENDING
        RobotRegistry registry2 = new InMemoryRobotRegistry();
        TestSupport.NoopEngine e2 = new TestSupport.NoopEngine();
        FleetService svc2 = buildService(store, registry2, e2, clock);
        svc2.registerRobot("R1", Set.of("LIFT"), 5);
        svc2.recoverOnStartup();
        assertThat(svc2.getTask("RUN").getStatus()).isIn(TaskStatus.ALLOCATED, TaskStatus.IN_PROGRESS);
        assertThat(svc2.getTask("WAIT").getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(e2.dispatchCount()).isEqualTo(1);

        // RUN 完成 -> WAIT 被调度，且整个生命周期 WAIT 只下发一次
        svc2.reportSuccess("RUN", "R1");
        log.info("[重启后] RUN 完成回报；[判定] WAIT={} 归属={} 引擎累计下发={}",
                svc2.getTask("WAIT").getStatus(), svc2.getTask("WAIT").getAssignedRobotId(),
                e2.dispatchCount());
        assertThat(svc2.getTask("WAIT").getStatus().isActive()).isTrue();
        assertThat(svc2.getTask("WAIT").getAssignedRobotId()).isEqualTo("R1");
        assertThat(e2.dispatchCount()).isEqualTo(2);
    }

    @Test
    void robotFleetAlsoRecoversFromLocalStore() throws Exception {
        Path dir = Files.createTempDirectory("fleet-recovery3-");
        dir.toFile().deleteOnExit();
        TaskStore taskStore = new JsonFileTaskStore(dir.resolve("tasks.json").toString());
        RobotStore robotStore = new JsonFileRobotStore(dir.resolve("robots.json").toString());
        AtomicLong clock = new AtomicLong(2_000L);

        // 进程 1：注册机器人 + 一个活动任务
        RobotRegistry reg1 = new InMemoryRobotRegistry();
        TestSupport.NoopEngine e1 = new TestSupport.NoopEngine();
        FleetService s1 = buildServiceWithRobotStore(taskStore, robotStore, reg1, e1, clock);
        s1.registerRobot("R1", Set.of("LIFT"), 5);
        s1.submit(task("RUN1", 1000));
        log.info("[重启前] 机器人文件存在={} RUN1={}", Files.exists(dir.resolve("robots.json")),
                s1.getTask("RUN1").getStatus());

        // 进程 2：空注册表启动，机器人名录应从本地文件恢复；活动任务退回排队并立即重派一次
        RobotRegistry reg2 = new InMemoryRobotRegistry();
        TestSupport.NoopEngine e2 = new TestSupport.NoopEngine();
        FleetService s2 = buildServiceWithRobotStore(taskStore, robotStore, reg2, e2, clock);
        s2.recoverOnStartup();

        log.info("[重启后] 恢复机器人={} RUN1={} 归属={} 引擎下发={}",
                reg2.all().stream().map(r -> r.getId() + ":" + r.getStatus()).toList(),
                s2.getTask("RUN1").getStatus(), s2.getTask("RUN1").getAssignedRobotId(),
                e2.dispatchCount());
        assertThat(reg2.all()).hasSize(1);
        assertThat(reg2.all().get(0).getId()).isEqualTo("R1");
        assertThat(s2.getTask("RUN1").getStatus().isActive()).isTrue();
        assertThat(s2.getTask("RUN1").getAssignedRobotId()).isEqualTo("R1");
        assertThat(e2.dispatchCount()).isEqualTo(1);
    }
}
