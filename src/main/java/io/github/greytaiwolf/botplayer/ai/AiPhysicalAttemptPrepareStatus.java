package io.github.greytaiwolf.botplayer.ai;

/** Safe outcomes for accepting a client prepare acknowledgement. */
public enum AiPhysicalAttemptPrepareStatus {
    START_GRANTED,
    NO_ACTIVE_ATTEMPT,
    ATTEMPT_TOMBSTONED,
    IDENTITY_MISMATCH,
    CLIENT_NOT_AFTER_EXPIRED,
    PHYSICAL_START_NOT_AFTER_EXPIRED,
    OFFER_EXPIRED,
    SETTLEMENT_REJECTED,
    CLOCK_ROLLBACK
}
