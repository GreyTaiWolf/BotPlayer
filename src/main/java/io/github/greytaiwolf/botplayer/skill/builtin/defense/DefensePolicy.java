package io.github.greytaiwolf.botplayer.skill.builtin.defense;

/**
 * 有限自卫的固定安全边界。
 *
 * <p>攻击和撤退配额在动作发出时即被消耗，以免失败回执或迟到回执导致无限重试。
 */
public record DefensePolicy(
        double retreatHealthFraction,
        double maximumMeleeDistanceSquared,
        int maximumAttackAttempts,
        int maximumRetreatAttempts) {
    public static final int MAXIMUM_ACTION_ATTEMPTS = 32;

    public DefensePolicy {
        if (!Double.isFinite(retreatHealthFraction)
                || retreatHealthFraction < 0.0D
                || retreatHealthFraction > 1.0D) {
            throw new IllegalArgumentException(
                    "retreatHealthFraction must be in [0, 1]");
        }
        if (!Double.isFinite(maximumMeleeDistanceSquared)
                || maximumMeleeDistanceSquared < 0.0D
                || maximumMeleeDistanceSquared > 64.0D) {
            throw new IllegalArgumentException(
                    "maximumMeleeDistanceSquared must be in [0, 64]");
        }
        requireBudget(maximumAttackAttempts, "maximumAttackAttempts");
        requireBudget(maximumRetreatAttempts, "maximumRetreatAttempts");
    }

    /** P5A 默认只在三格内、最多两次近战尝试后撤退。 */
    public static DefensePolicy p5aDefault() {
        return new DefensePolicy(0.35D, 9.0D, 2, 1);
    }

    private static void requireBudget(int value, String name) {
        if (value < 0 || value > MAXIMUM_ACTION_ATTEMPTS) {
            throw new IllegalArgumentException(
                    name + " must be in [0, "
                            + MAXIMUM_ACTION_ATTEMPTS + "]");
        }
    }
}
