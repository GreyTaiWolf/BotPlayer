package io.github.greytaiwolf.botplayer.skill.builtin.defense;

import java.util.Objects;
import java.util.UUID;

/**
 * 自卫决策所需的单一目标快照。
 *
 * <p>一个运行只能绑定一个实体标识；后续观测若换成其他实体，状态机会拒绝
 * 继续执行，而不会在多个目标间切换。
 */
public record DefenseTarget(
        UUID entityId,
        DefenseTargetClass targetClass,
        boolean alive,
        double distanceSquared) {
    public DefenseTarget {
        requireNonZeroUuid(entityId, "entityId");
        targetClass = Objects.requireNonNull(targetClass, "targetClass");
        if (!Double.isFinite(distanceSquared) || distanceSquared < 0.0D) {
            throw new IllegalArgumentException(
                    "distanceSquared must be finite and non-negative");
        }
    }

    static void requireNonZeroUuid(UUID value, String name) {
        Objects.requireNonNull(value, name);
        if (value.getMostSignificantBits() == 0L
                && value.getLeastSignificantBits() == 0L) {
            throw new IllegalArgumentException(name + " must not be zero");
        }
    }
}
