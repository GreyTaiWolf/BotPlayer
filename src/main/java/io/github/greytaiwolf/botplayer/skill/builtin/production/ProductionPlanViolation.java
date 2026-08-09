package io.github.greytaiwolf.botplayer.skill.builtin.production;

import java.util.Objects;

/**
 * 一个可展示但无执行细节的 fail-closed 拒绝原因。
 */
public record ProductionPlanViolation(
        String scope, ProductionPlanViolationCode code) {
    public ProductionPlanViolation {
        Objects.requireNonNull(scope, "scope");
        if (scope.length() > 80) {
            throw new IllegalArgumentException("violation scope is too long");
        }
        Objects.requireNonNull(code, "code");
    }
}
