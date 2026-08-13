package io.github.greytaiwolf.botplayer.technique.runtime;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Start result that never exposes mutable runtime internals. */
public record TechniqueSubmission(
        Status status,
        Optional<UUID> techniqueRunId,
        String safeSummary) {
    public TechniqueSubmission {
        Objects.requireNonNull(status, "status");
        techniqueRunId = Optional.ofNullable(
                Objects.requireNonNull(techniqueRunId, "techniqueRunId")
                        .orElse(null));
        safeSummary = TechniqueText.summary(safeSummary);
        if (status == Status.ACCEPTED && techniqueRunId.isEmpty()) {
            throw new IllegalArgumentException(
                    "accepted technique submission requires a run id");
        }
        if (status != Status.ACCEPTED && techniqueRunId.isPresent()) {
            throw new IllegalArgumentException(
                    "rejected technique submission cannot contain a run id");
        }
    }

    public static TechniqueSubmission accepted(UUID runId, String summary) {
        return new TechniqueSubmission(Status.ACCEPTED,
                Optional.of(Objects.requireNonNull(runId, "runId")), summary);
    }

    public static TechniqueSubmission rejected(Status status, String summary) {
        if (status == Status.ACCEPTED) {
            throw new IllegalArgumentException(
                    "rejected technique submission cannot be ACCEPTED");
        }
        return new TechniqueSubmission(status, Optional.empty(), summary);
    }

    public enum Status {
        ACCEPTED,
        UNKNOWN_TECHNIQUE,
        BOT_BUSY,
        RUNTIME_CAPACITY,
        /** New technique ingress is closed during lifecycle shutdown. */
        RUNTIME_CLOSED,
        /**
         * The exact body generation is quarantined because a child cleanup
         * deadline elapsed without a terminal acknowledgement.
         */
        GENERATION_QUARANTINED,
        INVALID_REQUEST
    }
}
