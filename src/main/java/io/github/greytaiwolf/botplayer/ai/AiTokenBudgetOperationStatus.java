package io.github.greytaiwolf.botplayer.ai;

/** Stable outcomes for bounded token-reservation accounting operations. */
public enum AiTokenBudgetOperationStatus {
    RESERVED,
    RELEASED,
    SETTLED,
    EXPIRED,
    NOT_FOUND,
    STALE_RESERVATION,
    ADMISSION_REJECTED,
    INVALID_ADMISSION,
    SCOPE_MISMATCH,
    INVALID_EXPIRATION,
    TOKEN_BUDGET_EXHAUSTED,
    ACTIVE_RESERVATION_LIMIT,
    RESERVATION_ID_EXHAUSTED,
    LEDGER_CLOSED,
    CLOCK_ROLLBACK
}
