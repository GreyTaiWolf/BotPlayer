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
import io.github.greytaiwolf.botplayer.skill.menu.MenuSlotRole;
import io.github.greytaiwolf.botplayer.skill.menu.MenuTransactionLimits;
import io.github.greytaiwolf.botplayer.skill.reservation.ReservationKey;
import io.github.greytaiwolf.botplayer.skill.reservation.ReservationMode;
import io.github.greytaiwolf.botplayer.skill.reservation.ReservationRequest;
import io.github.greytaiwolf.botplayer.skill.runtime.core.ActionBackedSkillNodeHandler;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillNodeContext;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillNodeDirective;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillNodeHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

/**
 * 将一份已经审核的“原版白名单容器指定数量 transfer”节点接到真实菜单动作。
 *
 * <p>它只接受普通单箱、双箱、木桶、原版潜影盒和末影箱；开始和完成时都要求 Bot 回到原生
 * {@code InventoryMenu}、cursor 与主手为空。容器内容不会在打开前读取；末影箱只确认其原版
 * 方块实体和精确状态，绝不读取方块实体内容。其私有玩家账本必须等原版右键打开后由 action
 * adapter 绑定到当前 Bot 的 {@code PlayerEnderChestContainer}。
 * {@link WorldInteractionActionSpec.WorldMenuTransfer} 只会在原版右键成功打开精确 3×9 或
 * 6×9 menu（{@code ChestMenu} 或 {@code ShulkerBoxMenu}）后，从当时的完整快照构造全量或
 * 逐右键的精确数量 {@code PICKUP}
 * 计划。方块、相邻双箱半边、菜单族、slot、距离或 player replacement 任一变化都会失败关闭，
 * 绝不直接写库存或猜测未知/模组菜单布局。
 */
