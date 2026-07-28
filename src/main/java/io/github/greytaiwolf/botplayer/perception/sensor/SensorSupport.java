package io.github.greytaiwolf.botplayer.perception.sensor;

import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
import io.github.greytaiwolf.botplayer.perception.BudgetKind;
import io.github.greytaiwolf.botplayer.perception.EntityObservation;
import io.github.greytaiwolf.botplayer.perception.PerceptionBudget;
import io.github.greytaiwolf.botplayer.perception.event.SpatialPoint;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * P3 传感器共享的有界读取工具。公开的 loaded-ray 探针用于 GameTest
 * 直接验证未加载区块边界，其余方法仍保持包内可见。
 */
public final class SensorSupport {
    private static final double MAX_LOADED_RAY_DISTANCE = 64.0D;
    private static final int MAX_RAY_LOAD_CHECKS = 64;
    private static final double MINIMUM_FOV_DOT = 0.35D;
    private static final double CLOSE_RANGE_SQUARED = 4.0D;

    private SensorSupport() {}

    static SpatialPoint point(Vec3 vector) {
        return new SpatialPoint(vector.x, vector.y, vector.z);
    }

    static String dimension(ServerLevel level) {
        return level.dimension().location().toString();
    }

    static String entityTypeId(Entity entity) {
        return BuiltInRegistries.ENTITY_TYPE
                .getKey(entity.getType())
                .toString();
    }

    static EntityObservation entityObservation(
            BotServerPlayer player,
            Entity entity,
            String relation,
            boolean visible) {
        Vec3 position = entity.position();
        return new EntityObservation(
                entity.getUUID(),
                entityTypeId(entity),
                relation,
                point(position),
                point(entity.getDeltaMovement()),
                Math.sqrt(player.distanceToSqr(entity)),
                visible,
                entity.isAlive());
    }

    static String relation(BotServerPlayer player, Entity entity) {
        if (entity instanceof Player) {
            return "player";
        }
        if (entity instanceof Enemy) {
            return "hostile";
        }
        if (entity instanceof Projectile projectile) {
            return projectile.getOwner() == player
                    ? "owned_projectile"
                    : "projectile";
        }
        if (entity instanceof ItemEntity) {
            return "item";
        }
        if (entity instanceof ExperienceOrb) {
            return "experience";
        }
        return "neutral";
    }

    static EntityCandidates collectEntities(
            ServerLevel level,
            BotServerPlayer player,
            AABB bounds,
            int maximumEntityReads,
            PerceptionBudget budget) {
        return collectMatchingEntities(
                level,
                player,
                bounds,
                maximumEntityReads,
                maximumEntityReads,
                entity -> true,
                budget);
    }

    static EntityCandidates collectMatchingEntities(
            ServerLevel level,
            BotServerPlayer player,
            AABB bounds,
            int maximumEntityReads,
            int maximumRawReads,
            Predicate<Entity> selector,
            PerceptionBudget budget) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(selector, "selector");
        Objects.requireNonNull(budget, "budget");
        if (maximumEntityReads < 1) {
            throw new IllegalArgumentException(
                    "maximumEntityReads must be positive");
        }
        if (maximumRawReads < maximumEntityReads
                || maximumRawReads > 2_048) {
            throw new IllegalArgumentException(
                    "maximumRawReads must be between maximumEntityReads and 2048");
        }

