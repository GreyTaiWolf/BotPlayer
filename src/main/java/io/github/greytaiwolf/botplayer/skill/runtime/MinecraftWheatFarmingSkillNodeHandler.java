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
import io.github.greytaiwolf.botplayer.action.minecraft.BreakDropProvenanceCapture;
import io.github.greytaiwolf.botplayer.action.minecraft.MinecraftActionSnapshot;
import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
import io.github.greytaiwolf.botplayer.skill.builtin.farming.WheatFarmingInventoryConservation;
import io.github.greytaiwolf.botplayer.skill.builtin.farming.WheatFarmingPlanCompiler;
import io.github.greytaiwolf.botplayer.skill.builtin.farming.WheatFarmingSkillIds;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * P5B 的最小原版农业节点：只处理一格已经成熟的小麦，随后在同一格原地补种。
 *
 * <p>本类不扫描方块、不读取 block entity、不直接写世界或 Inventory。输入坐标仅用于定位一个
 * 已知候选；每个节点都在服务端线程两次重新读取完整方块指纹、耕地、原版作物存活条件、光照、
 * 原生 InventoryMenu/cursor 与完整背包。实际副作用严格交给已有 {@code BREAK_BLOCK} 和
 * {@code USE_ON_BLOCK} 动作；任一 world、hand、generation 或库存漂移都拒绝完成。
 *
 * <p>收获不会把落在地面的农作物当成已经收集。原版 {@code BREAK_BLOCK} 回执必须携带同步
 * 捕获的每个掉落实体 UUID；只有所有收据实体消失、背包严格等于“破坏前 + 所有收据掉落”时，
 * harvest 才会完成。尚未自动进入背包的掉落仅能在全部仍处于原版拾取范围内时，走一次 UUID
 * 绑定 {@code PickupWait}；不能证明完整收集即失败关闭。补种节点随后只允许精确消耗当前主手
 * 一枚原版小麦种子。
 */
