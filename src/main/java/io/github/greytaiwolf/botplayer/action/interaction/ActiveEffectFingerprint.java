package io.github.greytaiwolf.botplayer.action.interaction;

import java.util.Objects;

/**
 * Immutable, server-observable identity for one currently active status effect.
 *
 * <p>The value intentionally contains no live Minecraft effect instance and
 * uses a bounded lower duration floor. Durations naturally tick down while an action
 * waits for a control lease, so treating a countdown as an exact dispatch
 * precondition would make a safe request spuriously fail. Instead, the backend
 * requires the live duration to remain at or above this floor. This DTO protects
 * identity, amplifier, presentation flags and the caller's minimum remaining
 * duration at the action dispatch boundary.
 */
public record ActiveEffectFingerprint(
        ResourceId effectId,
        int amplifier,
        int minimumRemainingDurationTicks,
        boolean ambient,
        boolean visible) implements Comparable<ActiveEffectFingerprint> {
    /** Vanilla's network/command representation is bounded to unsigned byte. */
    public static final int MAXIMUM_AMPLIFIER = 255;
    /** No action may run for more than the action-envelope tick ceiling. */
    public static final int MAXIMUM_DURATION_FLOOR_TICKS = 6_000;

    public ActiveEffectFingerprint {
        effectId = Objects.requireNonNull(effectId, "effectId");
        if (amplifier < 0 || amplifier > MAXIMUM_AMPLIFIER) {
            throw new IllegalArgumentException(
                    "effect amplifier must be between 0 and "
                            + MAXIMUM_AMPLIFIER);
        }
        if (minimumRemainingDurationTicks < 0
                || minimumRemainingDurationTicks
                        > MAXIMUM_DURATION_FLOOR_TICKS) {
            throw new IllegalArgumentException(
                    "minimumRemainingDurationTicks is outside the supported range");
        }
    }

    @Override
    public int compareTo(ActiveEffectFingerprint other) {
        return effectId.value().compareTo(Objects.requireNonNull(
                other, "other").effectId.value());
    }
}
