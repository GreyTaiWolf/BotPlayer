package io.github.greytaiwolf.botplayer.safety;

import java.util.Objects;

public record EffectSummary(
        String effectId,
        Category category,
        int amplifier,
        int remainingTicks,
        boolean ambient,
        boolean visible) {
    public EffectSummary {
        if (effectId == null
                || effectId.isBlank()
                || effectId.length() > 256) {
            throw new IllegalArgumentException(
                    "effectId must contain 1-256 characters");
        }
        Objects.requireNonNull(category, "category");
        if (amplifier < 0 || remainingTicks < 0) {
            throw new IllegalArgumentException(
                    "effect amplifier and duration must not be negative");
        }
    }

    public enum Category {
        BENEFICIAL,
        HARMFUL,
        NEUTRAL,
        UNKNOWN
    }
}
