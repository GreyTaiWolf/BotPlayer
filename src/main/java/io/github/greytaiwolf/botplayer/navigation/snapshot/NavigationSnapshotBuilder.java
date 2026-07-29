package io.github.greytaiwolf.botplayer.navigation.snapshot;

import io.github.greytaiwolf.botplayer.navigation.GridPoint;
import io.github.greytaiwolf.botplayer.navigation.NavigationGoal;
import io.github.greytaiwolf.botplayer.navigation.NavigationSettings;
import io.github.greytaiwolf.botplayer.navigation.path.TraversalCell;
import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 在服务端主线程按固定单元预算采样运动原语。
 *
 * <p>未知或未加载位置保持 UNKNOWN；该类不会请求区块，也不会把活动世界对象写入快照。
 */
public final class NavigationSnapshotBuilder {
    private final NavigationSettings settings;

    public NavigationSnapshotBuilder(NavigationSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public SnapshotBuildCursor begin(
            BotServerPlayer player,
            long botGeneration,
            NavigationGoal goal,
            long currentTick) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(goal, "goal");
        ServerLevel level = player.serverLevel();
        String dimension = level.dimension().location().toString();
        if (!dimension.equals(goal.dimension())) {
            throw new IllegalArgumentException(
                    "goal dimension does not match the active bot");
        }

        GridPoint start = GridPoint.from(player.blockPosition());
        GridPoint frontier = localFrontier(start, goal.center());
        int margin = Math.max(
                4, settings.horizontalRadius() / 3);
        int minimumX = Math.min(start.x(), frontier.x()) - margin;
        int maximumX = Math.max(start.x(), frontier.x()) + margin;
        int minimumZ = Math.min(start.z(), frontier.z()) - margin;
        int maximumZ = Math.max(start.z(), frontier.z()) + margin;
        int minimumY = Math.max(
                level.getMinBuildHeight(),
                Math.min(start.y(), frontier.y())
                        - settings.verticalRadius());
        int maximumY = Math.min(
                level.getMaxBuildHeight() - 2,
                Math.max(start.y(), frontier.y())
                        + settings.verticalRadius());

        int sizeX = maximumX - minimumX + 1;
        int sizeY = maximumY - minimumY + 1;
        int sizeZ = maximumZ - minimumZ + 1;
        long count = (long) sizeX * sizeY * sizeZ;
        if (count > 65_536L) {
            int half = settings.horizontalRadius();
            minimumX = frontier.x() - half;
            maximumX = frontier.x() + half;
            minimumZ = frontier.z() - half;
            maximumZ = frontier.z() + half;
            sizeX = maximumX - minimumX + 1;
            sizeZ = maximumZ - minimumZ + 1;
        }
        return new SnapshotBuildCursor(
                UUID.randomUUID(),
                player.getUUID(),
                botGeneration,
                dimension,
                currentTick,
                new GridPoint(minimumX, minimumY, minimumZ),
                sizeX,
                sizeY,
                sizeZ);
    }

    public SnapshotBuildProgress advance(
            SnapshotBuildCursor cursor,
            BotServerPlayer player,
            long expectedGeneration,
            long currentTick,
            int maximumCells,
            long changeSequence) {
        Objects.requireNonNull(cursor, "cursor");
        Objects.requireNonNull(player, "player");
        if (maximumCells < 1) {
            throw new IllegalArgumentException("maximumCells must be positive");
        }
        if (cursor.isCancelled()) {
            return terminal(
                    SnapshotBuildProgress.Status.CANCELLED,
                    cursor,
                    "快照构建已取消");
        }
        String activeDimension =
                player.serverLevel().dimension().location().toString();
        if (!cursor.botId().equals(player.getUUID())
                || cursor.botGeneration() != expectedGeneration
                || !cursor.dimension().equals(activeDimension)) {
            cursor.cancel();
            return terminal(
                    SnapshotBuildProgress.Status.STALE,
                    cursor,
                    "bot generation 或维度已经变化");
        }
        if (currentTick - cursor.startedTick()
                > settings.maximumSnapshotTicks()) {
            cursor.cancel();
            return terminal(
                    SnapshotBuildProgress.Status.TIMED_OUT,
                    cursor,
                    "快照构建超过 Tick 上限");
        }

        int before = cursor.sampledCells();
        int allowed = Math.min(maximumCells, settings.snapshotCellsPerBotTick());
        ServerLevel level = player.serverLevel();
        while (!cursor.isComplete()
                && cursor.sampledCells() - before < allowed) {
            cursor.accept(sample(level, cursor.nextPoint()));
        }
        int sampled = cursor.sampledCells() - before;
        if (!cursor.isComplete()) {
            return new SnapshotBuildProgress(
                    SnapshotBuildProgress.Status.BUILDING,
                    sampled,
                    cursor.sampledCells(),
                    Optional.empty(),
                    "快照正在按预算构建");
        }
        return new SnapshotBuildProgress(
                SnapshotBuildProgress.Status.COMPLETE,
                sampled,
                cursor.sampledCells(),
                Optional.of(cursor.finish(currentTick, changeSequence)),
                "快照构建完成");
    }

