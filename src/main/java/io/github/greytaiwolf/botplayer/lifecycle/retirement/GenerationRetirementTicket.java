package io.github.greytaiwolf.botplayer.lifecycle.retirement;

import java.util.Objects;

/**
 * 一次 generation 退役 attempt 的不可变票据。
 *
 * <p>后续 attempt 只能消费上一张精确的 PENDING 回执创建，不能跳过
 * 后端指定的重试 Tick。
 */
public record GenerationRetirementTicket(
        GenerationRetirementKey key,
        long startedTick,
        long deadlineTick,
        long currentTick,
        int attempt) {
    public static final int MAX_ATTEMPTS = 256;

    public GenerationRetirementTicket {
        key = Objects.requireNonNull(key, "key");
        if (startedTick < 0L
                || deadlineTick < startedTick
                || currentTick < startedTick) {
            throw new IllegalArgumentException(
                    "retirement ticks are invalid");
        }
        if (attempt < 1 || attempt > MAX_ATTEMPTS) {
            throw new IllegalArgumentException(
                    "retirement attempt is outside the bounded range");
        }
    }

    public static GenerationRetirementTicket first(
            GenerationRetirementKey key,
            long startedTick,
            long deadlineTick) {
        return new GenerationRetirementTicket(
                key,
                startedTick,
                deadlineTick,
                startedTick,
                1);
    }

    /** 消费本 attempt 的精确 PENDING 回执。 */
    public GenerationRetirementTicket next(
            GenerationRetirementReceipt receipt,
            long retryTick) {
        Objects.requireNonNull(receipt, "receipt");
        if (!receipt.matches(this)
                || receipt.status()
                        != GenerationRetirementStatus.PENDING) {
            throw new IllegalArgumentException(
                    "retirement retry requires its exact PENDING receipt");
        }
        if (retryTick < receipt.nextRetryTick()
                || retryTick <= currentTick) {
            throw new IllegalArgumentException(
                    "retirement retry cannot precede its authorized Tick");
        }
        if (attempt >= MAX_ATTEMPTS) {
            throw new IllegalStateException(
                    "retirement attempt budget is exhausted");
        }
        return new GenerationRetirementTicket(
                key,
                startedTick,
                deadlineTick,
                retryTick,
                attempt + 1);
    }
}
