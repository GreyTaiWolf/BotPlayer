package io.github.greytaiwolf.botplayer.ai;

/** Safe outcomes for server-owned distributed physical-attempt offer admission. */
public enum AiPhysicalAttemptOfferStatus {
    OFFERED,
    REQUEST_ATTEMPT_IN_FLIGHT,
    ATTEMPT_ID_EXHAUSTED,
    ACTIVE_ATTEMPT_CAPACITY,
    TOMBSTONE_CAPACITY,
    REQUEST_BINDING_MISMATCH,
    ATTEMPT_DEADLINE_EXCEEDS_CLIENT_NOT_AFTER,
    CLIENT_NOT_AFTER_EXPIRED,
    BUDGET_REJECTED,
    CLOCK_ROLLBACK
}
