package io.github.greytaiwolf.botplayer.navigation;

import io.github.greytaiwolf.botplayer.action.WorldInteractionAction;
import io.github.greytaiwolf.botplayer.action.interaction.BlockHitTarget;
import io.github.greytaiwolf.botplayer.action.interaction.WorldInteractionActionSpec;
import io.github.greytaiwolf.botplayer.action.minecraft.MinecraftActionSnapshot;
import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
import io.github.greytaiwolf.botplayer.navigation.TerrainAssistEvaluation.Decision;
import io.github.greytaiwolf.botplayer.navigation.TerrainAssistEvaluation.Kind;
import io.github.greytaiwolf.botplayer.navigation.TerrainAssistEvaluation.Mutation;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 只在纯路径失败后提出一个紧邻、可验证的真实世界修改，不在 A* 中模拟假想方块状态。
 */
public final class TerrainAssistController {
    private static final int MAXIMUM_BRIDGE_DEPTH = 8;
    private static final int BREAK_ACTION_TICKS = 240;
    private static final int PLACE_ACTION_TICKS = 40;
    private static final Set<Block> BREAK_ALLOWLIST = Set.of(
            Blocks.STONE,
            Blocks.COBBLESTONE,
            Blocks.DIRT,
            Blocks.GRASS_BLOCK,
            Blocks.COARSE_DIRT,
            Blocks.ROOTED_DIRT,
            Blocks.CLAY,
            Blocks.NETHERRACK,
            Blocks.ANDESITE,
            Blocks.DIORITE,
            Blocks.GRANITE);
    private static final Set<Block> BRIDGE_ALLOWLIST = Set.of(
            Blocks.COBBLESTONE,
            Blocks.STONE,
            Blocks.DIRT);

    public TerrainAssistEvaluation evaluate(
            BotServerPlayer player,
            NavigationGoal goal,
            NavigationPolicy request,
            TerrainAssistSettings server,
            int blocksBroken,
            int blocksPlaced) {
        if (!request.allowBreak() && !request.allowPlace()) {
            return TerrainAssistEvaluation.withoutAction(
                    TerrainAssistEvaluation.Status.NOT_REQUESTED,
                    "请求未授权 Terrain Assist");
        }
        Optional<Direction> direction = directionTowards(
                player.blockPosition(), goal.center().toBlockPos());
        if (direction.isEmpty()) {
            return TerrainAssistEvaluation.withoutAction(
                    TerrainAssistEvaluation.Status.NO_SAFE_EPISODE,
                    "目标没有可执行的水平局部方向");
        }
        ServerLevel level = player.serverLevel();
        BlockPos feet = player.blockPosition();
        Direction forward = direction.orElseThrow();
        Optional<BlockPos> obstruction =
                firstBodyObstruction(level, feet.relative(forward));
        if (obstruction.isPresent()) {
            return evaluateBreak(
                    player,
                    request,
                    server,
                    blocksBroken,
                    forward,
                    obstruction.orElseThrow());
        }
        return evaluateBridge(
                player,
                request,
                server,
                blocksPlaced,
                forward);
    }

    private TerrainAssistEvaluation evaluateBreak(
            BotServerPlayer player,
            NavigationPolicy request,
            TerrainAssistSettings server,
            int blocksBroken,
            Direction forward,
            BlockPos target) {
        if (!request.allowBreak()) {
            return TerrainAssistEvaluation.withoutAction(
                    TerrainAssistEvaluation.Status.NO_SAFE_EPISODE,
                    "紧邻阻挡存在，但请求未授权挖掘");
        }
        if (!server.allowBreak()) {
            return TerrainAssistEvaluation.withoutAction(
                    TerrainAssistEvaluation.Status.SERVER_POLICY_BLOCKED,
                    "服务端关闭了 Terrain Assist 挖掘");
        }
        int limit = Math.min(
                request.maximumBlocksBroken(),
                server.maximumBlocksBroken());
        if (blocksBroken >= limit) {
            return TerrainAssistEvaluation.withoutAction(
                    TerrainAssistEvaluation.Status.BUDGET_EXHAUSTED,
                    "Terrain Assist 挖掘预算已耗尽");
        }
        if (!safeBreakCandidate(player, target)) {
            return TerrainAssistEvaluation.withoutAction(
                    TerrainAssistEvaluation.Status.NO_SAFE_EPISODE,
                    "紧邻阻挡不在安全挖掘白名单内");
        }
        Direction face = forward.getOpposite();
        BlockHitTarget hit = MinecraftActionSnapshot.blockHit(
                player,
                new BlockHitResult(
                        Vec3.atCenterOf(target),
                        face,
                        target,
                        false));
        WorldInteractionAction action = new WorldInteractionAction(
                new WorldInteractionActionSpec.BreakBlock(
                        hit,
                        MinecraftActionSnapshot.selectedItem(player)));
        return TerrainAssistEvaluation.action(new Decision(
                action,
                new Mutation(Kind.BREAK, GridPoint.from(target)),
                BREAK_ACTION_TICKS,
                "执行一个受限通道挖掘并重新规划"));
    }

