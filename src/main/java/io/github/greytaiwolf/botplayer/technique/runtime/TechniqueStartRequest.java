package io.github.greytaiwolf.botplayer.technique.runtime;

import io.github.greytaiwolf.botplayer.technique.core.TechniqueId;
import io.github.greytaiwolf.botplayer.technique.core.TechniqueParameters;
import io.github.greytaiwolf.botplayer.technique.core.TechniqueVersion;
import java.util.Objects;
import java.util.UUID;

/** Immutable server-side request to start one registered technique. */
public record TechniqueStartRequest(
        UUID skillRunId,
        UUID botId,
        long botGeneration,
        TechniqueId techniqueId,
        TechniqueVersion techniqueVersion,
        TechniqueParameters parameters,
        long currentTick) {
    public TechniqueStartRequest {
        requireNonZero(skillRunId, "skillRunId");
        requireNonZero(botId, "botId");
        if (botGeneration < 1L || currentTick < 0L) {
            throw new IllegalArgumentException(
                    "technique start generation/tick is invalid");
        }
        Objects.requireNonNull(techniqueId, "techniqueId");
        Objects.requireNonNull(techniqueVersion, "techniqueVersion");
        Objects.requireNonNull(parameters, "parameters");
    }

    private static void requireNonZero(UUID value, String name) {
        if (new UUID(0L, 0L).equals(Objects.requireNonNull(value, name))) {
            throw new IllegalArgumentException(name + " must not be zero UUID");
        }
    }
}
