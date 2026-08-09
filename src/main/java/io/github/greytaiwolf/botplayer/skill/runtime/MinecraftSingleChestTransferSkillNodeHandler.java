package io.github.greytaiwolf.botplayer.skill.runtime;

import io.github.greytaiwolf.botplayer.action.ActionPriority;
import io.github.greytaiwolf.botplayer.action.WorldInteractionAction;
import io.github.greytaiwolf.botplayer.action.interaction.BlockCoordinates;
import io.github.greytaiwolf.botplayer.action.interaction.BlockHitTarget;
import io.github.greytaiwolf.botplayer.action.interaction.BlockTargetFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.WorldInteractionActionSpec;
import io.github.greytaiwolf.botplayer.action.minecraft.MinecraftActionSnapshot;
import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
import io.github.greytaiwolf.botplayer.skill.core.SkillFailureCode;
import io.github.greytaiwolf.botplayer.skill.core.SkillParameters;
import io.github.greytaiwolf.botplayer.skill.core.SkillSignal;
import io.github.greytaiwolf.botplayer.skill.menu.MenuFamily;
import io.github.greytaiwolf.botplayer.skill.menu.MenuTransactionLimits;
import io.github.greytaiwolf.botplayer.skill.reservation.ReservationKey;
import io.github.greytaiwolf.botplayer.skill.reservation.ReservationMode;
import io.github.greytaiwolf.botplayer.skill.reservation.ReservationRequest;
import io.github.greytaiwolf.botplayer.skill.runtime.core.ActionBackedSkillNodeHandler;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillNodeContext;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillNodeDirective;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillNodeHandler;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 将一份已经审核的“单箱完整堆叠 transfer”节点接到真实原版菜单动作。
 *
 * <p>它只接受普通 {@code minecraft:chest} 的 {@code type=single} 状态，且开始和
 * 完成时都要求 Bot 回到原生 {@code InventoryMenu}、cursor 与主手为空。箱子内容不会在
 * 打开前读取；{@link WorldInteractionActionSpec.WorldMenuTransfer} 会在原版右键成功
 * 打开精确 3×9 chest menu 后，从当时的完整快照构造 2/3 次 {@code PICKUP} 计划。
 * 因此坐标、方向、slot、距离、方块状态、菜单族或任一 player replacement 变化都只会
 * 拒绝该节点，绝不直接写库存或尝试猜测容器布局。
 */
