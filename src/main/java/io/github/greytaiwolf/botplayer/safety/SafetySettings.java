package io.github.greytaiwolf.botplayer.safety;

import io.github.greytaiwolf.botplayer.config.BotPlayerConfig;

public record SafetySettings(
        float criticalHealth,
        int criticalFood,
        int criticalAir,
        int criticalFrozenTicks,
        double entityRadius,
        double hostileRadius,
        double projectileRadius,
        double explosionRadius,
        int maximumEntityReads,
        int maximumRawEntityReads,
        int clearStableTicks,
        int maximumInterventions,
        int retreatInputTicks,
        int maximumSafeDrop) {
    public SafetySettings {
        if (!Float.isFinite(criticalHealth)
                || criticalHealth < 0.0F
                || criticalHealth > 2_048.0F) {
            throw new IllegalArgumentException(
                    "criticalHealth must be finite and between 0 and 2048");
        }
        requireRange(criticalFood, 0, 20, "criticalFood");
        requireRange(criticalAir, 0, 300, "criticalAir");
        requireRange(
                criticalFrozenTicks,
                0,
                1_000,
                "criticalFrozenTicks");
        requireRadius(entityRadius, 1.0D, 32.0D, "entityRadius");
        requireRadius(hostileRadius, 1.0D, entityRadius, "hostileRadius");
        requireRadius(
                projectileRadius,
                1.0D,
                entityRadius,
                "projectileRadius");
        requireRadius(
                explosionRadius,
                1.0D,
                entityRadius,
                "explosionRadius");
        requireRange(maximumEntityReads, 1, 128, "maximumEntityReads");
        requireRange(
                maximumRawEntityReads,
                maximumEntityReads,
                2_048,
                "maximumRawEntityReads");
        requireRange(clearStableTicks, 1, 100, "clearStableTicks");
        requireRange(
                maximumInterventions,
                1,
                16,
                "maximumInterventions");
        requireRange(retreatInputTicks, 2, 5, "retreatInputTicks");
        requireRange(maximumSafeDrop, 0, 4, "maximumSafeDrop");
    }

    public static SafetySettings fromConfig() {
        return new SafetySettings(
                BotPlayerConfig.SAFETY_CRITICAL_HEALTH.get().floatValue(),
                BotPlayerConfig.SAFETY_CRITICAL_FOOD.get(),
                BotPlayerConfig.SAFETY_CRITICAL_AIR.get(),
                BotPlayerConfig.SAFETY_CRITICAL_FROZEN_TICKS.get(),
                BotPlayerConfig.SAFETY_ENTITY_RADIUS.get(),
                BotPlayerConfig.SAFETY_HOSTILE_RADIUS.get(),
                BotPlayerConfig.SAFETY_PROJECTILE_RADIUS.get(),
                BotPlayerConfig.SAFETY_EXPLOSION_RADIUS.get(),
                BotPlayerConfig.SAFETY_MAXIMUM_ENTITY_READS.get(),
                BotPlayerConfig.SAFETY_MAXIMUM_RAW_ENTITY_READS.get(),
                BotPlayerConfig.SAFETY_CLEAR_STABLE_TICKS.get(),
                BotPlayerConfig.SAFETY_MAXIMUM_INTERVENTIONS.get(),
                BotPlayerConfig.SAFETY_RETREAT_INPUT_TICKS.get(),
                BotPlayerConfig.SAFETY_MAXIMUM_SAFE_DROP.get());
    }

    private static void requireRange(
            int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    name + " must be between " + minimum + " and " + maximum);
        }
    }

    private static void requireRadius(
            double value, double minimum, double maximum, String name) {
        if (!Double.isFinite(value)
                || value < minimum
                || value > maximum) {
            throw new IllegalArgumentException(
                    name + " must be finite and between "
                            + minimum
                            + " and "
                            + maximum);
        }
    }
}