    private GridPoint localFrontier(
            GridPoint start, GridPoint target) {
        long dx = (long) target.x() - start.x();
        long dz = (long) target.z() - start.z();
        double length = Math.hypot(dx, dz);
        if (length <= settings.horizontalRadius()) {
            return target;
        }
        double scale = settings.horizontalRadius() / length;
        return new GridPoint(
                start.x() + (int) Math.round(dx * scale),
                clamp(
                        target.y(),
                        start.y() - settings.verticalRadius(),
                        start.y() + settings.verticalRadius()),
                start.z() + (int) Math.round(dz * scale));
    }

    private static TraversalCell sample(
            ServerLevel level, GridPoint point) {
        BlockPos feet = point.toBlockPos();
        BlockPos head = feet.above();
        BlockPos support = feet.below();
        if (!level.isLoaded(feet)
                || !level.isLoaded(head)
                || !level.isLoaded(support)) {
            return TraversalCell.UNKNOWN;
        }
        try {
            BlockState feetState = level.getBlockState(feet);
            BlockState headState = level.getBlockState(head);
            BlockState supportState = level.getBlockState(support);
            boolean water = feetState.getFluidState().is(FluidTags.WATER)
                    || headState.getFluidState().is(FluidTags.WATER);
            boolean lava = feetState.getFluidState().is(FluidTags.LAVA)
                    || headState.getFluidState().is(FluidTags.LAVA);
            boolean climbable = feetState.is(BlockTags.CLIMBABLE)
                    || headState.is(BlockTags.CLIMBABLE);
            boolean openableDoor =
                    isClosedWoodenDoor(feetState)
                            || isClosedWoodenDoor(headState);
            boolean bodyClear =
                    (collisionEmpty(level, feet, feetState)
                                    || water
                                    || climbable
                                    || openableDoor)
                            && (collisionEmpty(level, head, headState)
                                    || water
                                    || climbable
                                    || openableDoor);
            boolean supportStable =
                    stableSupport(level, support, supportState);
            boolean hazardous = lava
                    || isHazard(feetState)
                    || isHazard(headState)
                    || isHazard(supportState);
            return new TraversalCell(
                    true,
                    bodyClear,
                    supportStable,
                    water,
                    climbable,
                    openableDoor,
                    hazardous);
        } catch (RuntimeException ignored) {
            return TraversalCell.UNKNOWN;
        }
    }

    private static boolean collisionEmpty(
            ServerLevel level, BlockPos position, BlockState state) {
        return state.getCollisionShape(level, position).isEmpty();
    }

    private static boolean stableSupport(
            ServerLevel level, BlockPos position, BlockState state) {
        if (!state.getFluidState().isEmpty() || isHazard(state)) {
            return false;
        }
        VoxelShape shape = state.getCollisionShape(level, position);
        return !shape.isEmpty()
                && shape.max(Direction.Axis.Y) >= 0.5D;
    }

    private static boolean isClosedWoodenDoor(BlockState state) {
        return state.is(BlockTags.WOODEN_DOORS)
                && state.hasProperty(BlockStateProperties.OPEN)
                && !state.getValue(BlockStateProperties.OPEN);
    }

    private static boolean isHazard(BlockState state) {
        return state.is(Blocks.FIRE)
                || state.is(Blocks.SOUL_FIRE)
                || state.is(Blocks.LAVA)
                || state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.CACTUS)
                || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.is(Blocks.POWDER_SNOW)
                || state.is(Blocks.CAMPFIRE)
                || state.is(Blocks.SOUL_CAMPFIRE);
    }

    private static SnapshotBuildProgress terminal(
            SnapshotBuildProgress.Status status,
            SnapshotBuildCursor cursor,
            String summary) {
        return new SnapshotBuildProgress(
                status,
                0,
                cursor.sampledCells(),
                Optional.empty(),
                summary);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
