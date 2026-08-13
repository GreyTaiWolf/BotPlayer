package io.github.greytaiwolf.botplayer.skill.runtime;

import io.github.greytaiwolf.botplayer.action.ActionEvidence;
import io.github.greytaiwolf.botplayer.action.ActionPriority;
import io.github.greytaiwolf.botplayer.action.WorldInteractionAction;
import io.github.greytaiwolf.botplayer.action.interaction.BlockCoordinates;
import io.github.greytaiwolf.botplayer.action.interaction.BlockHitTarget;
import io.github.greytaiwolf.botplayer.action.interaction.BlockTargetFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.InventoryContentsSnapshot;
import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import io.github.greytaiwolf.botplayer.action.interaction.WorldInteractionActionSpec;
import io.github.greytaiwolf.botplayer.action.interaction.menu.InventoryMenuSnapshot;
import io.github.greytaiwolf.botplayer.action.minecraft.MinecraftActionSnapshot;
import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
import io.github.greytaiwolf.botplayer.skill.builtin.farming.SugarCaneFarmingInventoryConservation;
import io.github.greytaiwolf.botplayer.skill.builtin.farming.SugarCaneFarmingPlanCompiler;
import io.github.greytaiwolf.botplayer.skill.builtin.farming.SugarCaneFarmingSkillIds;
import io.github.greytaiwolf.botplayer.skill.builtin.farming.SugarCaneHarvestEvidence;
import io.github.greytaiwolf.botplayer.skill.core.SkillFailureCode;
import io.github.greytaiwolf.botplayer.skill.core.SkillParameters;
import io.github.greytaiwolf.botplayer.skill.core.SkillSignal;
import io.github.greytaiwolf.botplayer.skill.reservation.ReservationKey;
import io.github.greytaiwolf.botplayer.skill.reservation.ReservationMode;
import io.github.greytaiwolf.botplayer.skill.reservation.ReservationRequest;
import io.github.greytaiwolf.botplayer.skill.runtime.core.ActionBackedSkillNodeHandler;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillNodeContext;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillNodeDirective;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillNodeHandler;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * P5B 最小原版甘蔗收获节点：只允许破坏一个已有甘蔗基座之上的最上节。
 *
 * <p>输入坐标只定位一个已知候选。开始、真实动作提交、动作完成和 UUID 拾取提交时都会在
 * 服务器线程重新核验当前 body/generation、完整 target/base/above 指纹、原生
 * InventoryMenu/cursor、完整库存、可达性和容量。实际副作用严格委托给已有
 * {@code BREAK_BLOCK} 与 {@code PickupWait}；本节点不直接修改世界、背包或掉落实体。
 *
 * <p>为了把“只破坏最上节”变成 action 边界合同，{@code BREAK_BLOCK} 同时冻结下方基座与
 * 上方空气作为可选 face-adjacent 指纹前置。后端在 START、每个采掘 Tick、STOP 前以及
 * success 前均重验这些邻居，不能只依赖 handler 的较早 preflight。
 */
