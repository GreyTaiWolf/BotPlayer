package io.github.greytaiwolf.botplayer.technique.runtime;

/** Result of attempting to attach one child acknowledgement to a live run. */
public enum TechniqueSignalStatus {
    ACCEPTED,
    RUN_NOT_FOUND,
    IDENTITY_MISMATCH,
    /** The owner thread has already advanced this run beyond the signal tick. */
    STALE_TICK,
    TICKET_NOT_FOUND,
    TICKET_ALREADY_TERMINAL
}
