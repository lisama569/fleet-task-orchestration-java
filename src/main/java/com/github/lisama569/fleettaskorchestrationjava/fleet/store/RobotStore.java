package com.github.lisama569.fleettaskorchestrationjava.fleet.store;

import com.github.lisama569.fleettaskorchestrationjava.fleet.model.Robot;
import java.util.List;

/** 机器人名录本地存储抽象。 */
public interface RobotStore {
    void saveAll(List<Robot> robots);
    List<Robot> loadAll();
}
