package io.github.greytaiwolf.botplayer.technique.core;

import java.util.Objects;

/**
 * Immutable presentation profile.  It affects only deterministic timing and
 * preference inside already-authorized techniques; it is not a permission or
 * budget override.
 */
public record TechniqueProfile(
        TechniqueSkillLevel skillLevel,
        CombatStyle combatStyle,
        BuildingStyle buildingStyle,
        float caution,
        float aggression,
        int reactionDelayMinTicks,
        int reactionDelayMaxTicks,
        float maximumYawPerTick,
        float maximumPitchPerTick,
        long deterministicSeedSalt) {
    public static final int MAX_REACTION_DELAY_TICKS = 200;

    public TechniqueProfile {
        Objects.requireNonNull(skillLevel, "skillLevel");
        Objects.requireNonNull(combatStyle, "combatStyle");
        Objects.requireNonNull(buildingStyle, "buildingStyle");
        requireUnit(caution, "caution");
        requireUnit(aggression, "aggression");
        if (reactionDelayMinTicks < 0
                || reactionDelayMaxTicks < reactionDelayMinTicks
                || reactionDelayMaxTicks > MAX_REACTION_DELAY_TICKS) {
            throw new IllegalArgumentException(
                    "reaction delay range must be within 0-"
                            + MAX_REACTION_DELAY_TICKS);
        }
        requireTurn(maximumYawPerTick, "maximumYawPerTick");
        requireTurn(maximumPitchPerTick, "maximumPitchPerTick");
    }

    public static TechniqueProfile defaults() {
        return new TechniqueProfile(TechniqueSkillLevel.PRACTICED,
                CombatStyle.BALANCED, BuildingStyle.BALANCED, 0.75F, 0.5F,
                1, 3, 30.0F, 20.0F, 0L);
    }

    private static void requireUnit(float value, String name) {
        if (!Float.isFinite(value) || value < 0.0F || value > 1.0F) {
            throw new IllegalArgumentException(name + " must be finite 0..1");
        }
    }

    private static void requireTurn(float value, String name) {
        if (!Float.isFinite(value) || value <= 0.0F || value > 180.0F) {
            throw new IllegalArgumentException(name
                    + " must be finite and within (0, 180]");
        }
    }
}
