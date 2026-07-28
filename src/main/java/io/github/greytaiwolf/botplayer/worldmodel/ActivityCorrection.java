package io.github.greytaiwolf.botplayer.worldmodel;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 玩家纠正是追加式标注；空类型表示玩家明确否定当前推断。
 */
public record ActivityCorrection(
        UUID reporterId,
        UUID actorId,
        Optional<ActivityType> correctedType,
        long fromTick,
        long throughTick) {
    public ActivityCorrection {
        Objects.requireNonNull(reporterId, "reporterId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(correctedType, "correctedType");
        correctedType.ifPresent(type -> {
            if (type == ActivityType.UNKNOWN || type == ActivityType.IDLE) {
                throw new IllegalArgumentException(
                        "use an empty correction to reject an activity");
            }
        });
        if (fromTick < 0 || throughTick < fromTick) {
            throw new IllegalArgumentException("correction tick range is invalid");
        }
    }
}
