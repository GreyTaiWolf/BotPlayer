package io.github.greytaiwolf.botplayer.lifecycle.retirement;

import java.util.Objects;
import java.util.UUID;

/**
 * 绑定一次 generation 退役及其唯一后续动作的不可变身份。
 */
public record GenerationRetirementKey(
        UUID retirementId,
        UUID botId,
        long generation,
        GenerationRetirementContinuation continuation) {
    private static final UUID ZERO_UUID = new UUID(0L, 0L);

    public GenerationRetirementKey {
        Objects.requireNonNull(retirementId, "retirementId");
        Objects.requireNonNull(botId, "botId");
        continuation = Objects.requireNonNull(
                continuation, "continuation");
        if (ZERO_UUID.equals(retirementId)
                || ZERO_UUID.equals(botId)) {
            throw new IllegalArgumentException(
                    "retirement and bot ids must be non-zero");
        }
        if (generation <= 0L) {
            throw new IllegalArgumentException(
                    "generation must be positive");
        }
    }
}
