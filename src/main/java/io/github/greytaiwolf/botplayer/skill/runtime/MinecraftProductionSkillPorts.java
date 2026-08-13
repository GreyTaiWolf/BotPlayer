package io.github.greytaiwolf.botplayer.skill.runtime;

import io.github.greytaiwolf.botplayer.BotPlayer;
import io.github.greytaiwolf.botplayer.action.WorldInteractionAction;
import io.github.greytaiwolf.botplayer.action.interaction.BlockCoordinates;
import io.github.greytaiwolf.botplayer.action.interaction.BlockHitTarget;
import io.github.greytaiwolf.botplayer.action.interaction.BlockStateFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.BlockTargetFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import io.github.greytaiwolf.botplayer.action.interaction.WorldInteractionActionSpec;
import io.github.greytaiwolf.botplayer.action.interaction.menu.P5ARecipe;
import io.github.greytaiwolf.botplayer.action.minecraft.MinecraftActionSnapshot;
import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
import io.github.greytaiwolf.botplayer.navigation.GridPoint;
import io.github.greytaiwolf.botplayer.skill.builtin.production.ProductionLedger;
import io.github.greytaiwolf.botplayer.skill.builtin.production.ProductionMaterial;
import io.github.greytaiwolf.botplayer.skill.builtin.production.ProductionMaterials;
import io.github.greytaiwolf.botplayer.skill.builtin.production.ProductionOperation;
import io.github.greytaiwolf.botplayer.skill.builtin.production.PlaceWorkstation;
import io.github.greytaiwolf.botplayer.skill.builtin.production.RecipeExecution;
import io.github.greytaiwolf.botplayer.skill.builtin.production.ResourceAcquisition;
import io.github.greytaiwolf.botplayer.skill.builtin.production.SingleChestTransfer;
import io.github.greytaiwolf.botplayer.skill.builtin.production.WorkstationKind;
import io.github.greytaiwolf.botplayer.skill.core.SkillParameters;
import io.github.greytaiwolf.botplayer.skill.core.SkillSignal;
import io.github.greytaiwolf.botplayer.skill.menu.MenuFamily;
import io.github.greytaiwolf.botplayer.skill.menu.MenuTransactionLimits;
import io.github.greytaiwolf.botplayer.skill.runtime.MinecraftProductionSkillNodeHandler.ActiveBot;
import io.github.greytaiwolf.botplayer.skill.runtime.MinecraftProductionSkillNodeHandler.ApprovedOperation;
import io.github.greytaiwolf.botplayer.skill.runtime.MinecraftProductionSkillNodeHandler.CompletionObservation;
import io.github.greytaiwolf.botplayer.skill.runtime.MinecraftProductionSkillNodeHandler.ExecutionTicket;
import io.github.greytaiwolf.botplayer.skill.runtime.MinecraftProductionSkillNodeHandler.MenuActionPort;
import io.github.greytaiwolf.botplayer.skill.runtime.MinecraftProductionSkillNodeHandler.PreflightObservation;
import io.github.greytaiwolf.botplayer.skill.runtime.MinecraftProductionSkillNodeHandler.ProductionAction;
import io.github.greytaiwolf.botplayer.skill.runtime.MinecraftProductionSkillNodeHandler.ProductionObservationPort;
import io.github.greytaiwolf.botplayer.skill.runtime.MinecraftProductionSkillNodeHandler.ResourceAcquisitionActionPort;
import io.github.greytaiwolf.botplayer.skill.runtime.MinecraftProductionSkillNodeHandler.ResourceDropCandidate;
import io.github.greytaiwolf.botplayer.skill.runtime.MinecraftProductionSkillNodeHandler.ResourceDropObservation;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillNodeContext;
import io.github.greytaiwolf.botplayer.skill.task.MinecraftTaskSensorAdapter;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorAvailability;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorBudget;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorEvidence;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorQuery;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorQueryType;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorResourceFilter;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorResponse;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorRunIdentity;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorScope;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorService;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorSnapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.phys.Vec3;

/**
 * P5A 生产 DAG 的 Minecraft 观察、采集和配方动作端口。
 *
 * <p>这是 production handler 允许接触原版活动对象的唯一适配器。它不直接写 Inventory、
 * 方块、block entity 或 menu：库存、菜单和资源候选都先经 {@link TaskSensorService} 的当前
 * run identity/额度，再把一次冻结的 {@link WorldInteractionAction} 交给 P2 action backend。
 * 工作站放置、右键工作台/炉子和菜单 {@code clicked()} 都由后端在各自受控 action 内执行；本类
 * 只冻结不可变指纹并在完成后重新观察。
 *
 * <p>binding 字符串只是有界、不可伪造的索引；实际语义保存在服务器线程私有的不可变
 * {@link FrozenBinding} 中。它不会持有 player、level、menu、block entity 或 ItemStack。
 * 第二次 preflight（ActionBacked 真实提交前）会覆盖同一个 binding id，因此预检与提交之间
 * 的任意方块、菜单或手持物漂移都会在动作创建前拒绝。
 */
