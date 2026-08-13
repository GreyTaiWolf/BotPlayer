package io.github.greytaiwolf.botplayer.skill.pack;

import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanValidation;
import java.util.List;
import java.util.Objects;

/**
 * 包级检查与底层 DAG 检查的不可变汇总；任何一层失败都不能批准。
 */
public record SkillPackValidation(
        SkillPackRevision revision,
        List<SkillPackViolation> violations,
        SkillPlanValidation planValidation) {
    public static final int MAX_VIOLATIONS = 128;

    public SkillPackValidation {
        Objects.requireNonNull(revision, "revision");
        violations = List.copyOf(
                Objects.requireNonNull(violations, "violations"));
        if (violations.size() > MAX_VIOLATIONS) {
            throw new IllegalArgumentException(
                    "violations exceeds maximum " + MAX_VIOLATIONS);
        }
        Objects.requireNonNull(planValidation, "planValidation");
    }

    public boolean valid() {
        return violations.isEmpty() && planValidation.valid();
    }
}
