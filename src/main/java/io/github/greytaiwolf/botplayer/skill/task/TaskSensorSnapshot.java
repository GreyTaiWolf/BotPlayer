package io.github.greytaiwolf.botplayer.skill.task;

import java.util.List;
import java.util.Objects;

/**
 * 单次主线程采样的不可变结果，携带查询身份、采样 Tick 和截断/不可用标记。
 */
public record TaskSensorSnapshot(
        TaskSensorQuery query,
        long sampledAtTick,
        TaskSensorAvailability availability,
        boolean truncated,
        List<TaskSensorEvidence> evidence) {
    public TaskSensorSnapshot {
        Objects.requireNonNull(query, "query");
        if (sampledAtTick < 0L) {
            throw new IllegalArgumentException(
                    "sampledAtTick must be non-negative");
        }
        Objects.requireNonNull(availability, "availability");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        if (evidence.size() > query.budget().maximumEvidence()) {
            throw new IllegalArgumentException(
                    "evidence exceeds query maximumEvidence");
        }
        evidence.forEach(value ->
                Objects.requireNonNull(value, "evidence item"));
        if (availability == TaskSensorAvailability.UNAVAILABLE
                && (!evidence.isEmpty() || truncated)) {
            throw new IllegalArgumentException(
                    "unavailable snapshots must not contain partial evidence");
        }
    }

    public boolean expiredAt(long currentTick, long effectiveMaximumAge) {
        if (currentTick < sampledAtTick || effectiveMaximumAge < 0L) {
            return true;
        }
        return currentTick - sampledAtTick > effectiveMaximumAge;
    }
}
