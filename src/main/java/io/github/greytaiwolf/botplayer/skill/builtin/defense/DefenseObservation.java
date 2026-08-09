package io.github.greytaiwolf.botplayer.skill.builtin.defense;

import java.util.Objects;

/** 单 Tick 的纯值观测，供状态机决定下一步而不触碰 Minecraft 对象。 */
public record DefenseObservation(
        double currentHealth,
        double maximumHealth,
        DefenseTarget target) {
    public DefenseObservation {
        if (!Double.isFinite(currentHealth) || currentHealth < 0.0D) {
            throw new IllegalArgumentException(
                    "currentHealth must be finite and non-negative");
        }
        if (!Double.isFinite(maximumHealth) || maximumHealth <= 0.0D) {
            throw new IllegalArgumentException(
                    "maximumHealth must be finite and positive");
        }
        if (currentHealth > maximumHealth) {
            throw new IllegalArgumentException(
                    "currentHealth must not exceed maximumHealth");
        }
        target = Objects.requireNonNull(target, "target");
    }

    public double healthFraction() {
        return currentHealth / maximumHealth;
    }
}