public final class MinecraftSingleChestTransferSkillNodeHandler
        implements SkillNodeHandler {
    /** 参数名与 descriptor 必须精确相同；未知附加参数也会被本 handler 拒绝。 */
    public static final String TARGET_X_PARAMETER = "target.x";
    public static final String TARGET_Y_PARAMETER = "target.y";
    public static final String TARGET_Z_PARAMETER = "target.z";
    public static final String SOURCE_SLOT_PARAMETER = "source.slot";
    public static final String TARGET_SLOT_PARAMETER = "target.slot";
    public static final String AMOUNT_PARAMETER = "transfer.amount";
    public static final String DIRECTION_PARAMETER = "transfer.direction";

    /** 3×9 原版通用容器的 27 格加玩家主背包与快捷栏 36 格。 */
    public static final int CHEST_FIRST_SLOT = 0;
    public static final int CHEST_LAST_SLOT = 26;
    public static final int PLAYER_FIRST_SLOT = 27;
    public static final int PLAYER_LAST_SLOT = 62;
    /** 双箱 54 格加玩家主背包与快捷栏 36 格。 */
    public static final int DOUBLE_CHEST_LAST_SLOT = 53;
    public static final int DOUBLE_CHEST_PLAYER_FIRST_SLOT = 54;
    public static final int DOUBLE_CHEST_PLAYER_LAST_SLOT = 89;

    private static final int MAXIMUM_ACTION_TICKS = 240;
    private static final MenuTransactionLimits TRANSFER_LIMITS =
            new MenuTransactionLimits(
                    WorldInteractionActionSpec.WorldMenuTransfer
                            .MAXIMUM_EXACT_TRANSFER_AMOUNT + 2,
                    MAXIMUM_ACTION_TICKS);
    private static final String RESERVATION_SCOPE =
            "minecraft.vanilla_container";
    private static final Set<String> VANILLA_DIRECTION_NAMES = Set.of(
            "down", "up", "north", "south", "west", "east");
    private static final Set<String> HORIZONTAL_DIRECTION_NAMES = Set.of(
            "north", "south", "west", "east");
    private static final Set<String> BOOLEAN_PROPERTY_VALUES = Set.of(
            "false", "true");
    private static final Set<String> CHEST_PROPERTY_NAMES = Set.of(
            "facing", "type", "waterlogged");
    private static final Set<String> BARREL_PROPERTY_NAMES = Set.of(
            "facing", "open");
    private static final Set<String> ENDER_CHEST_PROPERTY_NAMES = Set.of(
            "facing", "waterlogged");
    private static final Set<String> SHULKER_PROPERTY_NAMES = Set.of("facing");
    private static final Set<String> VANILLA_SHULKER_IDS = Set.of(
            "minecraft:shulker_box",
            "minecraft:white_shulker_box",
            "minecraft:orange_shulker_box",
            "minecraft:magenta_shulker_box",
            "minecraft:light_blue_shulker_box",
            "minecraft:yellow_shulker_box",
            "minecraft:lime_shulker_box",
            "minecraft:pink_shulker_box",
            "minecraft:gray_shulker_box",
            "minecraft:light_gray_shulker_box",
            "minecraft:cyan_shulker_box",
            "minecraft:purple_shulker_box",
            "minecraft:blue_shulker_box",
            "minecraft:brown_shulker_box",
            "minecraft:green_shulker_box",
            "minecraft:red_shulker_box",
            "minecraft:black_shulker_box");

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
     * 将物理容器作为跨 Bot 的独占租约；双箱两半必须规范化为同一个最小坐标 key。没有
     * 维度参数时，同坐标跨维度产生的保守冲突是安全的，而不是把另一个维度误认为同一
     * 容器继续执行。
     */
    @Override
    public List<ReservationRequest> requiredReservations(
            SkillNodeContext context) {
        requireOwnerThread();
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
                        reservationSubject(context, request)),
                ReservationMode.EXCLUSIVE));
    }

    @Override
    public SkillNodeDirective begin(SkillNodeContext context) {
        requireOwnerThread();
        Objects.requireNonNull(context, "context");
        if (parseParameters(context.node().parameters()).isEmpty()) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.INVALID_PARAMETERS,
                    "原版容器 transfer 节点参数不符合精确 schema");
        }
        if (prepare(context).isEmpty()) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.WORLD_CHANGED,
                    "原版容器 transfer 的方块、距离或原生菜单前置条件不成立");
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
                "vanilla-container-transfer",
                new WorldInteractionAction(
                        transferAction(prepared.request(),
                                prepared.container())),
                ActionPriority.SURVIVAL,
                MAXIMUM_ACTION_TICKS,
                SkillNodeDirective.Kind.WAIT_MENU,
                "等待原版容器菜单 transfer 完成",
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
                    || !player.canInteractWithBlock(position, 0.0D)) {
                return Optional.empty();
            }
            ContainerTarget container = inspectVanillaContainer(
                    player, position).orElse(null);
            if (container == null
                    || !request.matchesFamily(container.family())
                    || writesToSelectedHotbar(
                            request,
                            container.family(),
                            player.getInventory().selected)) {
                return Optional.empty();
            }
            return Optional.of(new Prepared(
                    player,
                    context.botGeneration(),
                    request,
                    container));
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
                    "原版容器 transfer 回执时节点参数已经不再匹配");
        }
        BotServerPlayer current = resolver.resolve(
                context.botId(), context.botGeneration()).orElse(null);
        if (current != prepared.player()
                || current == null
                || current.runtimeHandle().generation()
                        != prepared.generation()) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.STALE_GENERATION,
                    "原版容器 transfer 完成时 BotPlayer 代际已经变化");
        }
        if (signal.evidence().isEmpty()
                || !hasNativeEmptyInventoryMenu(current)
                || !MinecraftActionSnapshot.item(
                                current, current.getMainHandItem())
                        .isEmpty()) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.CONTAINER_CHANGED,
                    "原版容器菜单未以空 cursor 的原生背包状态关闭");
        }
        BlockPos position = new BlockPos(
                prepared.request().target().x(),
                prepared.request().target().y(),
                prepared.request().target().z());
        ContainerTarget observed = current.serverLevel().isLoaded(position)
                ? inspectVanillaContainer(current, position).orElse(null)
                : null;
        if (!prepared.container().equals(observed)) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.WORLD_CHANGED,
                    "原版容器 transfer 完成后目标方块快照无法确认");
        }
        return SkillNodeDirective.complete("原版容器菜单 transfer 已确认");
    }

    /**
     * 纯构造点便于 descriptor/单测审计：它从不接受可变 menu、window id 或非空手持物。
     * 3×9 容器只需一个目标快照；双箱必须额外传入另一半的冻结快照，不能只凭一个
     * {@code type=left/right} 状态假设其仍是同一个 6×9 容器。
     */
    static WorldInteractionActionSpec.WorldMenuTransfer transferAction(
            TransferRequest request, BlockTargetFingerprint targetSnapshot) {
        return createTransferAction(
                request, targetSnapshot, MenuFamily.CHEST_3X9);
    }

    static WorldInteractionActionSpec.WorldMenuTransfer transferAction(
            TransferRequest request,
            BlockTargetFingerprint targetSnapshot,
            BlockTargetFingerprint partnerSnapshot) {
        Objects.requireNonNull(partnerSnapshot, "partnerSnapshot");
        if (!isPairedDoubleChestSnapshots(targetSnapshot, partnerSnapshot)) {
            throw new IllegalArgumentException(
                    "double chest action requires its exact paired snapshots");
        }
        return createTransferAction(
                request, targetSnapshot, MenuFamily.CHEST_6X9);
    }

    private static WorldInteractionActionSpec.WorldMenuTransfer createTransferAction(
            TransferRequest request,
            BlockTargetFingerprint targetSnapshot,
            MenuFamily family) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(targetSnapshot, "targetSnapshot");
        Objects.requireNonNull(family, "family");
        if (!request.target().equals(targetSnapshot.position())
                || !isFamilyCompatibleSnapshot(targetSnapshot, family)
                || !request.matchesFamily(family)) {
            throw new IllegalArgumentException(
                    "vanilla container action requires its exact snapped target and layout");
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
                family,
                request.sourceSlot(),
                request.targetSlot(),
                request.amount(),
                TRANSFER_LIMITS);
    }

    private static WorldInteractionActionSpec.WorldMenuTransfer transferAction(
            TransferRequest request, ContainerTarget container) {
        Objects.requireNonNull(container, "container");
        return container.family() == MenuFamily.CHEST_6X9
                ? transferAction(
                        request,
                        container.targetSnapshot(),
                        container.partnerSnapshot().orElseThrow())
                : transferAction(request, container.targetSnapshot());
    }

    /**
     * 只接受七个精确标量参数，不做 Long→int、字符串数字、大小写或方向别名转换。
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
        Integer amount = exactInteger(values, AMOUNT_PARAMETER);
        Object directionValue = values.get(DIRECTION_PARAMETER);
        if (x == null || y == null || z == null
                || source == null || target == null || amount == null
                || !(directionValue instanceof String directionText)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new TransferRequest(
                    new BlockCoordinates(x, y, z),
                    source,
                    target,
                    amount,
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

    /**
     * 只在服务器线程、已加载 target 上识别封闭原版容器集合。此处不读取任何槽位；槽位快照
     * 仍要等原版右键打开对应的精确 3×9/6×9 menu 后由 action adapter 取得。
     */
    private static Optional<ContainerTarget> inspectVanillaContainer(
            BotServerPlayer player, BlockPos position) {
        try {
            if (!player.serverLevel().isLoaded(position)) {
                return Optional.empty();
            }
            BlockState state = player.serverLevel().getBlockState(position);
            if (state.is(Blocks.CHEST)) {
                return inspectChest(player, position, state);
            }
            if (state.is(Blocks.BARREL)) {
                return inspectBarrel(player, position);
            }
            if (state.is(Blocks.ENDER_CHEST)) {
                return inspectEnderChest(player, position);
            }
            if (state.getBlock() instanceof ShulkerBoxBlock) {
                return inspectShulkerBox(player, position);
            }
            return Optional.empty();
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static Optional<ContainerTarget> inspectChest(
            BotServerPlayer player, BlockPos position, BlockState state) {
        if (!state.hasProperty(ChestBlock.TYPE)
                || !state.hasProperty(ChestBlock.FACING)
                || !(player.serverLevel().getBlockEntity(position)
                        instanceof ChestBlockEntity chest)
                || chest.getContainerSize()
                        != CHEST_LAST_SLOT - CHEST_FIRST_SLOT + 1) {
            return Optional.empty();
        }
        BlockTargetFingerprint targetSnapshot = MinecraftActionSnapshot.block(
                player, position);
        ChestType type = state.getValue(ChestBlock.TYPE);
        if (type == ChestType.SINGLE) {
            return isSingleChestSnapshot(targetSnapshot)
                    ? Optional.of(new ContainerTarget(
                            ContainerKind.SINGLE_CHEST,
                            MenuFamily.CHEST_3X9,
                            targetSnapshot,
                            Optional.empty()))
                    : Optional.empty();
        }
        if ((type != ChestType.LEFT && type != ChestType.RIGHT)
                || !isDoubleChestSnapshot(targetSnapshot)) {
            return Optional.empty();
        }
        return inspectDoubleChestPartner(
                player, position, state, type, targetSnapshot)
                .map(partnerSnapshot -> new ContainerTarget(
                        ContainerKind.DOUBLE_CHEST,
                        MenuFamily.CHEST_6X9,
                        targetSnapshot,
                        Optional.of(partnerSnapshot)));
    }

    /**
     * 不依赖 ChestBlock 内部方向辅助方法：只接受恰好一个已加载、同朝向、左右相反的相邻
     * 原版 chest 半边。任一邻块未知、零候选或多候选都拒绝，避免把损坏/模组布局当双箱。
     */
    private static Optional<BlockTargetFingerprint> inspectDoubleChestPartner(
            BotServerPlayer player,
            BlockPos position,
            BlockState state,
            ChestType type,
            BlockTargetFingerprint targetSnapshot) {
        List<BlockTargetFingerprint> candidates = new ArrayList<>(1);
        for (Direction direction : Direction.values()) {
            if (!direction.getAxis().isHorizontal()) {
                continue;
            }
            BlockPos neighbor = position.relative(direction);
            if (!player.serverLevel().isLoaded(neighbor)) {
                return Optional.empty();
            }
            BlockState neighborState = player.serverLevel().getBlockState(neighbor);
            if (!neighborState.is(Blocks.CHEST)
                    || !neighborState.hasProperty(ChestBlock.TYPE)
                    || !neighborState.hasProperty(ChestBlock.FACING)
                    || neighborState.getValue(ChestBlock.TYPE) == ChestType.SINGLE
                    || neighborState.getValue(ChestBlock.TYPE) == type
                    || neighborState.getValue(ChestBlock.FACING)
                            != state.getValue(ChestBlock.FACING)
                    || !(player.serverLevel().getBlockEntity(neighbor)
                            instanceof ChestBlockEntity chest)
                    || chest.getContainerSize()
                            != CHEST_LAST_SLOT - CHEST_FIRST_SLOT + 1) {
                continue;
            }
            BlockTargetFingerprint snapshot = MinecraftActionSnapshot.block(
                    player, neighbor);
            if (isPairedDoubleChestSnapshots(targetSnapshot, snapshot)) {
                candidates.add(snapshot);
            }
        }
        return candidates.size() == 1
                ? Optional.of(candidates.getFirst())
                : Optional.empty();
    }

    private static Optional<ContainerTarget> inspectBarrel(
            BotServerPlayer player, BlockPos position) {
        if (!(player.serverLevel().getBlockEntity(position)
                instanceof BarrelBlockEntity barrel)
                || barrel.getContainerSize()
                        != CHEST_LAST_SLOT - CHEST_FIRST_SLOT + 1) {
            return Optional.empty();
        }
        BlockTargetFingerprint snapshot = MinecraftActionSnapshot.block(
                player, position);
        return isBarrelSnapshot(snapshot)
                ? Optional.of(new ContainerTarget(
                        ContainerKind.BARREL,
                        MenuFamily.CHEST_3X9,
                        snapshot,
                        Optional.empty()))
                : Optional.empty();
    }

    private static Optional<ContainerTarget> inspectShulkerBox(
            BotServerPlayer player, BlockPos position) {
        if (!(player.serverLevel().getBlockEntity(position)
                instanceof ShulkerBoxBlockEntity shulkerBox)
                || shulkerBox.getContainerSize()
                        != CHEST_LAST_SLOT - CHEST_FIRST_SLOT + 1) {
            return Optional.empty();
        }
        BlockTargetFingerprint snapshot = MinecraftActionSnapshot.block(
                player, position);
        return isVanillaShulkerSnapshot(snapshot)
                ? Optional.of(new ContainerTarget(
                        ContainerKind.SHULKER_BOX,
                        MenuFamily.CHEST_3X9,
                        snapshot,
                        Optional.empty()))
                : Optional.empty();
    }

    /**
     * 末影箱方块实体不保存可转移物品：真实 27 格账本属于当前玩家。这里仅验证原版 opener
     * 存在及精确状态；绝不读取 block entity 的 NBT、槽位或“共享”库存。
     */
    private static Optional<ContainerTarget> inspectEnderChest(
            BotServerPlayer player, BlockPos position) {
        if (!(player.serverLevel().getBlockEntity(position)
                instanceof EnderChestBlockEntity)) {
            return Optional.empty();
        }
        BlockTargetFingerprint snapshot = MinecraftActionSnapshot.block(
                player, position);
        return isEnderChestSnapshot(snapshot)
                ? Optional.of(new ContainerTarget(
                        ContainerKind.ENDER_CHEST,
                        MenuFamily.CHEST_3X9,
                        snapshot,
                        Optional.empty()))
                : Optional.empty();
    }

    private static boolean isFamilyCompatibleSnapshot(
            BlockTargetFingerprint target, MenuFamily family) {
        return switch (family) {
            case CHEST_3X9 -> isSingleChestSnapshot(target)
                    || isBarrelSnapshot(target)
                    || isEnderChestSnapshot(target)
                    || isVanillaShulkerSnapshot(target);
            case CHEST_6X9 -> isDoubleChestSnapshot(target);
            default -> false;
        };
    }

    /**
     * 该节点的冻结合同要求完成后主手仍为空。若把取出的物品写入当前选中的热键栏，原版
     * 菜单正确完成后主手必然变非空；必须在打开前拒绝，不能等已经提交物品后再误报失败。
     */
    static boolean writesToSelectedHotbar(
            TransferRequest request, MenuFamily family, int selectedHotbar) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(family, "family");
        if (selectedHotbar < 0 || selectedHotbar >= 9
                || request.direction() != TransferDirection.CHEST_TO_PLAYER
                || !request.matchesFamily(family)) {
            return false;
        }
        int selectedMenuSlot = family.slotCount() - 9 + selectedHotbar;
        return family.roleAt(selectedMenuSlot) == MenuSlotRole.PLAYER_HOTBAR
                && request.targetSlot() == selectedMenuSlot;
    }

    private static boolean isSingleChestSnapshot(
            BlockTargetFingerprint target) {
        return isChestSnapshot(target)
                && "single".equals(target.state().properties().get("type"));
    }

    private static boolean isDoubleChestSnapshot(
            BlockTargetFingerprint target) {
        if (!isChestSnapshot(target)) {
            return false;
        }
        String type = target.state().properties().get("type");
        return "left".equals(type) || "right".equals(type);
    }

    /**
     * 双箱另一半既要是相反 {@code type}，又必须是同维度、同朝向、位于该 {@code type}
     * 与 {@code facing} 唯一确定的连接方向。这让完成后的重新观察能识别“原目标尚在、
     * 但另一半被替换”的竞态，也不把反向伪造的 LEFT/RIGHT 状态当作真实双箱。
     */
    private static boolean isPairedDoubleChestSnapshots(
            BlockTargetFingerprint target,
            BlockTargetFingerprint partner) {
        if (!isDoubleChestSnapshot(target)
                || !isDoubleChestSnapshot(partner)
                || !target.dimension().equals(partner.dimension())
                || target.position().equals(partner.position())
                || !Objects.equals(
                        target.state().properties().get("facing"),
                        partner.state().properties().get("facing"))) {
            return false;
        }
        String targetType = target.state().properties().get("type");
        String partnerType = partner.state().properties().get("type");
        if (targetType.equals(partnerType)) {
            return false;
        }
        Direction facing = directionByName(
                target.state().properties().get("facing"));
        if (facing == null) {
            return false;
        }
        Direction connected = "left".equals(targetType)
                ? facing.getClockWise()
                : facing.getCounterClockWise();
        BlockCoordinates expectedPartner = new BlockCoordinates(
                target.position().x() + connected.getStepX(),
                target.position().y(),
                target.position().z() + connected.getStepZ());
        return expectedPartner.equals(partner.position());
    }

    private static Direction directionByName(String name) {
        return switch (name) {
            case "down" -> Direction.DOWN;
            case "up" -> Direction.UP;
            case "north" -> Direction.NORTH;
            case "south" -> Direction.SOUTH;
            case "west" -> Direction.WEST;
            case "east" -> Direction.EAST;
            default -> null;
        };
    }

    private static boolean isChestSnapshot(BlockTargetFingerprint target) {
        Map<String, String> properties = target.state().properties();
        return target.state().blockId().value().equals("minecraft:chest")
                && properties.keySet().equals(CHEST_PROPERTY_NAMES)
                && HORIZONTAL_DIRECTION_NAMES.contains(properties.get("facing"))
                && BOOLEAN_PROPERTY_VALUES.contains(
                        properties.get("waterlogged"));
    }

    private static boolean isBarrelSnapshot(BlockTargetFingerprint target) {
        Map<String, String> properties = target.state().properties();
        return target.state().blockId().value().equals("minecraft:barrel")
                && properties.keySet().equals(BARREL_PROPERTY_NAMES)
                && VANILLA_DIRECTION_NAMES.contains(properties.get("facing"))
                && BOOLEAN_PROPERTY_VALUES.contains(properties.get("open"));
    }

    private static boolean isEnderChestSnapshot(
            BlockTargetFingerprint target) {
        Map<String, String> properties = target.state().properties();
        return target.state().blockId().value().equals("minecraft:ender_chest")
                && properties.keySet().equals(ENDER_CHEST_PROPERTY_NAMES)
                && HORIZONTAL_DIRECTION_NAMES.contains(properties.get("facing"))
                && BOOLEAN_PROPERTY_VALUES.contains(
                        properties.get("waterlogged"));
    }

    private static boolean isVanillaShulkerSnapshot(
            BlockTargetFingerprint target) {
        Map<String, String> properties = target.state().properties();
        return VANILLA_SHULKER_IDS.contains(target.state().blockId().value())
                && properties.keySet().equals(SHULKER_PROPERTY_NAMES)
                && VANILLA_DIRECTION_NAMES.contains(properties.get("facing"));
    }

    /**
     * 在取得租约前只做一次轻量的服务器线程重观察。若方块尚不可观察，退回请求坐标：
     * 后续 {@link #prepare} 仍会拒绝该节点，不会让未知双箱进入动作路径。
     */
    private String reservationSubject(
            SkillNodeContext context, TransferRequest request) {
        try {
            BotServerPlayer player = resolver.resolve(
                    context.botId(), context.botGeneration()).orElse(null);
            if (player == null
                    || player.runtimeHandle().generation()
                            != context.botGeneration()) {
                return reservationSubject(request.target());
            }
            BlockPos position = new BlockPos(
                    request.target().x(),
                    request.target().y(),
                    request.target().z());
            ContainerTarget container = inspectVanillaContainer(player, position)
                    .orElse(null);
            if (container == null
                    || container.family() != MenuFamily.CHEST_6X9) {
                return reservationSubject(request.target());
            }
            return canonicalReservationSubject(
                    container.targetSnapshot().position(),
                    container.partnerSnapshot().orElseThrow().position());
        } catch (RuntimeException exception) {
            return reservationSubject(request.target());
        }
    }

    private static String reservationSubject(BlockCoordinates target) {
        return target.x() + "," + target.y() + "," + target.z();
    }

    static String canonicalReservationSubject(
            BlockCoordinates first, BlockCoordinates second) {
        return comparesBeforeOrEqual(first, second)
                ? reservationSubject(first)
                : reservationSubject(second);
    }

    private static boolean comparesBeforeOrEqual(
            BlockCoordinates first, BlockCoordinates second) {
        if (first.x() != second.x()) {
            return first.x() < second.x();
        }
        if (first.y() != second.y()) {
            return first.y() < second.y();
        }
        return first.z() <= second.z();
    }

    private void requireOwnerThread() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException(
                    "vanilla container transfer handler requires server thread");
        }
    }

    /** 生命周期只应按 botId/generation 解析当前身体，不得把 body 缓存在 runtime DTO。 */
    @FunctionalInterface
    public interface Resolver {
        Optional<BotServerPlayer> resolve(UUID botId, long generation);
    }

    /**
     * 单向语义同时锁住 source/target 所属的两个精确菜单区段。
     *
     * <p>参数值沿用既有 descriptor，以免旧的已审核 P5 节点在扩展布局后失效；实际容器种类
     * 一律由服务器线程从 target 方块重新观察，不接受模型声明。
     */
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
                    "vanilla container transfer direction is not allowed");
        }
    }

    /**
     * 已解析参数仍不携带维度、方块状态、菜单实例或活对象。{@code amount=0} 是全量
     * move-or-swap；正数才表示严格的逐右键指定数量。
     */
    public record TransferRequest(
            BlockCoordinates target,
            int sourceSlot,
            int targetSlot,
            int amount,
            TransferDirection direction) {
        public TransferRequest {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(direction, "direction");
            MenuFamily.CHEST_6X9.requireSlot(sourceSlot);
            MenuFamily.CHEST_6X9.requireSlot(targetSlot);
            if (sourceSlot == targetSlot) {
                throw new IllegalArgumentException(
                        "vanilla container transfer source and target must differ");
            }
            if (amount < 0 || amount > WorldInteractionActionSpec
                    .WorldMenuTransfer.MAXIMUM_EXACT_TRANSFER_AMOUNT) {
                throw new IllegalArgumentException(
                        "vanilla container transfer amount exceeds the P5 bound");
            }
            if (!matchesFamily(
                    MenuFamily.CHEST_3X9, sourceSlot, targetSlot, direction)
                    && !matchesFamily(
                            MenuFamily.CHEST_6X9,
                            sourceSlot,
                            targetSlot,
                            direction)) {
                throw new IllegalArgumentException(
                        "vanilla container transfer slots do not match its direction");
            }
        }

        /** 容器大小在打开前由服务器观察；不允许参数把 3×9 槽位解释成双箱槽位。 */
        boolean matchesFamily(MenuFamily family) {
            return matchesFamily(family, sourceSlot, targetSlot, direction);
        }

        private static boolean matchesFamily(
                MenuFamily family,
                int sourceSlot,
                int targetSlot,
                TransferDirection direction) {
            if ((family != MenuFamily.CHEST_3X9
                    && family != MenuFamily.CHEST_6X9)
                    || sourceSlot >= family.slotCount()
                    || targetSlot >= family.slotCount()) {
                return false;
            }
            boolean sourceContainer = family.roleAt(sourceSlot)
                    == MenuSlotRole.CONTAINER;
            boolean targetContainer = family.roleAt(targetSlot)
                    == MenuSlotRole.CONTAINER;
            boolean sourcePlayer = family.isPlayerInventorySlot(sourceSlot);
            boolean targetPlayer = family.isPlayerInventorySlot(targetSlot);
            return switch (direction) {
                case CHEST_TO_PLAYER -> sourceContainer && targetPlayer;
                case PLAYER_TO_CHEST -> sourcePlayer && targetContainer;
            };
        }
    }

    private enum ContainerKind {
        SINGLE_CHEST,
        DOUBLE_CHEST,
        BARREL,
        ENDER_CHEST,
        SHULKER_BOX
    }

    /**
     * target 与（仅双箱需要的）另一半都冻结为方块指纹；它们不携带活 BlockEntity 或菜单。
     */
    private record ContainerTarget(
            ContainerKind kind,
            MenuFamily family,
            BlockTargetFingerprint targetSnapshot,
            Optional<BlockTargetFingerprint> partnerSnapshot) {
        private ContainerTarget {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(family, "family");
            Objects.requireNonNull(targetSnapshot, "targetSnapshot");
            partnerSnapshot = Objects.requireNonNull(
                    partnerSnapshot, "partnerSnapshot");
            boolean invalidDoubleChest = kind == ContainerKind.DOUBLE_CHEST
                    && (family != MenuFamily.CHEST_6X9
                            || partnerSnapshot.isEmpty()
                            || !isPairedDoubleChestSnapshots(
                                    targetSnapshot,
                                    partnerSnapshot.orElseThrow()));
            boolean invalidSingleContainer = kind != ContainerKind.DOUBLE_CHEST
                    && (family != MenuFamily.CHEST_3X9
                            || partnerSnapshot.isPresent());
            if (invalidDoubleChest || invalidSingleContainer) {
                throw new IllegalArgumentException(
                        "container kind does not match its exact menu shape");
            }
        }
    }

    private record Prepared(
            BotServerPlayer player,
            long generation,
            TransferRequest request,
            ContainerTarget container) {
        private Prepared {
            Objects.requireNonNull(player, "player");
            if (generation <= 0L) {
                throw new IllegalArgumentException(
                        "vanilla container generation must be positive");
            }
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(container, "container");
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
                        AMOUNT_PARAMETER,
                        DIRECTION_PARAMETER);

        private SetHolder() {
        }
    }
}