public final class MinecraftProductionSkillPorts
        implements ProductionObservationPort,
        ResourceAcquisitionActionPort,
        MenuActionPort {
    /** 仅扫描 Bot 身边的有限 cube；不会加载未加载区块。 */
    static final int RESOURCE_RADIUS = 8;
    static final int RESOURCE_MAX_CANDIDATES = 24;
    static final int RESOURCE_MAX_BLOCKS = 256;
    static final int RESOURCE_MAX_EVIDENCE = 24;
    /** 破块前后只观察 source 周围 2 格内至多 12 枚掉落实体，不能退化为广域扫描。 */
    static final int RESOURCE_DROP_RADIUS = 2;
    static final int RESOURCE_DROP_MAX_CANDIDATES = 12;
    static final int RESOURCE_DROP_MAX_EVIDENCE = 12;
    static final int MAXIMUM_RESOURCE_DROP_PICKUP_TICKS = 80;
    static final int INVENTORY_SLOTS = 41;
    static final int INVENTORY_EVIDENCE = INVENTORY_SLOTS + 1;
    static final int NATIVE_MENU_SLOTS = MenuFamily.INVENTORY_2X2.slotCount();
    static final int NATIVE_MENU_EVIDENCE = NATIVE_MENU_SLOTS + 2;
    static final int MAX_FROZEN_BINDINGS = 1_024;
    static final int MAXIMUM_RESOURCE_ACTION_TICKS = 1_200;
    static final int MAXIMUM_RECIPE_ACTION_TICKS = 1_200;
    /** 仅寻找当前身体附近可达的原版放置位；跨越这个半径须由独立导航切片完成。 */
    static final int WORKSTATION_PLACEMENT_RADIUS = 3;
    static final int MAXIMUM_WORKSTATION_PLACEMENT_BLOCKS = 64;
    static final int MAXIMUM_WORKSTATION_PLACEMENT_ACTION_TICKS = 240;

    private static final String MENU_BINDING_PREFIX = "native-inventory:";
    private static final Map<String, ProductionMaterial> MATERIALS_BY_ID =
            materialsById();
    private static final Set<String> INVENTORY_ITEM_FIELDS = Set.of(
            "slot", "empty", "count");
    private static final Set<String> INVENTORY_NON_EMPTY_ITEM_FIELDS = Set.of(
            "slot", "empty", "count", "item", "damage", "digest");
    private static final Set<String> MENU_HEADER_FIELDS = Set.of(
            "family", "container.id", "state.id", "slot.count");
    private static final Set<String> MENU_CARRIED_EMPTY_FIELDS = Set.of(
            "empty", "count");
    private static final Set<String> RESOURCE_CANDIDATE_FIELDS = Set.of(
            "x", "y", "z", "block");
    private static final Set<String> DROPPED_ITEM_CANDIDATE_FIELDS = Set.of(
            "entity.id", "item", "count", "x", "y", "z");

    private final PlayerResolver players;
    private final TaskSensorService taskSensors;
    private final MinecraftTaskSensorAdapter taskSensorAdapter;
    private final Thread ownerThread;
    private final LinkedHashMap<String, FrozenBinding> bindings =
            new LinkedHashMap<>();

    /**
     * @param players lifecycle 提供的唯一 generation-bound ACTIVE body 解析器
     * @param taskSensors 与 production handler 注入的同一受限 TaskSensor 服务
     * @param taskSensorAdapter 上述服务唯一允许使用的 Minecraft sampler
     */
    public MinecraftProductionSkillPorts(
            PlayerResolver players,
            TaskSensorService taskSensors,
            MinecraftTaskSensorAdapter taskSensorAdapter) {
        this.players = Objects.requireNonNull(players, "players");
        this.taskSensors = Objects.requireNonNull(
                taskSensors, "taskSensors");
        this.taskSensorAdapter = Objects.requireNonNull(
                taskSensorAdapter, "taskSensorAdapter");
        ownerThread = Thread.currentThread();
    }

    @Override
    public Optional<PreflightObservation> observeForDispatch(
            ActiveBot bot,
            SkillNodeContext context,
            ApprovedOperation approved) {
        requireOwnerThread();
        Objects.requireNonNull(bot, "bot");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(approved, "approved");
        if (!bot.botId().equals(context.botId())
                || bot.generation() != context.botGeneration()) {
            return Optional.empty();
        }
        BotServerPlayer player = resolveCurrent(bot).orElse(null);
        if (player == null || !currentTick(player, context.currentTick())) {
            return Optional.empty();
        }
        NativeBaseline baseline = observeNativeBaseline(player, context)
                .orElse(null);
        if (baseline == null) {
            return Optional.empty();
        }
        ProductionOperation operation = approved.resolved().node().operation();
        String bindingId = bindingId(context, approved.operationId());
        FrozenBinding binding;
        ProductionPreconditionSnapshotBuilder snapshotBuilder =
                new ProductionPreconditionSnapshotBuilder(baseline);
        if (operation instanceof ResourceAcquisition acquisition) {
            Candidate candidate = selectCandidate(
                    player,
                    context,
                    expectedResourceBlock(acquisition),
                    true)
                    .orElse(null);
            if (candidate == null) {
                return Optional.empty();
            }
            Set<UUID> preexistingDropIds = observedDropIds(
                    player, context, candidate.target()).orElse(null);
            if (preexistingDropIds == null) {
                return Optional.empty();
            }
            binding = new ResourceBinding(
                    bot,
                    approved.operationId(),
                    context.currentTick(),
                    baseline,
                    candidate.target(),
                    preexistingDropIds);
            snapshotBuilder.noOpenMenu();
        } else if (operation instanceof RecipeExecution recipeExecution) {
            P5ARecipe recipe = approvedRecipe(recipeExecution,
                    approved).orElse(null);
            if (recipe == null) {
                return Optional.empty();
            }
            if (recipe.family() == MenuFamily.INVENTORY_2X2) {
                binding = new NativeRecipeBinding(
                        bot,
                        approved.operationId(),
                        context.currentTick(),
                        baseline,
                        recipe);
            } else {
                Candidate candidate = selectCandidate(
                        player,
                        context,
                        expectedWorkstationBlock(recipe.family())).orElse(null);
                if (candidate == null) {
                    return Optional.empty();
                }
                binding = new WorldRecipeBinding(
                        bot,
                        approved.operationId(),
                        context.currentTick(),
                        baseline,
                        recipe,
                        candidate.target());
            }
            /*
             * 动作会在同一受控 transaction 内从 native menu 打开工作台/炉子。这里故意
             * 保留真实的 inventory_2x2 基线；handler 会把这个经过端口证明的基线映射为
             * 对应的 menu contract，而不会要求玩家预先打开世界菜单。
             */
            snapshotBuilder.nativeInventoryMenu();
        } else if (operation instanceof PlaceWorkstation placement) {
            WorkstationPlacement placementTarget = selectWorkstationPlacement(
                    player, placement).orElse(null);
            ItemStackFingerprint held = MinecraftActionSnapshot.item(
                    player, player.getMainHandItem());
            if (placementTarget == null
                    || !matchesWorkstationHeldItem(placement, held)) {
                return Optional.empty();
            }
            binding = new WorkstationPlacementBinding(
                    bot,
                    approved.operationId(),
                    context.currentTick(),
                    baseline,
                    placement,
                    placementTarget.anchor(),
                    placementTarget.targetAir(),
                    placementTarget.expectedPlaced(),
                    held);
            snapshotBuilder.noOpenMenu();
        } else if (operation instanceof SingleChestTransfer) {
            // canonical wood-to-iron DAG 当前没有 chest operation；不得猜坐标或容器。
            return Optional.empty();
        } else {
            return Optional.empty();
        }
        freeze(bindingId, binding);
        return Optional.of(new PreflightObservation(
                snapshotBuilder.build(),
                context.currentTick(),
                bindingId,
                menuBinding(bindingId)));
    }

    @Override
    public Optional<CompletionObservation> observeAfterAction(
            ExecutionTicket ticket,
            SkillNodeContext context,
            SkillSignal signal) {
        requireOwnerThread();
        Objects.requireNonNull(ticket, "ticket");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(signal, "signal");
        if (!ticket.bot().botId().equals(context.botId())
                || ticket.bot().generation() != context.botGeneration()
                || !ticket.before().menuBinding().equals(
                        menuBinding(ticket.before().worldBinding()))) {
            return Optional.empty();
        }
        FrozenBinding binding = bindings.get(ticket.before().worldBinding());
        if (binding == null || !binding.matches(ticket)) {
            return Optional.empty();
        }
        BotServerPlayer player = resolveCurrent(ticket.bot()).orElse(null);
        if (player == null || !currentTick(player, context.currentTick())) {
            return Optional.empty();
        }
        NativeBaseline after = observeNativeBaseline(player, context)
                .orElse(null);
        if (after == null) {
            return Optional.empty();
        }
        boolean worldSatisfied = binding.worldStillValidAfter(player);
        return Optional.of(new CompletionObservation(
                new io.github.greytaiwolf.botplayer.skill.builtin.production
                        .ProductionPreconditionSnapshot(
                                after.stateId(),
                                after.ledger(),
                                Optional.of(MenuFamily.INVENTORY_2X2),
                                after.cursorEmpty(),
                                Optional.empty()),
                context.currentTick(),
                ticket.before().worldBinding(),
                ticket.before().menuBinding(),
                worldSatisfied,
                after.nativeMenu()));
    }

    @Override
    public Optional<ProductionAction> plan(
            ExecutionTicket ticket, TaskSensorService suppliedSensors) {
        requireOwnerThread();
        Objects.requireNonNull(ticket, "ticket");
        if (suppliedSensors != taskSensors) {
            return rejectResourceActionPlan(ticket, "task-sensor-instance");
        }
        if (!(ticket.resolved().node().operation()
                instanceof ResourceAcquisition acquisition)) {
            return rejectResourceActionPlan(ticket, "non-resource-operation");
        }
        FrozenBinding frozen = bindings.get(ticket.before().worldBinding());
        if (!(frozen instanceof ResourceBinding binding)
                || !binding.matches(ticket)) {
            return rejectResourceActionPlan(ticket, "frozen-binding");
        }
        BotServerPlayer player = resolveCurrent(ticket.bot()).orElse(null);
        if (player == null || !currentTick(player,
                ticket.before().observedAtTick())
                || !binding.baseline().matchesCurrent(player)) {
            return rejectResourceActionPlan(ticket, "player-or-native-baseline");
        }
        BlockTargetFingerprint target = binding.target();
        if (!target.state().blockId().value().equals(
                expectedResourceBlock(acquisition))) {
            return rejectResourceActionPlan(ticket, "expected-resource-block");
        }
        if (!groundedAtResourceApproach(
                player.blockPosition(), player.onGround(),
                target.position())) {
            return rejectResourceActionPlan(ticket, "grounded-resource-approach");
        }
        if (!isCurrentReachableBlock(player, target)) {
            return rejectResourceActionPlan(ticket, "current-resource-reach");
        }
        ItemStackFingerprint tool = MinecraftActionSnapshot.item(
                player, player.getMainHandItem());
        WorldInteractionActionSpec.BreakBlock breakBlock =
                new WorldInteractionActionSpec.BreakBlock(
                        hit(target), tool);
        return Optional.of(new ProductionAction(
                new WorldInteractionAction(breakBlock),
                MAXIMUM_RESOURCE_ACTION_TICKS,
                "等待原版方块破坏与资源掉落回读"));
    }

    /**
     * 资源采集在真正提交 BREAK_BLOCK 前拒绝时留下有限诊断。这个分支没有 world 写入；日志
     * 只包含 run/operation 身份和封闭原因，便于区分导航落地、冻结 binding 与原生基线漂移。
     */
    private static Optional<ProductionAction> rejectResourceActionPlan(
            ExecutionTicket ticket, String reason) {
        Objects.requireNonNull(ticket, "ticket");
        BotPlayer.LOGGER.warn(
                "P5A resource action plan rejected: reason={}, operation={}, bot={}, generation={}, tick={}, binding={}",
                Objects.requireNonNull(reason, "reason"),
                ticket.operationId(),
                ticket.bot().botId(),
                ticket.bot().generation(),
                ticket.before().observedAtTick(),
                ticket.before().worldBinding());
        return Optional.empty();
    }

    /**
     * 只在已经严格破坏 source 且玩家账本仍完全等于 before 时，扫描 source 附近新出现的
     * ItemEntity 标量。preflight 已冻结所有旧 UUID，因此现存的旧掉落物不能被误收。
     */
    @Override
    public ResourceDropObservation observeResourceDrop(
            ExecutionTicket ticket, SkillNodeContext context) {
        requireOwnerThread();
        Objects.requireNonNull(ticket, "ticket");
        Objects.requireNonNull(context, "context");
        if (!(ticket.resolved().node().operation()
                instanceof ResourceAcquisition acquisition)) {
            return ResourceDropObservation.unavailable();
        }
        FrozenBinding frozen = bindings.get(ticket.before().worldBinding());
        if (!(frozen instanceof ResourceBinding binding)
                || !binding.matches(ticket)) {
            return ResourceDropObservation.unavailable();
        }
        BotServerPlayer player = resolveCurrent(ticket.bot()).orElse(null);
        if (player == null || !currentTick(player, context.currentTick())
                || !binding.worldStillValidAfter(player)
                || observeNativeBaseline(player, context).isEmpty()) {
            return ResourceDropObservation.unavailable();
        }
        ResourceDropExpectation expectation = resourceDropExpectation(
                acquisition).orElse(null);
        if (expectation == null) {
            return ResourceDropObservation.unavailable();
        }
        TaskSensorSnapshot snapshot = query(new TaskSensorQuery(
                identity(context),
                TaskSensorQueryType.DROPPED_ITEMS,
                dropScope(player, context, binding.target()),
                new TaskSensorBudget(
                        RESOURCE_DROP_MAX_CANDIDATES,
                        0,
                        0,
                        0,
                        RESOURCE_DROP_MAX_EVIDENCE,
                        0L)), context.currentTick()).orElse(null);
        if (snapshot == null || snapshot.truncated()) {
            return ResourceDropObservation.unavailable();
        }
        List<ResourceDropCandidate> matching = new ArrayList<>();
        Set<UUID> seenIds = new java.util.LinkedHashSet<>();
        for (TaskSensorEvidence evidence : snapshot.evidence()) {
            ResourceDropCandidate candidate = droppedItemCandidate(evidence,
                    binding.target().dimension().value()).orElse(null);
            if (candidate == null || !seenIds.add(candidate.entityId())
                    || !candidate.dimensionId().equals(
                            binding.target().dimension().value())
                    || !withinDropEnvelope(candidate.position(),
                            binding.target().position())) {
                return ResourceDropObservation.unavailable();
            }
            if (!binding.preexistingDropIds().contains(candidate.entityId())
                    && expectation.itemId().equals(candidate.itemId())
                    && expectation.count() == candidate.count()) {
                matching.add(candidate);
            }
        }
        if (matching.isEmpty()) {
            return ResourceDropObservation.absent();
        }
        return matching.size() == 1
                ? ResourceDropObservation.found(matching.get(0))
                : ResourceDropObservation.ambiguous();
    }

    /**
     * 在 action 入队前重新读取 live UUID、物品、source air 和 native menu。handler 会在同一
     * server tick 内重新冻结候选格；这里要求 live 实体仍在该精确格和冻结 source 的有限包络
     * 内。PickupWait 后端仍以同一 UUID 做可达性、实体消失和库存增量校验，因此这里不直接
     * 移动玩家或写入库存。
     */
    @Override
    public Optional<ProductionAction> planResourceDropPickup(
            ExecutionTicket ticket,
            SkillNodeContext context,
            ResourceDropCandidate candidate) {
        requireOwnerThread();
        Objects.requireNonNull(ticket, "ticket");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(candidate, "candidate");
        if (!(ticket.resolved().node().operation()
                instanceof ResourceAcquisition acquisition)) {
            return rejectResourceDropPickupPlan(ticket, context,
                    "non-resource-operation");
        }
        FrozenBinding frozen = bindings.get(ticket.before().worldBinding());
        if (!(frozen instanceof ResourceBinding binding)
                || !binding.matches(ticket)
                || binding.preexistingDropIds().contains(candidate.entityId())
                || !binding.target().dimension().value().equals(
                        candidate.dimensionId())) {
            return rejectResourceDropPickupPlan(ticket, context,
                    "frozen-binding-or-candidate");
        }
        ResourceDropExpectation expectation = resourceDropExpectation(
                acquisition).orElse(null);
        if (expectation == null
                || !expectation.itemId().equals(candidate.itemId())
                || expectation.count() != candidate.count()) {
            return rejectResourceDropPickupPlan(ticket, context,
                    "expected-item-or-count");
        }
        BotServerPlayer player = resolveCurrent(ticket.bot()).orElse(null);
        NativeBaseline currentBaseline = player == null ? null
                : observeNativeBaseline(player, context).orElse(null);
        if (player == null || !currentTick(player, context.currentTick())
                || !binding.worldStillValidAfter(player)
                || !sameSafePickupBaseline(currentBaseline, binding.baseline(),
                        ticket.before().snapshot().playerLedger())) {
            return rejectResourceDropPickupPlan(ticket, context,
                    "player-world-or-native-baseline");
        }
        Entity entity = player.serverLevel().getEntity(candidate.entityId());
        if (!(entity instanceof ItemEntity itemEntity)
                || itemEntity.isRemoved()) {
            return rejectResourceDropPickupPlan(ticket, context,
                    "live-item-unavailable");
        }
        BlockPos livePosition = itemEntity.blockPosition();
        if (!withinDropEnvelope(candidate.position(),
                binding.target().position())) {
            return rejectResourceDropPickupPlan(ticket, context,
                    "frozen-candidate-outside-source-envelope");
        }
        if (!player.serverLevel().isLoaded(livePosition)
                || !withinDropEnvelope(GridPoint.from(livePosition),
                        binding.target().position())) {
            return rejectResourceDropPickupPlan(ticket, context,
                    "live-item-outside-source-envelope");
        }
        if (!livePosition.equals(candidate.position().toBlockPos())) {
            return rejectResourceDropPickupPlan(ticket, context,
                    "live-item-position-changed");
        }
        if (!candidate.itemId().equals(BuiltInRegistries.ITEM
                .getKey(itemEntity.getItem().getItem()).toString())
                || itemEntity.getItem().getCount() != candidate.count()) {
            return rejectResourceDropPickupPlan(ticket, context,
                    "live-item-or-count");
        }
        return Optional.of(new ProductionAction(
                new WorldInteractionAction(
                        new WorldInteractionActionSpec.PickupWait(
                                MAXIMUM_RESOURCE_DROP_PICKUP_TICKS,
                                Optional.of(candidate.entityId()))),
                MAXIMUM_RESOURCE_DROP_PICKUP_TICKS,
                "等待 UUID 绑定的原版资源掉落实体进入背包"));
    }

    /**
     * PickupWait 之前的拒绝没有 world 写入；记录封闭原因以区分物理漂移、menu/ledger 漂移和
     * provenance 漂移。reason 只能来自本方法内的固定字面量，避免把活动原版对象泄露到日志。
     */
    private static Optional<ProductionAction> rejectResourceDropPickupPlan(
            ExecutionTicket ticket, SkillNodeContext context, String reason) {
        Objects.requireNonNull(ticket, "ticket");
        Objects.requireNonNull(context, "context");
        BotPlayer.LOGGER.warn(
                "P5A resource drop pickup plan rejected: reason={}, operation={}, bot={}, generation={}, tick={}, binding={}",
                Objects.requireNonNull(reason, "reason"),
                ticket.operationId(),
                ticket.bot().botId(),
                ticket.bot().generation(),
                context.currentTick(),
                ticket.before().worldBinding());
        return Optional.empty();
    }

    @Override
    public Optional<ProductionAction> plan(ExecutionTicket ticket) {
        requireOwnerThread();
        Objects.requireNonNull(ticket, "ticket");
        ProductionOperation operation = ticket.resolved().node().operation();
        if (operation instanceof PlaceWorkstation placement) {
            return planWorkstationPlacement(ticket, placement);
        }
        if (!(operation instanceof RecipeExecution execution)) {
            return Optional.empty();
        }
        P5ARecipe recipe = approvedRecipe(execution,
                ticket.resolved()).orElse(null);
        if (recipe == null) {
            return Optional.empty();
        }
        FrozenBinding frozen = bindings.get(ticket.before().worldBinding());
        if (frozen == null || !frozen.matches(ticket)
                || frozen.recipeOptional().filter(recipe::equals).isEmpty()) {
            return Optional.empty();
        }
        BotServerPlayer player = resolveCurrent(ticket.bot()).orElse(null);
        if (player == null || !currentTick(player,
                ticket.before().observedAtTick())
                || !frozen.baseline().matchesCurrent(player)) {
            return Optional.empty();
        }
        Optional<BlockHitTarget> opener;
        if (frozen instanceof NativeRecipeBinding) {
            if (recipe.family() != MenuFamily.INVENTORY_2X2) {
                return Optional.empty();
            }
            opener = Optional.empty();
        } else if (frozen instanceof WorldRecipeBinding worldRecipe) {
            if (recipe.family() == MenuFamily.INVENTORY_2X2
                    || !isCurrentReachableBlock(player,
                            worldRecipe.target())) {
                return Optional.empty();
            }
            opener = Optional.of(hit(worldRecipe.target()));
        } else {
            return Optional.empty();
        }
        ItemStackFingerprint held = MinecraftActionSnapshot.item(
                player, player.getMainHandItem());
        MenuTransactionLimits limits = recipeLimits(recipe);
        return Optional.of(new ProductionAction(
                new WorldInteractionAction(
                        new WorldInteractionActionSpec.WorldMenuRecipe(
                                WorldInteractionActionSpec.Hand.MAIN_HAND,
                                opener,
                                held,
                                recipe,
                                execution.batches(),
                                limits)),
                MAXIMUM_RECIPE_ACTION_TICKS,
                recipe.isFurnace()
                        ? "等待原版炉子投入、轮询与领取"
                        : "等待原版白名单配方菜单事务完成"));
    }

    /**
     * 把 preflight 中冻结的工作站放置位重新逐项核对后，才构造严格的原版
     * {@link WorldInteractionActionSpec.PlaceBlock}。没有路径请求：当前身体不能同时触及锚点和
     * 空目标时返回空，由上层以后续导航/重试策略决定，而不是扩张这个原子放置动作的范围。
     */
    private Optional<ProductionAction> planWorkstationPlacement(
            ExecutionTicket ticket, PlaceWorkstation placement) {
        FrozenBinding frozen = bindings.get(ticket.before().worldBinding());
        if (!(frozen instanceof WorkstationPlacementBinding binding)
                || !binding.matches(ticket)
                || !binding.placement().equals(placement)) {
            return Optional.empty();
        }
        BotServerPlayer player = resolveCurrent(ticket.bot()).orElse(null);
        if (player == null || !currentTick(player,
                ticket.before().observedAtTick())
                || !binding.baseline().matchesCurrent(player)
                || !isCurrentReachableBlock(player,
                        binding.anchor().target())
                || !isCurrentReachableBlock(player, binding.targetAir())) {
            return Optional.empty();
        }
        ItemStackFingerprint held = MinecraftActionSnapshot.item(
                player, player.getMainHandItem());
        BlockTargetFingerprint currentExpectedPlaced =
                expectedWorkstationPlacement(
                        player,
                        position(binding.expectedPlaced()),
                        placement.workstation());
        if (!held.equals(binding.expectedHeldItem())
                || !matchesWorkstationHeldItem(placement, held)
                || !currentExpectedPlaced.equals(binding.expectedPlaced())
                || !placement.workstation().matchesExpectedPlacedState(
                        binding.expectedPlaced().state())) {
            return Optional.empty();
        }
        return Optional.of(new ProductionAction(
                new WorldInteractionAction(
                        new WorldInteractionActionSpec.PlaceBlock(
                                binding.anchor(),
                                binding.expectedPlaced(),
                                held)),
                MAXIMUM_WORKSTATION_PLACEMENT_ACTION_TICKS,
                "等待原版工作站精确放置与账本扣款回读"));
    }

    /**
     * lifecycle 只注入 generation-bound resolver，端口从不缓存 BotServerPlayer。
     */
    @FunctionalInterface
    public interface PlayerResolver {
        Optional<BotServerPlayer> resolve(UUID botId, long generation);
    }

    private Optional<NativeBaseline> observeNativeBaseline(
            BotServerPlayer player, SkillNodeContext context) {
        try {
            TaskSensorRunIdentity identity = identity(context);
            TaskSensorScope scope = scope(player, context);
            TaskSensorSnapshot inventory = query(new TaskSensorQuery(
                    identity,
                    TaskSensorQueryType.SELF_INVENTORY,
                    scope,
                    new TaskSensorBudget(0,
                            INVENTORY_SLOTS,
                            0,
                            0,
                            INVENTORY_EVIDENCE,
                            0L)), context.currentTick()).orElse(null);
            TaskSensorSnapshot menu = query(new TaskSensorQuery(
                    identity,
                    TaskSensorQueryType.OPEN_MENU,
                    scope,
                    new TaskSensorBudget(0,
                            NATIVE_MENU_SLOTS,
                            0,
                            0,
                            NATIVE_MENU_EVIDENCE,
                            0L)), context.currentTick()).orElse(null);
            ProductionLedger ledger = inventoryLedger(inventory).orElse(null);
            NativeMenuEvidence nativeMenu = nativeMenuEvidence(menu).orElse(null);
            if (ledger == null || nativeMenu == null
                    || !nativeMenu.nativeInventory()) {
                return Optional.empty();
            }
            InventoryMenu actual = player.inventoryMenu;
            if (player.containerMenu != actual
                    || !actual.stillValid(player)
                    || actual.slots.size() != NATIVE_MENU_SLOTS
                    || actual.containerId != nativeMenu.containerId()
                    || actual.getStateId() != nativeMenu.stateId()
                    || !actual.getCarried().isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new NativeBaseline(
                    player.serverLevel().dimension().location().toString(),
                    ledger,
                    nativeMenu.containerId(),
                    nativeMenu.stateId(),
                    true));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private Optional<Candidate> selectCandidate(
            BotServerPlayer player,
            SkillNodeContext context,
            String expectedBlockId) {
        return selectCandidate(player, context, expectedBlockId, false);
    }

    /**
     * 对 P5A 资源采集，前置导航门只保证 body 在同层或相邻一格的已接地可交互范围内。
     * 这里重新选择并冻结当前最近的精确资源方块；破块后的掉落由独立 UUID 导航与 PickupWait
     * 合同收集，因此不能再把“站在待破资源顶部”误当作采集安全前提。工作台/熔炉候选仍可
     * 使用普通可达性选择，故该要求是显式而窄的。
     */
    private Optional<Candidate> selectCandidate(
            BotServerPlayer player,
            SkillNodeContext context,
            String expectedBlockId,
            boolean requireGroundedApproach) {
        try {
            TaskSensorSnapshot candidates = query(new TaskSensorQuery(
                    identity(context),
                    TaskSensorQueryType.RESOURCE_CANDIDATES,
                    scope(player, context),
                    new TaskSensorBudget(
                            RESOURCE_MAX_CANDIDATES,
                            0,
                            RESOURCE_MAX_BLOCKS,
                            0,
                            RESOURCE_MAX_EVIDENCE,
                            0L),
                    resourceFilterForExpectedBlock(expectedBlockId)),
                    context.currentTick()).orElse(null);
            if (candidates == null
                    || candidates.availability()
                            != TaskSensorAvailability.AVAILABLE) {
                return Optional.empty();
            }
            Candidate nearest = null;
            BlockCoordinates nearestPosition = null;
            for (TaskSensorEvidence evidence : candidates.evidence()) {
                CandidateEvidence candidate = resourceCandidate(evidence)
                        .orElse(null);
                if (candidate == null) {
                    return Optional.empty();
                }
                if (!expectedBlockId.equals(candidate.blockId())) {
                    continue;
                }
                if (requireGroundedApproach
                        && !groundedAtResourceApproach(
                                player.blockPosition(),
                                player.onGround(),
                                candidate.position())) {
                    continue;
                }
                BlockPos position = new BlockPos(
                        candidate.position().x(),
                        candidate.position().y(),
                        candidate.position().z());
                if (!player.serverLevel().isLoaded(position)
                        || !player.canInteractWithBlock(position, 0.0D)) {
                    continue;
                }
                BlockTargetFingerprint target = MinecraftActionSnapshot.block(
                        player, position);
                if (!target.position().equals(candidate.position())
                        || !target.state().blockId().value().equals(
                                expectedBlockId)) {
                    continue;
                }
                if (nearest == null
                        || compareReachableResourceCandidates(
                                player.position(), candidate.position(),
                                nearestPosition) < 0) {
                    nearest = new Candidate(target);
                    nearestPosition = candidate.position();
                }
            }
            return Optional.ofNullable(nearest);
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    /**
     * 导航门不会把动态资源坐标写进计划；采集节点因而必须重新查询。优先选择距离当前
     * 身体最近且仍可交互的候选，并要求其位于已接地的一格 approach 范围内；随后由 UUID
     * 绑定的掉落收集合同处理真实掉落物，不能依赖被动碰撞拾取。
     */
    static int compareReachableResourceCandidates(
            Vec3 playerPosition,
            BlockCoordinates left,
            BlockCoordinates right) {
        Objects.requireNonNull(playerPosition, "playerPosition");
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        int distance = Double.compare(
                resourceDistanceSquared(playerPosition, left),
                resourceDistanceSquared(playerPosition, right));
        return distance != 0 ? distance : comparePosition(left, right);
    }

    static boolean groundedAtResourceApproach(
            BlockPos bodyPosition,
            boolean onGround,
            BlockCoordinates resourcePosition) {
        Objects.requireNonNull(bodyPosition, "bodyPosition");
        Objects.requireNonNull(resourcePosition, "resourcePosition");
        if (!onGround) {
            return false;
        }
        long dx = (long) bodyPosition.getX() - resourcePosition.x();
        long dy = (long) bodyPosition.getY() - resourcePosition.y();
        long dz = (long) bodyPosition.getZ() - resourcePosition.z();
        if (Math.abs(dx) > 1L || Math.abs(dy) > 1L || Math.abs(dz) > 1L) {
            return false;
        }
        return dx * dx + dz * dz <= 1L;
    }

    private static double resourceDistanceSquared(
            Vec3 playerPosition, BlockCoordinates position) {
        double dx = playerPosition.x - (position.x() + 0.5D);
        double dy = playerPosition.y - (position.y() + 0.5D);
        double dz = playerPosition.z - (position.z() + 0.5D);
        return dx * dx + dy * dy + dz * dz;
    }

    private static int comparePosition(
            BlockCoordinates left, BlockCoordinates right) {
        int y = Integer.compare(left.y(), right.y());
        if (y != 0) {
            return y;
        }
        int x = Integer.compare(left.x(), right.x());
        return x != 0 ? x : Integer.compare(left.z(), right.z());
    }

    /**
     * 在固定的小范围内寻找一个当前可达的非空气锚点及其正上方严格空气位。这个扫描只读已加载
     * 方块，不加载区块、不开导航；每次 dispatch 都会重新构造并冻结完整 anchor/air/state 指纹。
     */
    private static Optional<WorkstationPlacement> selectWorkstationPlacement(
            BotServerPlayer player, PlaceWorkstation placement) {
        try {
            BlockPos center = BlockPos.containing(
                    player.getX(), player.getY(), player.getZ());
            int inspected = 0;
            for (int distance = 0;
                    distance <= WORKSTATION_PLACEMENT_RADIUS;
                    distance++) {
                for (int x = -distance; x <= distance; x++) {
                    for (int z = -distance; z <= distance; z++) {
                        if (Math.max(Math.abs(x), Math.abs(z)) != distance) {
                            continue;
                        }
                        if (inspected++ >= MAXIMUM_WORKSTATION_PLACEMENT_BLOCKS) {
                            return Optional.empty();
                        }
                        BlockPos anchorPosition = center.offset(x, -1, z);
                        BlockPos destination = anchorPosition.above();
                        if (occupiesCurrentPlayerSpace(player, destination)
                                || !player.serverLevel().isLoaded(
                                        anchorPosition)
                                || !player.serverLevel().isLoaded(destination)
                                || player.serverLevel().getBlockState(
                                        anchorPosition).isAir()
                                || !player.serverLevel().getBlockState(
                                        destination).isAir()
                                || !player.canInteractWithBlock(
                                        anchorPosition, 0.0D)
                                || !player.canInteractWithBlock(
                                        destination, 0.0D)) {
                            continue;
                        }
                        BlockTargetFingerprint anchor = MinecraftActionSnapshot
                                .block(player, anchorPosition);
                        BlockTargetFingerprint targetAir = MinecraftActionSnapshot
                                .block(player, destination);
                        if (!isAirFingerprint(targetAir)) {
                            continue;
                        }
                        BlockTargetFingerprint expectedPlaced =
                                expectedWorkstationPlacement(
                                        player, destination,
                                        placement.workstation());
                        if (!placement.workstation()
                                .matchesExpectedPlacedState(
                                        expectedPlaced.state())) {
                            return Optional.empty();
                        }
                        return Optional.of(new WorkstationPlacement(
                                hit(anchor), targetAir, expectedPlaced));
                    }
                }
            }
            return Optional.empty();
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static BlockTargetFingerprint expectedWorkstationPlacement(
            BotServerPlayer player,
            BlockPos destination,
            WorkstationKind workstation) {
        Direction facing = player.getDirection().getOpposite();
        BlockStateFingerprint state = workstation.expectedPlacedState(
                facing.getSerializedName());
        return new BlockTargetFingerprint(
                new ResourceId(player.serverLevel().dimension().location()
                        .toString()),
                MinecraftActionSnapshot.coordinates(destination),
                state);
    }

    private static BlockPos position(BlockTargetFingerprint target) {
        return new BlockPos(
                target.position().x(), target.position().y(),
                target.position().z());
    }

    private static boolean occupiesCurrentPlayerSpace(
            BotServerPlayer player, BlockPos destination) {
        BlockPos feet = player.blockPosition();
        return destination.equals(feet) || destination.equals(feet.above());
    }

    private static boolean isAirFingerprint(BlockTargetFingerprint target) {
        return switch (target.state().blockId().value()) {
            case "minecraft:air", "minecraft:cave_air", "minecraft:void_air" ->
                    target.state().properties().isEmpty();
            default -> false;
        };
    }

    private static boolean isPlacementAdjacentToAnchor(
            BlockHitTarget anchor, BlockTargetFingerprint expectedPlaced) {
        if (!anchor.target().dimension().equals(expectedPlaced.dimension())) {
            return false;
        }
        long expectedX = anchor.target().position().x();
        long expectedY = anchor.target().position().y();
        long expectedZ = anchor.target().position().z();
        switch (anchor.face()) {
            case DOWN -> expectedY--;
            case UP -> expectedY++;
            case NORTH -> expectedZ--;
            case SOUTH -> expectedZ++;
            case WEST -> expectedX--;
            case EAST -> expectedX++;
        }
        return expectedPlaced.position().x() == expectedX
                && expectedPlaced.position().y() == expectedY
                && expectedPlaced.position().z() == expectedZ;
    }

    private static boolean matchesWorkstationHeldItem(
            PlaceWorkstation placement, ItemStackFingerprint held) {
        return !held.isEmpty()
                && held.itemId().filter(placement.workstation().material()
                        .id()::equals).isPresent();
    }

    private Optional<Set<UUID>> observedDropIds(
            BotServerPlayer player,
            SkillNodeContext context,
            BlockTargetFingerprint source) {
        try {
            TaskSensorSnapshot snapshot = query(new TaskSensorQuery(
                    identity(context),
                    TaskSensorQueryType.DROPPED_ITEMS,
                    dropScope(player, context, source),
                    new TaskSensorBudget(
                            RESOURCE_DROP_MAX_CANDIDATES,
                            0,
                            0,
                            0,
                            RESOURCE_DROP_MAX_EVIDENCE,
                            0L)), context.currentTick()).orElse(null);
            if (snapshot == null || snapshot.truncated()) {
                return Optional.empty();
            }
            Set<UUID> result = new java.util.LinkedHashSet<>();
            for (TaskSensorEvidence evidence : snapshot.evidence()) {
                ResourceDropCandidate candidate = droppedItemCandidate(
                        evidence, source.dimension().value()).orElse(null);
                if (candidate == null
                        || !result.add(candidate.entityId())
                        || !withinDropEnvelope(candidate.position(),
                                source.position())) {
                    return Optional.empty();
                }
            }
            return Optional.of(Set.copyOf(result));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static TaskSensorScope dropScope(
            BotServerPlayer player,
            SkillNodeContext context,
            BlockTargetFingerprint source) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(source, "source");
        return new TaskSensorScope(source.dimension().value(),
                source.position().x(), source.position().y(),
                source.position().z(), RESOURCE_DROP_RADIUS,
                Math.incrementExact(context.stateRevision()));
    }

    private static Optional<ResourceDropCandidate> droppedItemCandidate(
            TaskSensorEvidence evidence, String dimensionId) {
        if (evidence == null || !"dropped_item.candidate".equals(
                evidence.kind())) {
            return Optional.empty();
        }
        Map<String, Object> fields = evidence.fields().values();
        if (!fields.keySet().equals(DROPPED_ITEM_CANDIDATE_FIELDS)
                || !(fields.get("entity.id") instanceof String entityId)
                || !(fields.get("item") instanceof String itemId)
                || !(fields.get("count") instanceof Integer count)
                || !(fields.get("x") instanceof Integer x)
                || !(fields.get("y") instanceof Integer y)
                || !(fields.get("z") instanceof Integer z)) {
            return Optional.empty();
        }
        try {
            UUID uuid = UUID.fromString(entityId);
            if (uuid.getMostSignificantBits() == 0L
                    && uuid.getLeastSignificantBits() == 0L) {
                return Optional.empty();
            }
            return Optional.of(new ResourceDropCandidate(uuid, dimensionId,
                    new GridPoint(x, y, z), itemId, count));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static boolean withinDropEnvelope(
            GridPoint candidate, BlockCoordinates source) {
        long deltaX = (long) Objects.requireNonNull(candidate,
                "candidate").x() - source.x();
        long deltaY = (long) candidate.y() - source.y();
        long deltaZ = (long) candidate.z() - source.z();
        return Math.abs(deltaX) <= RESOURCE_DROP_RADIUS
                && Math.abs(deltaY) <= RESOURCE_DROP_RADIUS
                && Math.abs(deltaZ) <= RESOURCE_DROP_RADIUS;
    }

    private static Optional<ResourceDropExpectation> resourceDropExpectation(
            ResourceAcquisition acquisition) {
        Objects.requireNonNull(acquisition, "acquisition");
        Map<ProductionMaterial, Integer> quantities = acquisition.expectedGain()
                .quantities();
        if (quantities.size() != 1) {
            return Optional.empty();
        }
        Map.Entry<ProductionMaterial, Integer> entry = quantities.entrySet()
                .iterator().next();
        if (entry.getValue() != 1) {
            return Optional.empty();
        }
        return Optional.of(new ResourceDropExpectation(
                entry.getKey().id().value(), entry.getValue()));
    }

    private Optional<TaskSensorSnapshot> query(
            TaskSensorQuery query, long currentTick) {
        TaskSensorResponse response = taskSensors.query(
                query, currentTick, taskSensorAdapter);
        if (response.status() != TaskSensorResponse.Status.SAMPLED
                && response.status() != TaskSensorResponse.Status.CACHE_HIT) {
            return Optional.empty();
        }
        TaskSensorSnapshot snapshot = response.snapshot().orElse(null);
        if (snapshot == null || snapshot.sampledAtTick() != currentTick
                || !snapshot.query().equals(query)
                || snapshot.availability()
                        != TaskSensorAvailability.AVAILABLE) {
            return Optional.empty();
        }
        return Optional.of(snapshot);
    }

    private Optional<BotServerPlayer> resolveCurrent(ActiveBot bot) {
        try {
            BotServerPlayer player = players.resolve(
                    bot.botId(), bot.generation()).orElse(null);
            if (player == null
                    || !player.getUUID().equals(bot.botId())
                    || player.runtimeHandle().generation()
                            != bot.generation()) {
                return Optional.empty();
            }
            return Optional.of(player);
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static boolean currentTick(
            BotServerPlayer player, long expectedTick) {
        MinecraftServer server = player.getServer();
        return server != null && server.isSameThread()
                && server.getTickCount() == expectedTick;
    }

    private static TaskSensorRunIdentity identity(SkillNodeContext context) {
        return new TaskSensorRunIdentity(
                context.botId(),
                context.botGeneration(),
                context.runId(),
                context.stateRevision());
    }

    private static TaskSensorScope scope(
            BotServerPlayer player, SkillNodeContext context) {
        BlockPos center = BlockPos.containing(
                player.getX(), player.getY(), player.getZ());
        return new TaskSensorScope(
                player.serverLevel().dimension().location().toString(),
                center.getX(),
                center.getY(),
                center.getZ(),
                RESOURCE_RADIUS,
                Math.incrementExact(context.stateRevision()));
    }

    private static String bindingId(
            SkillNodeContext context, String operationId) {
        return "p5a-production:"
                + context.runId()
                + ":"
                + context.stateRevision()
                + ":"
                + operationId;
    }

    private static String menuBinding(String worldBinding) {
        return MENU_BINDING_PREFIX + worldBinding;
    }

    private void freeze(String id, FrozenBinding binding) {
        bindings.put(Objects.requireNonNull(id, "id"),
                Objects.requireNonNull(binding, "binding"));
        while (bindings.size() > MAX_FROZEN_BINDINGS) {
            bindings.remove(bindings.keySet().iterator().next());
        }
    }

    private static boolean isCurrentReachableBlock(
            BotServerPlayer player, BlockTargetFingerprint target) {
        try {
            BlockPos position = new BlockPos(
                    target.position().x(), target.position().y(),
                    target.position().z());
            return player.serverLevel().isLoaded(position)
                    && player.canInteractWithBlock(position, 0.0D)
                    && MinecraftActionSnapshot.block(player, position)
                            .equals(target);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /**
     * 世界工作站配方完成后仍绑定同一方块。熔炉的 {@code lit} 是配方动作自身会合法改变的
     * 原版运行态，因此只对熔炉忽略这一项；坐标、维度、方块类型、朝向和其余属性仍须精确
     * 相等。其他工作站继续要求完整 state 指纹相等。
     */
    static boolean workstationStateValidAfter(
            BlockTargetFingerprint frozen,
            BlockTargetFingerprint current,
            MenuFamily family) {
        Objects.requireNonNull(frozen, "frozen");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(family, "family");
        if (!frozen.dimension().equals(current.dimension())
                || !frozen.position().equals(current.position())
                || !frozen.state().blockId().equals(current.state().blockId())) {
            return false;
        }
        if (family != MenuFamily.FURNACE) {
            return frozen.state().equals(current.state());
        }
        Map<String, String> frozenProperties = new LinkedHashMap<>(
                frozen.state().properties());
        Map<String, String> currentProperties = new LinkedHashMap<>(
                current.state().properties());
        String frozenLit = frozenProperties.remove("lit");
        String currentLit = currentProperties.remove("lit");
        return frozenLit != null
                && currentLit != null
                && frozenProperties.equals(currentProperties);
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

    static Optional<ProductionLedger> inventoryLedger(
            TaskSensorSnapshot snapshot) {
        if (snapshot == null
                || snapshot.availability() != TaskSensorAvailability.AVAILABLE
                || snapshot.truncated()
                || snapshot.query().type()
                        != TaskSensorQueryType.SELF_INVENTORY
                || snapshot.evidence().size() != INVENTORY_EVIDENCE) {
            return Optional.empty();
        }
        boolean[] slots = new boolean[INVENTORY_SLOTS];
        boolean selectedSeen = false;
        Map<ProductionMaterial, Integer> quantities = new LinkedHashMap<>();
        for (TaskSensorEvidence evidence : snapshot.evidence()) {
            if ("inventory.selected".equals(evidence.kind())) {
                if (selectedSeen || !validSelected(evidence.fields())) {
                    return Optional.empty();
                }
                selectedSeen = true;
                continue;
            }
            if (!"inventory.slot".equals(evidence.kind())) {
                return Optional.empty();
            }
            Optional<InventoryEntry> entry = itemEntry(
                    evidence.fields(), INVENTORY_SLOTS);
            if (entry.isEmpty()) {
                return Optional.empty();
            }
            InventoryEntry value = entry.orElseThrow();
            if (slots[value.slot()]) {
                return Optional.empty();
            }
            slots[value.slot()] = true;
            if (!value.empty()) {
                ProductionMaterial material = MATERIALS_BY_ID.get(
                        value.itemId().orElseThrow());
                if (material != null) {
                    try {
                        quantities.merge(material, value.count(),
                                Math::addExact);
                    } catch (ArithmeticException exception) {
                        return Optional.empty();
                    }
                }
            }
        }
        for (boolean seen : slots) {
            if (!seen) {
                return Optional.empty();
            }
        }
        if (!selectedSeen) {
            return Optional.empty();
        }
        try {
            return Optional.of(quantities.isEmpty()
                    ? ProductionLedger.empty()
                    : new ProductionLedger(quantities));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    static Optional<CandidateEvidence> resourceCandidate(
            TaskSensorEvidence evidence) {
        if (evidence == null || !"resource.candidate".equals(
                evidence.kind())) {
            return Optional.empty();
        }
        Map<String, Object> fields = evidence.fields().values();
        if (!fields.keySet().equals(RESOURCE_CANDIDATE_FIELDS)) {
            return Optional.empty();
        }
        Object x = fields.get("x");
        Object y = fields.get("y");
        Object z = fields.get("z");
        Object block = fields.get("block");
        if (!(x instanceof Integer xValue)
                || !(y instanceof Integer yValue)
                || !(z instanceof Integer zValue)
                || !(block instanceof String blockId)) {
            return Optional.empty();
        }
        try {
            new ResourceId(blockId);
            return Optional.of(new CandidateEvidence(
                    new BlockCoordinates(xValue, yValue, zValue), blockId));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    static Optional<P5ARecipe> approvedRecipe(
            RecipeExecution execution,
            io.github.greytaiwolf.botplayer.skill.builtin.production
                    .ProductionResolvedNode resolved) {
        if (execution == null || resolved == null
                || resolved.node().operation() != execution) {
            return Optional.empty();
        }
        P5ARecipe recipe = P5ARecipe.find(execution.recipeId().value())
                .orElse(null);
        if (recipe == null || execution.batches() < 1
                || execution.batches() > recipe.maximumBatches()
                || resolved.menuContract().isEmpty()
                || resolved.menuContract().orElseThrow().family()
                        != recipe.family()
                || !resolved.expectedPlayerDelta().equals(
                        resolved.menuContract().orElseThrow()
                                .expectedPlayerDelta())) {
            return Optional.empty();
        }
        return Optional.of(recipe);
    }

    private static Optional<P5ARecipe> approvedRecipe(
            RecipeExecution execution, ApprovedOperation approved) {
        return approvedRecipe(execution, approved.resolved());
    }

    static MenuTransactionLimits recipeLimits(P5ARecipe recipe) {
        Objects.requireNonNull(recipe, "recipe");
        return recipe.isFurnace()
                ? new MenuTransactionLimits(64, 1_200L)
                : new MenuTransactionLimits(64, 240L);
    }

    static String expectedResourceBlock(ResourceAcquisition acquisition) {
        Objects.requireNonNull(acquisition, "acquisition");
        return switch (acquisition.method()) {
            case HARVEST_LOG -> "minecraft:oak_log";
            case MINE_COBBLESTONE -> "minecraft:cobblestone";
            case MINE_RAW_IRON -> "minecraft:iron_ore";
            case MINE_COAL -> "minecraft:coal_ore";
        };
    }

    /**
     * 资源候选的 24 项上限必须在扫描时就排除无关方块；不能先让石头地板耗尽证据额度，再在
     * 调用方过滤。这里仅把 production 的封闭方块 id 映射到同样封闭的 TaskSensor enum。
     */
    static TaskSensorResourceFilter resourceFilterForExpectedBlock(
            String expectedBlockId) {
        Objects.requireNonNull(expectedBlockId, "expectedBlockId");
        return switch (expectedBlockId) {
            case "minecraft:oak_log" -> TaskSensorResourceFilter.OAK_LOG;
            case "minecraft:cobblestone" ->
                    TaskSensorResourceFilter.COBBLESTONE;
            case "minecraft:iron_ore" -> TaskSensorResourceFilter.IRON_ORE;
            case "minecraft:coal_ore" -> TaskSensorResourceFilter.COAL_ORE;
            case "minecraft:crafting_table" ->
                    TaskSensorResourceFilter.CRAFTING_TABLE;
            case "minecraft:furnace" -> TaskSensorResourceFilter.FURNACE;
            default -> throw new IllegalArgumentException(
                    "production has no reviewed resource filter for "
                            + expectedBlockId);
        };
    }

    private static String expectedWorkstationBlock(MenuFamily family) {
        return switch (family) {
            case CRAFTING_3X3 -> "minecraft:crafting_table";
            case FURNACE -> "minecraft:furnace";
            case INVENTORY_2X2, CHEST_3X9 -> throw new IllegalArgumentException(
                    "production recipe has no world workstation for " + family);
        };
    }

    private static Optional<NativeMenuEvidence> nativeMenuEvidence(
            TaskSensorSnapshot snapshot) {
        if (snapshot == null
                || snapshot.availability() != TaskSensorAvailability.AVAILABLE
                || snapshot.truncated()
                || snapshot.query().type() != TaskSensorQueryType.OPEN_MENU
                || snapshot.evidence().size() != NATIVE_MENU_EVIDENCE) {
            return Optional.empty();
        }
        NativeMenuEvidence header = null;
        boolean carried = false;
        boolean[] slots = new boolean[NATIVE_MENU_SLOTS];
        for (TaskSensorEvidence evidence : snapshot.evidence()) {
            if ("menu.header".equals(evidence.kind())) {
                if (header != null) {
                    return Optional.empty();
                }
                header = menuHeader(evidence.fields()).orElse(null);
                if (header == null) {
                    return Optional.empty();
                }
            } else if ("menu.carried".equals(evidence.kind())) {
                if (carried || !emptyCarried(evidence.fields())) {
                    return Optional.empty();
                }
                carried = true;
            } else if ("menu.slot".equals(evidence.kind())) {
                Optional<InventoryEntry> entry = itemEntry(
                        evidence.fields(), NATIVE_MENU_SLOTS);
                if (entry.isEmpty() || slots[entry.orElseThrow().slot()]) {
                    return Optional.empty();
                }
                slots[entry.orElseThrow().slot()] = true;
            } else {
                return Optional.empty();
            }
        }
        if (header == null || !carried) {
            return Optional.empty();
        }
        for (boolean seen : slots) {
            if (!seen) {
                return Optional.empty();
            }
        }
        return Optional.of(header);
    }

    private static Optional<NativeMenuEvidence> menuHeader(
            SkillParameters fields) {
        Map<String, Object> values = fields.values();
        if (!values.keySet().equals(MENU_HEADER_FIELDS)
                || !(values.get("family") instanceof String family)
                || !(values.get("container.id") instanceof Integer containerId)
                || !(values.get("state.id") instanceof Integer stateId)
                || !(values.get("slot.count") instanceof Integer slotCount)
                || containerId < 0 || stateId < 0) {
            return Optional.empty();
        }
        return MenuFamily.resolveExact(family, slotCount)
                .filter(value -> value == MenuFamily.INVENTORY_2X2)
                .map(value -> new NativeMenuEvidence(
                        containerId, stateId, true));
    }

    private static boolean emptyCarried(SkillParameters fields) {
        Map<String, Object> values = fields.values();
        return values.keySet().equals(MENU_CARRIED_EMPTY_FIELDS)
                && Boolean.TRUE.equals(values.get("empty"))
                && Integer.valueOf(0).equals(values.get("count"));
    }

    private static boolean validSelected(SkillParameters fields) {
        Map<String, Object> values = fields.values();
        Object slot = values.get("slot");
        return values.size() == 1 && slot instanceof Integer index
                && index >= 0 && index <= 8;
    }

    private static Optional<InventoryEntry> itemEntry(
            SkillParameters fields, int slotCount) {
        Map<String, Object> values = fields.values();
        Object slot = values.get("slot");
        Object empty = values.get("empty");
        Object count = values.get("count");
        if (!(slot instanceof Integer index)
                || slotCount < 1 || index < 0 || index >= slotCount
                || !(empty instanceof Boolean emptyValue)
                || !(count instanceof Integer countValue)
                || countValue < 0 || countValue > 64) {
            return Optional.empty();
        }
        if (emptyValue) {
            return values.keySet().equals(INVENTORY_ITEM_FIELDS)
                    && countValue == 0
                    ? Optional.of(new InventoryEntry(index, true, 0,
                            Optional.empty()))
                    : Optional.empty();
        }
        Object item = values.get("item");
        Object damage = values.get("damage");
        Object digest = values.get("digest");
        if (!values.keySet().equals(INVENTORY_NON_EMPTY_ITEM_FIELDS)
                || countValue < 1
                || !(item instanceof String itemId)
                || !(damage instanceof Integer damageValue)
                || damageValue < 0
                || !(digest instanceof String digestValue)
                || !digestValue.matches("[0-9a-f]{64}")) {
            return Optional.empty();
        }
        try {
            new ResourceId(itemId);
            return Optional.of(new InventoryEntry(index, false, countValue,
                    Optional.of(itemId)));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static Map<String, ProductionMaterial> materialsById() {
        List<ProductionMaterial> materials = List.of(
                ProductionMaterials.OAK_LOG,
                ProductionMaterials.OAK_PLANKS,
                ProductionMaterials.STICK,
                ProductionMaterials.CRAFTING_TABLE,
                ProductionMaterials.WOODEN_PICKAXE,
                ProductionMaterials.COBBLESTONE,
                ProductionMaterials.FURNACE,
                ProductionMaterials.STONE_PICKAXE,
                ProductionMaterials.RAW_IRON,
                ProductionMaterials.COAL,
                ProductionMaterials.CHARCOAL,
                ProductionMaterials.IRON_INGOT,
                ProductionMaterials.IRON_PICKAXE);
        Map<String, ProductionMaterial> result = new LinkedHashMap<>();
        for (ProductionMaterial material : materials) {
            if (result.put(material.id().value(), material) != null) {
                throw new IllegalStateException(
                        "P5A material registry contains a duplicate id");
            }
        }
        return Map.copyOf(result);
    }

    private void requireOwnerThread() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException(
                    "production Minecraft ports require server thread");
        }
    }

    /** TaskSensor 资源候选的纯解析结果；未带 live BlockPos 或 BlockState。 */
    record CandidateEvidence(BlockCoordinates position, String blockId) {
        CandidateEvidence {
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(blockId, "blockId");
        }
    }

    private record Candidate(BlockTargetFingerprint target) {
        private Candidate {
            Objects.requireNonNull(target, "target");
        }
    }

    /** 已审核资源 fragment 的单一原版掉落实体预期。 */
    private record ResourceDropExpectation(String itemId, int count) {
        private ResourceDropExpectation {
            new ResourceId(Objects.requireNonNull(itemId, "itemId"));
            if (count != 1) {
                throw new IllegalArgumentException(
                        "P5A resource drop expectation must be exactly one item");
            }
        }
    }

    /**
     * preflight 观察得到的纯值放置位：锚点完整指纹、目标严格空气指纹以及原版 placement
     * context 预测出的完整结果 state 都必须成对保存，不能只保存坐标或 block id。
     */
    private record WorkstationPlacement(
            BlockHitTarget anchor,
            BlockTargetFingerprint targetAir,
            BlockTargetFingerprint expectedPlaced) {
        private WorkstationPlacement {
            anchor = Objects.requireNonNull(anchor, "anchor");
            targetAir = Objects.requireNonNull(targetAir, "targetAir");
            expectedPlaced = Objects.requireNonNull(
                    expectedPlaced, "expectedPlaced");
            if (!targetAir.dimension().equals(expectedPlaced.dimension())
                    || !targetAir.position().equals(expectedPlaced.position())
                    || !isAirFingerprint(targetAir)
                    || !isPlacementAdjacentToAnchor(anchor, expectedPlaced)) {
                throw new IllegalArgumentException(
                        "workstation placement must freeze one exact air target");
            }
        }
    }

    private record InventoryEntry(
            int slot, boolean empty, int count, Optional<String> itemId) {
        private InventoryEntry {
            itemId = Objects.requireNonNull(itemId, "itemId");
        }
    }

    private record NativeMenuEvidence(
            int containerId, int stateId, boolean nativeInventory) {
    }

    /**
     * 原生背包 baseline 只保存 TaskSensor 已复制的标量；{@link #matchesCurrent(BotServerPlayer)}
     * 是动作真正入队前的第二道快速漂移检查，仍不写菜单。
     */
    private record NativeBaseline(
            String dimensionId,
            ProductionLedger ledger,
            int containerId,
            int stateId,
            boolean cursorEmpty) {
        private NativeBaseline {
            Objects.requireNonNull(dimensionId, "dimensionId");
            Objects.requireNonNull(ledger, "ledger");
            if (containerId < 0 || stateId < 0 || !cursorEmpty) {
                throw new IllegalArgumentException(
                        "native production baseline is invalid");
            }
        }

        private boolean nativeMenu() {
            return true;
        }

        private boolean matchesCurrent(BotServerPlayer player) {
            try {
                InventoryMenu menu = player.inventoryMenu;
                return dimensionId.equals(player.serverLevel().dimension()
                                .location().toString())
                        && player.containerMenu == menu
                        && menu.stillValid(player)
                        && menu.slots.size() == NATIVE_MENU_SLOTS
                        && menu.containerId == containerId
                        && menu.getStateId() == stateId
                        && menu.getCarried().isEmpty();
            } catch (RuntimeException exception) {
                return false;
            }
        }
    }

    /**
     * PickUpWait 入队前必须仍处于同一原生背包、同一维度、空 cursor，并且 P5A 白名单账本
     * 精确等于破块前。故其他生产材料在导航间隙发生变化时不会先收取实体再事后失败；不比较
     * {@code stateId}，因为原版挖掘可合法改变工具耐久并推进 menu state。
     */
    private static boolean sameSafePickupBaseline(
            NativeBaseline current,
            NativeBaseline frozen,
            ProductionLedger expectedPlayerLedger) {
        return current != null
                && frozen != null
                && current.dimensionId().equals(frozen.dimensionId())
                && current.containerId() == frozen.containerId()
                && current.cursorEmpty()
                && current.ledger().equals(Objects.requireNonNull(
                        expectedPlayerLedger, "expectedPlayerLedger"));
    }

    private sealed interface FrozenBinding permits ResourceBinding,
            NativeRecipeBinding, WorldRecipeBinding,
            WorkstationPlacementBinding {
        ActiveBot bot();

        String operationId();

        long observedAtTick();

        NativeBaseline baseline();

        default boolean matches(ExecutionTicket ticket) {
            return bot().equals(ticket.bot())
                    && operationId().equals(ticket.operationId())
                    && observedAtTick() == ticket.before().observedAtTick();
        }

        default Optional<P5ARecipe> recipeOptional() {
            return Optional.empty();
        }

        default P5ARecipe recipe() {
            return recipeOptional().orElseThrow(() -> new IllegalStateException(
                    "resource binding has no menu recipe"));
        }

        boolean worldStillValidAfter(BotServerPlayer player);
    }

    private record ResourceBinding(
            ActiveBot bot,
            String operationId,
            long observedAtTick,
            NativeBaseline baseline,
            BlockTargetFingerprint target,
            Set<UUID> preexistingDropIds) implements FrozenBinding {
        private ResourceBinding {
            Objects.requireNonNull(bot, "bot");
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(baseline, "baseline");
            Objects.requireNonNull(target, "target");
            preexistingDropIds = Set.copyOf(Objects.requireNonNull(
                    preexistingDropIds, "preexistingDropIds"));
        }

        @Override
        public boolean worldStillValidAfter(BotServerPlayer player) {
            try {
                BlockPos position = new BlockPos(
                        target.position().x(), target.position().y(),
                        target.position().z());
                return target.dimension().value().equals(player.serverLevel()
                                .dimension().location().toString())
                        && player.serverLevel().isLoaded(position)
                        && player.serverLevel().getBlockState(position).isAir();
            } catch (RuntimeException exception) {
                return false;
            }
        }
    }

    private record NativeRecipeBinding(
            ActiveBot bot,
            String operationId,
            long observedAtTick,
            NativeBaseline baseline,
            P5ARecipe recipe) implements FrozenBinding {
        private NativeRecipeBinding {
            Objects.requireNonNull(bot, "bot");
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(baseline, "baseline");
            Objects.requireNonNull(recipe, "recipe");
        }

        @Override
        public Optional<P5ARecipe> recipeOptional() {
            return Optional.of(recipe);
        }

        @Override
        public boolean worldStillValidAfter(BotServerPlayer player) {
            return baseline.dimensionId().equals(player.serverLevel()
                    .dimension().location().toString());
        }
    }

    private record WorldRecipeBinding(
            ActiveBot bot,
            String operationId,
            long observedAtTick,
            NativeBaseline baseline,
            P5ARecipe recipe,
            BlockTargetFingerprint target) implements FrozenBinding {
        private WorldRecipeBinding {
            Objects.requireNonNull(bot, "bot");
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(baseline, "baseline");
            Objects.requireNonNull(recipe, "recipe");
            Objects.requireNonNull(target, "target");
        }

        @Override
        public Optional<P5ARecipe> recipeOptional() {
            return Optional.of(recipe);
        }

        @Override
        public boolean worldStillValidAfter(BotServerPlayer player) {
            try {
                BlockPos position = new BlockPos(
                        target.position().x(), target.position().y(),
                        target.position().z());
                if (!player.serverLevel().isLoaded(position)
                        || !player.canInteractWithBlock(position, 0.0D)) {
                    return false;
                }
                return workstationStateValidAfter(
                        target,
                        MinecraftActionSnapshot.block(player, position),
                        recipe.family());
            } catch (RuntimeException exception) {
                return false;
            }
        }
    }

    /**
     * 放置操作的冻结绑定。完成后不能只看到“目标非空气”就接受：锚点必须仍是原样，目标必须与
     * 预期完整 state 相等，且原生背包 menu 已恢复为 action 前的安全基线。
     */
    private record WorkstationPlacementBinding(
            ActiveBot bot,
            String operationId,
            long observedAtTick,
            NativeBaseline baseline,
            PlaceWorkstation placement,
            BlockHitTarget anchor,
            BlockTargetFingerprint targetAir,
            BlockTargetFingerprint expectedPlaced,
            ItemStackFingerprint expectedHeldItem) implements FrozenBinding {
        private WorkstationPlacementBinding {
            Objects.requireNonNull(bot, "bot");
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(baseline, "baseline");
            placement = Objects.requireNonNull(placement, "placement");
            anchor = Objects.requireNonNull(anchor, "anchor");
            targetAir = Objects.requireNonNull(targetAir, "targetAir");
            expectedPlaced = Objects.requireNonNull(
                    expectedPlaced, "expectedPlaced");
            expectedHeldItem = Objects.requireNonNull(
                    expectedHeldItem, "expectedHeldItem");
            if (expectedHeldItem.isEmpty()
                    || !matchesWorkstationHeldItem(placement,
                            expectedHeldItem)
                    || !targetAir.dimension().equals(
                            expectedPlaced.dimension())
                    || !targetAir.position().equals(
                            expectedPlaced.position())
                    || !isAirFingerprint(targetAir)
                    || !placement.workstation().matchesExpectedPlacedState(
                            expectedPlaced.state())) {
                throw new IllegalArgumentException(
                        "workstation placement binding is not exact");
            }
            new WorldInteractionActionSpec.PlaceBlock(
                    anchor, expectedPlaced, expectedHeldItem);
        }

        @Override
        public boolean worldStillValidAfter(BotServerPlayer player) {
            return isCurrentReachableBlock(player, anchor.target())
                    && isCurrentReachableBlock(player, expectedPlaced);
        }
    }

    /**
     * 将原生 inventory baseline 映射为生产 validator 所见快照；这里只构造纯 DTO，不改变
     * 原版 menu。menu operation 由 handler 在验证 contract 时识别为“动作内打开”的合法基线。
     */
    private static final class ProductionPreconditionSnapshotBuilder {
        private final NativeBaseline baseline;
        private Optional<MenuFamily> openedMenu = Optional.empty();

        private ProductionPreconditionSnapshotBuilder(NativeBaseline baseline) {
            this.baseline = Objects.requireNonNull(baseline, "baseline");
        }

        private void noOpenMenu() {
            openedMenu = Optional.empty();
        }

        private void nativeInventoryMenu() {
            openedMenu = Optional.of(MenuFamily.INVENTORY_2X2);
        }

        private io.github.greytaiwolf.botplayer.skill.builtin.production
                .ProductionPreconditionSnapshot build() {
            return new io.github.greytaiwolf.botplayer.skill.builtin.production
                    .ProductionPreconditionSnapshot(
                            baseline.stateId(),
                            baseline.ledger(),
                            openedMenu,
                            baseline.cursorEmpty(),
                            Optional.empty());
        }
    }
}
