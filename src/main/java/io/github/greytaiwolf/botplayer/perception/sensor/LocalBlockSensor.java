package io.github.greytaiwolf.botplayer.perception.sensor;

import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
import io.github.greytaiwolf.botplayer.perception.BlockObservation;
import io.github.greytaiwolf.botplayer.perception.BudgetKind;
import io.github.greytaiwolf.botplayer.perception.ObservationSnapshot;
import io.github.greytaiwolf.botplayer.perception.PerceptionBudget;
import io.github.greytaiwolf.botplayer.perception.SensorId;
import io.github.greytaiwolf.botplayer.perception.SensorSchedule;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 只读取注视目标、脚下固定邻域和有界任务焦点，不做体素泛扫或容器窥视。
 */
public final class LocalBlockSensor implements BotSensor {
    public static final int DEFAULT_FOOT_RADIUS = 1;
    public static final int DEFAULT_MAX_FOCUS_POSITIONS = 32;
    public static final int DEFAULT_MAX_OBSERVATIONS = 64;
    public static final double DEFAULT_FOCUS_DISTANCE = 16.0D;
    public static final SensorSchedule DEFAULT_SCHEDULE =
            new SensorSchedule(10, 20, 0);

    private static final int MAX_BLOCK_PROPERTIES = 32;

    private final int footRadius;
    private final int maximumFocusPositions;
    private final int maximumObservations;
    private final double maximumFocusDistance;
    private final SensorSchedule schedule;

    public LocalBlockSensor() {
        this(
                DEFAULT_FOOT_RADIUS,
                DEFAULT_MAX_FOCUS_POSITIONS,
                DEFAULT_MAX_OBSERVATIONS,
                DEFAULT_FOCUS_DISTANCE,
                DEFAULT_SCHEDULE);
    }

    public LocalBlockSensor(
            int footRadius,
            int maximumFocusPositions,
            int maximumObservations,
            double maximumFocusDistance,
            SensorSchedule schedule) {
        if (footRadius < 0 || footRadius > 2) {
            throw new IllegalArgumentException(
                    "footRadius must be between 0 and 2");
        }
        if (maximumFocusPositions < 0
                || maximumFocusPositions
                        > SensorContext.MAX_FOCUS_POSITIONS) {
            throw new IllegalArgumentException(
                    "maximumFocusPositions must be between 0 and "
                            + SensorContext.MAX_FOCUS_POSITIONS);
        }
        if (maximumObservations < 1
                || maximumObservations
                        > ObservationSnapshot.MAX_BLOCKS) {
            throw new IllegalArgumentException(
                    "maximumObservations must be between 1 and "
                            + ObservationSnapshot.MAX_BLOCKS);
        }
        if (!Double.isFinite(maximumFocusDistance)
                || maximumFocusDistance <= 0.0D
                || maximumFocusDistance > 32.0D) {
            throw new IllegalArgumentException(
                    "maximumFocusDistance must be between 0 and 32");
        }
        this.footRadius = footRadius;
        this.maximumFocusPositions = maximumFocusPositions;
        this.maximumObservations = maximumObservations;
        this.maximumFocusDistance = maximumFocusDistance;
        this.schedule = Objects.requireNonNull(schedule, "schedule");
    }

    @Override
    public SensorId id() {
        return SensorId.LOCAL_BLOCK;
    }

    @Override
    public SensorSchedule schedule() {
        return schedule;
    }

    @Override
    public SensorCost estimatedCost() {
        int footCandidates =
                (2 * footRadius + 1)
                        * (2 * footRadius + 1)
                        * (2 * footRadius + 1);
        return new SensorCost(Map.of(
                BudgetKind.BLOCK_READ,
                maximumFocusPositions + footCandidates + 1,
                BudgetKind.RAYCAST,
                maximumFocusPositions + footCandidates));
    }

