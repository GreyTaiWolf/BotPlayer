package io.github.greytaiwolf.botplayer.navigation.path;

public enum RoutePlanStatus {
    COMPLETE,
    PARTIAL_FRONTIER,
    NO_PATH,
    BUDGET_EXHAUSTED,
    CANCELLED,
    STALE_SNAPSHOT,
    INVALID_SNAPSHOT
}