public final class MinecraftSingleChestTransferSkillNodeHandler
        implements SkillNodeHandler {
    /** 参数名与 descriptor 必须精确相同；未知附加参数也会被本 handler 拒绝。 */
    public static final String TARGET_X_PARAMETER = "target.x";
    public static final String TARGET_Y_PARAMETER = "target.y";
    public static final String TARGET_Z_PARAMETER = "target.z";
    public static final String SOURCE_SLOT_PARAMETER = "source.slot";
    public static final String TARGET_SLOT_PARAMETER = "target.slot";
    public static final String DIRECTION_PARAMETER = "transfer.direction";

    /** 单箱 27 格加玩家主背包与快捷栏 36 格。 */
    public static final int CHEST_FIRST_SLOT = 0;
    public static final int CHEST_LAST_SLOT = 26;
    public static final int PLAYER_FIRST_SLOT = 27;
    public static final int PLAYER_LAST_SLOT = 62;

    private static final int MAXIMUM_ACTION_TICKS = 240;
    private static final MenuTransactionLimits TRANSFER_LIMITS =
            new MenuTransactionLimits(3, MAXIMUM_ACTION_TICKS);
    private static final String RESERVATION_SCOPE =
            "minecraft.single_chest";

    private final Resolver resolver;
    private final ActionBackedSkillNodeHandler actionDelegate;
    private final Thread ownerThread;

    public MinecraftSingleChestTransferSkillNodeHandler(
            Resolver resolver,
            ActionBackedSkillNodeHandler.ActionGateway actions,
            ActionBackedSkillNodeHandler.SignalSink signals) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        actionDelegate = new ActionBackedSkillNodeHandler(
                this::planAction,
                Objects.requireNonNull(actions, "actions"),
                Objects.requireNonNull(signals, "signals"));
        ownerThread = Thread.currentThread();
    }

    /**
     * 将物理箱子作为跨 Bot 的独占租约；没有维度参数时，同坐标跨维度产生的保守冲突是
     * 安全的，而不是把另一个维度误认为同一容器继续执行。
     */
    @Override
    public List<ReservationRequest> requiredReservations(
            SkillNodeContext context) {
        Objects.requireNonNull(context, "context");
        TransferRequest request = parseParameters(
                context.node().parameters()).orElse(null);
        if (request == null) {
            return List.of();
        }
        return List.of(new ReservationRequest(
                new ReservationKey(
                        ReservationKey.Kind.CONTAINER,
                        RESERVATION_SCOPE,
                        reservationSubject(request.target())),
                ReservationMode.EXCLUSIVE));
    }

    @Override
    public SkillNodeDirective begin(SkillNodeContext context) {
        requireOwnerThread();
        Objects.requireNonNull(context, "context");
        if (parseParameters(context.node().parameters()).isEmpty()) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.INVALID_PARAMETERS,
                    "单箱 transfer 节点参数不符合精确 schema");
        }
        if (prepare(context).isEmpty()) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.WORLD_CHANGED,
                    "单箱 transfer 的原版箱子、距离或原生菜单前置条件不成立");
        }
        /*
         * ActionBacked 会再次 prepare 并冻结新的方块/物品指纹。双读都发生在服务器线程；
         * 两者之间若被模组回调改变，第二次读取或后端 bind 会保守失败。
         */
        return actionDelegate.begin(context);
    }

    @Override
    public SkillNodeDirective signal(
            SkillNodeContext context, SkillSignal signal) {
        requireOwnerThread();
        return actionDelegate.signal(context, signal);
    }

    @Override
    public void cancelled(SkillNodeContext context, String reason) {
        requireOwnerThread();
        actionDelegate.cancelled(context, reason);
    }

    private Optional<ActionBackedSkillNodeHandler.Operation> planAction(
            SkillNodeContext context) {
        Prepared prepared = prepare(context).orElse(null);
        if (prepared == null) {
            return Optional.empty();
        }
        return Optional.of(new ActionBackedSkillNodeHandler.Operation(
                "chest-transfer",
                new WorldInteractionAction(
                        transferAction(prepared.request(),
                                prepared.targetSnapshot())),
                ActionPriority.SURVIVAL,
                MAXIMUM_ACTION_TICKS,
                SkillNodeDirective.Kind.WAIT_MENU,
                "等待原版单箱菜单 transfer 完成",
                (signalContext, signal) -> verify(
                        prepared, signalContext, signal)));
    }

    private Optional<Prepared> prepare(SkillNodeContext context) {
        try {
            TransferRequest request = parseParameters(
                    context.node().parameters()).orElse(null);
            if (request == null) {
                return Optional.empty();
            }
            BotServerPlayer player = resolver.resolve(
                    context.botId(), context.botGeneration()).orElse(null);
            if (player == null
                    || player.runtimeHandle().generation()
                            != context.botGeneration()
                    || !hasNativeEmptyInventoryMenu(player)) {
                return Optional.empty();
            }
            ItemStackFingerprint held = MinecraftActionSnapshot.item(
                    player, player.getMainHandItem());
            if (!held.isEmpty()) {
                return Optional.empty();
            }
            BlockPos position = new BlockPos(
                    request.target().x(),
                    request.target().y(),
                    request.target().z());
            if (position.getY() < player.serverLevel().getMinBuildHeight()
                    || position.getY()
                            >= player.serverLevel().getMaxBuildHeight()
                    || !player.serverLevel().isLoaded(position)
                    || !player.canInteractWithBlock(position, 0.0D)
                    || !isExactSingleChest(player, position)) {
                return Optional.empty();
            }
            BlockTargetFingerprint snapshot = MinecraftActionSnapshot.block(
                    player, position);
            if (!isExactSingleChestSnapshot(snapshot)) {
                return Optional.empty();
            }
            return Optional.of(new Prepared(
                    player,
                    context.botGeneration(),
                    request,
                    snapshot));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private SkillNodeDirective verify(
            Prepared prepared,
            SkillNodeContext context,
            SkillSignal signal) {
        TransferRequest currentRequest = parseParameters(
                context.node().parameters()).orElse(null);
        if (!prepared.request().equals(currentRequest)) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.INVALID_PARAMETERS,
                    "单箱 transfer 回执时节点参数已经不再匹配");
        }
        BotServerPlayer current = resolver.resolve(
                context.botId(), context.botGeneration()).orElse(null);
        if (current != prepared.player()
                || current == null
                || current.runtimeHandle().generation()
                        != prepared.generation()) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.STALE_GENERATION,
                    "单箱 transfer 完成时 BotPlayer 代际已经变化");
        }
        if (signal.evidence().isEmpty()
                || !hasNativeEmptyInventoryMenu(current)
                || !MinecraftActionSnapshot.item(
                                current, current.getMainHandItem())
                        .isEmpty()) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.CONTAINER_CHANGED,
                    "原版单箱菜单未以空 cursor 的原生背包状态关闭");
        }
        BlockPos position = new BlockPos(
                prepared.request().target().x(),
                prepared.request().target().y(),
                prepared.request().target().z());
        if (!current.serverLevel().isLoaded(position)
                || !isExactSingleChest(current, position)
                || !MinecraftActionSnapshot.block(current, position)
                        .equals(prepared.targetSnapshot())) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.WORLD_CHANGED,
                    "单箱 transfer 完成后目标方块快照无法确认");
        }
        return SkillNodeDirective.complete("原版单箱菜单 transfer 已确认");
    }

    /**
     * 纯构造点便于 descriptor/单测审计：它从不接受可变 menu、window id 或非空手持物。
     */
    static WorldInteractionActionSpec.WorldMenuTransfer transferAction(
            TransferRequest request, BlockTargetFingerprint targetSnapshot) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(targetSnapshot, "targetSnapshot");
        if (!request.target().equals(targetSnapshot.position())
                || !isExactSingleChestSnapshot(targetSnapshot)) {
            throw new IllegalArgumentException(
                    "single chest action requires the exact snapped chest target");
        }
        return new WorldInteractionActionSpec.WorldMenuTransfer(
                WorldInteractionActionSpec.Hand.MAIN_HAND,
                new BlockHitTarget(
                        targetSnapshot,
                        BlockHitTarget.Face.UP,
                        0.5D,
                        1.0D,
                        0.5D,
                        false),
                ItemStackFingerprint.empty(),
                MenuFamily.CHEST_3X9,
                request.sourceSlot(),
                request.targetSlot(),
                TRANSFER_LIMITS);
    }

    /**
     * 只接受六个精确标量参数，不做 Long→int、字符串数字、大小写或方向别名转换。
     */
    public static Optional<TransferRequest> parseParameters(
            SkillParameters parameters) {
        Objects.requireNonNull(parameters, "parameters");
        Map<String, Object> values = parameters.values();
        if (!values.keySet().equals(SetHolder.REQUIRED_PARAMETER_NAMES)) {
            return Optional.empty();
        }
        Integer x = exactInteger(values, TARGET_X_PARAMETER);
        Integer y = exactInteger(values, TARGET_Y_PARAMETER);
        Integer z = exactInteger(values, TARGET_Z_PARAMETER);
        Integer source = exactInteger(values, SOURCE_SLOT_PARAMETER);
        Integer target = exactInteger(values, TARGET_SLOT_PARAMETER);
        Object directionValue = values.get(DIRECTION_PARAMETER);
        if (x == null || y == null || z == null
                || source == null || target == null
                || !(directionValue instanceof String directionText)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new TransferRequest(
                    new BlockCoordinates(x, y, z),
                    source,
                    target,
                    TransferDirection.parse(directionText)));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static Integer exactInteger(
            Map<String, Object> values, String name) {
        Object value = values.get(name);
        return value instanceof Integer integer ? integer : null;
    }

    private static boolean hasNativeEmptyInventoryMenu(BotServerPlayer player) {
        try {
            return player.containerMenu == player.inventoryMenu
                    && player.inventoryMenu.stillValid(player)
                    && player.inventoryMenu.slots.size()
                            == MenuFamily.INVENTORY_2X2.slotCount()
                    && player.inventoryMenu.getCarried().isEmpty();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean isExactSingleChest(
            BotServerPlayer player, BlockPos position) {
        try {
            BlockState state = player.serverLevel().getBlockState(position);
            if (!state.is(Blocks.CHEST)
                    || !isSingleChestState(state)) {
                return false;
            }
            return player.serverLevel().getBlockEntity(position)
                    instanceof ChestBlockEntity chest
                    && chest.getContainerSize()
                            == CHEST_LAST_SLOT - CHEST_FIRST_SLOT + 1;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean isExactSingleChestSnapshot(
            BlockTargetFingerprint target) {
        return target.state().blockId().value().equals("minecraft:chest")
                && "single".equals(
                        target.state().properties().get("type"));
    }

    private static boolean isSingleChestState(BlockState state) {
        return state.getValues().entrySet().stream().anyMatch(entry ->
                entry.getKey().getName().equals("type")
                        && entry.getValue().toString().equals("single"));
    }

    private static String reservationSubject(BlockCoordinates target) {
        return target.x() + "," + target.y() + "," + target.z();
    }

    private void requireOwnerThread() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException(
                    "single chest transfer handler requires server thread");
        }
    }

    /** 生命周期只应按 botId/generation 解析当前身体，不得把 body 缓存在 runtime DTO。 */
    @FunctionalInterface
    public interface Resolver {
        Optional<BotServerPlayer> resolve(UUID botId, long generation);
    }

    /** 单向语义同时锁住 source/target 所属的两个精确菜单区段。 */
    public enum TransferDirection {
        CHEST_TO_PLAYER("chest_to_player"),
        PLAYER_TO_CHEST("player_to_chest");

        private final String parameterValue;

        TransferDirection(String parameterValue) {
            this.parameterValue = parameterValue;
        }

        public String parameterValue() {
            return parameterValue;
        }

        private static TransferDirection parse(String value) {
            for (TransferDirection direction : values()) {
                if (direction.parameterValue.equals(value)) {
                    return direction;
                }
            }
            throw new IllegalArgumentException(
                    "single chest transfer direction is not allowed");
        }
    }

    /** 已解析参数仍不携带维度、方块状态、菜单实例或活对象。 */
    public record TransferRequest(
            BlockCoordinates target,
            int sourceSlot,
            int targetSlot,
            TransferDirection direction) {
        public TransferRequest {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(direction, "direction");
            MenuFamily.CHEST_3X9.requireSlot(sourceSlot);
            MenuFamily.CHEST_3X9.requireSlot(targetSlot);
            if (sourceSlot == targetSlot) {
                throw new IllegalArgumentException(
                        "single chest transfer source and target must differ");
            }
            boolean sourceChest = sourceSlot >= CHEST_FIRST_SLOT
                    && sourceSlot <= CHEST_LAST_SLOT;
            boolean targetChest = targetSlot >= CHEST_FIRST_SLOT
                    && targetSlot <= CHEST_LAST_SLOT;
            boolean sourcePlayer = sourceSlot >= PLAYER_FIRST_SLOT
                    && sourceSlot <= PLAYER_LAST_SLOT;
            boolean targetPlayer = targetSlot >= PLAYER_FIRST_SLOT
                    && targetSlot <= PLAYER_LAST_SLOT;
            boolean valid = switch (direction) {
                case CHEST_TO_PLAYER -> sourceChest && targetPlayer;
                case PLAYER_TO_CHEST -> sourcePlayer && targetChest;
            };
            if (!valid) {
                throw new IllegalArgumentException(
                        "single chest transfer slots do not match its direction");
            }
        }
    }

    private record Prepared(
            BotServerPlayer player,
            long generation,
            TransferRequest request,
            BlockTargetFingerprint targetSnapshot) {
        private Prepared {
            Objects.requireNonNull(player, "player");
            if (generation <= 0L) {
                throw new IllegalArgumentException(
                        "single chest generation must be positive");
            }
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(targetSnapshot, "targetSnapshot");
        }
    }

    /** 避免在每次解析时重新分配并保持 required key 集合不可变。 */
    private static final class SetHolder {
        private static final java.util.Set<String> REQUIRED_PARAMETER_NAMES =
                java.util.Set.of(
                        TARGET_X_PARAMETER,
                        TARGET_Y_PARAMETER,
                        TARGET_Z_PARAMETER,
                        SOURCE_SLOT_PARAMETER,
                        TARGET_SLOT_PARAMETER,
                        DIRECTION_PARAMETER);

        private SetHolder() {
        }
    }
}
