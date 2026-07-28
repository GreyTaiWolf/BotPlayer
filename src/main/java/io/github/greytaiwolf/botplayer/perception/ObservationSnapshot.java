package io.github.greytaiwolf.botplayer.perception;

import io.github.greytaiwolf.botplayer.perception.event.PerceivedEvent;
import io.github.greytaiwolf.botplayer.worldmodel.ActivityHypothesis;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 可以安全交给回放、规划器和未来 AI 的不可变 P3 观察快照。
 */
public record ObservationSnapshot(
        UUID streamId,
        long snapshotId,
        long perceivedWatermark,
        UUID botId,
        long botGeneration,
        String dimension,
        long gameTick,
        SelfObservation self,
        InventoryObservation inventory,
        VisionObservation vision,
        List<EntityObservation> entities,
        List<ThreatObservation> threats,
        List<BlockObservation> blocks,
        List<PerceivedEvent> recentSounds,
        List<PerceivedEvent> recentEvents,
        List<ActivityHypothesis> activities,
        PerceptionLimits limits) {
    public static final int MAX_ENTITIES = 512;
    public static final int MAX_THREATS = 128;
    public static final int MAX_BLOCKS = 1_024;
    public static final int MAX_EVENTS = 512;
    public static final int MAX_ACTIVITIES = 64;

    public ObservationSnapshot {
        Objects.requireNonNull(streamId, "streamId");
        if (snapshotId <= 0
                || perceivedWatermark < 0) {
            throw new IllegalArgumentException("snapshot sequence values are invalid");
        }
        Objects.requireNonNull(botId, "botId");
        if (botGeneration <= 0 || gameTick < 0) {
            throw new IllegalArgumentException("snapshot generation/tick is invalid");
        }
        Objects.requireNonNull(dimension, "dimension");
        if (dimension.isBlank() || dimension.length() > 128) {
            throw new IllegalArgumentException("dimension must contain 1-128 characters");
        }
        Objects.requireNonNull(self, "self");
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(vision, "vision");
        entities = copyBounded(entities, "entities", MAX_ENTITIES);
        threats = copyBounded(threats, "threats", MAX_THREATS);
        blocks = copyBounded(blocks, "blocks", MAX_BLOCKS);
        recentSounds = copyBounded(recentSounds, "recentSounds", MAX_EVENTS);
        recentEvents = copyBounded(recentEvents, "recentEvents", MAX_EVENTS);
        activities = copyBounded(activities, "activities", MAX_ACTIVITIES);
        Objects.requireNonNull(limits, "limits");
    }

    private static <T> List<T> copyBounded(
            List<T> values, String field, int maximumSize) {
        Objects.requireNonNull(values, field);
        if (values.size() > maximumSize) {
            throw new IllegalArgumentException(
                    field + " exceeds maximum size " + maximumSize);
        }
        values.forEach(value -> Objects.requireNonNull(value, field + " entry"));
        return List.copyOf(values);
    }
}
