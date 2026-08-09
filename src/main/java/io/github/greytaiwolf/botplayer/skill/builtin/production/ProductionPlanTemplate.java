package io.github.greytaiwolf.botplayer.skill.builtin.production;

import java.util.List;
import java.util.Objects;

/**
 * 一个尚未绑定任何 Bot、世界或菜单会话的生产 DAG 模板。
 */
public record ProductionPlanTemplate(
        ProductionSchema schema,
        ProductionBudget requestedBudget,
        List<ProductionPlanNode> nodes,
        List<ProductionPlanEdge> edges) {
    public ProductionPlanTemplate {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(requestedBudget, "requestedBudget");
        nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
        edges = List.copyOf(Objects.requireNonNull(edges, "edges"));
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException(
                    "production plan template must include nodes");
        }
        if (nodes.size() > ProductionBudget.ABSOLUTE_MAX_NODES
                || edges.size() > ProductionBudget.ABSOLUTE_MAX_EDGES) {
            throw new IllegalArgumentException(
                    "production plan template exceeds absolute DAG limits");
        }
    }
}
