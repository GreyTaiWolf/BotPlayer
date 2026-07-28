package io.github.greytaiwolf.botplayer.perception.sensor;

import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
import io.github.greytaiwolf.botplayer.perception.BudgetKind;
import io.github.greytaiwolf.botplayer.perception.PerceptionBudget;
import io.github.greytaiwolf.botplayer.perception.SensorId;
import io.github.greytaiwolf.botplayer.perception.SensorSchedule;
import io.github.greytaiwolf.botplayer.perception.VisionObservation;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 对 bot 当前视线执行一次有界方块与实体射线，不跨入未加载区块。
 */
public final class VisionRaySensor implements BotSensor {
    public static final double DEFAULT_DISTANCE = 16.0D;
    public static final int DEFAULT_MAX_ENTITY_READS = 64;
    public static final SensorSchedule DEFAULT_SCHEDULE =
            new SensorSchedule(1, 2, 5);

    private final double maximumDistance;
    private final int maximumEntityReads;
    private final SensorSchedule schedule;

    public VisionRaySensor() {
        this(
                DEFAULT_DISTANCE,
                DEFAULT_MAX_ENTITY_READS,
                DEFAULT_SCHEDULE);
    }

    public VisionRaySensor(
            double maximumDistance,
            int maximumEntityReads,
            SensorSchedule schedule) {
        if (!Double.isFinite(maximumDistance)
                || maximumDistance <= 0.0D
                || maximumDistance > 64.0D) {
            throw new IllegalArgumentException(
                    "maximumDistance must be between 0 and 64");
        }
        if (maximumEntityReads < 1
                || maximumEntityReads > 512) {
            throw new IllegalArgumentException(
                    "maximumEntityReads must be between 1 and 512");
        }
        this.maximumDistance = maximumDistance;
        this.maximumEntityReads = maximumEntityReads;
        this.schedule = Objects.requireNonNull(schedule, "schedule");
    }

