package io.github.greytaiwolf.botplayer.skill.plan;

import java.util.UUID;

/**
 * prerequisiteNodeId 成功后 dependentNodeId 才能进入 READY。
 */
public record SkillPlanEdge(
        UUID prerequisiteNodeId, UUID dependentNodeId) {
    public SkillPlanEdge {
        PlanChecks.requireNonZero(
                prerequisiteNodeId, "prerequisiteNodeId");
        PlanChecks.requireNonZero(
                dependentNodeId, "dependentNodeId");
    }
}
