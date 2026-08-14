package io.github.greytaiwolf.botplayer.technique.core;

/** Stable, secret-free reason for a technique terminal outcome. */
public enum TechniqueFailureCode {
    NONE,
    ACTION_FAILED,
    ACTION_CLEANUP_UNSAFE,
    NAVIGATION_FAILED,
    TARGET_CHANGED,
    TARGET_GONE,
    TARGET_NOT_VISIBLE,
    POSE_INVALID,
    WINDOW_MISSED,
    OUT_OF_REACH,
    PLACEMENT_UNREACHABLE,
    PLACEMENT_STATE_MISMATCH,
    WORLD_CHANGED,
    MISSING_ITEM,
    PERMISSION_DENIED,
    SAFETY_PREEMPTED,
    CANCELLED,
    PREEMPTED,
    GENERATION_CHANGED,
    TIMEOUT,
    BUDGET_EXCEEDED,
    CHANNEL_CONFLICT,
    /** The lifecycle closed the technique runtime before the run could finish. */
    RUNTIME_CLOSED,
    UNSUPPORTED,
    INVALID_REQUEST,
    INTERNAL_ERROR
}
