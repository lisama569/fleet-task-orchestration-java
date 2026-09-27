package com.github.lisama569.fleettaskorchestrationjava.fleet.scheduler;

import com.github.lisama569.fleettaskorchestrationjava.fleet.model.AllocationDecision;
import com.github.lisama569.fleettaskorchestrationjava.fleet.model.RejectReason;
import com.github.lisama569.fleettaskorchestrationjava.fleet.model.Robot;
import com.github.lisama569.fleettaskorchestrationjava.fleet.model.RobotStatus;
import com.github.lisama569.fleettaskorchestrationjava.fleet.model.Task;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 分配策略（纯判定，无副作用）：
 * <ol>
 *   <li>只有 IDLE 机器人可被分配，保证同一机器人同一时刻不会被派给多个互斥任务。</li>
 *   <li>按能力、负载逐层筛选，收集每个候选被排除的依据。</li>
 *   <li>可行候选中选“剩余负载最小（最贴近任务需求、容量不浪费）”者，容量相同按 robotId 升序。</li>
 * </ol>
 * 没有可行机器人时，按证据给出可区分的拒绝原因：
 * 全离线 / 全忙 / 能力不匹配 / 负载不支持。
 */
public class CapabilityLoadPolicy implements AllocationPolicy {

    @Override
    public AllocationDecision decide(Task task, List<Robot> robots) {
        List<String> evals = new ArrayList<>();

        if (robots == null || robots.isEmpty()) {
            return AllocationDecision.rejected(RejectReason.ALL_ROBOTS_OFFLINE,
                    "没有任何注册机器人", List.of());
        }

        List<Robot> online = robots.stream().filter(r -> r.getStatus() != RobotStatus.OFFLINE).toList();
        if (online.isEmpty()) {
            evals.add("所有 " + robots.size() + " 台机器人均 OFFLINE");
            return AllocationDecision.rejected(RejectReason.ALL_ROBOTS_OFFLINE,
                    "全部机器人掉线，无可调度资源", evals);
        }

        List<Robot> idle = online.stream().filter(r -> r.getStatus() == RobotStatus.IDLE).toList();
        if (idle.isEmpty()) {
            evals.add("在线 " + online.size() + " 台机器人全部 BUSY（互斥约束，无法派单）");
            return AllocationDecision.rejected(RejectReason.ALL_ROBOTS_BUSY,
                    "在线机器人全部占用中", evals);
        }

        // 能力筛选
        List<Robot> capable = new ArrayList<>();
        List<Robot> loadCandidates = new ArrayList<>();
        for (Robot r : idle) {
            boolean hasCap = r.getCapabilities().contains(task.getCapability());
            boolean loadOk = r.getCapacity() >= task.getLoad();
            if (!hasCap) {
                evals.add(r.getId() + " 排除：缺少能力 " + task.getCapability()
                        + "（机器人能力=" + r.getCapabilities() + "）");
                continue;
            }
            capable.add(r);
            if (!loadOk) {
                evals.add(r.getId() + " 排除：负载不足，需要 " + task.getLoad()
                        + "，机器人容量 " + r.getCapacity());
                continue;
            }
            loadCandidates.add(r);
            evals.add(r.getId() + " 可行：能力=" + task.getCapability()
                    + "，容量 " + r.getCapacity() + ">=" + task.getLoad()
                    + "，剩余 " + (r.getCapacity() - task.getLoad()));
        }

        if (capable.isEmpty()) {
            return AllocationDecision.rejected(RejectReason.CAPABILITY_MISMATCH,
                    "空闲机器人都不具备能力 " + task.getCapability(), evals);
        }
        if (loadCandidates.isEmpty()) {
            return AllocationDecision.rejected(RejectReason.LOAD_UNSUPPORTED,
                    "具备能力的机器人都无法承载负载 " + task.getLoad(), evals);
        }

        // 最贴近负载者优先（剩余容量最小），平局按 id 稳定排序 -> 可解释、可复现
        Robot best = loadCandidates.stream()
                .min(Comparator.comparingInt((Robot r) -> r.getCapacity() - task.getLoad())
                        .thenComparing(Robot::getId))
                .orElseThrow();

        String why = "选择 " + best.getId()
                + "：在 " + loadCandidates.size() + " 台可行机器人中剩余容量最小("
                + (best.getCapacity() - task.getLoad()) + ")，容量相同时按 id 升序";
        return AllocationDecision.allocated(best.getId(), why, evals);
    }
}
