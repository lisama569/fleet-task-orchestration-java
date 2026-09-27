package com.github.lisama569.fleettaskorchestrationjava.fleet.scheduler;

import com.github.lisama569.fleettaskorchestrationjava.fleet.model.AllocationDecision;
import com.github.lisama569.fleettaskorchestrationjava.fleet.model.Robot;
import com.github.lisama569.fleettaskorchestrationjava.fleet.model.Task;
import java.util.List;

/** 分配策略：从候选机器人中为任务选出最合适者，或给出可区分的拒绝原因。 */
public interface AllocationPolicy {
    AllocationDecision decide(Task task, List<Robot> robots);
}
