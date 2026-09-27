package com.github.lisama569.fleettaskorchestrationjava.fleet.registry;

import com.github.lisama569.fleettaskorchestrationjava.fleet.model.Robot;
import java.util.List;

/** 机器人注册表抽象（本地内存模拟）。 */
public interface RobotRegistry {
    void register(Robot robot);
    Robot get(String robotId);
    List<Robot> all();
    void clear();
}
