package io.github.greytaiwolf.botplayer.perception;

import java.util.Objects;

public record ThreatObservation(
        EntityObservation entity,
        float score,
        String reason) {
    public ThreatObservation {
        Objects.requireNonNull(entity, "entity");
        if (!Float.isFinite(score) || score < 0.0F || score > 1.0F) {
            throw new IllegalArgumentException("score must be between 0 and 1");
        }
        Objects.requireNonNull(reason, "reason");
        if (reason.isBlank() || reason.length() > 128) {
            throw new IllegalArgumentException("reason must contain 1-128 characters");
        }
    }
}
