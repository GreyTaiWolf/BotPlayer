package io.github.greytaiwolf.botplayer.ai;

/** Safe outcomes for exact server-side attempt closure. */
public enum AiPhysicalAttemptCloseStatus {
    CLOSED_UNSTARTED,
    CLOSED_COMMITTED,
    NO_ACTIVE_ATTEMPT,
    ATTEMPT_TOMBSTONED,
    IDENTITY_MISMATCH
}
