package io.github.greytaiwolf.botplayer.perception;

import io.github.greytaiwolf.botplayer.perception.event.SpatialPoint;
import java.util.Objects;
import java.util.UUID;

public record EntityObservation(
        UUID entityId,
        String typeId,
        String relation,
        SpatialPoint position,
        SpatialPoint velocity,
        double distance,
        boolean visible,
        boolean alive) {
    public EntityObservation {
        Objects.requireNonNull(entityId, "entityId");
        typeId = requireText(typeId, "typeId", 128);
        relation = requireText(relation, "relation", 32);
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(velocity, "velocity");
        if (!Double.isFinite(distance) || distance < 0.0D) {
            throw new IllegalArgumentException("distance must be finite and non-negative");
        }
    }

    private static String requireText(String value, String field, int maximumLength) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(
                    field + " must contain 1-" + maximumLength + " characters");
        }
        return value;
    }
}
