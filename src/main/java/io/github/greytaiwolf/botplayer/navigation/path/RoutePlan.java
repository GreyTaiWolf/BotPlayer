package io.github.greytaiwolf.botplayer.navigation.path;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record RoutePlan(
        UUID snapshotId,
        RoutePlanStatus status,
        List<RouteNode> nodes,
        long totalCost,
        int expandedNodes,
        boolean touchedUnknownBoundary) {
    public RoutePlan {
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(nodes, "nodes");
        nodes = List.copyOf(nodes);
        if (totalCost < 0L || expandedNodes < 0) {
            throw new IllegalArgumentException(
                    "totalCost and expandedNodes must not be negative");
        }
        boolean hasRoute =
                status == RoutePlanStatus.COMPLETE
                        || status == RoutePlanStatus.PARTIAL_FRONTIER;
        if (hasRoute != !nodes.isEmpty()) {
            throw new IllegalArgumentException(
                    "only complete or partial results may contain a route");
        }
    }
}
