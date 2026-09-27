package com.github.lisama569.fleettaskorchestrationjava.service;

import com.github.lisama569.fleettaskorchestrationjava.model.Robot;
import com.github.lisama569.fleettaskorchestrationjava.model.RobotStatus;
import com.github.lisama569.fleettaskorchestrationjava.store.RobotStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Set;

/**
 * 机器人注册、掉线、替换与查询。
 * 掉线/替换时若机器人正占用任务，则释放占用并把任务交还调度队列（由 Scheduler 处理）。
 */
@Service
public class RobotService {
    private static final Logger log = LoggerFactory.getLogger(RobotService.class);

    private final RobotStore robotStore;
    private final FleetLock fleetLock;
    private final Scheduler scheduler;

    public RobotService(RobotStore robotStore, FleetLock fleetLock, Scheduler scheduler) {
        this.robotStore = robotStore;
        this.fleetLock = fleetLock;
        this.scheduler = scheduler;
    }

    /** 注册机器人；重复注册同一 robotId 视为幂等刷新（能力/负载更新），已离线机器人重新上线。 */
    public Robot register(String robotId, Set<String> capabilities, int maxLoad) {
        return fleetLock.inLock(() -> {
            Robot existing = robotStore.get(robotId);
            if (existing != null && existing.getStatus() != RobotStatus.OFFLINE) {
                log.info("[robot.register] idempotent refresh robotId={} caps={} maxLoad={}",
                        robotId, capabilities, maxLoad);
                return existing;
            }
            Robot robot = new Robot(robotId, capabilities, maxLoad);
            robotStore.putIfAbsent(robot);
            log.info("[robot.register] registered robotId={} caps={} maxLoad={}",
                    robotId, capabilities, maxLoad);
            scheduler.scheduleOnce();
            return robot;
        });
    }

    /** 掉线：释放占用，任务回队列，机器人不再参与分配。幂等。 */
    public Robot markOffline(String robotId) {
        return fleetLock.inLock(() -> {
            Robot robot = robotStore.get(robotId);
            if (robot == null) {
                throw new IllegalArgumentException("robot not found: " + robotId);
            }
            if (robot.getStatus() == RobotStatus.OFFLINE) {
                log.info("[robot.offline] idempotent, already offline robotId={}", robotId);
                return robot;
            }
            String occupied = robot.getCurrentTaskId();
            robot.setStatus(RobotStatus.OFFLINE);
            robot.setCurrentTaskId(null);
            log.info("[robot.offline] robotId={} releasedTask={}", robotId, occupied);
            if (occupied != null) {
                scheduler.requeueTask(occupied, "ROBOT_OFFLINE");
            }
            scheduler.scheduleOnce();
            return robot;
        });
    }

    /** 替换：等价于旧机器人掉线 + 新参数重新注册。 */
    public Robot replace(String robotId, Set<String> capabilities, int maxLoad) {
        return fleetLock.inLock(() -> {
            Robot old = robotStore.get(robotId);
            if (old != null && old.getStatus() != RobotStatus.OFFLINE) {
                markOffline(robotId);
            }
            Robot fresh = new Robot(robotId, capabilities, maxLoad);
            // 重新注册：覆盖 store 中的旧记录
            robotStore.remove(robotId);
            robotStore.putIfAbsent(fresh);
            log.info("[robot.replace] robotId={} caps={} maxLoad={}", robotId, capabilities, maxLoad);
            scheduler.scheduleOnce();
            return fresh;
        });
    }

    public Robot get(String robotId) {
        return robotStore.get(robotId);
    }

    public Collection<Robot> all() {
        return robotStore.all();
    }
}
