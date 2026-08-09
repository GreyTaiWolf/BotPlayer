package io.github.greytaiwolf.botplayer.skill.runtime;

import io.github.greytaiwolf.botplayer.action.ActionPriority;
import io.github.greytaiwolf.botplayer.action.WorldInteractionAction;
import io.github.greytaiwolf.botplayer.action.interaction.WorldInteractionActionSpec;
import io.github.greytaiwolf.botplayer.action.interaction.menu.P5ARecipe;
import io.github.greytaiwolf.botplayer.skill.builtin.P5ABuiltinSkillIds;
import io.github.greytaiwolf.botplayer.skill.builtin.production.PlaceWorkstation;
import io.github.greytaiwolf.botplayer.skill.builtin.production.ProductionLedger;
import io.github.greytaiwolf.botplayer.skill.builtin.production.ProductionOperation;
import io.github.greytaiwolf.botplayer.skill.builtin.production.ProductionPreconditionRejection;
import io.github.greytaiwolf.botplayer.skill.builtin.production.ProductionPreconditionResult;
import io.github.greytaiwolf.botplayer.skill.builtin.production.ProductionPreconditionSnapshot;
import io.github.greytaiwolf.botplayer.skill.builtin.production.ProductionPreconditionValidator;
import io.github.greytaiwolf.botplayer.skill.builtin.production.ProductionResolvedNode;
import io.github.greytaiwolf.botplayer.skill.builtin.production.ProductionSkillPlanCompiler;
import io.github.greytaiwolf.botplayer.skill.builtin.production.RecipeExecution;
import io.github.greytaiwolf.botplayer.skill.builtin.production.ResourceAcquisition;
import io.github.greytaiwolf.botplayer.skill.builtin.production.SingleChestTransfer;
import io.github.greytaiwolf.botplayer.skill.core.SkillFailureCode;
import io.github.greytaiwolf.botplayer.skill.core.SkillParameters;
import io.github.greytaiwolf.botplayer.skill.core.SkillSignal;
import io.github.greytaiwolf.botplayer.skill.menu.MenuFamily;
import io.github.greytaiwolf.botplayer.skill.reservation.ReservationKey;
import io.github.greytaiwolf.botplayer.skill.reservation.ReservationMode;
import io.github.greytaiwolf.botplayer.skill.reservation.ReservationRequest;
import io.github.greytaiwolf.botplayer.skill.runtime.core.ActionBackedSkillNodeHandler;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillNodeContext;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillNodeDirective;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillNodeHandler;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * P5A 木头到铁镐生产 DAG 的真实动作边界。
 *
 * <p>这个 handler 不直接写 Inventory、方块或炉子，也不把活动 Minecraft 对象带进通用
 * runtime。它只解码 {@link ProductionSkillPlanCompiler} 编译进来的 operation id，在同一
 * 服务器 tick 向注入的观察端口索取账本/菜单证据，然后把一条已经冻结的
 * {@link WorldInteractionAction} 交给 {@link ActionBackedSkillNodeHandler}。动作完成后必须
 * 再次观察并逐项证明预期账本、world binding 和 menu binding；任一端口缺失、世界变化或
 * 回执不足都会失败，绝不把“未接线的采集/配方”当作成功。
 *
 * <p>{@link ResourceAcquisitionActionPort} 是 TaskSensor 到具体挖掘/采集动作的窄适配层：
 * lifecycle 注入同一份受额度约束的 {@link TaskSensorService}，端口只能据此选择已观察到的
 * 目标并返回一条冻结的原版世界动作。{@link MenuActionPort} 同样只在现有受控世界动作端点能
 * 精确表达 recipe/furnace/chest 或工作站放置合同时返回动作；当前没有可表达的动作时它应
 * 返回空，本 handler 会保守拒绝而不是伪造库存变化。
 */
