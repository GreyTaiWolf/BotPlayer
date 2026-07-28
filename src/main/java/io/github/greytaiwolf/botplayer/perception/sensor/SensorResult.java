package io.github.greytaiwolf.botplayer.perception.sensor;

import io.github.greytaiwolf.botplayer.perception.BlockObservation;
import io.github.greytaiwolf.botplayer.perception.EntityObservation;
import io.github.greytaiwolf.botplayer.perception.InventoryObservation;
import io.github.greytaiwolf.botplayer.perception.ObservationSnapshot;
import io.github.greytaiwolf.botplayer.perception.SelfObservation;
import io.github.greytaiwolf.botplayer.perception.SensorId;
import io.github.greytaiwolf.botplayer.perception.ThreatObservation;
import io.github.greytaiwolf.botplayer.perception.VisionObservation;
import io.github.greytaiwolf.botplayer.perception.event.PerceivedEvent;
import java.util.List;
import java.util.Objects;

/**
 * 传感器输出的封闭集合；所有分支只携带不可变 DTO，不携带 Minecraft 活动对象。
 */
public sealed interface SensorResult
        permits SensorResult.SelfState,
                SensorResult.Inventory,
                SensorResult.Vision,
                SensorResult.Entities,
                SensorResult.Threats,
                SensorResult.Blocks,
                SensorResult.Sounds,
                SensorResult.Unavailable {
    SensorId sensorId();

    default boolean truncated() {
        return false;
    }

    record SelfState(SelfObservation observation, boolean truncated)
            implements SensorResult {
        public SelfState {
            Objects.requireNonNull(observation, "observation");
        }

        public SelfState(SelfObservation observation) {
            this(observation, false);
        }

        @Override
        public SensorId sensorId() {
            return SensorId.SELF_STATE;
        }
    }

    record Inventory(InventoryObservation observation) implements SensorResult {
        public Inventory {
            Objects.requireNonNull(observation, "observation");
        }

        @Override
        public SensorId sensorId() {
            return SensorId.INVENTORY;
        }

        @Override
        public boolean truncated() {
            return observation.truncated();
        }
    }

    record Vision(VisionObservation observation, boolean truncated)
            implements SensorResult {
        public Vision {
            Objects.requireNonNull(observation, "observation");
        }

        @Override
        public SensorId sensorId() {
            return SensorId.VISION_RAY;
        }
    }

    record Entities(List<EntityObservation> observations, boolean truncated)
            implements SensorResult {
        public Entities {
            observations = copyBounded(
                    observations,
                    ObservationSnapshot.MAX_ENTITIES,
                    "observations");
        }

        @Override
        public SensorId sensorId() {
            return SensorId.LOCAL_ENTITY;
        }
    }

    record Threats(List<ThreatObservation> observations, boolean truncated)
            implements SensorResult {
        public Threats {
            observations = copyBounded(
                    observations,
                    ObservationSnapshot.MAX_THREATS,
                    "observations");
        }

        @Override
        public SensorId sensorId() {
            return SensorId.NEARBY_THREAT;
        }
    }

    record Blocks(List<BlockObservation> observations, boolean truncated)
            implements SensorResult {
        public Blocks {
            observations = copyBounded(
                    observations,
                    ObservationSnapshot.MAX_BLOCKS,
                    "observations");
        }

        @Override
        public SensorId sensorId() {
            return SensorId.LOCAL_BLOCK;
        }
    }

    record Sounds(List<PerceivedEvent> observations, boolean truncated)
            implements SensorResult {
        public Sounds {
            observations = copyBounded(
                    observations,
                    ObservationSnapshot.MAX_EVENTS,
                    "observations");
        }

        @Override
        public SensorId sensorId() {
            return SensorId.SOUND_EVENT;
        }
    }

    record Unavailable(
            SensorId sensorId, Reason reason, boolean truncated)
            implements SensorResult {
        public Unavailable {
            Objects.requireNonNull(sensorId, "sensorId");
            Objects.requireNonNull(reason, "reason");
        }
    }

    enum Reason {
        BUDGET_EXHAUSTED,
        TARGET_UNLOADED,
        SENSOR_FAILURE
    }

    private static <T> List<T> copyBounded(
            List<T> values, int maximumSize, String field) {
        Objects.requireNonNull(values, field);
        if (values.size() > maximumSize) {
            throw new IllegalArgumentException(
                    field + " exceeds maximum size " + maximumSize);
        }
        values.forEach(value ->
                Objects.requireNonNull(value, field + " entry"));
        return List.copyOf(values);
    }
}