    @Override
    public SensorId id() {
        return SensorId.VISION_RAY;
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
                BudgetKind.RAYCAST,
                1,
                BudgetKind.ENTITY_READ,
                maximumEntityReads));
    }

    @Override
    public SensorResult sample(
            SensorContext context, PerceptionBudget budget) {
        Objects.requireNonNull(context, "context").assertMainThread();
        Objects.requireNonNull(budget, "budget");
        if (!budget.tryConsume(BudgetKind.RAYCAST)) {
            return new SensorResult.Unavailable(
                    id(),
                    SensorResult.Reason.BUDGET_EXHAUSTED,
                    true);
        }

        BotServerPlayer player = context.player();
        ServerLevel level = context.level();
        Vec3 eye = player.getEyePosition();
        Vec3 requestedEnd =
                eye.add(player.getLookAngle().scale(maximumDistance));
        SensorSupport.LoadedRay loadedRay = SensorSupport.loadedRay(
                level, eye, requestedEnd);
        Vec3 loadedEnd = loadedRay.loadedEnd();
        if (eye.distanceToSqr(loadedEnd) <= 1.0E-12D
                && loadedRay.unloadedPoint().isPresent()) {
            Vec3 unknown = loadedRay.unloadedPoint().orElseThrow();
            return new SensorResult.Vision(
                    new VisionObservation(
                            VisionObservation.Kind.UNKNOWN_UNLOADED,
                            Optional.empty(),
                            Optional.empty(),
                            Optional.of(SensorSupport.point(unknown)),
                            eye.distanceTo(unknown),
                            false,
                            false),
                    false);
        }
        BlockHitResult blockHit;
        try {
            blockHit = level.clip(new ClipContext(
                    eye,
                    loadedEnd,
                    ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE,
                    player));
        } catch (RuntimeException exception) {
            // 第三方方块形状异常时不把未知射线误报为可见目标。
            return new SensorResult.Unavailable(
                    id(),
                    SensorResult.Reason.SENSOR_FAILURE,
                    true);
        }
        double nearestDistance = blockHit.getType() == HitResult.Type.BLOCK
                ? eye.distanceTo(blockHit.getLocation())
                : eye.distanceTo(loadedEnd);
        EntityScan entityScan =
                nearestEntityHit(
                        context,
                        level,
                        budget,
                        eye,
                        loadedEnd,
                        nearestDistance);
        boolean truncated = entityScan.truncated();
        if (truncated) {
            /*
             * 实体枚举不完整时，即使前缀中已有命中，也不能证明它是最近注视目标。
             */
            return new SensorResult.Unavailable(
                    id(),
                    SensorResult.Reason.SENSOR_FAILURE,
                    true);
        }
        if (entityScan.hit().isPresent()) {
            EntityHit hit = entityScan.hit().orElseThrow();
            return new SensorResult.Vision(
                    new VisionObservation(
                            VisionObservation.Kind.ENTITY,
                            Optional.of(hit.entityTypeId()),
                            Optional.of(hit.entityId()),
                            Optional.of(SensorSupport.point(hit.location())),
                            eye.distanceTo(hit.location()),
                            true,
                            true),
                    truncated);
        }
        if (blockHit.getType() == HitResult.Type.BLOCK) {
            try {
                BlockState state =
                        level.getBlockState(blockHit.getBlockPos());
                return new SensorResult.Vision(
                        new VisionObservation(
                                VisionObservation.Kind.BLOCK,
                                Optional.of(
                                        BuiltInRegistries.BLOCK
                                                .getKey(state.getBlock())
                                                .toString()),
                                Optional.empty(),
                                Optional.of(SensorSupport.point(
                                        blockHit.getLocation())),
                                eye.distanceTo(blockHit.getLocation()),
                                true,
                                true),
                        truncated);
            } catch (RuntimeException exception) {
                return new SensorResult.Unavailable(
                        id(),
                        SensorResult.Reason.SENSOR_FAILURE,
                        true);
            }
        }
        if (loadedRay.unloadedPoint().isPresent()) {
            Vec3 unknown = loadedRay.unloadedPoint().orElseThrow();
            return new SensorResult.Vision(
                    new VisionObservation(
                            VisionObservation.Kind.UNKNOWN_UNLOADED,
                            Optional.empty(),
                            Optional.empty(),
                            Optional.of(SensorSupport.point(unknown)),
                            eye.distanceTo(unknown),
                            false,
                            false),
                    truncated);
        }
        return new SensorResult.Vision(
                VisionObservation.miss(maximumDistance),
                truncated);
    }

    private EntityScan nearestEntityHit(
            SensorContext context,
            ServerLevel level,
            PerceptionBudget budget,
            Vec3 start,
            Vec3 end,
            double maximumHitDistance) {
        if (start.distanceToSqr(end) <= 1.0E-12D) {
            return new EntityScan(Optional.empty(), false);
        }
        BotServerPlayer player = context.player();
        AABB bounds = new AABB(start, end).inflate(1.0D);
        SensorSupport.EntityCandidates entityCandidates =
                SensorSupport.collectEntities(
                        level,
                        player,
                        bounds,
                        maximumEntityReads,
                        budget);

        String nearestTypeId = null;
        UUID nearestEntityId = null;
        Vec3 nearestLocation = null;
        double nearestDistance = maximumHitDistance;
        boolean truncated = entityCandidates.truncated();
        for (Entity candidate : entityCandidates.candidates()) {
            try {
                if (!candidate.isAlive()
                        || candidate.isSpectator()
                        || !candidate.isPickable()) {
                    continue;
                }
                if (!level.isLoaded(candidate.blockPosition())) {
                    truncated = true;
                    continue;
                }
                AABB pickBox = candidate
                        .getBoundingBox()
                        .inflate(candidate.getPickRadius());
                Optional<Vec3> intersection = pickBox.contains(start)
                        ? Optional.of(start)
                        : pickBox.clip(start, end);
                if (intersection.isEmpty()) {
                    continue;
                }
                Vec3 location = intersection.orElseThrow();
                double distance = start.distanceTo(location);
                if (distance < nearestDistance) {
                    String typeId =
                            SensorSupport.entityTypeId(candidate);
                    UUID entityId = candidate.getUUID();
                    nearestDistance = distance;
                    nearestTypeId = typeId;
                    nearestEntityId = entityId;
                    nearestLocation = location;
                }
            } catch (RuntimeException exception) {
                // 单个第三方实体的碰撞箱或注册信息异常不终止 Tick。
                truncated = true;
                continue;
            }
        }
        if (nearestTypeId == null
                || nearestEntityId == null
                || nearestLocation == null) {
            return new EntityScan(Optional.empty(), truncated);
        }
        return new EntityScan(
                Optional.of(new EntityHit(
                        nearestTypeId,
                        nearestEntityId,
                        nearestLocation)),
                truncated);
    }

    private record EntityHit(
            String entityTypeId,
            UUID entityId,
            Vec3 location) {}

    private record EntityScan(
            Optional<EntityHit> hit, boolean truncated) {}
}
