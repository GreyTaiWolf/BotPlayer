package io.github.greytaiwolf.botplayer.navigation;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record NavigationSessionView(
        UUID navigationId,
        UUID botId,
        long botGeneration,
        NavigationState state,
        NavigationGoal goal,
        Optional<GridPoint> nextWaypoint,
        int routeIndex,
        int routeSize,
        int segmentsCompleted,
        int replans,
        int recoveryAttempts,
        int blocksBroken,
        int blocksPlaced,
        Optional<NavigationFailure> terminalFailure,
        String safeSummary) {
    public NavigationSessionView {
        Objects.requireNonNull(navigationId, "navigationId");
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(nextWaypoint, "nextWaypoint");
        Objects.requireNonNull(terminalFailure, "terminalFailure");
        Objects.requireNonNull(safeSummary, "safeSummary");
        if (botGeneration <= 0L
                || routeIndex < 0
                || routeSize < 0
                || routeIndex > routeSize
                || segmentsCompleted < 0
                || replans < 0
                || recoveryAttempts < 0
                || blocksBroken < 0
                || blocksPlaced < 0) {
            throw new IllegalArgumentException(
                    "navigation session view contains an invalid bound");
        }
    }
}
