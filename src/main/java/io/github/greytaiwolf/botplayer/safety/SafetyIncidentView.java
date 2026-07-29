package io.github.greytaiwolf.botplayer.safety;

import io.github.greytaiwolf.botplayer.navigation.GridPoint;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record SafetyIncidentView(
        UUID incidentId,
        UUID botId,
        long botGeneration,
        SafetyState state,
        HazardType hazardType,
        HazardSeverity severity,
        long firstObservedTick,
        long lastObservedTick,
        int interventions,
        int clearStableTicks,
        Optional<SafetyIntervention> currentIntervention,
        Optional<GridPoint> escapeTarget,
        String evidence) {
    public SafetyIncidentView {
        Objects.requireNonNull(incidentId, "incidentId");
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(hazardType, "hazardType");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(
                currentIntervention, "currentIntervention");
        Objects.requireNonNull(escapeTarget, "escapeTarget");
        Objects.requireNonNull(evidence, "evidence");
        if (botGeneration <= 0L
                || firstObservedTick < 0L
                || lastObservedTick < firstObservedTick
                || interventions < 0
                || clearStableTicks < 0) {
            throw new IllegalArgumentException(
                    "incident view contains an invalid bound");
        }
    }
}