public final class MinecraftWheatFarmingSkillNodeHandler
        implements SkillNodeHandler {
    private static final int MAXIMUM_ACTION_TICKS =
            WheatFarmingPlanCompiler.MAXIMUM_ACTION_TICKS;
    private static final int MAXIMUM_PICKUP_TICKS = 80;
    /**
     * Mature wheat broken with wheat seeds (no Fortune) may emit wheat plus up to
     * three seed entities.
     */
    private static final int MAXIMUM_WHEAT_DROP_ENTITIES = 4;
    private static final int MINIMUM_HARVEST_EMPTY_STORAGE_SLOTS =
            MAXIMUM_WHEAT_DROP_ENTITIES;
    private static final int MINIMUM_RAW_BRIGHTNESS = 8;
    private static final String RESERVATION_SCOPE =
            "minecraft.wheat_crop";
    private static final ResourceId WHEAT_SEEDS_ID =
            new ResourceId("minecraft:wheat_seeds");
    private static final ResourceId WHEAT_ID = new ResourceId("minecraft:wheat");
    private static final ResourceId FARMLAND_ID =
            new ResourceId("minecraft:farmland");

    private final Mode mode;
    private final Resolver resolver;
    private final ActionBackedSkillNodeHandler actionDelegate;
    private final ActionBackedSkillNodeHandler pickupActionDelegate;
    private final Map<UUID, PendingHarvestPickup> pendingHarvestPickups =
            new LinkedHashMap<>();
    private final Thread ownerThread;

    public MinecraftWheatFarmingSkillNodeHandler(
            Mode mode,
            Resolver resolver,
            ActionBackedSkillNodeHandler.ActionGateway actions,
            ActionBackedSkillNodeHandler.SignalSink signals) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        ActionBackedSkillNodeHandler.ActionGateway actionGateway =
                Objects.requireNonNull(actions, "actions");
        ActionBackedSkillNodeHandler.SignalSink signalSink =
                Objects.requireNonNull(signals, "signals");
        actionDelegate = new ActionBackedSkillNodeHandler(
                this::planAction,
                actionGateway,
                signalSink);
        pickupActionDelegate = new ActionBackedSkillNodeHandler(
                this::planHarvestPickup,
                actionGateway,
                signalSink);
        ownerThread = Thread.currentThread();
    }

    /**
     * 只锁一格方块，且在没有维度参数时故意跨维度保守冲突。两个节点间即使租约切换，
     * 补种前也会再次读取完整的耕地与空气状态，不能把短暂空档当作授权。
     */
    @Override
    public List<ReservationRequest> requiredReservations(
            SkillNodeContext context) {
        requireOwnerThread();
        Objects.requireNonNull(context, "context");
        BlockCoordinates target = parseTarget(context.node().parameters())
                .orElse(null);
        if (target == null) {
            return List.of();
        }
        return List.of(new ReservationRequest(
                new ReservationKey(
                        ReservationKey.Kind.BLOCK,
                        RESERVATION_SCOPE,
                        target.x() + "," + target.y() + "," + target.z()),
                ReservationMode.EXCLUSIVE));
    }

    @Override
    public SkillNodeDirective begin(SkillNodeContext context) {
        requireOwnerThread();
        Objects.requireNonNull(context, "context");
        if (!mode.matches(context)) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.INVALID_PARAMETERS,
                    "小麦农业节点与已注册技能身份不匹配");
        }
        if (parseTarget(context.node().parameters()).isEmpty()) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.INVALID_PARAMETERS,
                    "小麦农业节点参数不符合精确坐标 schema");
        }
        if (prepare(context).isEmpty()) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.WORLD_CHANGED,
                    "小麦农业的方块、耕地、光照、库存或原生菜单前置条件不成立");
        }
        /*
         * 在真正入 action mailbox 前再次 prepare，从而使第一次检查与提交之间的任何
         * crop/farmland/seed/menu 漂移都在无副作用路径上失败。
         */
        return actionDelegate.begin(context);
    }

    @Override
    public SkillNodeDirective signal(
            SkillNodeContext context, SkillSignal signal) {
        requireOwnerThread();
        if (pendingHarvestPickups.containsKey(context.runId())) {
            return pickupActionDelegate.signal(context, signal);
        }
        return actionDelegate.signal(context, signal);
    }

    /**
     * The action-runtime state machine cannot transition directly from one
     * {@code WAITING_ACTION} to another.  After a verified block break, first
     * return to {@code RUNNING}; this next server tick is the only point at
     * which the bounded UUID pickup action may be submitted.
     */
    @Override
    public SkillNodeDirective tick(SkillNodeContext context) {
        requireOwnerThread();
        Objects.requireNonNull(context, "context");
        PendingHarvestPickup pending = pendingHarvestPickups.get(
                context.runId());
        if (pending == null) {
            return SkillNodeDirective.continueRunning("小麦农业节点继续运行");
        }
        if (!pending.matches(context)) {
            pendingHarvestPickups.remove(context.runId(), pending);
            return SkillNodeDirective.fail(
                    SkillFailureCode.WORLD_CHANGED,
                    "小麦掉落拾取等待期间节点身份或目标已变化");
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
        pendingHarvestPickups.remove(context.runId());
        /* ActionBacked 会取消尚未完成的真实动作；不在取消路径伪造 crop 或 seed 回滚。 */
        actionDelegate.cancelled(context, reason);
        pickupActionDelegate.cancelled(context, reason);
    }

    private Optional<ActionBackedSkillNodeHandler.Operation> planAction(
            SkillNodeContext context) {
        Prepared prepared = prepare(context).orElse(null);
        if (prepared == null) {
            return Optional.empty();
        }
        return Optional.of(switch (prepared) {
            case HarvestPrepared harvest -> new ActionBackedSkillNodeHandler
                    .Operation(
                            "harvest-wheat",
                            new WorldInteractionAction(
                                    new WorldInteractionActionSpec
                                            .BreakBlock(
                                                    hit(harvest.crop()),
                                                    harvest.heldBefore())),
                            ActionPriority.SURVIVAL,
                            MAXIMUM_ACTION_TICKS,
                            SkillNodeDirective.Kind.WAIT_ACTION,
                            "等待原版成熟小麦破坏完成",
                            (signalContext, signal) -> verifyHarvest(
                                    harvest, signalContext, signal));
            case PlantPrepared plant -> new ActionBackedSkillNodeHandler
                    .Operation(
                            "plant-wheat",
                            new WorldInteractionAction(
                                    new WorldInteractionActionSpec
                                            .UseOnBlock(
                                                    WorldInteractionActionSpec
                                                            .Hand.MAIN_HAND,
                                                    hit(plant.farmland()),
                                                    plant.heldSeed())),
                            ActionPriority.SURVIVAL,
                            MAXIMUM_ACTION_TICKS,
                            SkillNodeDirective.Kind.WAIT_ACTION,
                            "等待原版小麦种子播种完成",
                            (signalContext, signal) -> verifyPlant(
                                    plant, signalContext, signal));
        });
    }

    /**
     * A harvest pickup never manufactures an item or scans an area. It only consumes
     * the finite provenance list frozen by the immediately preceding BREAK_BLOCK and
     * asks the existing action backend to wait for one of those exact entities to
     * collide with the player. The verifier proves that every receipt arrived.
     */
    private Optional<ActionBackedSkillNodeHandler.Operation> planHarvestPickup(
            SkillNodeContext context) {
        PendingHarvestPickup pending = pendingHarvestPickups.get(
                context.runId());
        if (pending == null || !pending.matches(context)) {
            throw new ActionBackedSkillNodeHandler.PlanningFailure(
                    SkillFailureCode.INTERNAL_ERROR,
                    "小麦掉落拾取开始时没有匹配的收获收据");
        }
        BotServerPlayer player = currentPlayer(pending.prepared().player(),
                pending.prepared().generation(), context).orElse(null);
        if (player == null || !harvestWorldStillSafe(pending.prepared(),
                player) || !hasNativeEmptyInventoryMenu(player)) {
            throw new ActionBackedSkillNodeHandler.PlanningFailure(
                    SkillFailureCode.WORLD_CHANGED,
                    "小麦掉落拾取开始时世界、Bot 或原生菜单已变化");
        }
        InventoryContentsSnapshot current = MinecraftActionSnapshot
                .inventoryContents(player);
        if (!pending.prepared().inventoryBefore().equals(current)) {
            throw new ActionBackedSkillNodeHandler.PlanningFailure(
                    SkillFailureCode.ITEM_CONSERVATION_VIOLATION,
                    "小麦掉落拾取入队前背包不再是收获前的精确快照");
        }
        if (!allDropsLiveAndPickupReachable(player, pending.manifest())) {
            throw new ActionBackedSkillNodeHandler.PlanningFailure(
                    SkillFailureCode.MISSING_ITEM,
                    "小麦掉落实体在原版 UUID 拾取前不可完整确认或不在拾取范围");
        }
        return Optional.of(new ActionBackedSkillNodeHandler.Operation(
                "pickup-wheat-drops",
                new WorldInteractionAction(
                        new WorldInteractionActionSpec.PickupWait(
                                MAXIMUM_PICKUP_TICKS,
                                Optional.of(pending.targetEntityId()))),
                ActionPriority.SURVIVAL,
                MAXIMUM_PICKUP_TICKS,
                SkillNodeDirective.Kind.WAIT_ACTION,
                "等待原版小麦掉落实体进入背包",
                (signalContext, signal) -> verifyHarvestPickup(
                        pending, signalContext, signal)));
    }

    private Optional<Prepared> prepare(SkillNodeContext context) {
        try {
            if (!mode.matches(context)) {
                return Optional.empty();
            }
            BlockCoordinates target = parseTarget(
                    context.node().parameters()).orElse(null);
            if (target == null) {
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
            BlockPos cropPosition = new BlockPos(
                    target.x(), target.y(), target.z());
            Level level = player.serverLevel();
            if (!withinBuildHeight(level, cropPosition)
                    || !level.isLoaded(cropPosition)
                    || !level.isLoaded(cropPosition.below())
                    || !player.canInteractWithBlock(cropPosition, 0.0D)) {
                return Optional.empty();
            }
            InventoryContentsSnapshot inventory =
                    MinecraftActionSnapshot.inventoryContents(player);
            InventoryMenuSnapshot menu =
                    MinecraftActionSnapshot.inventoryMenu(player);
            BlockTargetFingerprint farmland = MinecraftActionSnapshot.block(
                    player, cropPosition.below());
            if (!isFarmland(farmland)
                    || !wheatCanSurvive(level, cropPosition)) {
                return Optional.empty();
            }
            return switch (mode) {
                case HARVEST -> prepareHarvest(
                        player, cropPosition, farmland, inventory, menu);
                case PLANT -> preparePlant(
                        player, cropPosition, farmland, inventory, menu);
            };
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static Optional<Prepared> prepareHarvest(
            BotServerPlayer player,
            BlockPos cropPosition,
            BlockTargetFingerprint farmland,
            InventoryContentsSnapshot inventory,
            InventoryMenuSnapshot menu) {
        BlockTargetFingerprint crop = MinecraftActionSnapshot.block(
                player, cropPosition);
        BlockState state = player.serverLevel().getBlockState(cropPosition);
        if (!isMatureWheat(state, crop)) {
            return Optional.empty();
        }
        ItemStackFingerprint held = MinecraftActionSnapshot.selectedItem(
                player);
        if (!isPlainVanillaWheatSeeds(player, held)
                || !hasHarvestStorageCapacity(menu)) {
            return Optional.empty();
        }
        return Optional.of(new HarvestPrepared(
                player,
                player.runtimeHandle().generation(),
                cropPosition,
                crop,
                farmland,
                held,
                inventory,
                menu));
    }

    private static Optional<Prepared> preparePlant(
            BotServerPlayer player,
            BlockPos cropPosition,
            BlockTargetFingerprint farmland,
            InventoryContentsSnapshot inventory,
            InventoryMenuSnapshot menu) {
        if (!player.serverLevel().getBlockState(cropPosition).isAir()) {
            return Optional.empty();
        }
        ItemStackFingerprint heldSeed = MinecraftActionSnapshot.selectedItem(
                player);
        if (!isPlainVanillaWheatSeeds(player, heldSeed)
                || WheatFarmingInventoryConservation
                        .afterOneSeedConsumed(inventory, heldSeed)
                        .isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new PlantPrepared(
                player,
                player.runtimeHandle().generation(),
                cropPosition,
                farmland,
                heldSeed,
                inventory,
                menu));
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
                    "成熟小麦收获完成时 BotPlayer 或原生菜单已变化");
        }
        if (!harvestWorldStillSafe(prepared, player)) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.WORLD_CHANGED,
                    "成熟小麦收获后耕地、光照或目标状态无法确认");
        }
        HarvestDropManifest manifest = harvestDropManifest(signal, player)
                .orElse(null);
        if (manifest == null || !signalHasBreakEvidence(signal)) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.WORLD_CHANGED,
                    "成熟小麦收获回执缺少完整原版掉落实体收据");
        }
        InventoryContentsSnapshot expected = WheatFarmingInventoryConservation
                .afterCollectedDrops(prepared.inventoryBefore(),
                        manifest.expectedStacks())
                .orElse(null);
        if (expected == null) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.ITEM_CONSERVATION_VIOLATION,
                    "成熟小麦掉落无法在受限背包容量内形成精确守恒账本");
        }
        InventoryMenuSnapshot afterMenu = MinecraftActionSnapshot.inventoryMenu(
                player);
        InventoryContentsSnapshot afterInventory =
                MinecraftActionSnapshot.inventoryContents(player);
        if (!afterMenu.cursor().isEmpty()
                || afterMenu.selectedHotbar()
                        != prepared.menuBefore().selectedHotbar()) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.ITEM_CONSERVATION_VIOLATION,
                    "成熟小麦收获期间 cursor 或选中热栏发生未授权变化");
        }
        if (expected.equals(afterInventory)
                && allDropsRemoved(player, manifest)) {
            return SkillNodeDirective.complete(
                    "成熟小麦掉落已由原版进入背包并完成精确守恒核验");
        }
        if (!prepared.inventoryBefore().equals(afterInventory)) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.ITEM_CONSERVATION_VIOLATION,
                    "成熟小麦收获后的背包仅允许完整收据掉落，拒绝部分或额外漂移");
        }
        if (!allDropsLiveAndPickupReachable(player, manifest)) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.MISSING_ITEM,
                    "成熟小麦掉落实体不再完整可见或不在原版拾取范围");
        }
        PendingHarvestPickup pending = new PendingHarvestPickup(
                prepared,
                manifest,
                expected,
                manifest.wheatReceipt().entityId(),
                context.node().nodeId());
        if (pendingHarvestPickups.putIfAbsent(context.runId(), pending)
                != null) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.INTERNAL_ERROR,
                    "成熟小麦收获已经存在未完成的 UUID 掉落拾取");
        }
        return SkillNodeDirective.continueRunning(
                "成熟小麦掉落实体仍在原版拾取范围，下一 tick 提交 UUID 绑定拾取");
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
                            pending.targetEntityId())) {
                return SkillNodeDirective.fail(
                        SkillFailureCode.INTERNAL_ERROR,
                        "小麦掉落拾取回执不属于冻结的 UUID 收据");
            }
            BotServerPlayer player = currentPlayer(pending.prepared().player(),
                    pending.prepared().generation(), context).orElse(null);
            if (player == null || !hasNativeEmptyInventoryMenu(player)) {
                return SkillNodeDirective.fail(
                        SkillFailureCode.STALE_GENERATION,
                        "小麦掉落拾取完成时 BotPlayer 或原生菜单已变化");
            }
            if (!harvestWorldStillSafe(pending.prepared(), player)
                    || !allDropsRemoved(player, pending.manifest())) {
                return SkillNodeDirective.fail(
                        SkillFailureCode.WORLD_CHANGED,
                        "小麦掉落拾取后目标、耕地或收据实体未完整确认");
            }
            InventoryMenuSnapshot afterMenu = MinecraftActionSnapshot
                    .inventoryMenu(player);
            InventoryContentsSnapshot afterInventory =
                    MinecraftActionSnapshot.inventoryContents(player);
            if (!afterMenu.cursor().isEmpty()
                    || afterMenu.selectedHotbar()
                            != pending.prepared().menuBefore()
                                    .selectedHotbar()
                    || !pending.expectedInventoryAfter().equals(
                            afterInventory)) {
                return SkillNodeDirective.fail(
                        SkillFailureCode.ITEM_CONSERVATION_VIOLATION,
                        "小麦掉落拾取未能证明所有收据物精确进入背包");
            }
            return SkillNodeDirective.complete(
                    "成熟小麦掉落已由 UUID 绑定原版拾取进入背包");
        } finally {
            pendingHarvestPickups.remove(context.runId(), pending);
        }
    }

    private SkillNodeDirective verifyPlant(
            PlantPrepared prepared,
            SkillNodeContext context,
            SkillSignal signal) {
        BotServerPlayer player = currentPlayer(prepared.player(),
                prepared.generation(), context).orElse(null);
        if (player == null || !hasNativeEmptyInventoryMenu(player)) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.STALE_GENERATION,
                    "小麦补种完成时 BotPlayer 或原生菜单已变化");
        }
        Level level = player.serverLevel();
        if (!level.isLoaded(prepared.cropPosition())) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.WORLD_CHANGED,
                    "小麦补种后目标区块不再可安全读取");
        }
        BlockState crop = level.getBlockState(prepared.cropPosition());
        BlockTargetFingerprint cropFingerprint = MinecraftActionSnapshot.block(
                player, prepared.cropPosition());
        if (!isNewlyPlantedWheat(crop, cropFingerprint)
                || !prepared.farmland().equals(MinecraftActionSnapshot.block(
                        player, prepared.cropPosition().below()))
                || !wheatCanSurvive(level, prepared.cropPosition())) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.WORLD_CHANGED,
                    "小麦补种后作物、耕地或光照状态无法确认");
        }
        InventoryContentsSnapshot expected =
                WheatFarmingInventoryConservation.afterOneSeedConsumed(
                        prepared.inventoryBefore(), prepared.heldSeed())
                        .orElse(null);
        InventoryMenuSnapshot afterMenu = MinecraftActionSnapshot.inventoryMenu(
                player);
        InventoryContentsSnapshot afterInventory =
                MinecraftActionSnapshot.inventoryContents(player);
        ItemStackFingerprint expectedSelected =
                WheatFarmingInventoryConservation.selectedAfterOneSeedConsumed(
                        prepared.heldSeed());
        if (expected == null
                || !afterMenu.cursor().isEmpty()
                || afterMenu.selectedHotbar()
                        != prepared.menuBefore().selectedHotbar()
                || !expectedSelected.equals(
                        MinecraftActionSnapshot.selectedItem(player))
                || !expected.equals(afterInventory)) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.ITEM_CONSERVATION_VIOLATION,
                    "小麦补种未能证明仅消耗一枚当前主手种子");
        }
        return signalHasUseOnEvidence(signal)
                ? SkillNodeDirective.complete(
                        "已由原版补种一格小麦并核验精确种子消耗")
                : SkillNodeDirective.fail(
                        SkillFailureCode.WORLD_CHANGED,
                        "小麦补种回执缺少原版方块交互证据");
    }

    private Optional<BotServerPlayer> currentPlayer(
            BotServerPlayer expected,
            long expectedGeneration,
            SkillNodeContext context) {
        BotServerPlayer current = resolver.resolve(
                context.botId(), context.botGeneration()).orElse(null);
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
        return level.isLoaded(prepared.cropPosition())
                && level.getBlockState(prepared.cropPosition()).isAir()
                && prepared.farmland().equals(MinecraftActionSnapshot.block(
                        player, prepared.cropPosition().below()))
                && wheatCanSurvive(level, prepared.cropPosition());
    }

    /**
     * Decodes the strict bounded break-drop protocol.  One original drop retains
     * the legacy ordered triple for P5A compatibility; two or more drops use one
     * compact receipt per entity so a four-entity wheat break remains below the
     * ActionOutcome evidence cap. A generic BREAK_BLOCK success without either
     * exact protocol is not a farming success.
     */
    private static Optional<HarvestDropManifest> harvestDropManifest(
            SkillSignal signal, BotServerPlayer player) {
        Objects.requireNonNull(signal, "signal");
        Objects.requireNonNull(player, "player");
        List<BreakDropProvenanceCapture.Provenance> provenances =
                harvestDropProvenances(signal).orElse(null);
        if (provenances == null
                || provenances.isEmpty()
                || provenances.size() > MAXIMUM_WHEAT_DROP_ENTITIES) {
            return Optional.empty();
        }
        List<HarvestDropReceipt> receipts = new ArrayList<>(
                provenances.size());
        Set<UUID> entityIds = new LinkedHashSet<>();
        int wheat = 0;
        int seeds = 0;
        for (BreakDropProvenanceCapture.Provenance provenance :
                provenances) {
            UUID entityId = provenance.entityId();
            ResourceId itemId = new ResourceId(provenance.itemId());
            int itemCount = provenance.count();
            if (isZero(entityId) || !entityIds.add(entityId)
                    || itemCount < 1 || itemCount > 64
                    || (!itemId.equals(WHEAT_ID)
                    && !itemId.equals(WHEAT_SEEDS_ID))) {
                return Optional.empty();
            }
            if (itemId.equals(WHEAT_ID)) {
                wheat = safeAdd(wheat, itemCount).orElse(-1);
            } else {
                seeds = safeAdd(seeds, itemCount).orElse(-1);
            }
            if (wheat < 0 || seeds < 0) {
                return Optional.empty();
            }
            ItemStackFingerprint fingerprint = MinecraftActionSnapshot.item(
                    player,
                    new ItemStack(itemId.equals(WHEAT_ID)
                            ? Items.WHEAT
                            : Items.WHEAT_SEEDS, itemCount));
            receipts.add(new HarvestDropReceipt(entityId, fingerprint));
        }
        /*
         * Wheat is broken with plain wheat seeds, so vanilla's mature CropBlock
         * contract is exactly one wheat and at most three bonus seed entities.
         * Reject silk/fortune-like or modded loot rather than widening this slice.
         */
        return wheat == 1 && seeds >= 0 && seeds <= 3
                ? Optional.of(new HarvestDropManifest(receipts))
                : Optional.empty();
    }

    private static Optional<List<BreakDropProvenanceCapture.Provenance>>
            harvestDropProvenances(SkillSignal signal) {
        Objects.requireNonNull(signal, "signal");
        return harvestDropProvenances(signal.evidence());
    }

    static Optional<List<BreakDropProvenanceCapture.Provenance>>
            harvestDropProvenances(List<ActionEvidence> evidence) {
        Objects.requireNonNull(evidence, "evidence");
        List<ActionEvidence> compact = new ArrayList<>();
        List<ActionEvidence> legacy = new ArrayList<>();
        for (ActionEvidence item : evidence) {
            if (item == null) {
                return Optional.empty();
            }
            if (item.key().equals(
                    BreakDropProvenanceCapture
                            .COMPACT_RECEIPT_EVIDENCE_KEY)) {
                compact.add(item);
            } else if (item.key().equals("block.drop.entity.id")
                    || item.key().equals("block.drop.item")
                    || item.key().equals("block.drop.count")) {
                legacy.add(item);
            } else if (item.key().startsWith("block.drop.")) {
                return Optional.empty();
            }
        }
        if (!compact.isEmpty()) {
            if (!legacy.isEmpty()
                    || compact.size() < 2
                    || compact.size() > MAXIMUM_WHEAT_DROP_ENTITIES) {
                return Optional.empty();
            }
            List<BreakDropProvenanceCapture.Provenance> receipts =
                    new ArrayList<>(compact.size());
            for (ActionEvidence receiptEvidence : compact) {
                BreakDropProvenanceCapture.Provenance receipt =
                        BreakDropProvenanceCapture.parseCompactReceipt(
                                receiptEvidence.value()).orElse(null);
                if (receipt == null) {
                    return Optional.empty();
                }
                receipts.add(receipt);
            }
            return Optional.of(List.copyOf(receipts));
        }
        if (legacy.size() != 3) {
            return Optional.empty();
        }
        ActionEvidence entity = legacy.get(0);
        ActionEvidence item = legacy.get(1);
        ActionEvidence count = legacy.get(2);
        if (!entity.key().equals("block.drop.entity.id")
                || !item.key().equals("block.drop.item")
                || !count.key().equals("block.drop.count")) {
            return Optional.empty();
        }
        try {
            UUID entityId = UUID.fromString(entity.value());
            ResourceId itemId = new ResourceId(item.value());
            int itemCount = Integer.parseInt(count.value());
            if (!entityId.toString().equals(entity.value())
                    || !Integer.toString(itemCount).equals(count.value())) {
                return Optional.empty();
            }
            return Optional.of(List.of(
                    new BreakDropProvenanceCapture.Provenance(entityId,
                            itemId.value(), itemCount)));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static Optional<Integer> safeAdd(int left, int right) {
        try {
            return Optional.of(Math.addExact(left, right));
        } catch (ArithmeticException exception) {
            return Optional.empty();
        }
    }

    private static boolean allDropsLiveAndPickupReachable(
            BotServerPlayer player, HarvestDropManifest manifest) {
        HarvestDropReceipt wheatReceipt = manifest.wheatReceipt();
        Entity target = player.serverLevel().getEntity(
                wheatReceipt.entityId());
        if (!(target instanceof ItemEntity targetItem)
                || target.isRemoved()
                || !wheatReceipt.expectedStack().equals(
                        MinecraftActionSnapshot.item(player,
                                targetItem.getItem()))
                || !player.canInteractWithEntity(targetItem, 1.0D)) {
            return false;
        }
        int observedWheat = 0;
        int observedSeeds = 0;
        for (HarvestDropReceipt receipt : manifest.receipts()) {
            Entity entity = player.serverLevel().getEntity(
                    receipt.entityId());
            if (entity == null || entity.isRemoved()) {
                /* Same-item drops may have merged into another receipt entity. */
                continue;
            }
            if (!(entity instanceof ItemEntity itemEntity)
                    || !player.canInteractWithEntity(itemEntity, 1.0D)) {
                return false;
            }
            ItemStackFingerprint current = MinecraftActionSnapshot.item(
                    player, itemEntity.getItem());
            if (!current.sameItemAndComponents(receipt.expectedStack())) {
                return false;
            }
            if (current.itemId().filter(WHEAT_ID::equals).isPresent()) {
                observedWheat = safeAdd(observedWheat, current.count())
                        .orElse(-1);
            } else if (current.itemId().filter(WHEAT_SEEDS_ID::equals)
                    .isPresent()) {
                observedSeeds = safeAdd(observedSeeds, current.count())
                        .orElse(-1);
            } else {
                return false;
            }
            if (observedWheat < 0 || observedSeeds < 0) {
                return false;
            }
        }
        return observedWheat == manifest.wheatCount()
                && observedSeeds == manifest.seedCount();
    }

    private static boolean allDropsRemoved(
            BotServerPlayer player, HarvestDropManifest manifest) {
        for (HarvestDropReceipt receipt : manifest.receipts()) {
            Entity entity = player.serverLevel().getEntity(
                    receipt.entityId());
            if (entity != null && !entity.isRemoved()) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasHarvestStorageCapacity(
            InventoryMenuSnapshot menu) {
        int emptyStorageSlots = 0;
        for (int inventorySlot = 0;
                inventorySlot <= 35;
                inventorySlot++) {
            if (menu.itemAt(inventorySlot).isEmpty()) {
                emptyStorageSlots++;
            }
        }
        return emptyStorageSlots >= MINIMUM_HARVEST_EMPTY_STORAGE_SLOTS;
    }

    private static boolean isZero(UUID value) {
        return new UUID(0L, 0L).equals(value);
    }

    private static boolean signalHasBreakEvidence(SkillSignal signal) {
        return hasEvidence(signal, "block.after", "minecraft:air")
                && hasEvidence(signal, "block.position", null);
    }

    private static boolean signalHasUseOnEvidence(SkillSignal signal) {
        return hasEvidence(signal, "block.position", null)
                && hasEvidence(signal, "inventory.changed", "true");
    }

    private static boolean signalHasPickupEvidence(
            SkillSignal signal, UUID targetEntityId) {
        return hasEvidence(signal, "entity.id", targetEntityId.toString())
                && hasEvidence(signal, "item.expected_count", "1")
                && hasPositiveIntegerEvidence(signal, "item.gained_count");
    }

    private static boolean hasEvidence(
            SkillSignal signal, String key, String requiredValue) {
        return signal.evidence().stream().anyMatch(evidence ->
                evidence.key().equals(key)
                        && (requiredValue == null
                        || evidence.value().equals(requiredValue)));
    }

    private static boolean hasPositiveIntegerEvidence(
            SkillSignal signal, String key) {
        for (ActionEvidence evidence : signal.evidence()) {
            if (!evidence.key().equals(key)) {
                continue;
            }
            try {
                return Integer.parseInt(evidence.value()) > 0;
            } catch (IllegalArgumentException exception) {
                return false;
            }
        }
        return false;
    }

    private static boolean hasNativeEmptyInventoryMenu(BotServerPlayer player) {
        return player.containerMenu == player.inventoryMenu
                && player.inventoryMenu.getCarried().isEmpty();
    }

    private static boolean withinBuildHeight(Level level, BlockPos position) {
        return position.getY() >= level.getMinBuildHeight()
                && position.getY() < level.getMaxBuildHeight()
                && position.getY() - 1 >= level.getMinBuildHeight();
    }

    private static boolean isMatureWheat(
            BlockState state, BlockTargetFingerprint fingerprint) {
        return state.is(Blocks.WHEAT)
                && state.hasProperty(CropBlock.AGE)
                && state.getValue(CropBlock.AGE) == Blocks.WHEAT.getMaxAge()
                && fingerprint.state().blockId().equals(WHEAT_ID)
                && fingerprint.state().properties().equals(Map.of(
                        "age", Integer.toString(Blocks.WHEAT.getMaxAge())));
    }

    private static boolean isNewlyPlantedWheat(
            BlockState state, BlockTargetFingerprint fingerprint) {
        return state.is(Blocks.WHEAT)
                && state.hasProperty(CropBlock.AGE)
                && state.getValue(CropBlock.AGE) == 0
                && fingerprint.state().blockId().equals(WHEAT_ID)
                && fingerprint.state().properties().equals(Map.of("age", "0"));
    }

    private static boolean isFarmland(BlockTargetFingerprint fingerprint) {
        return fingerprint.state().blockId().equals(FARMLAND_ID);
    }

    private static boolean wheatCanSurvive(Level level, BlockPos cropPosition) {
        return level.getRawBrightness(cropPosition, 0)
                        >= MINIMUM_RAW_BRIGHTNESS
                && Blocks.WHEAT.defaultBlockState().canSurvive(
                        level, cropPosition);
    }

    private static boolean isWheatSeeds(ItemStackFingerprint held) {
        return !held.isEmpty()
                && held.itemId().filter(WHEAT_SEEDS_ID::equals).isPresent();
    }

    /**
     * Do not let an arbitrary component-bearing stack of the same item id widen
     * the vanilla loot contract (for example through a synthetic enchantment).
     * This reads only a canonical immutable fingerprint; it never replaces the
     * held stack.
     */
    private static boolean isPlainVanillaWheatSeeds(
            BotServerPlayer player, ItemStackFingerprint held) {
        return isWheatSeeds(held)
                && held.sameItemAndComponents(MinecraftActionSnapshot.item(
                        player, new ItemStack(Items.WHEAT_SEEDS, 1)));
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
        Object x = values.get(WheatFarmingSkillIds.TARGET_X_PARAMETER);
        Object y = values.get(WheatFarmingSkillIds.TARGET_Y_PARAMETER);
        Object z = values.get(WheatFarmingSkillIds.TARGET_Z_PARAMETER);
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

    private void requireOwnerThread() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException(
                    "wheat farming skill node handler requires server thread");
        }
    }

    /** 生命周期或测试只暴露 generation 绑定的当前 body，不缓存活动 Minecraft 对象。 */
    @FunctionalInterface
    public interface Resolver {
        Optional<BotServerPlayer> resolve(UUID botId, long generation);
    }

    public enum Mode {
        HARVEST(WheatFarmingSkillIds.HARVEST_MATURE_WHEAT),
        PLANT(WheatFarmingSkillIds.PLANT_WHEAT);

        private final io.github.greytaiwolf.botplayer.skill.core.SkillId skillId;

        Mode(io.github.greytaiwolf.botplayer.skill.core.SkillId skillId) {
            this.skillId = skillId;
        }

        private boolean matches(SkillNodeContext context) {
            return context.node().skillId().equals(skillId)
                    && context.node().skillVersion().equals(
                            WheatFarmingSkillIds.VERSION);
        }
    }

    /** Exact immutable receipt for one item entity from the synchronous wheat break. */
    private record HarvestDropReceipt(
            UUID entityId, ItemStackFingerprint expectedStack) {
        private HarvestDropReceipt {
            if (isZero(Objects.requireNonNull(entityId, "entityId"))) {
                throw new IllegalArgumentException(
                        "harvest drop entity id must be non-zero");
            }
            expectedStack = Objects.requireNonNull(expectedStack,
                    "expectedStack");
            if (expectedStack.isEmpty()
                    || (!expectedStack.itemId().filter(WHEAT_ID::equals)
                            .isPresent()
                    && !expectedStack.itemId().filter(
                            WHEAT_SEEDS_ID::equals).isPresent())) {
                throw new IllegalArgumentException(
                        "harvest drop must be vanilla wheat or wheat seeds");
            }
        }
    }

    /**
     * A bounded mature-wheat loot manifest. It lives only while one harvest node is
     * running; it is not serialized into a plan or checkpoint.
     */
    private record HarvestDropManifest(List<HarvestDropReceipt> receipts) {
        private HarvestDropManifest {
            receipts = List.copyOf(Objects.requireNonNull(receipts,
                    "receipts"));
            if (receipts.isEmpty()
                    || receipts.size() > MAXIMUM_WHEAT_DROP_ENTITIES) {
                throw new IllegalArgumentException(
                        "harvest drop receipt count is outside its bound");
            }
            Set<UUID> unique = new LinkedHashSet<>();
            int wheat = 0;
            int seeds = 0;
            for (HarvestDropReceipt receipt : receipts) {
                HarvestDropReceipt required = Objects.requireNonNull(receipt,
                        "harvest drop receipt");
                if (!unique.add(required.entityId())) {
                    throw new IllegalArgumentException(
                            "harvest drop receipts must have unique entity ids");
                }
                int count = required.expectedStack().count();
                if (required.expectedStack().itemId().filter(
                        WHEAT_ID::equals).isPresent()) {
                    wheat = Math.addExact(wheat, count);
                } else {
                    seeds = Math.addExact(seeds, count);
                }
            }
            if (wheat != 1 || seeds < 0 || seeds > 3) {
                throw new IllegalArgumentException(
                        "harvest drop manifest violates mature wheat loot bounds");
            }
        }

        private List<ItemStackFingerprint> expectedStacks() {
            return receipts.stream().map(HarvestDropReceipt::expectedStack)
                    .toList();
        }

        private HarvestDropReceipt wheatReceipt() {
            return receipts.stream().filter(receipt -> receipt.expectedStack()
                            .itemId().filter(WHEAT_ID::equals).isPresent())
                    .findFirst().orElseThrow();
        }

        private int wheatCount() {
            return receipts.stream().filter(receipt -> receipt.expectedStack()
                            .itemId().filter(WHEAT_ID::equals).isPresent())
                    .mapToInt(receipt -> receipt.expectedStack().count())
                    .sum();
        }

        private int seedCount() {
            return receipts.stream().filter(receipt -> receipt.expectedStack()
                            .itemId().filter(WHEAT_SEEDS_ID::equals)
                                    .isPresent())
                    .mapToInt(receipt -> receipt.expectedStack().count())
                    .sum();
        }
    }

    /**
     * Pickup-only transition state. It retains no dropped {@link ItemEntity} or
     * {@link Level}; each step resolves the current bot body and receipt UUIDs
     * again before acting.
     */
    private record PendingHarvestPickup(
            HarvestPrepared prepared,
            HarvestDropManifest manifest,
            InventoryContentsSnapshot expectedInventoryAfter,
            UUID targetEntityId,
            UUID nodeId) {
        private PendingHarvestPickup {
            prepared = Objects.requireNonNull(prepared, "prepared");
            manifest = Objects.requireNonNull(manifest, "manifest");
            expectedInventoryAfter = Objects.requireNonNull(
                    expectedInventoryAfter, "expectedInventoryAfter");
            if (isZero(Objects.requireNonNull(targetEntityId,
                    "targetEntityId"))
                    || manifest.receipts().stream().noneMatch(receipt ->
                    receipt.entityId().equals(targetEntityId))) {
                throw new IllegalArgumentException(
                        "pickup target must belong to the harvest receipt manifest");
            }
            nodeId = Objects.requireNonNull(nodeId, "nodeId");
        }

        private boolean matches(SkillNodeContext context) {
            return prepared.player().getUUID().equals(context.botId())
                    && prepared.generation() == context.botGeneration()
                    && nodeId.equals(context.node().nodeId())
                    && Mode.HARVEST.matches(context);
        }
    }

    private sealed interface Prepared permits HarvestPrepared, PlantPrepared {
        BotServerPlayer player();

        long generation();

        BlockPos cropPosition();

        BlockTargetFingerprint farmland();

        InventoryContentsSnapshot inventoryBefore();

        InventoryMenuSnapshot menuBefore();
    }

    private record HarvestPrepared(
            BotServerPlayer player,
            long generation,
            BlockPos cropPosition,
            BlockTargetFingerprint crop,
            BlockTargetFingerprint farmland,
            ItemStackFingerprint heldBefore,
            InventoryContentsSnapshot inventoryBefore,
            InventoryMenuSnapshot menuBefore) implements Prepared {
        private HarvestPrepared {
            requirePrepared(player, generation, cropPosition, farmland,
                    inventoryBefore, menuBefore);
            crop = Objects.requireNonNull(crop, "crop");
            heldBefore = Objects.requireNonNull(heldBefore, "heldBefore");
            if (!isWheatSeeds(heldBefore)
                    || !hasHarvestStorageCapacity(menuBefore)) {
                throw new IllegalArgumentException(
                        "harvest preparation requires seeds and bounded storage capacity");
            }
        }
    }

    private record PlantPrepared(
            BotServerPlayer player,
            long generation,
            BlockPos cropPosition,
            BlockTargetFingerprint farmland,
            ItemStackFingerprint heldSeed,
            InventoryContentsSnapshot inventoryBefore,
            InventoryMenuSnapshot menuBefore) implements Prepared {
        private PlantPrepared {
            requirePrepared(player, generation, cropPosition, farmland,
                    inventoryBefore, menuBefore);
            heldSeed = Objects.requireNonNull(heldSeed, "heldSeed");
            if (!isWheatSeeds(heldSeed)) {
                throw new IllegalArgumentException(
                        "plant preparation requires exact wheat seeds");
            }
        }
    }

    private static void requirePrepared(
            BotServerPlayer player,
            long generation,
            BlockPos cropPosition,
            BlockTargetFingerprint farmland,
            InventoryContentsSnapshot inventoryBefore,
            InventoryMenuSnapshot menuBefore) {
        Objects.requireNonNull(player, "player");
        if (generation <= 0L) {
            throw new IllegalArgumentException(
                    "wheat farming generation must be positive");
        }
        Objects.requireNonNull(cropPosition, "cropPosition");
        Objects.requireNonNull(farmland, "farmland");
        Objects.requireNonNull(inventoryBefore, "inventoryBefore");
        Objects.requireNonNull(menuBefore, "menuBefore");
        if (!menuBefore.cursor().isEmpty()) {
            throw new IllegalArgumentException(
                    "wheat farming requires an empty native inventory cursor");
        }
    }

    private static final class SetHolder {
        private static final java.util.Set<String> TARGET_PARAMETER_NAMES =
                java.util.Set.of(
                        WheatFarmingSkillIds.TARGET_X_PARAMETER,
                        WheatFarmingSkillIds.TARGET_Y_PARAMETER,
                        WheatFarmingSkillIds.TARGET_Z_PARAMETER);

        private SetHolder() {
        }
    }
}
