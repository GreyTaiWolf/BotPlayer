package io.github.greytaiwolf.botplayer.technique.runtime;

import java.util.Objects;

/** Narrow port from the pure runtime to an Action/Navigation implementation. */
public interface TechniqueChildDispatcher {
    Submission submit(TechniqueChildTicket ticket);

    void cancel(TechniqueChildTicket ticket, TechniqueCancelReason reason);

    record Submission(Status status, String safeSummary) {
        public Submission {
            Objects.requireNonNull(status, "status");
            safeSummary = TechniqueText.summary(safeSummary);
        }

        public static Submission accepted(String summary) {
            return new Submission(Status.ACCEPTED, summary);
        }

        public static Submission rejected(Status status, String summary) {
            if (status == Status.ACCEPTED) {
                throw new IllegalArgumentException(
                        "rejected submission cannot use ACCEPTED");
            }
            return new Submission(status, summary);
        }
    }

    enum Status {
        ACCEPTED,
        CHANNEL_BUSY,
        CAPACITY_EXCEEDED,
        REJECTED
    }
}
