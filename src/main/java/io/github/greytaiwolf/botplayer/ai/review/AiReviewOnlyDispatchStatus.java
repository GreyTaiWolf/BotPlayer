package io.github.greytaiwolf.botplayer.ai.review;

/** Safe, prompt-free outcome of an owner manual P6-R1 review request. */
public enum AiReviewOnlyDispatchStatus {
    DISPATCHED,
    BOT_NOT_ACTIVE,
    NOT_OWNER,
    AGENT_NOT_BOUND,
    SNAPSHOT_UNAVAILABLE,
    SNAPSHOT_NOT_CURRENT,
    OWNER_OFFLINE,
    INTERNAL_ERROR
}
