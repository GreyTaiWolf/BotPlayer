package io.github.greytaiwolf.botplayer.skill.runtime;

import io.github.greytaiwolf.botplayer.action.WorldInteractionAction;
import io.github.greytaiwolf.botplayer.action.interaction.BlockCoordinates;
import io.github.greytaiwolf.botplayer.action.interaction.BlockHitTarget;
import io.github.greytaiwolf.botplayer.action.interaction.BlockTargetFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import io.github.greytaiwolf.botplayer.action.interaction.WorldInteractionActionSpec;
import io.github.greytaiwolf.botplayer.action.interaction.menu.P5ARecipe;
import io.github.greytaiwolf.botplayer.action.minecraft.MinecraftActionSnapshot;
import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
import io.github.greytaiwolf.botplayer.skill.builtin.production.ProductionLedger;
import io.github.greytaiwolf.botplayer.skill.builtin.production.ProductionMaterial;
import io.github.greytaiwolf.botplayer.skill.builtin.production.ProductionMaterials;
import io.github.greytaiwolf.botplayer.skill.builtin.production.ProductionOperation;
import io.github.greytaiwolf.botplayer.skill.builtin.production.RecipeExecution;
import io.github.greytaiwolf.botplayer.skill.builtin.production.ResourceAcquisition;
import io.github.greytaiwolf.botplayer.skill.builtin.production.SingleChestTransfer;
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
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillNodeContext;
import io.github.greytaiwolf.botplayer.skill.task.MinecraftTaskSensorAdapter;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorAvailability;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorBudget;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorEvidence;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorQuery;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorQueryType;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorResponse;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorRunIdentity;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorScope;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorService;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorSnapshot;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.inventory.InventoryMenu;

/**
 * P5A 生产 DAG 的 Minecraft 观察、采集和配方动作端口。
 *
 * <p>这是 production handler 允许接触原版活动对象的唯一适配器。它不直接写 Inventory、
 * 方块、block entity 或 menu：库存、菜单和资源候选都先经 {@link TaskSensorService} 的当前
 * run identity/额度，再把一次冻结的 {@link WorldInteractionAction} 交给 P2 action backend。
 * 右键工作台/炉子和菜单 {@code clicked()} 都由后端在同一 action 内执行，本类只冻结现有
 * 原版方块指纹并在完成后重新观察。
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
    static final int INVENTORY_SLOTS = 41;
    static final int INVENTORY_EVIDENCE = INVENTORY_SLOTS + 1;
    static final int NATIVE_MENU_SLOTS = MenuFamily.INVENTORY_2X2.slotCount();
    static final int NATIVE_MENU_EVIDENCE = NATIVE_MENU_SLOTS + 2;
    static final int MAX_FROZEN_BINDINGS = 1_024;
    static final int MAXIMUM_RESOURCE_ACTION_TICKS = 1_200;
    static final int MAXIMUM_RECIPE_ACTION_TICKS = 1_200;

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
                    player, context, expectedResourceBlock(acquisition))
                    .orElse(null);
            if (candidate == null) {
                return Optional.empty();
            }
            binding = new ResourceBinding(
                    bot,
                    approved.operationId(),
                    context.currentTick(),
                    baseline,
                    candidate.target());
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
            return Optional.empty();
        }
        if (!(ticket.resolved().node().operation()
                instanceof ResourceAcquisition acquisition)) {
            return Optional.empty();
        }
        FrozenBinding frozen = bindings.get(ticket.before().worldBinding());
        if (!(frozen instanceof ResourceBinding binding)
                || !binding.matches(ticket)) {
            return Optional.empty();
        }
        BotServerPlayer player = resolveCurrent(ticket.bot()).orElse(null);
        if (player == null || !currentTick(player,
                ticket.before().observedAtTick())
                || !binding.baseline().matchesCurrent(player)) {
            return Optional.empty();
        }
        BlockTargetFingerprint target = binding.target();
        if (!target.state().blockId().value().equals(
                expectedResourceBlock(acquisition))
                || !isCurrentReachableBlock(player, target)) {
            return Optional.empty();
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

    @Override
    public Optional<ProductionAction> plan(ExecutionTicket ticket) {
        requireOwnerThread();
        Objects.requireNonNull(ticket, "ticket");
        if (!(ticket.resolved().node().operation()
                instanceof RecipeExecution execution)) {
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
                            0L)), context.currentTick()).orElse(null);
            if (candidates == null
                    || candidates.availability()
                            != TaskSensorAvailability.AVAILABLE) {
                return Optional.empty();
            }
            for (TaskSensorEvidence evidence : candidates.evidence()) {
                CandidateEvidence candidate = resourceCandidate(evidence)
                        .orElse(null);
                if (candidate == null) {
                    return Optional.empty();
                }
                if (!expectedBlockId.equals(candidate.blockId())) {
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
                return Optional.of(new Candidate(target));
            }
            return Optional.empty();
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
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

    private sealed interface FrozenBinding permits ResourceBinding,
            NativeRecipeBinding, WorldRecipeBinding {
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
            BlockTargetFingerprint target) implements FrozenBinding {
        private ResourceBinding {
            Objects.requireNonNull(bot, "bot");
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(baseline, "baseline");
            Objects.requireNonNull(target, "target");
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
            return isCurrentReachableBlock(player, target);
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
