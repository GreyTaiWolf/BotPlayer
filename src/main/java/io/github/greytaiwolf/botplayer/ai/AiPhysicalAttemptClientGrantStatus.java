package io.github.greytaiwolf.botplayer.ai;

/** Safe outcomes for the client-local start-grant fence. */
public enum AiPhysicalAttemptClientGrantStatus {
    HANDED_OFF,
    ALREADY_HANDED_OFF,
    CLOSED,
    IDENTITY_MISMATCH,
    LOCAL_SESSION_INACTIVE,
    LOCAL_BINDING_EPOCH_MISMATCH,
    LOCAL_CLOCK_ROLLBACK,
    PHYSICAL_START_NOT_AFTER_EXPIRED,
    CLIENT_NOT_AFTER_EXPIRED
}
