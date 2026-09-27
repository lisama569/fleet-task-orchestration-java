package com.github.lisama569.fleettaskorchestrationjava.fleet.model;

import java.util.List;

/**
 * 一次分配判定的结果（不可变值对象）。
 * feasible=true 时 robotId 为被选中的机器人；reason 记录“为什么选它”。
 * feasible=false 时 rejectReason 给出可区分的拒绝原因，reason 记录候选评估依据。
 */
public record AllocationDecision(boolean feasible,
                                 String robotId,
                                 RejectReason rejectReason,
                                 String reason,
                                 List<String> candidateEvals) {

    public static AllocationDecision allocated(String robotId, String reason, List<String> evals) {
        return new AllocationDecision(true, robotId, RejectReason.NONE, reason, List.copyOf(evals));
    }

    public static AllocationDecision rejected(RejectReason code, String reason, List<String> evals) {
        return new AllocationDecision(false, null, code, reason, List.copyOf(evals));
    }
}
