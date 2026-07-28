package io.github.greytaiwolf.botplayer.perception.sensor;

import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
import io.github.greytaiwolf.botplayer.perception.BudgetKind;
import io.github.greytaiwolf.botplayer.perception.EntityObservation;
import io.github.greytaiwolf.botplayer.perception.ObservationSnapshot;
import io.github.greytaiwolf.botplayer.perception.PerceptionBudget;
import io.github.greytaiwolf.botplayer.perception.SensorId;
import io.github.greytaiwolf.botplayer.perception.SensorSchedule;
import io.github.greytaiwolf.botplayer.perception.ThreatObservation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;

/**
 * 对近距离可见敌对实体、来袭弹射物和已点燃炸药生成确定性威胁排序。
 */
public final class NearbyThreatSensor implements BotSensor {
    public static final double DEFAULT_RADIUS = 12.0D;
    public static final int DEFAULT_MAX_THREATS = 64;
    public static final int DEFAULT_MAX_ENTITY_READS = 192;
    public static final int MAX_RAW_ENTITY_SCANS = 2_048;
    public static final SensorSchedule DEFAULT_SCHEDULE =
            new SensorSchedule(3, 5, 5);

    private final double radius;
    private final int maximumThreats;
    private final int maximumEntityReads;
    private final SensorSchedule schedule;

    public NearbyThreatSensor() {
        this(
                DEFAULT_RADIUS,
                DEFAULT_MAX_THREATS,
                DEFAULT_MAX_ENTITY_READS,
                DEFAULT_SCHEDULE);
    }

    public NearbyThreatSensor(
            double radius,
            int maximumThreats,
            SensorSchedule schedule) {
        this(
                radius,
                maximumThreats,
                Math.min(512, Math.max(maximumThreats, 1) * 3),
                schedule);
    }

    public NearbyThreatSensor(
            double radius,
            int maximumThreats,
            int maximumEntityReads,
            SensorSchedule schedule) {
        if (!Double.isFinite(radius)
                || radius <= 0.0D
                || radius > 32.0D) {
            throw new IllegalArgumentException(
                    "radius must be between 0 and 32");
        }
        if (maximumThreats < 1
                || maximumThreats > ObservationSnapshot.MAX_THREATS) {
            throw new IllegalArgumentException(
                    "maximumThreats must be between 1 and "
                            + ObservationSnapshot.MAX_THREATS);
        }
        if (maximumEntityReads < maximumThreats
                || maximumEntityReads > 512) {
            throw new IllegalArgumentException(
                    "maximumEntityReads must be between "
                            + maximumThreats
                            + " and 512");
        }
        this.radius = radius;
        this.maximumThreats = maximumThreats;
        this.maximumEntityReads = maximumEntityReads;
        this.schedule = Objects.requireNonNull(schedule, "schedule");
    }

    @Override
    public SensorId id() {
        return SensorId.NEARBY_THREAT;
    }

    @Override
    public SensorSchedule schedule() {
        return schedule;
    }

    @Override
    public SensorCost estimatedCost() {
        return new SensorCost(Map.of(
                BudgetKind.ENTITY_SCAN,
                MAX_RAW_ENTITY_SCANS,
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
                SensorSupport.collectMatchingEntities(
                        level,
                        player,
                        player.getBoundingBox().inflate(radius),
                        maximumEntityReads,
                        MAX_RAW_ENTITY_SCANS,
                        NearbyThreatSensor::isPotentialThreat,
                        budget);

        List<ThreatObservation> threats = new ArrayList<>();
        boolean truncated = entityCandidates.truncated();
        for (Entity entity : entityCandidates.candidates()) {
            if (threats.size() >= maximumThreats) {
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
                ThreatKind threatKind = classify(player, entity);
                if (threatKind == null) {
                    continue;
                }
                if (!budget.tryConsume(BudgetKind.RAYCAST)) {
                    truncated = true;
                    break;
                }
                if (!SensorSupport.canSeeEntity(player, entity)) {
                    continue;
                }
                EntityObservation observation =
                        SensorSupport.entityObservation(
                                player,
                                entity,
                                threatKind.relation(),
                                true);
                threats.add(new ThreatObservation(
                        observation,
                        score(player, entity, threatKind),
                        reason(player, entity, threatKind)));
            } catch (RuntimeException exception) {
                // 单个第三方实体失效时仅标记本次威胁视图不完整。
                truncated = true;
                continue;
            }
        }
        threats.sort(
                Comparator.comparingDouble(ThreatObservation::score)
                        .reversed()
                        .thenComparingDouble(threat ->
                                threat.entity().distance())
                        .thenComparing(threat ->
                                threat.entity().entityId().toString()));
        return new SensorResult.Threats(threats, truncated);
    }

    private ThreatKind classify(
            BotServerPlayer player, Entity entity) {
        if (entity instanceof PrimedTnt) {
            return ThreatKind.EXPLOSIVE;
        }
        if (entity instanceof Projectile projectile) {
            if (projectile.getOwner() == player
                    || projectile.getDeltaMovement().lengthSqr()
                            <= 1.0E-4D) {
                return null;
            }
            Vec3 towardPlayer =
                    player.getEyePosition().subtract(projectile.position());
            return projectile.getDeltaMovement().dot(towardPlayer) > 0.0D
                    ? ThreatKind.PROJECTILE
                    : null;
        }
        return entity instanceof Enemy
                ? ThreatKind.HOSTILE
                : null;
    }

    private static boolean isPotentialThreat(Entity entity) {
        return entity instanceof Enemy
                || entity instanceof Projectile
                || entity instanceof PrimedTnt;
    }

    private float score(
            BotServerPlayer player,
            Entity entity,
            ThreatKind kind) {
        double distance =
                Math.sqrt(player.distanceToSqr(entity));
        double proximity =
                1.0D - Math.min(1.0D, distance / radius);
        double score = switch (kind) {
            case HOSTILE -> 0.35D + proximity * 0.45D;
            case PROJECTILE -> 0.55D + proximity * 0.40D;
            case EXPLOSIVE -> 0.70D + proximity * 0.30D;
        };
        if (entity instanceof Mob mob
                && mob.getTarget() == player) {
            score += 0.20D;
        }
        return (float) Math.clamp(score, 0.0D, 1.0D);
    }

    private String reason(
            BotServerPlayer player,
            Entity entity,
            ThreatKind kind) {
        if (kind == ThreatKind.HOSTILE
                && entity instanceof Mob mob
                && mob.getTarget() == player) {
            return "hostile_targeting_bot";
        }
        return switch (kind) {
            case HOSTILE -> "visible_hostile";
            case PROJECTILE -> "incoming_projectile";
            case EXPLOSIVE -> "primed_explosive";
        };
    }

    private enum ThreatKind {
        HOSTILE("hostile"),
        PROJECTILE("projectile"),
        EXPLOSIVE("explosive");

        private final String relation;

        ThreatKind(String relation) {
            this.relation = relation;
        }

        private String relation() {
            return relation;
        }
    }
}
