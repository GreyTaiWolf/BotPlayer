package io.github.greytaiwolf.botplayer.technique.bridge;

import java.util.Objects;

/** Safe result of submitting one exact prebound Technique Action. */
public record TechniqueActionSubmission(Status status) {
    public TechniqueActionSubmission {
        status = Objects.requireNonNull(status, "status");
    }

    public static TechniqueActionSubmission enqueued() {
        return new TechniqueActionSubmission(Status.ENQUEUED);
    }

    public static TechniqueActionSubmission rejected(Status status) {
        Status required = Objects.requireNonNull(status, "status");
        if (required == Status.ENQUEUED) {
            throw new IllegalArgumentException("use enqueued() for ENQUEUED");
        }
        return new TechniqueActionSubmission(required);
    }

    /** Mirrors only safe ingress outcomes; it never exposes an Action future. */
    public enum Status {
        ENQUEUED,
        MAILBOX_FULL,
        COMPLETION_BACKPRESSURE,
        BOT_GENERATION_CLOSED,
        RUNTIME_CLOSED,
        ALREADY_CONSUMED,
        REJECTED_PREBINDING
    }
}
