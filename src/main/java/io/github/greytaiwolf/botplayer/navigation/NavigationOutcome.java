package io.github.greytaiwolf.botplayer.navigation;

import java.util.Objects;
import java.util.UUID;

public record NavigationOutcome(
        UUID navigationId,
        NavigationState state,
        NavigationFailure failure,
        GridPoint finalPosition,
        long startedTick,
        long finishedTick,
        int segmentsCompleted,
        int replans,
        int recoveryAttempts,
        long expandedNodes,
        int blocksBroken,
        int blocksPlaced,
        String safeSummary) {
    public NavigationOutcome {
        Objects.requireNonNull(navigationId, "navigationId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(failure, "failure");
        Objects.requireNonNull(finalPosition, "finalPosition");
        if (!state.isTerminal()) {
            throw new IllegalArgumentException(
                    "navigation outcome must be terminal");
        }
        if ((state == NavigationState.SUCCEEDED)
                != (failure == NavigationFailure.NONE)) {
            throw new IllegalArgumentException(
                    "only successful navigation may use failure NONE");
        }
        if (startedTick < 0L || finishedTick < startedTick) {
            throw new IllegalArgumentException("invalid outcome tick range");
        }
        if (segmentsCompleted < 0
                || replans < 0
                || recoveryAttempts < 0
                || expandedNodes < 0L
                || blocksBroken < 0
                || blocksPlaced < 0) {
            throw new IllegalArgumentException(
                    "outcome counters must not be negative");
        }
        Objects.requireNonNull(safeSummary, "safeSummary");
        if (safeSummary.length() > 256
                || !safeSummary.equals(safeSummary.strip())
                || safeSummary.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    "safeSummary must be a trimmed 0-256 character string");
        }
    }
}