public final class MinecraftSugarCaneFarmingSkillNodeHandler
        implements SkillNodeHandler {
    private static final int MAXIMUM_ACTION_TICKS =
            SugarCaneFarmingPlanCompiler.MAXIMUM_ACTION_TICKS;
    private static final int MAXIMUM_PICKUP_TICKS = 80;
    /** One extra runtime tick lets the backend verify an exhausted PickupWait. */
    private static final int MAXIMUM_PICKUP_ACTION_TICKS =
            MAXIMUM_PICKUP_TICKS + 1;
    private static final int MINIMUM_HARVEST_EMPTY_STORAGE_SLOTS = 1;
    private static final String RESERVATION_SCOPE =
            "minecraft.sugar_cane";
    private static final ResourceId SUGAR_CANE_ID =
            new ResourceId("minecraft:sugar_cane");
    private static final ResourceId AIR_ID = new ResourceId("minecraft:air");

    private final Resolver resolver;
    private final ActionBackedSkillNodeHandler actionDelegate;
    private final ActionBackedSkillNodeHandler pickupActionDelegate;
    private final Map<UUID, PendingHarvestPickup> pendingHarvestPickups =
            new LinkedHashMap<>();
    private final Thread ownerThread;

    public MinecraftSugarCaneFarmingSkillNodeHandler(
            Resolver resolver,
            ActionBackedSkillNodeHandler.ActionGateway actions,
            ActionBackedSkillNodeHandler.SignalSink signals) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        ActionBackedSkillNodeHandler.ActionGateway actionGateway =
                Objects.requireNonNull(actions, "actions");
        ActionBackedSkillNodeHandler.SignalSink signalSink =
                Objects.requireNonNull(signals, "signals");
        actionDelegate = new ActionBackedSkillNodeHandler(
                this::planHarvestAction, actionGateway, signalSink);
        pickupActionDelegate = new ActionBackedSkillNodeHandler(
                this::planHarvestPickup, actionGateway, signalSink);
        ownerThread = Thread.currentThread();
    }

    /**
     * Lock both the target and its base. The top-air fence is action-local rather
     * than a reservation because it is deliberately expected to stay empty and
     * cannot be owned as a harvest resource.
     */
    @Override
    public List<ReservationRequest> requiredReservations(
            SkillNodeContext context) {
        requireOwnerThread();
        Objects.requireNonNull(context, "context");
        BlockCoordinates target = parseTarget(context.node().parameters())
                .orElse(null);
        if (target == null || target.y() == Integer.MIN_VALUE) {
            return List.of();
        }
        return List.of(
                blockReservation(target),
                blockReservation(new BlockCoordinates(target.x(),
                        target.y() - 1, target.z())));
    }

    @Override
    public SkillNodeDirective begin(SkillNodeContext context) {
        requireOwnerThread();
        Objects.requireNonNull(context, "context");
        if (!matches(context)) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.INVALID_PARAMETERS,
                    "甘蔗收获节点与已注册技能身份不匹配");
        }
        if (parseTarget(context.node().parameters()).isEmpty()) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.INVALID_PARAMETERS,
                    "甘蔗收获节点参数不符合精确坐标 schema");
        }
        if (prepare(context).isEmpty()) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.WORLD_CHANGED,
                    "甘蔗目标、基座、上方空气、库存或原生菜单前置条件不成立");
        }
        /* Re-observe immediately before the action mailbox receives the packet. */
        return actionDelegate.begin(context);
    }

    @Override
    public SkillNodeDirective signal(
            SkillNodeContext context, SkillSignal signal) {
        requireOwnerThread();
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(signal, "signal");
        return pendingHarvestPickups.containsKey(context.runId())
                ? pickupActionDelegate.signal(context, signal)
                : actionDelegate.signal(context, signal);
    }

    /**
     * The generic skill FSM returns to RUNNING after a verified break. The next
     * server tick is the only point where an unfinished receipt may submit its
     * bounded UUID-bound pickup action.
     */
    @Override
    public SkillNodeDirective tick(SkillNodeContext context) {
        requireOwnerThread();
        Objects.requireNonNull(context, "context");
        PendingHarvestPickup pending = pendingHarvestPickups.get(
                context.runId());
        if (pending == null) {
            return SkillNodeDirective.continueRunning("甘蔗收获节点继续运行");
        }
        if (!pending.matches(context)) {
            pendingHarvestPickups.remove(context.runId(), pending);
            return SkillNodeDirective.fail(
                    SkillFailureCode.WORLD_CHANGED,
                    "甘蔗掉落拾取等待期间节点身份或目标已变化");
        }
        SkillNodeDirective started = pickupActionDelegate.begin(context);
        if (started.kind() != SkillNodeDirective.Kind.WAIT_ACTION) {
            pendingHarvestPickups.remove(context.runId(), pending);
        }
        return started;
    }

    @Override
    public void cancelled(SkillNodeContext context, String reason) {
        requireOwnerThread();
        Objects.requireNonNull(context, "context");
        pendingHarvestPickups.remove(context.runId());
        /* Both delegates only cancel actual mailbox tickets; no world rollback is faked. */
        actionDelegate.cancelled(context, reason);
        pickupActionDelegate.cancelled(context, reason);
    }

    private Optional<ActionBackedSkillNodeHandler.Operation> planHarvestAction(
            SkillNodeContext context) {
        HarvestPrepared prepared = prepare(context).orElse(null);
        if (prepared == null) {
            return Optional.empty();
        }
        return Optional.of(new ActionBackedSkillNodeHandler.Operation(
                "harvest-upper-sugar-cane",
                new WorldInteractionAction(
                        new WorldInteractionActionSpec.BreakBlock(
                                hit(prepared.target()),
                                prepared.heldBefore(),
                                List.of(prepared.base(),
                                        prepared.airAbove()))),
                ActionPriority.SURVIVAL,
                MAXIMUM_ACTION_TICKS,
                SkillNodeDirective.Kind.WAIT_ACTION,
                "等待原版最上节甘蔗破坏完成",
                (signalContext, signal) -> verifyHarvest(prepared,
                        signalContext, signal)));
    }

    /**
     * A pickup continuation never scans or selects arbitrary items. It can only
     * wait for the exact UUID emitted by the synchronous preceding break receipt.
     */
    private Optional<ActionBackedSkillNodeHandler.Operation> planHarvestPickup(
            SkillNodeContext context) {
        PendingHarvestPickup pending = pendingHarvestPickups.get(
                context.runId());
        if (pending == null || !pending.matches(context)) {
            throw new ActionBackedSkillNodeHandler.PlanningFailure(
                    SkillFailureCode.INTERNAL_ERROR,
                    "甘蔗掉落拾取开始时没有匹配的收获收据");
        }
        BotServerPlayer player = currentPlayer(pending.prepared().player(),
                pending.prepared().generation(), context).orElse(null);
        if (player == null || !harvestWorldStillSafe(pending.prepared(),
                player) || !hasNativeEmptyInventoryMenu(player)) {
            throw new ActionBackedSkillNodeHandler.PlanningFailure(
                    SkillFailureCode.WORLD_CHANGED,
                    "甘蔗掉落拾取开始时世界、Bot 或原生菜单已变化");
        }
        InventoryContentsSnapshot current = MinecraftActionSnapshot
                .inventoryContents(player);
        if (!pending.prepared().inventoryBefore().equals(current)) {
            throw new ActionBackedSkillNodeHandler.PlanningFailure(
                    SkillFailureCode.ITEM_CONSERVATION_VIOLATION,
                    "甘蔗掉落拾取入队前背包不再是收获前的精确快照");
        }
        if (!dropLiveAndPickupReachable(player, pending.receipt(),
                pending.expectedDrop())) {
            throw new ActionBackedSkillNodeHandler.PlanningFailure(
                    SkillFailureCode.MISSING_ITEM,
                    "甘蔗掉落实体在原版 UUID 拾取前不可确认或不在拾取范围");
        }
        return Optional.of(new ActionBackedSkillNodeHandler.Operation(
                "pickup-sugar-cane-drop",
                new WorldInteractionAction(
                        new WorldInteractionActionSpec.PickupWait(
                                MAXIMUM_PICKUP_TICKS,
                                Optional.of(pending.receipt().entityId()))),
                ActionPriority.SURVIVAL,
                MAXIMUM_PICKUP_ACTION_TICKS,
                SkillNodeDirective.Kind.WAIT_ACTION,
                "等待原版甘蔗掉落实体进入背包",
                (signalContext, signal) -> verifyHarvestPickup(pending,
                        signalContext, signal)));
    }

    private Optional<HarvestPrepared> prepare(SkillNodeContext context) {
        try {
            if (!matches(context)) {
                return Optional.empty();
            }
            BlockCoordinates coordinates = parseTarget(
                    context.node().parameters()).orElse(null);
            if (coordinates == null) {
                return Optional.empty();
            }
            BotServerPlayer player = resolver.resolve(context.botId(),
                    context.botGeneration()).orElse(null);
            if (player == null
                    || player.runtimeHandle().generation()
                            != context.botGeneration()
                    || !hasNativeEmptyInventoryMenu(player)) {
                return Optional.empty();
            }
            BlockPos targetPosition = new BlockPos(coordinates.x(),
                    coordinates.y(), coordinates.z());
            Level level = player.serverLevel();
            if (!withinHarvestHeight(level, targetPosition)
                    || !level.isLoaded(targetPosition)
                    || !level.isLoaded(targetPosition.below())
                    || !level.isLoaded(targetPosition.above())
                    || !player.canInteractWithBlock(targetPosition, 0.0D)) {
                return Optional.empty();
            }
            BlockPos basePosition = targetPosition.below();
            BlockPos abovePosition = targetPosition.above();
            BlockTargetFingerprint target = MinecraftActionSnapshot.block(
                    player, targetPosition);
            BlockTargetFingerprint base = MinecraftActionSnapshot.block(
                    player, basePosition);
            BlockTargetFingerprint airAbove = MinecraftActionSnapshot.block(
                    player, abovePosition);
            BlockState targetState = level.getBlockState(targetPosition);
            BlockState baseState = level.getBlockState(basePosition);
            BlockState aboveState = level.getBlockState(abovePosition);
            if (!isExactSugarCane(targetState, target)
                    || !isExactSugarCane(baseState, base)
                    || !isExactAir(aboveState, airAbove)
                    || !targetState.canSurvive(level, targetPosition)
                    || !baseState.canSurvive(level, basePosition)) {
                return Optional.empty();
            }
            return Optional.of(new HarvestPrepared(
                    player,
                    player.runtimeHandle().generation(),
                    targetPosition,
                    target,
                    base,
                    airAbove,
                    MinecraftActionSnapshot.selectedItem(player),
                    MinecraftActionSnapshot.inventoryContents(player),
                    MinecraftActionSnapshot.inventoryMenu(player)));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private SkillNodeDirective verifyHarvest(
            HarvestPrepared prepared,
            SkillNodeContext context,
            SkillSignal signal) {
        BotServerPlayer player = currentPlayer(prepared.player(),
                prepared.generation(), context).orElse(null);
        if (player == null || !hasNativeEmptyInventoryMenu(player)) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.STALE_GENERATION,
                    "甘蔗收获完成时 BotPlayer 或原生菜单已变化");
        }
        if (!harvestWorldStillSafe(prepared, player)) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.WORLD_CHANGED,
                    "甘蔗收获后目标、基座或上方空气无法确认");
        }
        SugarCaneHarvestEvidence.Receipt receipt = SugarCaneHarvestEvidence
                .exactSingleDrop(signal.evidence()).orElse(null);
        if (receipt == null || !signalHasBreakEvidence(signal)) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.WORLD_CHANGED,
                    "甘蔗收获回执缺少精确的一枚原版掉落实体收据");
        }
        ItemStackFingerprint expectedDrop = canonicalCane(player);
        InventoryContentsSnapshot expected =
                SugarCaneFarmingInventoryConservation.afterCollectedDrop(
                        prepared.inventoryBefore(), expectedDrop).orElse(null);
        if (expected == null) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.ITEM_CONSERVATION_VIOLATION,
                    "甘蔗掉落无法在受限背包容量内形成精确守恒账本");
        }
        InventoryMenuSnapshot afterMenu = MinecraftActionSnapshot.inventoryMenu(
                player);
        InventoryContentsSnapshot afterInventory = MinecraftActionSnapshot
                .inventoryContents(player);
        if (!afterMenu.cursor().isEmpty()
                || afterMenu.containerId()
                        != prepared.menuBefore().containerId()
                || afterMenu.selectedHotbar()
                        != prepared.menuBefore().selectedHotbar()) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.ITEM_CONSERVATION_VIOLATION,
                    "甘蔗收获期间 cursor、菜单或选中热栏发生未授权变化");
        }
        if (expected.equals(afterInventory)
                && dropRemoved(player, receipt)) {
            return SkillNodeDirective.complete(
                    "一枚原版甘蔗掉落已进入背包并完成精确守恒核验");
        }
        if (!prepared.inventoryBefore().equals(afterInventory)) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.ITEM_CONSERVATION_VIOLATION,
                    "甘蔗收获后的背包仅允许完整收据掉落，拒绝部分或额外漂移");
        }
        if (!dropLiveAndPickupReachable(player, receipt, expectedDrop)) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.MISSING_ITEM,
                    "甘蔗掉落实体不再完整可见或不在原版拾取范围");
        }
        PendingHarvestPickup pending = new PendingHarvestPickup(
                prepared,
                receipt,
                expectedDrop,
                expected,
                context.node().nodeId());
        if (pendingHarvestPickups.putIfAbsent(context.runId(), pending)
                != null) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.INTERNAL_ERROR,
                    "甘蔗收获已经存在未完成的 UUID 掉落拾取");
        }
        return SkillNodeDirective.continueRunning(
                "甘蔗掉落实体仍在原版拾取范围，下一 tick 提交 UUID 绑定拾取");
    }

    private SkillNodeDirective verifyHarvestPickup(
            PendingHarvestPickup pending,
            SkillNodeContext context,
            SkillSignal signal) {
        try {
            if (!pending.matches(context)
                    || !pending.equals(pendingHarvestPickups.get(
                            context.runId()))
                    || !signalHasPickupEvidence(signal,
                            pending.receipt().entityId())) {
                return SkillNodeDirective.fail(
                        SkillFailureCode.INTERNAL_ERROR,
                        "甘蔗掉落拾取回执不属于冻结的 UUID 收据");
            }
            BotServerPlayer player = currentPlayer(pending.prepared().player(),
                    pending.prepared().generation(), context).orElse(null);
            if (player == null || !hasNativeEmptyInventoryMenu(player)) {
                return SkillNodeDirective.fail(
                        SkillFailureCode.STALE_GENERATION,
                        "甘蔗掉落拾取完成时 BotPlayer 或原生菜单已变化");
            }
            if (!harvestWorldStillSafe(pending.prepared(), player)
                    || !dropRemoved(player, pending.receipt())) {
                return SkillNodeDirective.fail(
                        SkillFailureCode.WORLD_CHANGED,
                        "甘蔗掉落拾取后目标、基座、上方空气或收据实体未完整确认");
            }
            InventoryMenuSnapshot afterMenu = MinecraftActionSnapshot
                    .inventoryMenu(player);
            InventoryContentsSnapshot afterInventory = MinecraftActionSnapshot
                    .inventoryContents(player);
            if (!afterMenu.cursor().isEmpty()
                    || afterMenu.containerId()
                            != pending.prepared().menuBefore().containerId()
                    || afterMenu.selectedHotbar()
                            != pending.prepared().menuBefore()
                                    .selectedHotbar()
                    || !pending.expectedInventoryAfter().equals(
                            afterInventory)) {
                return SkillNodeDirective.fail(
                        SkillFailureCode.ITEM_CONSERVATION_VIOLATION,
                        "甘蔗掉落拾取未能证明该 UUID 的一枚物品精确进入背包");
            }
            return SkillNodeDirective.complete(
                    "甘蔗掉落已由 UUID 绑定原版拾取进入背包");
        } finally {
            pendingHarvestPickups.remove(context.runId(), pending);
        }
    }

    private Optional<BotServerPlayer> currentPlayer(
            BotServerPlayer expected,
            long expectedGeneration,
            SkillNodeContext context) {
        BotServerPlayer current = resolver.resolve(context.botId(),
                context.botGeneration()).orElse(null);
        return current == expected
                && current != null
                && expectedGeneration == context.botGeneration()
                && current.runtimeHandle().generation() == expectedGeneration
                ? Optional.of(current)
                : Optional.empty();
    }

    private static boolean harvestWorldStillSafe(
            HarvestPrepared prepared, BotServerPlayer player) {
        Level level = player.serverLevel();
        BlockPos target = prepared.targetPosition();
        BlockPos base = target.below();
        BlockPos above = target.above();
        if (!level.isLoaded(target)
                || !level.isLoaded(base)
                || !level.isLoaded(above)
                || !isExactAir(level.getBlockState(target),
                        MinecraftActionSnapshot.block(player, target))
                || !prepared.base().equals(MinecraftActionSnapshot.block(
                        player, base))
                || !prepared.airAbove().equals(MinecraftActionSnapshot.block(
                        player, above))) {
            return false;
        }
        BlockState baseState = level.getBlockState(base);
        return isExactSugarCane(baseState, prepared.base())
                && baseState.canSurvive(level, base);
    }

    private static boolean dropLiveAndPickupReachable(
            BotServerPlayer player,
            SugarCaneHarvestEvidence.Receipt receipt,
            ItemStackFingerprint expectedDrop) {
        Entity entity = player.serverLevel().getEntity(receipt.entityId());
        return entity instanceof ItemEntity itemEntity
                && !itemEntity.isRemoved()
                && expectedDrop.equals(MinecraftActionSnapshot.item(player,
                        itemEntity.getItem()))
                && player.canInteractWithEntity(itemEntity, 1.0D);
    }

    private static boolean dropRemoved(
            BotServerPlayer player, SugarCaneHarvestEvidence.Receipt receipt) {
        Entity entity = player.serverLevel().getEntity(receipt.entityId());
        return entity == null || entity.isRemoved();
    }

    private static ItemStackFingerprint canonicalCane(BotServerPlayer player) {
        return MinecraftActionSnapshot.item(player,
                new ItemStack(Items.SUGAR_CANE, 1));
    }

    private static boolean hasHarvestStorageCapacity(
            InventoryMenuSnapshot menu) {
        int emptyStorageSlots = 0;
        for (int inventorySlot = 0; inventorySlot <= 35; inventorySlot++) {
            if (menu.itemAt(inventorySlot).isEmpty()) {
                emptyStorageSlots++;
            }
        }
        return emptyStorageSlots >= MINIMUM_HARVEST_EMPTY_STORAGE_SLOTS;
    }

    private static boolean signalHasBreakEvidence(SkillSignal signal) {
        return hasEvidence(signal, "block.after", "minecraft:air")
                && hasEvidence(signal, "block.position", null);
    }

    private static boolean signalHasPickupEvidence(
            SkillSignal signal, UUID targetEntityId) {
        return hasEvidence(signal, "entity.id", targetEntityId.toString())
                && hasEvidence(signal, "item.expected_count", "1")
                && hasCanonicalPositiveIntegerEvidence(signal,
                        "item.gained_count");
    }

    private static boolean hasEvidence(
            SkillSignal signal, String key, String requiredValue) {
        return signal.evidence().stream().anyMatch(evidence ->
                evidence.key().equals(key)
                        && (requiredValue == null
                        || evidence.value().equals(requiredValue)));
    }

    private static boolean hasCanonicalPositiveIntegerEvidence(
            SkillSignal signal, String key) {
        for (ActionEvidence evidence : signal.evidence()) {
            if (!evidence.key().equals(key)) {
                continue;
            }
            try {
                int value = Integer.parseInt(evidence.value());
                return value > 0
                        && Integer.toString(value).equals(evidence.value());
            } catch (IllegalArgumentException exception) {
                return false;
            }
        }
        return false;
    }

    private static boolean hasNativeEmptyInventoryMenu(BotServerPlayer player) {
        return player.containerMenu == player.inventoryMenu
                && player.inventoryMenu.stillValid(player)
                && player.inventoryMenu.getCarried().isEmpty();
    }

    private static boolean withinHarvestHeight(
            Level level, BlockPos target) {
        long targetY = target.getY();
        return targetY - 1L >= level.getMinBuildHeight()
                && targetY > level.getMinBuildHeight()
                && targetY + 1L < level.getMaxBuildHeight();
    }

    private static boolean isExactSugarCane(
            BlockState state, BlockTargetFingerprint fingerprint) {
        return state.is(Blocks.SUGAR_CANE)
                && state.hasProperty(BlockStateProperties.AGE_15)
                && fingerprint.state().blockId().equals(SUGAR_CANE_ID)
                && fingerprint.state().properties().equals(Map.of(
                        "age", Integer.toString(state.getValue(
                                BlockStateProperties.AGE_15))));
    }

    private static boolean isExactAir(
            BlockState state, BlockTargetFingerprint fingerprint) {
        return state.isAir()
                && fingerprint.state().blockId().equals(AIR_ID)
                && fingerprint.state().properties().isEmpty();
    }

    private static BlockHitTarget hit(BlockTargetFingerprint target) {
        return new BlockHitTarget(
                target,
                BlockHitTarget.Face.UP,
                0.5D,
                1.0D,
                0.5D,
                false);
    }

    static Optional<BlockCoordinates> parseTarget(SkillParameters parameters) {
        Objects.requireNonNull(parameters, "parameters");
        Map<String, Object> values = parameters.values();
        if (!values.keySet().equals(SetHolder.TARGET_PARAMETER_NAMES)) {
            return Optional.empty();
        }
        Object x = values.get(SugarCaneFarmingSkillIds.TARGET_X_PARAMETER);
        Object y = values.get(SugarCaneFarmingSkillIds.TARGET_Y_PARAMETER);
        Object z = values.get(SugarCaneFarmingSkillIds.TARGET_Z_PARAMETER);
        if (!(x instanceof Integer xValue)
                || !(y instanceof Integer yValue)
                || !(z instanceof Integer zValue)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BlockCoordinates(xValue, yValue, zValue));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static ReservationRequest blockReservation(BlockCoordinates target) {
        return new ReservationRequest(
                new ReservationKey(
                        ReservationKey.Kind.BLOCK,
                        RESERVATION_SCOPE,
                        target.x() + "," + target.y() + "," + target.z()),
                ReservationMode.EXCLUSIVE);
    }

    private static boolean matches(SkillNodeContext context) {
        return context.node().skillId().equals(
                SugarCaneFarmingSkillIds.HARVEST_UPPER_SUGAR_CANE)
                && context.node().skillVersion().equals(
                        SugarCaneFarmingSkillIds.VERSION);
    }

    private void requireOwnerThread() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException(
                    "sugar cane farming skill node handler requires server thread");
        }
    }

    /** Lifecycle and tests expose only the generation-bound active body. */
    @FunctionalInterface
    public interface Resolver {
        Optional<BotServerPlayer> resolve(UUID botId, long generation);
    }

    private record HarvestPrepared(
            BotServerPlayer player,
            long generation,
            BlockPos targetPosition,
            BlockTargetFingerprint target,
            BlockTargetFingerprint base,
            BlockTargetFingerprint airAbove,
            ItemStackFingerprint heldBefore,
            InventoryContentsSnapshot inventoryBefore,
            InventoryMenuSnapshot menuBefore) {
        private HarvestPrepared {
            Objects.requireNonNull(player, "player");
            if (generation <= 0L) {
                throw new IllegalArgumentException(
                        "sugar cane farming generation must be positive");
            }
            Objects.requireNonNull(targetPosition, "targetPosition");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(base, "base");
            Objects.requireNonNull(airAbove, "airAbove");
            Objects.requireNonNull(heldBefore, "heldBefore");
            Objects.requireNonNull(inventoryBefore, "inventoryBefore");
            menuBefore = Objects.requireNonNull(menuBefore, "menuBefore");
            if (!menuBefore.cursor().isEmpty()
                    || !hasHarvestStorageCapacity(menuBefore)) {
                throw new IllegalArgumentException(
                        "sugar cane harvest requires empty cursor and bounded storage capacity");
            }
        }
    }

    /**
     * Pickup transition state contains immutable fingerprints and a UUID, never a
     * live {@link ItemEntity} or {@link Level}.
     */
    private record PendingHarvestPickup(
            HarvestPrepared prepared,
            SugarCaneHarvestEvidence.Receipt receipt,
            ItemStackFingerprint expectedDrop,
            InventoryContentsSnapshot expectedInventoryAfter,
            UUID nodeId) {
        private PendingHarvestPickup {
            prepared = Objects.requireNonNull(prepared, "prepared");
            receipt = Objects.requireNonNull(receipt, "receipt");
            expectedDrop = Objects.requireNonNull(expectedDrop, "expectedDrop");
            expectedInventoryAfter = Objects.requireNonNull(
                    expectedInventoryAfter, "expectedInventoryAfter");
            nodeId = Objects.requireNonNull(nodeId, "nodeId");
            if (expectedDrop.isEmpty()
                    || !expectedDrop.itemId().filter(SUGAR_CANE_ID::equals)
                            .isPresent()
                    || expectedDrop.count() != 1) {
                throw new IllegalArgumentException(
                        "pickup receipt must prove exactly one vanilla sugar cane");
            }
        }

        private boolean matches(SkillNodeContext context) {
            return prepared.player().getUUID().equals(context.botId())
                    && prepared.generation() == context.botGeneration()
                    && nodeId.equals(context.node().nodeId())
                    && MinecraftSugarCaneFarmingSkillNodeHandler.matches(
                            context);
        }
    }

    private static final class SetHolder {
        private static final Set<String> TARGET_PARAMETER_NAMES = Set.of(
                SugarCaneFarmingSkillIds.TARGET_X_PARAMETER,
                SugarCaneFarmingSkillIds.TARGET_Y_PARAMETER,
                SugarCaneFarmingSkillIds.TARGET_Z_PARAMETER);

        private SetHolder() {
        }
    }
}
