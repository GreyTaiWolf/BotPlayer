package io.github.greytaiwolf.botplayer.technique.runtime;

/** Source of a technique cancellation request. */
public enum TechniqueCancelReason {
    REQUESTED,
    SAFETY_PREEMPTION,
    GENERATION_CHANGED,
    SERVER_STOP
}
