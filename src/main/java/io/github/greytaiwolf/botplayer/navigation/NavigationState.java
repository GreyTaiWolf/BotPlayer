package io.github.greytaiwolf.botplayer.navigation;

public enum NavigationState {
    CREATED,
    SNAPSHOTTING,
    PLANNING,
    FOLLOWING,
    INTERACTING,
    REPLANNING,
    RECOVERING,
    SUSPENDED_BY_SAFETY,
    VERIFYING,
    SUCCEEDED,
    CANCELLED,
    FAILED,
    STALE;

    public boolean isTerminal() {
        return this == SUCCEEDED
                || this == CANCELLED
                || this == FAILED
                || this == STALE;
    }
}
