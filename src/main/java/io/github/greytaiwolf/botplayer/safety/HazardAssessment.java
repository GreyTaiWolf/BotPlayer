package io.github.greytaiwolf.botplayer.safety;

import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record HazardAssessment(
        HazardType type,
        HazardSeverity severity,
        int estimatedImpactTicks,
        boolean reversible,
        boolean alreadyDamaging,
        Optional<UUID> sourceEntityId,
        String evidence) {
    public static final Comparator<HazardAssessment> PRIORITY =
            Comparator.comparingInt(
                            (HazardAssessment value) ->
                                    value.severity().rank())
                    .reversed()
                    .thenComparingInt(
                            HazardAssessment::estimatedImpactTicks)
                    .thenComparing(
                            HazardAssessment::reversible)
                    .thenComparing(
                            HazardAssessment::alreadyDamaging,
                            Comparator.reverseOrder())
                    .thenComparingInt(value -> value.type().ordinal())
                    .thenComparing(value ->
                            value.sourceEntityId()
                                    .map(UUID::toString)
                                    .orElse(""));

    public HazardAssessment {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(severity, "severity");
        if (estimatedImpactTicks < 0
                || estimatedImpactTicks > 72_000) {
            throw new IllegalArgumentException(
                    "estimatedImpactTicks must be between 0 and 72000");
        }
        Objects.requireNonNull(sourceEntityId, "sourceEntityId");
        Objects.requireNonNull(evidence, "evidence");
        if (evidence.length() > 256) {
            throw new IllegalArgumentException(
                    "hazard evidence exceeds 256 characters");
        }
    }
}