public final class MinecraftProductionSkillNodeHandler
        implements SkillNodeHandler {
    /** 每个编译进来的生产节点最多占用 2,400 tick；descriptor 使用同一上界。 */
    public static final int MAXIMUM_ACTION_TICKS = 2_400;

    private static final String NATIVE_INVENTORY_SCOPE =
            "minecraft.production.inventory";
    private static final String ACQUISITION_SCOPE =
            "minecraft.production.acquire";
    private static final String MENU_SCOPE = "minecraft.production.menu";
    private static final String WORKSTATION_PLACEMENT_SCOPE =
            "minecraft.production.place-workstation";
    private static final int MAX_BINDING_LENGTH = 256;
    /** 方块已由原版打碎但掉落物尚未被身体拾取时的最大重观察次数。 */
    private static final int MAX_RESOURCE_PICKUP_POLLS = 20;

    private final ActiveBotResolver bots;
    private final ProductionObservationPort observations;
    private final TaskSensorService taskSensors;
    private final ResourceAcquisitionActionPort acquisitionActions;
    private final MenuActionPort menuActions;
    private final ProductionPreconditionValidator preconditions =
            new ProductionPreconditionValidator();
    private final ActionBackedSkillNodeHandler actionDelegate;
    private final Map<UUID, ResourcePickupPoll> resourcePickupPolls =
            new LinkedHashMap<>();
    private final Thread ownerThread;

    /**
     * @param bots 只按 botId/generation 解析当前活动 body 的生命周期端口
     * @param observations 同一服务器线程的账本、world/menu binding 观察端口
     * @param taskSensors 受通用 runtime authority/额度保护的 TaskSensor 服务
     * @param acquisitionActions TaskSensor 驱动的采集世界动作端口
     * @param menuActions 已存在的原版菜单/工作站放置动作端口；不可表达时返回空
     * @param actions P2 action mailbox 的受控提交端口
     * @param signals 通用 runtime 的异步回执 inbox
     */
    public MinecraftProductionSkillNodeHandler(
            ActiveBotResolver bots,
            ProductionObservationPort observations,
            TaskSensorService taskSensors,
            ResourceAcquisitionActionPort acquisitionActions,
            MenuActionPort menuActions,
            ActionBackedSkillNodeHandler.ActionGateway actions,
            ActionBackedSkillNodeHandler.SignalSink signals) {
        this.bots = Objects.requireNonNull(bots, "bots");
        this.observations = Objects.requireNonNull(
                observations, "observations");
        this.taskSensors = Objects.requireNonNull(taskSensors, "taskSensors");
        this.acquisitionActions = Objects.requireNonNull(
                acquisitionActions, "acquisitionActions");
        this.menuActions = Objects.requireNonNull(menuActions, "menuActions");
        actionDelegate = new ActionBackedSkillNodeHandler(
                this::planAction,
                Objects.requireNonNull(actions, "actions"),
                Objects.requireNonNull(signals, "signals"));
        ownerThread = Thread.currentThread();
    }

    /**
     * 参数只携带一个固定 operation id，所以无法安全地预留具体世界方块。先独占 Bot 的
     * 原生库存，再按编译进来的 operation/menu family 做保守全局分段，避免两个 Bot 在尚未
     * 观察到具体工作台、熔炉或采集目标之前并行抢占同一种未绑定工作。
     */
    @Override
    public List<ReservationRequest> requiredReservations(
            SkillNodeContext context) {
        Objects.requireNonNull(context, "context");
        ApprovedOperation approved = approvedOperation(
                context.node().parameters()).orElse(null);
        if (approved == null) {
            return List.of();
        }
        List<ReservationRequest> requests = new ArrayList<>(2);
        requests.add(new ReservationRequest(
                new ReservationKey(
                        ReservationKey.Kind.CONTAINER,
                        NATIVE_INVENTORY_SCOPE,
                        "bot:" + context.botId()),
                ReservationMode.EXCLUSIVE));
        requests.add(new ReservationRequest(
                operationReservation(approved), ReservationMode.EXCLUSIVE));
        return List.copyOf(requests);
    }

    @Override
    public SkillNodeDirective begin(SkillNodeContext context) {
        requireOwnerThread();
        Objects.requireNonNull(context, "context");
        Preparation preparation = prepare(context);
        if (!preparation.ready()) {
            return preparation.failure().orElseThrow();
        }
        /*
         * ActionBacked 会在实际提交前重新 prepare。两次观察都必须是当前 tick 的完整证据；
         * 这使第一次检查与 action 入队之间的菜单/方块漂移只能拒绝，而不会复用旧 snapshot。
         */
        return actionDelegate.begin(context);
    }

    @Override
    public SkillNodeDirective signal(
            SkillNodeContext context, SkillSignal signal) {
        requireOwnerThread();
        return actionDelegate.signal(context, signal);
    }

    /**
     * {@link WorldInteractionActionSpec.BreakBlock} 只证明方块已经由原版破坏；掉落实体到达
     * 玩家背包可能晚一小段 tick。若且仅若第一次完成观察证明所有 binding/menu 都仍准确、
     * 玩家白名单账本恰好尚未变化，才有限重观察；任何额外物品变化、菜单漂移或超时都失败，
     * 不会把等待当成补发物品的授权。
     */
    @Override
    public SkillNodeDirective tick(SkillNodeContext context) {
        requireOwnerThread();
        Objects.requireNonNull(context, "context");
        ResourcePickupPoll poll = resourcePickupPolls.get(context.runId());
        if (poll == null) {
            return SkillNodeDirective.continueRunning("生产节点继续运行");
        }
        if (!poll.matches(context)) {
            resourcePickupPolls.remove(context.runId(), poll);
            return SkillNodeDirective.fail(
                    SkillFailureCode.WORLD_CHANGED,
                    "资源掉落等待期间节点身份或 operation 已变化");
        }
        SkillNodeDirective verified = verify(poll.ticket(), context,
                poll.signal());
        if (!mayAwaitResourcePickup(poll.ticket(), context, poll.signal(),
                verified)) {
            resourcePickupPolls.remove(context.runId(), poll);
            return verified;
        }
        if (poll.remainingPolls() <= 1) {
            resourcePickupPolls.remove(context.runId(), poll);
            return SkillNodeDirective.fail(
                    SkillFailureCode.MISSING_ITEM,
                    "原版方块已破坏，但资源掉落在有限等待内未进入背包");
        }
        resourcePickupPolls.put(context.runId(), poll.next());
        return SkillNodeDirective.continueRunning(
                "等待原版资源掉落进入背包（剩余 "
                        + (poll.remainingPolls() - 1) + " tick）");
    }

    @Override
    public void cancelled(SkillNodeContext context, String reason) {
        requireOwnerThread();
        resourcePickupPolls.remove(context.runId());
        actionDelegate.cancelled(context, reason);
    }

    /**
     * 精确 schema：一个字符串 operation.id，且必须能回查到当前编译器的 canonical node。
     * 未知参数、额外键、数值伪装和未来 operation 一律不进入 handler。
     */
    public static Optional<ApprovedOperation> approvedOperation(
            SkillParameters parameters) {
        Objects.requireNonNull(parameters, "parameters");
        Map<String, Object> values = parameters.values();
        if (!values.keySet().equals(SetHolder.OPERATION_PARAMETER_ONLY)) {
            return Optional.empty();
        }
        Object value = values.get(
                P5ABuiltinSkillIds.BOOTSTRAP_IRON_OPERATION_ID_PARAMETER);
        if (!(value instanceof String operationId)) {
            return Optional.empty();
        }
        return ProductionSkillPlanCompiler.approvedOperation(operationId)
                .map(resolved -> new ApprovedOperation(operationId, resolved));
    }

    private Optional<ActionBackedSkillNodeHandler.Operation> planAction(
            SkillNodeContext context) {
        Preparation preparation = prepare(context);
        if (!preparation.ready()) {
            return Optional.empty();
        }
        ExecutionTicket ticket = preparation.ticket().orElseThrow();
        ProductionAction action = planConcreteAction(ticket).orElse(null);
        if (action == null || !actionMatchesOperation(ticket, action)) {
            return Optional.empty();
        }
        boolean menuOperation = ticket.resolved().menuContract().isPresent();
        return Optional.of(new ActionBackedSkillNodeHandler.Operation(
                operationKey(ticket.resolved().node().operation()),
                action.action(),
                ActionPriority.AUTONOMOUS,
                action.maximumTicks(),
                menuOperation
                        ? SkillNodeDirective.Kind.WAIT_MENU
                        : SkillNodeDirective.Kind.WAIT_ACTION,
                action.waitingSummary(),
                (signalContext, signal) -> ticket.resolved().node().operation()
                        instanceof ResourceAcquisition
                                ? verifyResourceAction(ticket,
                                        signalContext, signal)
                                : verify(ticket, signalContext, signal)));
    }

    private Optional<ProductionAction> planConcreteAction(
            ExecutionTicket ticket) {
        ProductionOperation operation = ticket.resolved().node().operation();
        try {
            if (operation instanceof ResourceAcquisition) {
                return acquisitionActions.plan(ticket, taskSensors);
            }
            if (operation instanceof RecipeExecution
                    || operation instanceof SingleChestTransfer
                    || operation instanceof PlaceWorkstation) {
                return menuActions.plan(ticket);
            }
            return Optional.empty();
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    /**
     * 不让动作端口拿一个“等待掉落”“使用物品”之类的泛动作冒充生产步骤。采集必须真实走
     * {@code BREAK_BLOCK}；配方和单箱转移分别必须走已有的严格菜单动作类别；工作站必须走
     * 完整 {@code PLACE_BLOCK}。这样即使未来端口实现有缺口，也只会在提交前拒绝，而不会留下
     * 可由外部账本伪造掩盖的成功路径。
     */
    private static boolean actionMatchesOperation(
            ExecutionTicket ticket, ProductionAction action) {
        WorldInteractionActionSpec.Kind actionKind = action.action()
                .spec().kind();
        ProductionOperation operation = ticket.resolved().node().operation();
        if (operation instanceof ResourceAcquisition) {
            return actionKind == WorldInteractionActionSpec.Kind.BREAK_BLOCK;
        }
        if (operation instanceof RecipeExecution) {
            if (actionKind
                    != WorldInteractionActionSpec.Kind.WORLD_MENU_RECIPE
                    || !(action.action().spec()
                            instanceof WorldInteractionActionSpec
                                    .WorldMenuRecipe recipeAction)) {
                return false;
            }
            RecipeExecution execution = (RecipeExecution) operation;
            return P5ARecipe.find(execution.recipeId().value())
                    .filter(recipe -> recipe == recipeAction.recipe()
                            && execution.batches()
                                    == recipeAction.batches())
                    .isPresent();
        }
        if (operation instanceof SingleChestTransfer) {
            return actionKind
                    == WorldInteractionActionSpec.Kind.WORLD_MENU_TRANSFER;
        }
        if (operation instanceof PlaceWorkstation placement) {
            if (actionKind != WorldInteractionActionSpec.Kind.PLACE_BLOCK
                    || !(action.action().spec()
                            instanceof WorldInteractionActionSpec.PlaceBlock
                                    placeBlock)) {
                return false;
            }
            return !placeBlock.expectedHeldItem().isEmpty()
                    && placeBlock.expectedHeldItem().itemId().filter(
                            placement.workstation().material().id()::equals)
                    .isPresent()
                    && placement.workstation().matchesExpectedPlacedState(
                            placeBlock.expectedPlaced().state());
        }
        return false;
    }

    private SkillNodeDirective verifyResourceAction(
            ExecutionTicket ticket,
            SkillNodeContext context,
            SkillSignal signal) {
        SkillNodeDirective verified = verify(ticket, context, signal);
        if (!mayAwaitResourcePickup(ticket, context, signal, verified)) {
            return verified;
        }
        resourcePickupPolls.put(context.runId(), new ResourcePickupPoll(
                ticket,
                signal,
                context.node().nodeId(),
                MAX_RESOURCE_PICKUP_POLLS));
        return SkillNodeDirective.continueRunning(
                "方块已由原版破坏，有限等待资源掉落进入背包");
    }

    /**
     * 只有常规 verify 唯一因玩家账本仍为原样而失败时才能轮询。这里重新读取同一端口的
     * 当前 tick 观察，而非依赖 action 的“inventory.changed”文字证据；TaskSensor 可能命中
     * 本 tick cache，但 binding、cursor、menu 和完整白名单账本仍必须逐项重新满足。
     */
    private boolean mayAwaitResourcePickup(
            ExecutionTicket ticket,
            SkillNodeContext context,
            SkillSignal signal,
            SkillNodeDirective verified) {
        if (!(ticket.resolved().node().operation()
                instanceof ResourceAcquisition)
                || verified.kind() != SkillNodeDirective.Kind.FAIL
                || verified.failureCode().orElse(null)
                        != SkillFailureCode.ITEM_CONSERVATION_VIOLATION) {
            return false;
        }
        CompletionObservation completion;
        try {
            completion = observations.observeAfterAction(ticket, context,
                    signal).orElse(null);
        } catch (RuntimeException exception) {
            return false;
        }
        return completion != null
                && completion.observedAtTick() == context.currentTick()
                && ticket.before().worldBinding().equals(
                        completion.worldBinding())
                && ticket.before().menuBinding().equals(
                        completion.menuBinding())
                && completion.worldBindingSatisfied()
                && completion.menuBindingSatisfied()
                && completion.snapshot().cursorEmpty()
                && completion.snapshot().chestLedger().equals(
                        ticket.before().snapshot().chestLedger())
                && completion.snapshot().playerLedger().equals(
                        ticket.before().snapshot().playerLedger());
    }

    private Preparation prepare(SkillNodeContext context) {
        ApprovedOperation approved = approvedOperation(
                context.node().parameters()).orElse(null);
        if (approved == null || !matchesBootstrapDescriptor(context)) {
            return Preparation.failed(SkillNodeDirective.fail(
                    SkillFailureCode.INVALID_PARAMETERS,
                    "生产节点参数或 descriptor 不符合已审核的 bootstrap iron 合同"));
        }
        ActiveBot bot;
        try {
            bot = bots.resolve(context.botId(), context.botGeneration())
                    .filter(value -> value.botId().equals(context.botId())
                            && value.generation()
                            == context.botGeneration())
                    .orElse(null);
        } catch (RuntimeException exception) {
            bot = null;
        }
        if (bot == null) {
            return Preparation.failed(SkillNodeDirective.fail(
                    SkillFailureCode.BOT_NOT_ACTIVE,
                    "生产节点开始时 BotPlayer 已不再是当前活动 body"));
        }
        PreflightObservation observation;
        try {
            observation = observations.observeForDispatch(
                    bot, context, approved).orElse(null);
        } catch (RuntimeException exception) {
            observation = null;
        }
        if (observation == null
                || observation.observedAtTick() != context.currentTick()) {
            return Preparation.failed(SkillNodeDirective.fail(
                    SkillFailureCode.WORLD_CHANGED,
                    "生产节点没有当前 tick 的完整世界与菜单观察"));
        }
        if (approved.resolved().node().operation()
                instanceof ResourceAcquisition
                && (observation.snapshot().openedMenuFamily().isPresent()
                || !observation.snapshot().cursorEmpty())) {
            return Preparation.failed(SkillNodeDirective.fail(
                    SkillFailureCode.CONTAINER_CHANGED,
                    "资源采集不能在打开菜单或携带 cursor 的状态开始"));
        }
        ProductionPreconditionResult result;
        try {
            result = preconditions.validate(approved.resolved(),
                    validationSnapshot(approved.resolved(),
                            observation.snapshot()));
        } catch (RuntimeException exception) {
            return Preparation.failed(SkillNodeDirective.fail(
                    SkillFailureCode.WORLD_CHANGED,
                    "生产节点的前置账本或菜单观察不完整"));
        }
        if (!result.ready()) {
            return Preparation.failed(SkillNodeDirective.fail(
                    mapRejection(result.rejection().orElseThrow()),
                    "生产节点前置条件未满足："
                            + result.rejection().orElseThrow().name()));
        }
        return Preparation.ready(new ExecutionTicket(
                bot,
                approved.operationId(),
                approved.resolved(),
                observation,
                result));
    }

    /**
     * {@code WorldMenuRecipe} 会在同一受控 action 内从原生 {@link MenuFamily#INVENTORY_2X2}
     * 打开工作台或炉子。因而端口可以且必须在打开前只报告真实 native baseline；这里仅把
     * 那个已经空 cursor 的 baseline 投影为审核合同所需的目标 menu family，绝不允许调用方
     * 用任意已打开的菜单、非空 cursor 或 chest ledger 冒充原子 world-menu 前置条件。
     */
    private static ProductionPreconditionSnapshot validationSnapshot(
            ProductionResolvedNode resolved,
            ProductionPreconditionSnapshot observed) {
        Objects.requireNonNull(resolved, "resolved");
        Objects.requireNonNull(observed, "observed");
        if (!(resolved.node().operation() instanceof RecipeExecution)
                || resolved.menuContract().isEmpty()
                || resolved.menuContract().orElseThrow().family()
                        == MenuFamily.INVENTORY_2X2
                || !observed.openedMenuFamily().equals(
                        Optional.of(MenuFamily.INVENTORY_2X2))
                || !observed.cursorEmpty()
                || observed.chestLedger().isPresent()) {
            return observed;
        }
        return new ProductionPreconditionSnapshot(
                observed.observationRevision(),
                observed.playerLedger(),
                Optional.of(resolved.menuContract().orElseThrow().family()),
                true,
                Optional.empty());
    }

    private SkillNodeDirective verify(
            ExecutionTicket ticket,
            SkillNodeContext context,
            SkillSignal signal) {
        ApprovedOperation current = approvedOperation(
                context.node().parameters()).orElse(null);
        if (current == null
                || !matchesBootstrapDescriptor(context)
                || !current.operationId().equals(ticket.operationId())
                || !current.resolved().equals(ticket.resolved())) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.INVALID_PARAMETERS,
                    "生产动作回执时 operation id 或 descriptor 已经变化");
        }
        ActiveBot active;
        try {
            active = bots.resolve(context.botId(), context.botGeneration())
                    .filter(ticket.bot()::equals)
                    .orElse(null);
        } catch (RuntimeException exception) {
            active = null;
        }
        if (active == null) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.STALE_GENERATION,
                    "生产动作完成时 BotPlayer 代际已经变化");
        }
        if (signal.evidence().isEmpty()) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.ACTION_FAILED,
                    "生产动作回执缺少原版结果证据");
        }
        CompletionObservation completion;
        try {
            completion = observations.observeAfterAction(
                    ticket, context, signal).orElse(null);
        } catch (RuntimeException exception) {
            completion = null;
        }
        if (completion == null
                || completion.observedAtTick() != context.currentTick()) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.WORLD_CHANGED,
                    "生产动作完成后没有当前 tick 的权威观察");
        }
        if (!ticket.before().worldBinding().equals(
                completion.worldBinding())) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.WORLD_CHANGED,
                    "生产动作完成时观察到不同的世界 binding");
        }
        if (!ticket.before().menuBinding().equals(
                completion.menuBinding())) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.CONTAINER_CHANGED,
                    "生产动作完成时观察到不同的菜单 binding");
        }
        if (!completion.worldBindingSatisfied()) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.WORLD_CHANGED,
                    "生产动作的冻结世界 binding 已经漂移");
        }
        if (!completion.menuBindingSatisfied()) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.CONTAINER_CHANGED,
                    "生产动作的冻结菜单 binding 已经漂移");
        }
        ProductionPreconditionSnapshot after = completion.snapshot();
        if (!after.cursorEmpty()) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.CONTAINER_CHANGED,
                    "生产动作没有以空 cursor 的已核验菜单状态收口");
        }
        if (!ticket.expected().expectedPlayerAfter().orElseThrow()
                .equals(after.playerLedger())) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.ITEM_CONSERVATION_VIOLATION,
                    "生产动作后的玩家账本不等于已审核 delta");
        }
        Optional<ProductionLedger> expectedChest = ticket.expected()
                        .expectedChestAfter();
        if (expectedChest.isPresent()) {
            if (!expectedChest.equals(after.chestLedger())) {
                return SkillNodeDirective.fail(
                        SkillFailureCode.ITEM_CONSERVATION_VIOLATION,
                        "生产动作后的箱子账本不等于已审核 delta");
            }
        } else if (!after.chestLedger().equals(
                ticket.before().snapshot().chestLedger())) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.CONTAINER_CHANGED,
                    "非箱子生产动作意外改变了已观察到的箱子账本");
        }
        return SkillNodeDirective.complete("已通过原版动作与账本回读确认生产步骤");
    }

    private static ReservationKey operationReservation(
            ApprovedOperation approved) {
        ProductionOperation operation = approved.resolved().node().operation();
        if (operation instanceof ResourceAcquisition) {
            return new ReservationKey(
                    ReservationKey.Kind.WORK_AREA,
                    ACQUISITION_SCOPE,
                    approved.operationId());
        }
        if (operation instanceof PlaceWorkstation) {
            return new ReservationKey(
                    ReservationKey.Kind.WORK_AREA,
                    WORKSTATION_PLACEMENT_SCOPE,
                    approved.operationId());
        }
        MenuFamily family = approved.resolved().menuContract()
                .orElseThrow()
                .family();
        return new ReservationKey(
                ReservationKey.Kind.CONTAINER,
                MENU_SCOPE,
                family.name().toLowerCase(java.util.Locale.ROOT));
    }

    private static String operationKey(ProductionOperation operation) {
        if (operation instanceof ResourceAcquisition) {
            return "production-acquire";
        }
        if (operation instanceof RecipeExecution) {
            return "production-recipe";
        }
        if (operation instanceof SingleChestTransfer) {
            return "production-transfer";
        }
        if (operation instanceof PlaceWorkstation) {
            return "production-place-workstation";
        }
        throw new IllegalArgumentException("unsupported production operation");
    }

    private static boolean matchesBootstrapDescriptor(SkillNodeContext context) {
        return context.node().skillId().equals(P5ABuiltinSkillIds.BOOTSTRAP_IRON)
                && context.node().skillVersion().equals(
                        P5ABuiltinSkillIds.VERSION);
    }

    private static SkillFailureCode mapRejection(
            ProductionPreconditionRejection rejection) {
        return switch (Objects.requireNonNull(rejection, "rejection")) {
            case PLAYER_LEDGER_INSUFFICIENT -> SkillFailureCode.MISSING_ITEM;
            case CHEST_LEDGER_INSUFFICIENT -> SkillFailureCode.MISSING_ITEM;
            case CHEST_LEDGER_MISSING,
                    UNEXPECTED_MENU_OPEN,
                    CURSOR_NOT_EMPTY,
                    MENU_FAMILY_MISMATCH -> SkillFailureCode.CONTAINER_CHANGED;
            case MENU_NOT_OPEN -> SkillFailureCode.MENU_UNSUPPORTED;
        };
    }

    private void requireOwnerThread() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException(
                    "production skill node handler requires server thread");
        }
    }

    /** lifecycle 向 handler 暴露的当前 body 身份，不包含活动 Minecraft 对象。 */
    @FunctionalInterface
    public interface ActiveBotResolver {
        Optional<ActiveBot> resolve(UUID botId, long generation);
    }

    /** 只保存稳定 identity；具体 Minecraft body 必须由每次端口调用重新解析。 */
    public record ActiveBot(UUID botId, long generation) {
        public ActiveBot {
            requireNonZero(botId, "botId");
            if (generation <= 0L) {
                throw new IllegalArgumentException(
                        "production active bot generation must be positive");
            }
        }
    }

    /** operation id 和 compiler 产物必须成对传播，禁止调用方自建 resolved node。 */
    public record ApprovedOperation(
            String operationId, ProductionResolvedNode resolved) {
        public ApprovedOperation {
            operationId = requireBinding(operationId, "operationId");
            Objects.requireNonNull(resolved, "resolved");
            if (ProductionSkillPlanCompiler.approvedOperation(operationId)
                    .filter(resolved::equals).isEmpty()) {
                throw new IllegalArgumentException(
                        "production operation is not compiler-approved");
            }
        }
    }

    /**
     * 当前 tick 的 preflight 观察。world/menu binding 是适配层冻结进具体动作的安全摘要，
     * 可含不可变坐标/维度/配方或 menu family，但不能是菜单对象、window id 或其他活动
     * Minecraft 对象。
     */
    public record PreflightObservation(
            ProductionPreconditionSnapshot snapshot,
            long observedAtTick,
            String worldBinding,
            String menuBinding) {
        public PreflightObservation {
            snapshot = Objects.requireNonNull(snapshot, "snapshot");
            if (observedAtTick < 0L) {
                throw new IllegalArgumentException(
                        "production preflight tick must be non-negative");
            }
            worldBinding = requireBinding(worldBinding, "worldBinding");
            menuBinding = requireBinding(menuBinding, "menuBinding");
        }
    }

    /**
     * 供动作工厂使用的不可变票据。它不含 player、level、block entity 或 live menu；工厂须
     * 自行按 {@link ActiveBot} 重新解析 body，并把 preflight binding 写入原版动作的验证边界。
     */
    public record ExecutionTicket(
            ActiveBot bot,
            String operationId,
            ProductionResolvedNode resolved,
            PreflightObservation before,
            ProductionPreconditionResult expected) {
        public ExecutionTicket {
            bot = Objects.requireNonNull(bot, "bot");
            operationId = requireBinding(operationId, "operationId");
            resolved = Objects.requireNonNull(resolved, "resolved");
            before = Objects.requireNonNull(before, "before");
            expected = Objects.requireNonNull(expected, "expected");
            if (!expected.ready()
                    || expected.expectedPlayerAfter().isEmpty()
                    || ProductionSkillPlanCompiler.approvedOperation(operationId)
                            .filter(resolved::equals).isEmpty()) {
                throw new IllegalArgumentException(
                        "production execution ticket must carry an approved ready contract");
            }
        }
    }

    /**
     * 完成时重新读取的权威证据。binding boolean 由 Minecraft 适配层针对实际动作判断：
     * 采集动作可以允许目标方块按预期消失，而不能允许目标替换、维度切换或菜单 session 漂移；
     * {@code menuBindingSatisfied} 还必须证明世界菜单已经关闭回该动作允许的基线（原生
     * {@code InventoryMenu} 是否以 {@link MenuFamily#INVENTORY_2X2} 表示由适配层统一）。
     */
    public record CompletionObservation(
            ProductionPreconditionSnapshot snapshot,
            long observedAtTick,
            String worldBinding,
            String menuBinding,
            boolean worldBindingSatisfied,
            boolean menuBindingSatisfied) {
        public CompletionObservation {
            snapshot = Objects.requireNonNull(snapshot, "snapshot");
            if (observedAtTick < 0L) {
                throw new IllegalArgumentException(
                        "production completion tick must be non-negative");
            }
            worldBinding = requireBinding(worldBinding, "worldBinding");
            menuBinding = requireBinding(menuBinding, "menuBinding");
        }
    }

    /**
     * 唯一允许 handler 读取世界/菜单的注入端口。端口实现必须在服务器线程运行，并通过
     * {@link ExecutionTicket#before()} 中的 binding 做动作前后关联；返回空表示观察无法证实。
     */
    public interface ProductionObservationPort {
        Optional<PreflightObservation> observeForDispatch(
                ActiveBot bot,
                SkillNodeContext context,
                ApprovedOperation operation);

        Optional<CompletionObservation> observeAfterAction(
                ExecutionTicket ticket,
                SkillNodeContext context,
                SkillSignal signal);
    }

    /**
     * TaskSensor 驱动的资源采集端口。它不得给物品、直接破方块或复用上一次 query 的目标；
     * 只能在传入的 service 额度内查询，并在成功时交出一条冻结的原版世界动作。
     */
    @FunctionalInterface
    public interface ResourceAcquisitionActionPort {
        Optional<ProductionAction> plan(
                ExecutionTicket ticket, TaskSensorService taskSensors);
    }

    /**
     * 具体菜单 recipe/furnace/chest 或工作站放置动作端口。现有 action backend 不能精确表达
     * 某个受审核合同（包括完整的 {@code PlaceBlock}）时应返回空；handler 将以
     * ACTION_REJECTED 结束该节点，而不是用账本模拟成功。
     */
    @FunctionalInterface
    public interface MenuActionPort {
        Optional<ProductionAction> plan(ExecutionTicket ticket);
    }

    /**
     * 工厂返回的唯一可执行载体。限制为 {@link WorldInteractionAction}，并在提交前由
     * {@link #actionMatchesOperation(ExecutionTicket, ProductionAction)} 进一步限制为本节点
     * 对应的 {@code BREAK_BLOCK}/{@code WORLD_MENU_RECIPE}/{@code WORLD_MENU_TRANSFER}/
     * {@code PLACE_BLOCK} 种类，从类型上排除 command 或直接库存写入这类不能证明真实世界
     * 结果的替代路径。
     */
    public record ProductionAction(
            WorldInteractionAction action,
            int maximumTicks,
            String waitingSummary) {
        public ProductionAction {
            action = Objects.requireNonNull(action, "action");
            if (maximumTicks < 1 || maximumTicks > MAXIMUM_ACTION_TICKS) {
                throw new IllegalArgumentException(
                        "production action ticks must be within 1.."
                                + MAXIMUM_ACTION_TICKS);
            }
            waitingSummary = requireSummary(waitingSummary);
        }
    }

    /**
     * 只保存纯 ticket、原 action signal 和当前 node id；不缓存掉落实体、player 或世界对象。
     */
    private record ResourcePickupPoll(
            ExecutionTicket ticket,
            SkillSignal signal,
            UUID nodeId,
            int remainingPolls) {
        private ResourcePickupPoll {
            ticket = Objects.requireNonNull(ticket, "ticket");
            signal = Objects.requireNonNull(signal, "signal");
            nodeId = Objects.requireNonNull(nodeId, "nodeId");
            if (remainingPolls < 1
                    || remainingPolls > MAX_RESOURCE_PICKUP_POLLS) {
                throw new IllegalArgumentException(
                        "resource pickup poll count is outside its bound");
            }
        }

        private boolean matches(SkillNodeContext context) {
            return ticket.bot().botId().equals(context.botId())
                    && ticket.bot().generation() == context.botGeneration()
                    && nodeId.equals(context.node().nodeId())
                    && ticket.operationId().equals(
                            approvedOperation(context.node().parameters())
                                    .map(ApprovedOperation::operationId)
                                    .orElse(null));
        }

        private ResourcePickupPoll next() {
            return new ResourcePickupPoll(ticket, signal, nodeId,
                    remainingPolls - 1);
        }
    }

    private record Preparation(
            Optional<ExecutionTicket> ticket,
            Optional<SkillNodeDirective> failure) {
        private Preparation {
            ticket = Objects.requireNonNull(ticket, "ticket");
            failure = Objects.requireNonNull(failure, "failure");
            if (ticket.isPresent() == failure.isPresent()) {
                throw new IllegalArgumentException(
                        "production preparation must contain exactly one outcome");
            }
        }

        private static Preparation ready(ExecutionTicket ticket) {
            return new Preparation(Optional.of(Objects.requireNonNull(
                    ticket, "ticket")), Optional.empty());
        }

        private static Preparation failed(SkillNodeDirective failure) {
            if (Objects.requireNonNull(failure, "failure").kind()
                    != SkillNodeDirective.Kind.FAIL) {
                throw new IllegalArgumentException(
                        "production preparation failure must be terminal fail");
            }
            return new Preparation(Optional.empty(), Optional.of(failure));
        }

        private boolean ready() {
            return ticket.isPresent();
        }
    }

    private static String requireBinding(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > MAX_BINDING_LENGTH
                || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    name + " must be a safe non-blank bounded value");
        }
        return value;
    }

    private static String requireSummary(String value) {
        Objects.requireNonNull(value, "waitingSummary");
        if (value.isBlank()
                || value.length() > SkillNodeDirective.MAX_SUMMARY_LENGTH
                || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    "waitingSummary must be a safe non-blank bounded value");
        }
        return value;
    }

    private static void requireNonZero(UUID value, String name) {
        Objects.requireNonNull(value, name);
        if (value.getMostSignificantBits() == 0L
                && value.getLeastSignificantBits() == 0L) {
            throw new IllegalArgumentException(name + " must not be zero UUID");
        }
    }

    private static final class SetHolder {
        private static final java.util.Set<String> OPERATION_PARAMETER_ONLY =
                java.util.Set.of(
                        P5ABuiltinSkillIds
                                .BOOTSTRAP_IRON_OPERATION_ID_PARAMETER);

        private SetHolder() {
        }
    }
}