    private TerrainAssistEvaluation evaluateBridge(
            BotServerPlayer player,
            NavigationPolicy request,
            TerrainAssistSettings server,
            int blocksPlaced,
            Direction forward) {
        if (!request.allowPlace()) {
            return TerrainAssistEvaluation.withoutAction(
                    TerrainAssistEvaluation.Status.NO_SAFE_EPISODE,
                    "未发现请求允许的安全局部修改");
        }
        if (!server.allowPlace()) {
            return TerrainAssistEvaluation.withoutAction(
                    TerrainAssistEvaluation.Status.SERVER_POLICY_BLOCKED,
                    "服务端关闭了 Terrain Assist 搭桥");
        }
        int limit = Math.min(
                request.maximumBlocksPlaced(),
                server.maximumBlocksPlaced());
        int remaining = limit - blocksPlaced;
        if (remaining <= 0) {
            return TerrainAssistEvaluation.withoutAction(
                    TerrainAssistEvaluation.Status.BUDGET_EXHAUSTED,
                    "Terrain Assist 搭桥预算已耗尽");
        }
        ServerLevel level = player.serverLevel();
        BlockPos feet = player.blockPosition();
        BlockPos currentSupport = feet.below();
        if (!stableSupport(level, currentSupport)
                || !safeBridgeSpan(
                        level, feet, forward, remaining)) {
            return TerrainAssistEvaluation.withoutAction(
                    TerrainAssistEvaluation.Status.NO_SAFE_EPISODE,
                    "未发现两端稳定且非流体/虚空的简单桥");
        }
        ItemStack selected = player.getInventory().getSelected();
        if (!(selected.getItem() instanceof BlockItem blockItem)
                || !BRIDGE_ALLOWLIST.contains(blockItem.getBlock())) {
            return TerrainAssistEvaluation.withoutAction(
                    TerrainAssistEvaluation.Status.NO_SAFE_EPISODE,
                    "主手没有允许的普通桥面材料");
        }
        BlockPos placed = currentSupport.relative(forward);
        BlockHitTarget hit = MinecraftActionSnapshot.blockHit(
                player,
                new BlockHitResult(
                        Vec3.atCenterOf(currentSupport),
                        forward,
                        currentSupport,
                        false));
        WorldInteractionAction action = new WorldInteractionAction(
                new WorldInteractionActionSpec.UseOnBlock(
                        WorldInteractionActionSpec.Hand.MAIN_HAND,
                        hit,
                        MinecraftActionSnapshot.selectedItem(player)));
        return TerrainAssistEvaluation.action(new Decision(
                action,
                new Mutation(Kind.PLACE, GridPoint.from(placed)),
                PLACE_ACTION_TICKS,
                "放置一个受限桥面方块并重新规划"));
    }

    private static Optional<BlockPos> firstBodyObstruction(
            ServerLevel level, BlockPos feet) {
        for (BlockPos candidate : new BlockPos[] {
            feet, feet.above()
        }) {
            if (!level.isLoaded(candidate)) {
                return Optional.of(candidate.immutable());
            }
            BlockState state = level.getBlockState(candidate);
            if (!state.getCollisionShape(level, candidate).isEmpty()) {
                return Optional.of(candidate.immutable());
            }
        }
        return Optional.empty();
    }

