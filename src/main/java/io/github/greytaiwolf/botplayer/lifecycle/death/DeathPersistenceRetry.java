package io.github.greytaiwolf.botplayer.lifecycle.death;

/**
 * 死亡 playerdata 提交使用的独立有界重试时钟。
 */
public record DeathPersistenceRetry(
        int failures,
        long nextAttemptTick,
        long deadlineTick) {
    public static final int MAX_ATTEMPTS = 4;
    public static final long RETRY_INTERVAL_TICKS = 20L;

    public DeathPersistenceRetry {
        if (failures < 0 || failures > MAX_ATTEMPTS) {
            throw new IllegalArgumentException(
                    "failures are outside the retry budget");
        }
        if (nextAttemptTick < 0L || deadlineTick < 0L) {
            throw new IllegalArgumentException(
                    "retry ticks must be non-negative");
        }
    }

    public static DeathPersistenceRetry open(
            long currentTick, long deadlineTick) {
        if (currentTick < 0L || deadlineTick < currentTick) {
            throw new IllegalArgumentException(
                    "deadline must not precede the current tick");
        }
        return new DeathPersistenceRetry(
                0, currentTick, deadlineTick);
    }

    public boolean canAttempt(long currentTick) {
        return !exhausted(currentTick)
                && currentTick >= nextAttemptTick;
    }

    public boolean exhausted(long currentTick) {
        return failures >= MAX_ATTEMPTS
                || currentTick > deadlineTick;
    }

    public DeathPersistenceRetry afterFailure(
            long currentTick) {
        if (!canAttempt(currentTick)) {
            throw new IllegalStateException(
                    "a persistence failure was recorded without an eligible attempt");
        }
        int updatedFailures = failures + 1;
        long delayedTick = saturatedAdd(
                currentTick, RETRY_INTERVAL_TICKS);
        long updatedNext = Math.min(
                delayedTick, deadlineTick);
        return new DeathPersistenceRetry(
                updatedFailures,
                updatedNext,
                deadlineTick);
    }

    private static long saturatedAdd(
            long value, long increment) {
        return value > Long.MAX_VALUE - increment
                ? Long.MAX_VALUE
                : value + increment;
    }
}
