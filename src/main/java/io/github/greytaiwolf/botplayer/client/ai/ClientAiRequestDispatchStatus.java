package io.github.greytaiwolf.botplayer.client.ai;

/** Safe local outcome of receiving one server-to-client AI request dispatch. */
public enum ClientAiRequestDispatchStatus {
    /** A physical-attempt offer passed local staging; only its exact prepare ACK may be sent. */
    PREPARED,
    /** The local provider call was admitted and is now cancellable. */
    STARTED,
    /** The dispatch owner is not the currently connected local player. */
    NOT_LOCAL_OWNER,
    /** The server-issued client deadline has already elapsed. */
    EXPIRED,
    /** No local credential binding exists for the exact server/owner/bot tuple. */
    BINDING_MISSING,
    /** A local binding exists but its independent agent id differs from the dispatch. */
    AGENT_MISMATCH,
    /** The same request id is active or terminally tombstoned; it never issues a second Provider call. */
    DUPLICATE_REQUEST,
    /** The bounded terminal-request tombstone ledger is full until an existing entry expires. */
    TOMBSTONE_CAPACITY,
    /** A newer request, logout, explicit cancellation, or deadline retired this session. */
    CANCELLED,
    /** The S2C payload was not the exact fixed P6-R1 review-only contract. */
    REVIEW_CONTRACT_REJECTED,
    /** Local provider construction, scheduling, or invocation could not start safely. */
    PROVIDER_UNAVAILABLE
}
