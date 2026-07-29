package io.github.greytaiwolf.botplayer.safety;

import io.github.greytaiwolf.botplayer.navigation.GridPoint;
import java.util.Objects;
import java.util.UUID;

public record ThreatSummary(
        UUID entityId,
        Kind kind,
        GridPoint position,
        double distance,
        double approachScore,
        boolean targetingBot) {
    public ThreatSummary {
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(position, "position");
        if (!Double.isFinite(distance)
                || distance < 0.0D
                || !Double.isFinite(approachScore)
                || approachScore < -1.0D
                || approachScore > 1.0D) {
            throw new IllegalArgumentException(
                    "threat distance or approach score is invalid");
        }
    }

    public enum Kind {
        HOSTILE,
        PROJECTILE,
        EXPLOSIVE
    }
}
