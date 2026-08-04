package io.github.greytaiwolf.botplayer.lifecycle.death;

/**
 * 原版死亡事务冻结的精确经验三元组。
 *
 * <p>浮点进度按原始 bit 比较，避免持久化恢复时把相近值误当成同一份交接。
 */
public record DeathExperienceSnapshot(
        int level, int total, float progress) {
    public static final DeathExperienceSnapshot ZERO =
            new DeathExperienceSnapshot(0, 0, 0.0F);

    public DeathExperienceSnapshot {
        if (level < 0 || total < 0) {
            throw new IllegalArgumentException(
                    "experience values must be non-negative");
        }
        if (!Float.isFinite(progress)
                || progress < 0.0F
                || progress > 1.0F) {
            throw new IllegalArgumentException(
                    "experience progress must be finite and within [0, 1]");
        }
    }

    public boolean exactlyMatches(
            int candidateLevel,
            int candidateTotal,
            float candidateProgress) {
        return level == candidateLevel
                && total == candidateTotal
                && Float.floatToRawIntBits(progress)
                        == Float.floatToRawIntBits(
                                candidateProgress);
    }
}
