package io.github.greytaiwolf.botplayer.skill.builtin.defense;

import io.github.greytaiwolf.botplayer.safety.SafetyRetreat;
import java.util.Objects;
import java.util.Optional;

/**
 * 单 Tick 的纯值观测，供状态机决定下一步而不触碰 Minecraft 对象。
 *
 * <p>威胁数量和撤退候选只能来自同一份完整 L0 安全帧；缺失或截断时状态机必须拒绝近战。
 */
public record DefenseObservation(
        double currentHealth,
        double maximumHealth,
        DefenseTarget target,
        boolean threatCoverageIncomplete,
        int hostileThreatCount,
        Optional<SafetyRetreat> safeRetreat) {
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
        if (hostileThreatCount < 0 || hostileThreatCount > 256) {
            throw new IllegalArgumentException(
                    "hostileThreatCount must be in [0, 256]");
        }
        safeRetreat = Objects.requireNonNull(safeRetreat, "safeRetreat");
        if (threatCoverageIncomplete && safeRetreat.isPresent()) {
            throw new IllegalArgumentException(
                    "incomplete threat coverage cannot prove a retreat path");
        }
    }

    /** 旧的三字段调用保守地视为威胁覆盖不完整，因而绝不会产生近战。 */
    public DefenseObservation(
            double currentHealth,
            double maximumHealth,
            DefenseTarget target) {
        this(
                currentHealth,
                maximumHealth,
                target,
                true,
                0,
                Optional.empty());
    }

    public double healthFraction() {
        return currentHealth / maximumHealth;
    }
}
