package io.github.greytaiwolf.botplayer.skill.builtin.production;

import java.util.List;
import java.util.Objects;

/**
 * 规划器唯一的输出：接受时给出已解析节点，拒绝时不给任何可调度节点。
 */
public record ProductionPlanValidation(
        boolean accepted,
        List<ProductionPlanViolation> violations,
        List<ProductionResolvedNode> resolvedNodes,
        ProductionLedger projectedFinalLedger,
        ProductionBudgetUsage usage) {
    public ProductionPlanValidation {
        violations = List.copyOf(Objects.requireNonNull(
                violations, "violations"));
        resolvedNodes = List.copyOf(Objects.requireNonNull(
                resolvedNodes, "resolvedNodes"));
        Objects.requireNonNull(projectedFinalLedger, "projectedFinalLedger");
        Objects.requireNonNull(usage, "usage");
        if (accepted != violations.isEmpty()) {
            throw new IllegalArgumentException(
                    "accepted validation must agree with violations");
        }
        if (!accepted && !resolvedNodes.isEmpty()) {
            throw new IllegalArgumentException(
                    "rejected validation must not expose executable nodes");
        }
    }

    public static ProductionPlanValidation rejected(
            List<ProductionPlanViolation> violations,
            ProductionBudgetUsage usage) {
        return new ProductionPlanValidation(false, violations, List.of(),
                ProductionLedger.empty(), usage);
    }

    public static ProductionPlanValidation accepted(
            List<ProductionResolvedNode> resolvedNodes,
            ProductionLedger finalLedger,
            ProductionBudgetUsage usage) {
        return new ProductionPlanValidation(true, List.of(), resolvedNodes,
                finalLedger, usage);
    }
}