    @Override
    public SensorResult sample(
            SensorContext context, PerceptionBudget budget) {
        Objects.requireNonNull(context, "context").assertMainThread();
        Objects.requireNonNull(budget, "budget");
        BotServerPlayer player = context.player();
        ServerLevel level = context.level();
        long gameTick = context.gameTick();
        GazeResolution gaze = resolveGaze(
                context, level, budget);
        Map<BlockPos, Candidate> unique = new HashMap<>();
        gaze.position().ifPresent(position ->
                addCandidate(
                        unique,
                        position,
                        Source.GAZE,
                        gaze.visibilityVerified(),
                        player));
        addFootCandidates(unique, player);

        boolean truncated = gaze.truncated();
        List<BlockPos> focusPositions = context.focusPositions();
        int focusCount =
                Math.min(focusPositions.size(), maximumFocusPositions);
        if (focusCount < focusPositions.size()) {
            truncated = true;
        }
        for (int index = 0; index < focusCount; index++) {
            BlockPos focus = focusPositions.get(index);
            if (player.getEyePosition()
                            .distanceToSqr(Vec3.atCenterOf(focus))
                    > maximumFocusDistance * maximumFocusDistance) {
                truncated = true;
                continue;
            }
            addCandidate(
                    unique,
                    focus,
                    Source.FOCUS,
                    false,
                    player);
        }

        List<Candidate> candidates =
                new ArrayList<>(unique.values());
        candidates.sort(Candidate.ORDER);
        List<BlockObservation> observations = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (observations.size() >= maximumObservations) {
                truncated = true;
                break;
            }
            BlockPos position = candidate.position();
            if (!SensorSupport.isWithinBuildHeight(
                            level, position)
                    || !level.isLoaded(position)) {
                truncated = true;
                continue;
            }
            boolean lineOfSight =
                    candidate.source() != Source.FOOT;
            if (lineOfSight && !candidate.visibilityVerified()) {
                if (!budget.tryConsume(BudgetKind.RAYCAST)) {
                    truncated = true;
                    break;
                }
                boolean visible;
                try {
                    visible = SensorSupport.canSeeBlock(
                            player, position);
                } catch (RuntimeException exception) {
                    // 第三方方块形状异常时该位置保持未知。
                    truncated = true;
                    continue;
                }
                if (!visible) {
                    continue;
                }
            }
            if (!budget.tryConsume(BudgetKind.BLOCK_READ)) {
                truncated = true;
                break;
            }
            try {
                BlockView view = viewBlock(
                        level,
                        gameTick,
                        position,
                        lineOfSight);
                observations.add(view.observation());
                truncated |= view.propertiesTruncated();
            } catch (RuntimeException exception) {
                // 单个第三方方块状态或属性异常不终止服务器 Tick。
                truncated = true;
            }
        }
        return new SensorResult.Blocks(observations, truncated);
    }

    private GazeResolution resolveGaze(
            SensorContext context,
            ServerLevel level,
            PerceptionBudget budget) {
        Optional<BlockPos> supplied = context.lookedAtBlock();
        if (supplied.isPresent()) {
            BlockPos position = supplied.orElseThrow();
            boolean withinDistance =
                    context.player()
                                    .getEyePosition()
                                    .distanceToSqr(Vec3.atCenterOf(position))
                            <= maximumFocusDistance
                                    * maximumFocusDistance;
            return withinDistance
                    ? new GazeResolution(
                            supplied, false, false)
                    : new GazeResolution(
                            Optional.empty(), true, false);
        }
        if (!budget.tryConsume(BudgetKind.RAYCAST)) {
            return new GazeResolution(
                    Optional.empty(), true, false);
        }
        BotServerPlayer player = context.player();
        Vec3 eye = player.getEyePosition();
        Vec3 requestedEnd = eye.add(
                player.getLookAngle().scale(maximumFocusDistance));
        SensorSupport.LoadedRay loaded;
        BlockHitResult hit;
        try {
            loaded = SensorSupport.loadedRay(
                    level, eye, requestedEnd);
            hit = level.clip(new ClipContext(
                    eye,
                    loaded.loadedEnd(),
                    ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE,
                    player));
        } catch (RuntimeException exception) {
            return new GazeResolution(
                    Optional.empty(), true, false);
        }
        if (hit.getType() == HitResult.Type.BLOCK) {
            return new GazeResolution(
                    Optional.of(hit.getBlockPos().immutable()),
                    false,
                    true);
        }
        return new GazeResolution(
                Optional.empty(),
                loaded.unloadedPoint().isPresent(),
                false);
    }

    private void addFootCandidates(
            Map<BlockPos, Candidate> unique,
            BotServerPlayer player) {
        BlockPos origin = player.getOnPos();
        addCandidate(
                unique,
                origin,
                Source.FOOT,
                false,
                player);
        for (int deltaX = -footRadius;
                deltaX <= footRadius;
                deltaX++) {
            for (int deltaY = -footRadius;
                    deltaY <= footRadius;
                    deltaY++) {
                for (int deltaZ = -footRadius;
                        deltaZ <= footRadius;
                        deltaZ++) {
                    if (deltaX == 0
                            && deltaY == 0
                            && deltaZ == 0) {
                        continue;
                    }
                    addCandidate(
                            unique,
                            origin.offset(deltaX, deltaY, deltaZ),
                            Source.NEARBY,
                            false,
                            player);
                }
            }
        }
    }

    private void addCandidate(
            Map<BlockPos, Candidate> unique,
            BlockPos position,
            Source source,
            boolean visibilityVerified,
            BotServerPlayer player) {
        BlockPos immutable = position.immutable();
        Candidate replacement = new Candidate(
                immutable,
                source,
                visibilityVerified,
                player.getEyePosition()
                        .distanceToSqr(Vec3.atCenterOf(immutable)));
        unique.merge(
                immutable,
                replacement,
                (existing, added) -> existing.source().priority
                                <= added.source().priority
                        ? existing
                        : added);
    }

    private BlockView viewBlock(
            ServerLevel level,
            long gameTick,
            BlockPos position,
            boolean lineOfSight) {
        BlockState state = level.getBlockState(position);
        TreeMap<String, String> properties = new TreeMap<>();
        state.getValues().forEach((property, value) ->
                properties.put(property.getName(), value.toString()));
        if (state.hasBlockEntity()) {
            properties.put(
                    "botplayer.opaque_block_entity",
                    "true");
        }
        boolean propertiesTruncated =
                properties.size() > MAX_BLOCK_PROPERTIES;
        while (properties.size() > MAX_BLOCK_PROPERTIES) {
            properties.pollLastEntry();
        }
        BlockObservation observation = new BlockObservation(
                SensorSupport.dimension(level),
                position.getX(),
                position.getY(),
                position.getZ(),
                BuiltInRegistries.BLOCK
                        .getKey(state.getBlock())
                        .toString(),
                properties,
                lineOfSight,
                gameTick);
        return new BlockView(observation, propertiesTruncated);
    }

    private enum Source {
        GAZE(0),
        FOOT(1),
        NEARBY(2),
        FOCUS(3);

        private final int priority;

        Source(int priority) {
            this.priority = priority;
        }
    }

    private record Candidate(
            BlockPos position,
            Source source,
            boolean visibilityVerified,
            double distanceSquared) {
        private static final Comparator<Candidate> ORDER =
                Comparator.comparingInt(
                                (Candidate candidate) ->
                                        candidate.source().priority)
                        .thenComparingDouble(
                                Candidate::distanceSquared)
                        .thenComparing(
                                Candidate::position,
                                SensorSupport.blockOrder());
    }

    private record GazeResolution(
            Optional<BlockPos> position,
            boolean truncated,
            boolean visibilityVerified) {}

    private record BlockView(
            BlockObservation observation,
            boolean propertiesTruncated) {}
}
