package io.github.greytaiwolf.botplayer.action.interaction;

import java.util.Objects;

/**
 * Immutable server-thread observation of one live active effect.
 *
 * <p>Unlike {@link ActiveEffectFingerprint}, which carries a requested minimum
 * duration floor, this DTO records the remaining duration observed right now.
 * It remains pure data so the action precondition comparison never retains a
 * Minecraft {@code MobEffectInstance} outside the backend read boundary.
 */
public record ActiveEffectObservation(
        ResourceId effectId,
        int amplifier,
        int remainingDurationTicks,
        boolean ambient,
        boolean visible) implements Comparable<ActiveEffectObservation> {
    public ActiveEffectObservation {
        effectId = Objects.requireNonNull(effectId, "effectId");
        if (amplifier < 0
                || amplifier > ActiveEffectFingerprint.MAXIMUM_AMPLIFIER) {
            throw new IllegalArgumentException(
                    "effect amplifier is outside the supported range");
        }
        if (remainingDurationTicks < 0) {
            throw new IllegalArgumentException(
                    "remainingDurationTicks must not be negative");
        }
    }

    @Override
    public int compareTo(ActiveEffectObservation other) {
        return effectId.value().compareTo(Objects.requireNonNull(
                other, "other").effectId.value());
    }
}
