package io.github.greytaiwolf.botplayer.technique.runtime;

import io.github.greytaiwolf.botplayer.action.ActionChannel;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Immutable server-issued identity of one bounded child request. */
public record TechniqueChildTicket(
        UUID ticketId,
        UUID techniqueRunId,
        UUID botId,
        long botGeneration,
        long revision,
        String operationKey,
        Set<ActionChannel> channels,
        long submittedTick,
        TechniqueChildState state) {
    public TechniqueChildTicket {
        requireNonZero(ticketId, "ticketId");
        requireNonZero(techniqueRunId, "techniqueRunId");
        requireNonZero(botId, "botId");
        if (botGeneration < 1L || revision < 1L || submittedTick < 0L) {
            throw new IllegalArgumentException(
                    "technique child identity must be positive");
        }
        operationKey = TechniqueText.operation(operationKey);
        channels = new TechniqueChildRequest(operationKey, channels).channels();
        Objects.requireNonNull(state, "state");
    }

    public TechniqueChildTicket terminal(TechniqueChildState next) {
        Objects.requireNonNull(next, "next");
        if (!next.isTerminal()) {
            throw new IllegalArgumentException("child completion must be terminal");
        }
        if (state != TechniqueChildState.ACTIVE) {
            throw new IllegalStateException("technique child is already terminal");
        }
        return new TechniqueChildTicket(ticketId, techniqueRunId, botId,
                botGeneration, revision, operationKey, channels, submittedTick,
                next);
    }

    private static void requireNonZero(UUID value, String name) {
        if (new UUID(0L, 0L).equals(Objects.requireNonNull(value, name))) {
            throw new IllegalArgumentException(name + " must not be zero UUID");
        }
    }
}
