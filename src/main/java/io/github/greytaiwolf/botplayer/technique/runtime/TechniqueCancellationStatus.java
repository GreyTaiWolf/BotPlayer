package io.github.greytaiwolf.botplayer.technique.runtime;

/** Result of requesting cancellation/preemption for a live technique. */
public enum TechniqueCancellationStatus {
    CANCELLING,
    PREEMPTING,
    ALREADY_TERMINAL,
    RUN_NOT_FOUND,
    IDENTITY_MISMATCH,
    /** The owner thread has already advanced this run beyond the request tick. */
    STALE_TICK
}
