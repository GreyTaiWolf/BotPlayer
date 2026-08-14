package io.github.greytaiwolf.botplayer.technique.runtime;

import io.github.greytaiwolf.botplayer.technique.core.TechniqueFailureCode;
import java.util.Objects;
import java.util.UUID;

/** Exact, terminal child acknowledgement accepted by the Technique runtime. */
public record TechniqueSignal(
        UUID techniqueRunId,
        UUID ticketId,
        UUID botId,
        long botGeneration,
        long ticketRevision,
        TechniqueChildState state,
        TechniqueFailureCode failureCode,
        String safeSummary) {
    public TechniqueSignal {
        requireNonZero(techniqueRunId, "techniqueRunId");
        requireNonZero(ticketId, "ticketId");
        requireNonZero(botId, "botId");
        if (botGeneration < 1L || ticketRevision < 1L) {
            throw new IllegalArgumentException(
                    "technique signal generation/revision must be positive");
        }
        Objects.requireNonNull(state, "state");
        if (!state.isTerminal()) {
            throw new IllegalArgumentException(
                    "technique signal must acknowledge a terminal child state");
        }
        Objects.requireNonNull(failureCode, "failureCode");
        if (state == TechniqueChildState.SUCCEEDED
                && failureCode != TechniqueFailureCode.NONE) {
            throw new IllegalArgumentException(
                    "successful child signal must use NONE failure code");
        }
        if (state != TechniqueChildState.SUCCEEDED
                && failureCode == TechniqueFailureCode.NONE) {
            throw new IllegalArgumentException(
                    "non-successful child signal requires a failure code");
        }
        safeSummary = TechniqueText.summary(safeSummary);
    }

    private static void requireNonZero(UUID value, String name) {
        if (new UUID(0L, 0L).equals(Objects.requireNonNull(value, name))) {
            throw new IllegalArgumentException(name + " must not be zero UUID");
        }
    }
}
