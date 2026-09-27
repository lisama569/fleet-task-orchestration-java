package com.github.lisama569.fleettaskorchestrationjava.tests;

import com.github.lisama569.fleettaskorchestrationjava.service.FleetLock;
import com.github.lisama569.fleettaskorchestrationjava.service.RobotService;
import com.github.lisama569.fleettaskorchestrationjava.service.Scheduler;
import com.github.lisama569.fleettaskorchestrationjava.service.TaskService;
import com.github.lisama569.fleettaskorchestrationjava.store.RobotStore;
import com.github.lisama569.fleettaskorchestrationjava.store.SnapshotStore;
import com.github.lisama569.fleettaskorchestrationjava.store.TaskStore;

import java.nio.file.Path;

/**
 * 测试夹具：不走 Spring 容器，手工装配一套服务，便于确定性地构造并发与"重启"。
 */
public class FleetTestFixture {
    public final TaskStore taskStore;
    public final RobotStore robotStore;
    public final FleetLock fleetLock;
    public final Scheduler scheduler;
    public final TaskService taskService;
    public final RobotService robotService;
    public final SnapshotStore snapshotStore;

    private FleetTestFixture(Path snapshotFile, long agingThresholdMillis) {
        this.taskStore = new TaskStore();
        this.robotStore = new RobotStore();
        this.fleetLock = new FleetLock();
        this.scheduler = new Scheduler(taskStore, robotStore, fleetLock, agingThresholdMillis);
        this.taskService = new TaskService(taskStore, robotStore, fleetLock, scheduler);
        this.robotService = new RobotService(robotStore, fleetLock, scheduler);
        this.snapshotStore = new SnapshotStore(taskStore, robotStore, fleetLock, scheduler,
                snapshotFile.toString());
        // 模拟服务启动：加载快照 + 恢复 + 注册落盘回调
        this.snapshotStore.init();
    }

    /** 全新启动（快照文件不存在） */
    public static FleetTestFixture freshStart(Path snapshotFile, long agingThresholdMillis) {
        return new FleetTestFixture(snapshotFile, agingThresholdMillis);
    }

    /** 模拟服务重启：全新对象图 + 同一个快照文件 */
    public static FleetTestFixture restart(Path snapshotFile, long agingThresholdMillis) {
        return new FleetTestFixture(snapshotFile, agingThresholdMillis);
    }
}
