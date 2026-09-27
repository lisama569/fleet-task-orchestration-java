package com.github.lisama569.fleettaskorchestrationjava.fleet.registry;

import com.github.lisama569.fleettaskorchestrationjava.fleet.model.Robot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存机器人注册表（本地模拟）。
 * 用 ConcurrentHashMap 保证注册/查询线程安全；机器人内部状态的一致性由 FleetService 统一锁保护。
 */
public class InMemoryRobotRegistry implements RobotRegistry {

    private final Map<String, Robot> robots = new ConcurrentHashMap<>();

    @Override
    public void register(Robot robot) {
        if (robot == null || robot.getId() == null) {
            throw new IllegalArgumentException("robot 不能为空");
        }
        robots.put(robot.getId(), robot);
    }

    @Override
    public Robot get(String robotId) {
        return robots.get(robotId);
    }

    @Override
    public List<Robot> all() {
        return new ArrayList<>(robots.values());
    }

    @Override
    public void clear() {
        robots.clear();
    }
}
