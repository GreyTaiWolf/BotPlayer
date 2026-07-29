package io.github.greytaiwolf.botplayer.navigation.path;

import io.github.greytaiwolf.botplayer.navigation.GridPoint;
import java.util.Objects;

public record RouteNode(
        GridPoint point,
        LocomotionMode locomotionMode,
        TraversalKind traversalKind) {
    public RouteNode {
        Objects.requireNonNull(point, "point");
        Objects.requireNonNull(locomotionMode, "locomotionMode");
        Objects.requireNonNull(traversalKind, "traversalKind");
    }
}
