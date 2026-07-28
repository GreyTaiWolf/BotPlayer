package io.github.greytaiwolf.botplayer.perception;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record PerceptionLimits(
        PerceptionPressure pressure,
        PerceptionBudgetReport budget,
        Set<SensorId> sampledSensors,
        Set<SensorId> truncatedSensors,
        Map<SensorId, Long> lastSuccessfulSampleTicks,
        boolean forcedChunkLoads) {
    public PerceptionLimits {
        Objects.requireNonNull(pressure, "pressure");
        Objects.requireNonNull(budget, "budget");
        sampledSensors = Set.copyOf(
                Objects.requireNonNull(sampledSensors, "sampledSensors"));
        truncatedSensors = Set.copyOf(
                Objects.requireNonNull(truncatedSensors, "truncatedSensors"));
        lastSuccessfulSampleTicks = Map.copyOf(Objects.requireNonNull(
                lastSuccessfulSampleTicks,
                "lastSuccessfulSampleTicks"));
        lastSuccessfulSampleTicks.forEach((sensor, tick) -> {
            Objects.requireNonNull(sensor, "freshness sensor");
            if (tick == null || tick < 0L) {
                throw new IllegalArgumentException(
                        "successful sample ticks must not be negative");
            }
        });
        if (!sampledSensors.containsAll(truncatedSensors)) {
            throw new IllegalArgumentException(
                    "truncated sensors must also be sampled");
        }
    }
}
