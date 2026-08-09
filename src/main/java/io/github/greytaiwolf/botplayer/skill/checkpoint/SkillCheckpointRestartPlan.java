package io.github.greytaiwolf.botplayer.skill.checkpoint;

import io.github.greytaiwolf.botplayer.skill.plan.SkillPlan;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 从已批准完整计划裁出的、可由新 generation 重新执行的后缀计划。
 *
 * <p>它只携带完整计划的内容引用和已完成节点前缀，不携带旧 run/checkpoint identity。
 */
public record SkillCheckpointRestartPlan(
        SkillCheckpointPlan sourcePlan,
        SkillPlan suffixPlan,
        List<UUID> completedPrefix) {
    public SkillCheckpointRestartPlan {
        Objects.requireNonNull(sourcePlan, "sourcePlan");
        Objects.requireNonNull(suffixPlan, "suffixPlan");
        if (suffixPlan.nodes().isEmpty()) {
            throw new IllegalArgumentException(
                    "restart suffix must contain at least one node");
        }
        if (sourcePlan.planId().equals(suffixPlan.planId())) {
            throw new IllegalArgumentException(
                    "restart suffix must use a distinct deterministic planId");
        }
        completedPrefix = List.copyOf(Objects.requireNonNull(
                completedPrefix, "completedPrefix"));
        java.util.Set<UUID> unique = new HashSet<>();
        for (UUID nodeId : completedPrefix) {
            CheckpointNbt.requireNonZeroUuid(nodeId, "completedPrefix nodeId");
            if (!unique.add(nodeId)) {
                throw new IllegalArgumentException(
                        "completedPrefix contains a duplicate nodeId");
            }
        }
    }
}
