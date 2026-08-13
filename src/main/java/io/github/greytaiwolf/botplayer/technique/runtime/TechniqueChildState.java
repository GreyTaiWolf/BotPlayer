package io.github.greytaiwolf.botplayer.technique.runtime;

/** Lifecycle state reported by one Action/Navigation child ticket. */
public enum TechniqueChildState {
    ACTIVE,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    PREEMPTED,
    STALE;

    public boolean isTerminal() {
        return this != ACTIVE;
    }
}
