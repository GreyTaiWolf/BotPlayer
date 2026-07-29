package io.github.greytaiwolf.botplayer.safety;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record DamageCandidate(
        UUID botId,
        long botGeneration,
        long gameTick,
        String damageTypeId,
        Optional<UUID> directEntityId,
        Optional<UUID> causingEntityId,
        float finalDamage) {
    public DamageCandidate {
        Objects.requireNonNull(botId, "botId");
        if (botGeneration <= 0L || gameTick < 0L) {
            throw new IllegalArgumentException(
                    "generation must be positive and tick non-negative");
        }
        if (damageTypeId == null
                || damageTypeId.isBlank()
                || damageTypeId.length() > 256) {
            throw new IllegalArgumentException(
                    "damageTypeId must contain 1-256 characters");
        }
        Objects.requireNonNull(directEntityId, "directEntityId");
        Objects.requireNonNull(causingEntityId, "causingEntityId");
        if (!Float.isFinite(finalDamage) || finalDamage <= 0.0F) {
            throw new IllegalArgumentException(
                    "finalDamage must be finite and positive");
        }
    }
}