        List<Entity> candidates =
                new ArrayList<>(Math.min(maximumEntityReads, 64));
        int[] rawReads = {0};
        int[] matchedReads = {0};
        boolean[] truncated = {false};
        try {
            level.getEntities().get(
                    EntityTypeTest.forClass(Entity.class),
                    bounds,
                    entity -> {
                        if (rawReads[0] >= maximumRawReads) {
                            truncated[0] = true;
                            return AbortableIterationConsumer
                                    .Continuation.ABORT;
                        }
                        if (!budget.tryConsume(
                                BudgetKind.ENTITY_SCAN)) {
                            truncated[0] = true;
                            return AbortableIterationConsumer
                                    .Continuation.ABORT;
                        }
                        rawReads[0]++;
                        if (entity == null) {
                            truncated[0] = true;
                            return AbortableIterationConsumer
                                    .Continuation.CONTINUE;
                        }
                        boolean selected;
                        try {
                            selected = entity != player
                                    && selector.test(entity);
                        } catch (RuntimeException exception) {
                            truncated[0] = true;
                            return AbortableIterationConsumer
                                    .Continuation.CONTINUE;
                        }
                        if (!selected) {
                            return AbortableIterationConsumer
                                    .Continuation.CONTINUE;
                        }
                        if (matchedReads[0] >= maximumEntityReads
                                || !budget.tryConsume(
                                        BudgetKind.ENTITY_READ)) {
                            truncated[0] = true;
                            return AbortableIterationConsumer
                                    .Continuation.ABORT;
                        }
                        matchedReads[0]++;
                        candidates.add(entity);
                        return AbortableIterationConsumer
                                .Continuation.CONTINUE;
                    });
        } catch (RuntimeException exception) {
            // 第三方实体索引或回调异常只使本次观察不完整。
            truncated[0] = true;
        }
        return new EntityCandidates(candidates, truncated[0]);
    }

    static Comparator<BlockPos> blockOrder() {
        return Comparator.comparingInt(BlockPos::getX)
                .thenComparingInt(BlockPos::getY)
                .thenComparingInt(BlockPos::getZ);
    }

    static boolean isWithinBuildHeight(
            ServerLevel level, BlockPos position) {
        return position.getY() >= level.getMinBuildHeight()
                && position.getY() < level.getMaxBuildHeight();
    }

    public static LoadedRay loadedRay(
            ServerLevel level, Vec3 start, Vec3 requestedEnd) {
        Vec3 delta = requestedEnd.subtract(start);
        double distance = delta.length();
        boolean exceedsSafetyLimit =
                distance > MAX_LOADED_RAY_DISTANCE;
        Vec3 checkedEnd = exceedsSafetyLimit
                ? start.add(delta.scale(
                        MAX_LOADED_RAY_DISTANCE / distance))
                : requestedEnd;
        Vec3 checkedDelta = checkedEnd.subtract(start);
        BlockPos startPosition = BlockPos.containing(start);
        BlockPos endPosition = BlockPos.containing(checkedEnd);
        int chunkX = startPosition.getX() >> 4;
        int chunkZ = startPosition.getZ() >> 4;
        int endChunkX = endPosition.getX() >> 4;
        int endChunkZ = endPosition.getZ() >> 4;
        double entryProgress = 0.0D;
        Vec3 lastLoaded = start;
        // 沿射线逐个检查相交区块；每个区块至多一次，且绝不请求区块对象。
        for (int checks = 0;
                checks < MAX_RAY_LOAD_CHECKS;
                checks++) {
            Vec3 entryPoint =
                    start.add(checkedDelta.scale(entryProgress));
            BlockPos probe = new BlockPos(
                    chunkX << 4,
                    BlockPos.containing(entryPoint).getY(),
                    chunkZ << 4);
            if (!isLoaded(level, probe)) {
                double safeProgress = entryProgress > 0.0D
                        ? Math.nextDown(entryProgress)
                        : 0.0D;
                return new LoadedRay(
                        start.add(
                                checkedDelta.scale(safeProgress)),
                        Optional.of(entryPoint),
                        distance);
            }
            if (chunkX == endChunkX
                    && chunkZ == endChunkZ) {
                lastLoaded = checkedEnd;
                break;
            }
            double nextX = nextChunkBoundaryProgress(
                    start.x, checkedDelta.x, chunkX);
            double nextZ = nextChunkBoundaryProgress(
                    start.z, checkedDelta.z, chunkZ);
            double nextProgress =
                    Math.clamp(Math.min(nextX, nextZ), 0.0D, 1.0D);
            lastLoaded = start.add(
                    checkedDelta.scale(nextProgress));
            entryProgress = nextProgress;
            if (Math.abs(nextX - nextZ) <= 1.0E-12D) {
                chunkX += checkedDelta.x > 0.0D ? 1 : -1;
                chunkZ += checkedDelta.z > 0.0D ? 1 : -1;
            } else if (nextX < nextZ) {
                chunkX += checkedDelta.x > 0.0D ? 1 : -1;
            } else {
                chunkZ += checkedDelta.z > 0.0D ? 1 : -1;
            }
            if (checks == MAX_RAY_LOAD_CHECKS - 1) {
                return new LoadedRay(
                        lastLoaded,
                        Optional.of(lastLoaded),
                        distance);
            }
        }
        if (exceedsSafetyLimit) {
            return new LoadedRay(
                    checkedEnd,
                    Optional.of(checkedEnd),
                    distance);
        }
        return new LoadedRay(requestedEnd, Optional.empty(), distance);
    }

    static boolean canSeeEntity(
            BotServerPlayer player, Entity entity) {
        Vec3 eye = player.getEyePosition();
        Vec3 targetEye = entity.getEyePosition();
        Vec3 offset = targetEye.subtract(eye);
        if (offset.lengthSqr() > CLOSE_RANGE_SQUARED
                && player.getLookAngle().dot(offset.normalize())
                        < MINIMUM_FOV_DOT) {
            return false;
        }
        LoadedRay loaded = loadedRay(
                player.serverLevel(),
                eye,
                targetEye);
        return loaded.unloadedPoint().isEmpty()
                && player.hasLineOfSight(entity);
    }

    static boolean canSeeBlock(
            BotServerPlayer player, BlockPos target) {
        ServerLevel level = player.serverLevel();
        Vec3 eye = player.getEyePosition();
        Vec3 targetCenter = Vec3.atCenterOf(target);
        LoadedRay loaded = loadedRay(level, eye, targetCenter);
        if (loaded.unloadedPoint().isPresent()) {
            return false;
        }
        HitResult hit = level.clip(new ClipContext(
                eye,
                targetCenter,
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                player));
        return hit.getType() == HitResult.Type.MISS
                || hit instanceof BlockHitResult blockHit
                        && blockHit.getBlockPos().equals(target);
    }

    static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable", exception);
        }
    }

    static void updateString(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        updateInt(digest, bytes.length);
        digest.update(bytes);
    }

    static void updateInt(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    static String lowerCaseName(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    private static boolean isLoaded(
            ServerLevel level, BlockPos position) {
        return level.isLoaded(position);
    }

    private static double nextChunkBoundaryProgress(
            double start,
            double delta,
            int chunkCoordinate) {
        if (delta == 0.0D) {
            return Double.POSITIVE_INFINITY;
        }
        double boundary = delta > 0.0D
                ? (double) (chunkCoordinate + 1) * 16.0D
                : (double) chunkCoordinate * 16.0D;
        return (boundary - start) / delta;
    }

    record EntityCandidates(
            List<Entity> candidates, boolean truncated) {
        EntityCandidates {
            candidates = List.copyOf(
                    Objects.requireNonNull(candidates, "candidates"));
        }
    }

    public record LoadedRay(
            Vec3 loadedEnd,
            Optional<Vec3> unloadedPoint,
            double requestedDistance) {
        public LoadedRay {
            Objects.requireNonNull(loadedEnd, "loadedEnd");
            Objects.requireNonNull(unloadedPoint, "unloadedPoint");
            if (!Double.isFinite(requestedDistance)
                    || requestedDistance < 0.0D) {
                throw new IllegalArgumentException(
                        "requestedDistance must be finite and non-negative");
            }
        }
    }
}
