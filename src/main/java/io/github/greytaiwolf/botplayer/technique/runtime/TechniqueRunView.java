package io.github.greytaiwolf.botplayer.technique.runtime;

import io.github.greytaiwolf.botplayer.technique.core.TechniqueId;
import io.github.greytaiwolf.botplayer.technique.core.TechniqueVersion;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Read-only snapshot supplied to technique handlers and diagnostics. */
public record TechniqueRunView(
        UUID techniqueRunId,
        UUID skillRunId,
        UUID botId,
        long botGeneration,
        TechniqueId techniqueId,
        TechniqueVersion techniqueVersion,
        TechniqueState state,
        String phase,
        long startedTick,
        long deadlineTick,
        long revision,
        int submittedChildren,
        int recoveryAttempts,
        List<TechniqueChildTicket> childTickets) {
    public TechniqueRunView {
        Objects.requireNonNull(techniqueRunId, "techniqueRunId");
        Objects.requireNonNull(skillRunId, "skillRunId");
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(techniqueId, "techniqueId");
        Objects.requireNonNull(techniqueVersion, "techniqueVersion");
        Objects.requireNonNull(state, "state");
        phase = TechniqueText.phase(phase);
        if (botGeneration < 1L || startedTick < 0L
                || deadlineTick < startedTick || revision < 1L
                || submittedChildren < 0 || recoveryAttempts < 0) {
            throw new IllegalArgumentException("technique run view is invalid");
        }
        childTickets = List.copyOf(Objects.requireNonNull(childTickets,
                "childTickets"));
    }
}
