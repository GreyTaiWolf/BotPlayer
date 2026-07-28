package io.github.greytaiwolf.botplayer.perception;

import io.github.greytaiwolf.botplayer.perception.event.SpatialPoint;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record VisionObservation(
        Kind kind,
        Optional<String> objectId,
        Optional<UUID> entityId,
        Optional<SpatialPoint> position,
        double distance,
        boolean loaded,
        boolean lineOfSight) {
    public enum Kind {
        MISS,
        BLOCK,
        ENTITY,
        UNKNOWN_UNLOADED,
        UNKNOWN_STALE
    }

    public VisionObservation {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(objectId, "objectId");
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(position, "position");
        if (!Double.isFinite(distance) || distance < 0.0D) {
            throw new IllegalArgumentException("distance must be finite and non-negative");
        }
        if (kind == Kind.BLOCK && objectId.isEmpty()) {
            throw new IllegalArgumentException("block vision requires objectId");
        }
        if (kind == Kind.ENTITY && entityId.isEmpty()) {
            throw new IllegalArgumentException("entity vision requires entityId");
        }
    }

    public static VisionObservation miss(double distance) {
        return new VisionObservation(
                Kind.MISS,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                distance,
                true,
                true);
    }

    public static VisionObservation stale(double distance) {
        return new VisionObservation(
                Kind.UNKNOWN_STALE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                distance,
                false,
                false);
    }
}
