package io.github.greytaiwolf.botplayer.skill.plan;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 保留输入顺序的有界计划；结构错误由验证器集中报告。
 */
public record SkillPlan(
        UUID planId,
        UUID botId,
        long revision,
        List<SkillPlanNode> nodes,
        List<SkillPlanEdge> edges) {
    public static final int ABSOLUTE_MAX_NODES = 2_048;
    public static final int ABSOLUTE_MAX_EDGES = 16_384;

    public SkillPlan {
        PlanChecks.requireNonZero(planId, "planId");
        PlanChecks.requireNonZero(botId, "botId");
        if (revision < 1L) {
            throw new IllegalArgumentException(
                    "revision must be positive");
        }
        nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
        edges = List.copyOf(Objects.requireNonNull(edges, "edges"));
        if (nodes.size() > ABSOLUTE_MAX_NODES) {
            throw new IllegalArgumentException(
                    "nodes exceeds absolute maximum "
                            + ABSOLUTE_MAX_NODES);
        }
        if (edges.size() > ABSOLUTE_MAX_EDGES) {
            throw new IllegalArgumentException(
                    "edges exceeds absolute maximum "
                            + ABSOLUTE_MAX_EDGES);
        }
    }
}
