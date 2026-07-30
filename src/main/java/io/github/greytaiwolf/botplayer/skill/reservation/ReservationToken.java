package io.github.greytaiwolf.botplayer.skill.reservation;

import java.util.Objects;
import java.util.UUID;

/**
 * 仅在当前服务器进程和 bot generation 内有效的资源租约。
 */
public record ReservationToken(
        UUID reservationId,
        UUID botId,
        long botGeneration,
        UUID skillRunId,
        ReservationKey key,
        ReservationMode mode,
        long acquiredTick,
        long expiresTick) {
    private static final UUID ZERO_UUID = new UUID(0L, 0L);

    public ReservationToken {
        requireNonZero(reservationId, "reservationId");
        requireNonZero(botId, "botId");
        requireNonZero(skillRunId, "skillRunId");
        if (botGeneration <= 0L) {
            throw new IllegalArgumentException(
                    "botGeneration must be positive");
        }
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(mode, "mode");
        if (acquiredTick < 0L) {
            throw new IllegalArgumentException(
                    "acquiredTick must not be negative");
        }
        if (expiresTick <= acquiredTick) {
            throw new IllegalArgumentException(
                    "expiresTick must follow acquiredTick");
        }
    }

    public boolean expiredAt(long currentTick) {
        if (currentTick < 0L) {
            throw new IllegalArgumentException(
                    "currentTick must not be negative");
        }
        return currentTick >= expiresTick;
    }

    private static void requireNonZero(UUID value, String name) {
        Objects.requireNonNull(value, name);
        if (ZERO_UUID.equals(value)) {
            throw new IllegalArgumentException(name + " must not be zero");
        }
    }
}
