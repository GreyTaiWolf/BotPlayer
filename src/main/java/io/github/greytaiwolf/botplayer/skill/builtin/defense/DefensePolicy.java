package io.github.greytaiwolf.botplayer.skill.builtin.defense;

/**
 * 有限自卫的固定安全边界。
 *
 * <p>攻击和撤退配额在动作发出时即被消耗，以免失败回执或迟到回执导致无限重试。
 */
public record DefensePolicy(
        double retreatHealthFraction,
        double maximumMeleeDistanceSquared,
        double confirmedSafeRetreatDistanceSquared,
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
        if (!Double.isFinite(confirmedSafeRetreatDistanceSquared)
                || confirmedSafeRetreatDistanceSquared
                        <= maximumMeleeDistanceSquared
                || confirmedSafeRetreatDistanceSquared > 256.0D) {
            throw new IllegalArgumentException(
                    "confirmedSafeRetreatDistanceSquared must be in "
                            + "(maximumMeleeDistanceSquared, 256]");
        }
        requireBudget(maximumAttackAttempts, "maximumAttackAttempts");
        requireBudget(maximumRetreatAttempts, "maximumRetreatAttempts");
    }

    /** 保持早期调用方的构造形状，同时使用五格外的确认安全距离。 */
    public DefensePolicy(
            double retreatHealthFraction,
            double maximumMeleeDistanceSquared,
            int maximumAttackAttempts,
            int maximumRetreatAttempts) {
        this(
                retreatHealthFraction,
                maximumMeleeDistanceSquared,
                defaultConfirmedSafeRetreatDistanceSquared(
                        maximumMeleeDistanceSquared),
                maximumAttackAttempts,
                maximumRetreatAttempts);
    }

    /** P5A 默认只在三格内、最多两次近战尝试后撤退，五格外才可确认脱离近战。 */
    public static DefensePolicy p5aDefault() {
        return new DefensePolicy(0.35D, 9.0D, 25.0D, 2, 1);
    }

    private static void requireBudget(int value, String name) {
        if (value < 0 || value > MAXIMUM_ACTION_ATTEMPTS) {
            throw new IllegalArgumentException(
                    name + " must be in [0, "
                            + MAXIMUM_ACTION_ATTEMPTS + "]");
        }
    }

    private static double defaultConfirmedSafeRetreatDistanceSquared(
            double maximumMeleeDistanceSquared) {
        return Math.min(
                256.0D,
                Math.max(25.0D, maximumMeleeDistanceSquared + 16.0D));
    }
}
