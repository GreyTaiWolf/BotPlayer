package io.github.greytaiwolf.botplayer.perception.sensor;

import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
import io.github.greytaiwolf.botplayer.perception.BudgetKind;
import io.github.greytaiwolf.botplayer.perception.EntityObservation;
import io.github.greytaiwolf.botplayer.perception.ObservationSnapshot;
import io.github.greytaiwolf.botplayer.perception.PerceptionBudget;
import io.github.greytaiwolf.botplayer.perception.SensorId;
import io.github.greytaiwolf.botplayer.perception.SensorSchedule;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * 读取局部、已加载且有视线的实体；服务器实体索引顺序不会进入快照顺序。
 */
public final class LocalEntitySensor implements BotSensor {
    public static final double DEFAULT_RADIUS = 16.0D;
    public static final int DEFAULT_MAX_OBSERVATIONS = 128;
    public static final int DEFAULT_MAX_ENTITY_READS = 256;
    public static final SensorSchedule DEFAULT_SCHEDULE =
            new SensorSchedule(5, 10, 0);

    private final double radius;
    private final int maximumObservations;
    private final int maximumEntityReads;
    private final SensorSchedule schedule;

    public LocalEntitySensor() {
        this(
                DEFAULT_RADIUS,
                DEFAULT_MAX_OBSERVATIONS,
                DEFAULT_MAX_ENTITY_READS,
                DEFAULT_SCHEDULE);
    }

    public LocalEntitySensor(
            double radius,
            int maximumObservations,
            SensorSchedule schedule) {
        this(
                radius,
                maximumObservations,
                Math.min(512, Math.max(maximumObservations, 1) * 2),
                schedule);
    }

    public LocalEntitySensor(
            double radius,
            int maximumObservations,
            int maximumEntityReads,
            SensorSchedule schedule) {
        if (!Double.isFinite(radius)
                || radius <= 0.0D
                || radius > 64.0D) {
            throw new IllegalArgumentException(
                    "radius must be between 0 and 64");
        }
        if (maximumObservations < 1
                || maximumObservations
                        > ObservationSnapshot.MAX_ENTITIES) {
            throw new IllegalArgumentException(
                    "maximumObservations must be between 1 and "
                            + ObservationSnapshot.MAX_ENTITIES);
        }
        if (maximumEntityReads < maximumObservations
                || maximumEntityReads > 512) {
            throw new IllegalArgumentException(
                    "maximumEntityReads must be between "
                            + maximumObservations
                            + " and 512");
        }
        this.radius = radius;
        this.maximumObservations = maximumObservations;
        this.maximumEntityReads = maximumEntityReads;
        this.schedule = Objects.requireNonNull(schedule, "schedule");
    }

    @Override
    public SensorId id() {
        return SensorId.LOCAL_ENTITY;
    }

    @Override
    public SensorSchedule schedule() {
        return schedule;
    }

    @Override
    public SensorCost estimatedCost() {
        return new SensorCost(Map.of(
                BudgetKind.ENTITY_SCAN,
                maximumEntityReads,
                BudgetKind.ENTITY_READ,
                maximumEntityReads,
                BudgetKind.RAYCAST,
                maximumEntityReads));
    }

    @Override
    public SensorResult sample(
            SensorContext context, PerceptionBudget budget) {
        Objects.requireNonNull(context, "context").assertMainThread();
        Objects.requireNonNull(budget, "budget");
        BotServerPlayer player = context.player();
        ServerLevel level = context.level();
        SensorSupport.EntityCandidates entityCandidates =
                SensorSupport.collectEntities(
                        level,
                        player,
                        player.getBoundingBox().inflate(radius),
                        maximumEntityReads,
                        budget);

        List<EntityObservation> observations = new ArrayList<>();
        boolean truncated = entityCandidates.truncated();
        for (Entity entity : entityCandidates.candidates()) {
            if (observations.size() >= maximumObservations) {
                truncated = true;
                break;
            }
            try {
                if (!entity.isAlive() || entity.isSpectator()) {
                    continue;
                }
                if (!level.isLoaded(entity.blockPosition())) {
                    truncated = true;
                    continue;
                }
                if (!budget.tryConsume(BudgetKind.RAYCAST)) {
                    truncated = true;
                    break;
                }
                if (!SensorSupport.canSeeEntity(player, entity)) {
                    continue;
                }
                observations.add(SensorSupport.entityObservation(
                        player,
                        entity,
                        SensorSupport.relation(player, entity),
                        true));
            } catch (RuntimeException exception) {
                // 单个第三方实体失效时丢弃该实体，不污染其余观察。
                truncated = true;
                continue;
            }
        }
        observations.sort(
                java.util.Comparator
                        .comparingDouble(EntityObservation::distance)
                        .thenComparing(observation ->
                                observation.entityId().toString()));
        return new SensorResult.Entities(observations, truncated);
    }
}
