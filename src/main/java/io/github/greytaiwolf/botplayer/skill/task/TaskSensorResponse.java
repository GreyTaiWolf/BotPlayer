package io.github.greytaiwolf.botplayer.skill.task;

import java.util.Objects;
import java.util.Optional;

/**
 * 查询不会因预期的过期或预算拒绝抛异常；调用方必须显式处理这些保守结果。
 */
public record TaskSensorResponse(
        Status status, Optional<TaskSensorSnapshot> snapshot) {
    public TaskSensorResponse {
        Objects.requireNonNull(status, "status");
        snapshot = Objects.requireNonNull(snapshot, "snapshot");
        if ((status == Status.SAMPLED || status == Status.CACHE_HIT)
                != snapshot.isPresent()) {
            throw new IllegalArgumentException(
                    "only successful responses may contain a snapshot");
        }
    }

    public enum Status {
        SAMPLED,
        CACHE_HIT,
        STALE_IDENTITY,
        TICK_NOT_OPEN,
        TICK_BUDGET_EXHAUSTED,
        RUN_BUDGET_EXHAUSTED,
        CACHE_CAPACITY_EXCEEDED,
        INVALID_SNAPSHOT,
        SAMPLER_FAILURE
    }
}
