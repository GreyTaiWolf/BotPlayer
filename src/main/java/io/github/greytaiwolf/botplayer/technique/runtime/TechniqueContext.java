package io.github.greytaiwolf.botplayer.technique.runtime;

import io.github.greytaiwolf.botplayer.technique.core.TechniqueId;
import io.github.greytaiwolf.botplayer.technique.core.TechniqueVersion;
import java.util.Objects;
import java.util.UUID;

/**
 * Scalar execution identity visible to a technique handler.  It intentionally
 * contains no Entity, Level, ItemStack, menu or mutable server handle.
 */
public record TechniqueContext(
        UUID techniqueRunId,
        UUID skillRunId,
        UUID botId,
        long botGeneration,
        TechniqueId techniqueId,
        TechniqueVersion techniqueVersion,
        long currentTick,
        long revision) {
    public TechniqueContext {
        requireNonZero(techniqueRunId, "techniqueRunId");
        requireNonZero(skillRunId, "skillRunId");
        requireNonZero(botId, "botId");
        if (botGeneration < 1L || currentTick < 0L || revision < 1L) {
            throw new IllegalArgumentException(
                    "technique context generation/tick/revision is invalid");
        }
        Objects.requireNonNull(techniqueId, "techniqueId");
        Objects.requireNonNull(techniqueVersion, "techniqueVersion");
    }

    private static void requireNonZero(UUID value, String name) {
        if (new UUID(0L, 0L).equals(Objects.requireNonNull(value, name))) {
            throw new IllegalArgumentException(name + " must not be zero UUID");
        }
    }
}
