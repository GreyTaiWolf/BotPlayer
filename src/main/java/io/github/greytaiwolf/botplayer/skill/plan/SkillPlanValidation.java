package io.github.greytaiwolf.botplayer.skill.plan;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 只有 valid 结果才暴露可执行的确定性串行拓扑顺序。
 */
public record SkillPlanValidation(
        boolean valid,
        List<SkillPlanViolation> violations,
        List<UUID> serialExecutionOrder,
        int maximumDepth,
        boolean violationsTruncated) {
    public static final int MAX_VIOLATIONS = 512;

    public SkillPlanValidation {
        violations = List.copyOf(
                Objects.requireNonNull(violations, "violations"));
        serialExecutionOrder = List.copyOf(
                Objects.requireNonNull(
                        serialExecutionOrder,
                        "serialExecutionOrder"));
        if (violations.size() > MAX_VIOLATIONS) {
            throw new IllegalArgumentException(
                    "violations exceeds maximum size "
                            + MAX_VIOLATIONS);
        }
        if (serialExecutionOrder.size()
                > SkillPlan.ABSOLUTE_MAX_NODES) {
            throw new IllegalArgumentException(
                    "serialExecutionOrder exceeds its absolute bound");
        }
        if (maximumDepth < 0
                || maximumDepth > SkillPlan.ABSOLUTE_MAX_NODES) {
            throw new IllegalArgumentException(
                    "maximumDepth is outside its absolute bound");
        }
        if (valid
                != (violations.isEmpty()
                        && !violationsTruncated)) {
            throw new IllegalArgumentException(
                    "valid must match violations and truncation");
        }
        if (!valid && !serialExecutionOrder.isEmpty()) {
            throw new IllegalArgumentException(
                    "invalid plans must not expose an execution order");
        }
    }
}