    private static boolean safeBreakCandidate(
            BotServerPlayer player, BlockPos target) {
        ServerLevel level = player.serverLevel();
        if (!level.isLoaded(target)) {
            return false;
        }
        BlockState state = level.getBlockState(target);
        ItemStack selected = player.getInventory().getSelected();
        if (!BREAK_ALLOWLIST.contains(state.getBlock())
                || level.getBlockEntity(target) != null
                || !state.getFluidState().isEmpty()
                || state.getDestroySpeed(level, target) < 0.0F
                || (state.requiresCorrectToolForDrops()
                        && !selected.isCorrectToolForDrops(state))
                || level.getBlockState(target.above()).getBlock()
                        instanceof FallingBlock) {
            return false;
        }
        for (Direction direction : Direction.values()) {
            BlockPos adjacent = target.relative(direction);
            if (!level.isLoaded(adjacent)
                    || !level.getBlockState(adjacent)
                            .getFluidState()
                            .isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static boolean safeBridgeSpan(
            ServerLevel level,
            BlockPos feet,
            Direction forward,
            int maximumPlacements) {
        for (int distance = 1;
                distance <= maximumPlacements;
                distance++) {
            BlockPos body = feet.relative(forward, distance);
            BlockPos support = body.below();
            if (!bodyClear(level, body)
                    || !replaceableBridgeCell(level, support)
                    || !hasKnownSafeDepth(level, support)) {
                return false;
            }
            BlockPos farSupport = support.relative(forward);
            if (stableSupport(level, farSupport)
                    && bodyClear(level, body.relative(forward))) {
                return true;
            }
        }
        return false;
    }

    private static boolean replaceableBridgeCell(
            ServerLevel level, BlockPos position) {
        if (!level.isLoaded(position)) {
            return false;
        }
        BlockState state = level.getBlockState(position);
        return state.isAir() && state.getFluidState().isEmpty();
    }

    private static boolean bodyClear(
            ServerLevel level, BlockPos feet) {
        BlockPos head = feet.above();
        if (!level.isLoaded(feet) || !level.isLoaded(head)) {
            return false;
        }
        BlockState feetState = level.getBlockState(feet);
        BlockState headState = level.getBlockState(head);
        return feetState.getFluidState().isEmpty()
                && headState.getFluidState().isEmpty()
                && feetState.getCollisionShape(level, feet).isEmpty()
                && headState.getCollisionShape(level, head).isEmpty();
    }

    private static boolean hasKnownSafeDepth(
            ServerLevel level, BlockPos gap) {
        for (int depth = 1; depth <= MAXIMUM_BRIDGE_DEPTH; depth++) {
            BlockPos probe = gap.below(depth);
            if (!level.isLoaded(probe)) {
                return false;
            }
            BlockState state = level.getBlockState(probe);
            if (!state.getFluidState().isEmpty()
                    || state.is(Blocks.FIRE)
                    || state.is(Blocks.SOUL_FIRE)
                    || state.is(Blocks.MAGMA_BLOCK)
                    || state.is(Blocks.CACTUS)) {
                return false;
            }
            if (stableSupport(level, probe)) {
                return true;
            }
        }
        return false;
    }

    private static boolean stableSupport(
            ServerLevel level, BlockPos position) {
        if (!level.isLoaded(position)) {
            return false;
        }
        BlockState state = level.getBlockState(position);
        return state.getFluidState().isEmpty()
                && !state.getCollisionShape(level, position).isEmpty();
    }

    private static Optional<Direction> directionTowards(
            BlockPos start, BlockPos goal) {
        int dx = goal.getX() - start.getX();
        int dz = goal.getZ() - start.getZ();
        if (dx == 0 && dz == 0) {
            return Optional.empty();
        }
        if (Math.abs(dx) >= Math.abs(dz)) {
            return Optional.of(
                    dx >= 0 ? Direction.EAST : Direction.WEST);
        }
        return Optional.of(
                dz >= 0 ? Direction.SOUTH : Direction.NORTH);
    }
}
