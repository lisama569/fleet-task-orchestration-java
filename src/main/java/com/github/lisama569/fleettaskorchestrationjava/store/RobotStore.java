package com.github.lisama569.fleettaskorchestrationjava.store;

import com.github.lisama569.fleettaskorchestrationjava.model.Robot;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 机器人内存存储。
 */
@Component
public class RobotStore {
    private final Map<String, Robot> robots = new ConcurrentHashMap<>();

    public Robot putIfAbsent(Robot robot) {
        return robots.putIfAbsent(robot.getRobotId(), robot);
    }

    public Robot get(String robotId) {
        return robots.get(robotId);
    }

    public Collection<Robot> all() {
        return robots.values();
    }

    public void remove(String robotId) {
        robots.remove(robotId);
    }

    public void clear() {
        robots.clear();
    }
}
