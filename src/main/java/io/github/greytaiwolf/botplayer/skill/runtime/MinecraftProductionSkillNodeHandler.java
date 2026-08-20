package io.github.greytaiwolf.botplayer.skill.runtime;

import io.github.greytaiwolf.botplayer.BotPlayer;
import io.github.greytaiwolf.botplayer.action.ActionEvidence;
import io.github.greytaiwolf.botplayer.action.ActionPriority;
import io.github.greytaiwolf.botplayer.action.WorldInteractionAction;
import io.github.greytaiwolf.botplayer.action.interaction.WorldInteractionActionSpec;
import io.github.greytaiwolf.botplayer.action.interaction.menu.P5ARecipe;
import io.github.greytaiwolf.botplayer.navigation.GridPoint;
import io.github.greytaiwolf.botplayer.navigation.NavigationArrivalRequirement;
import io.github.greytaiwolf.botplayer.navigation.NavigationFailure;
import io.github.greytaiwolf.botplayer.navigation.NavigationGoal;
import io.github.greytaiwolf.botplayer.navigation.NavigationOutcome;
import io.github.greytaiwolf.botplayer.navigation.NavigationPolicy;
import io.github.greytaiwolf.botplayer.navigation.NavigationRequest;
import io.github.greytaiwolf.botplayer.navigation.NavigationService;
import io.github.greytaiwolf.botplayer.navigation.NavigationSubmission;
import io.github.greytaiwolf.botplayer.skill.builtin.P5ABuiltinSkillIds;
import io.github.greytaiwolf.botplayer.skill.builtin.production.PlaceWorkstation;
import io.github.greytaiwolf.botplayer.skill.builtin.production.ProductionLedger;
import io.github.greytaiwolf.botplayer.skill.builtin.production.ProductionMaterial;
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
import io.github.greytaiwolf.botplayer.skill.core.SkillSignalStatus;
import io.github.greytaiwolf.botplayer.skill.core.SkillSignalType;
import io.github.greytaiwolf.botplayer.skill.menu.MenuFamily;
import io.github.greytaiwolf.botplayer.skill.reservation.ReservationKey;
import io.github.greytaiwolf.botplayer.skill.reservation.ReservationMode;
import io.github.greytaiwolf.botplayer.skill.reservation.ReservationRequest;
import io.github.greytaiwolf.botplayer.skill.runtime.core.ActionBackedSkillNodeHandler;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillNodeContext;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillNodeDirective;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillNodeHandler;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillNodeHandler.FailedSignalDisposition;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

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
    /** 破块后等待受限 TaskSensor 首次看到新掉落实体的最大观察次数。 */
    private static final int MAX_RESOURCE_DROP_OBSERVATIONS = 40;
    /** 实体跨过已冻结格点时，至多重新导航一次；绝不无限追逐动态实体。 */
    private static final int MAX_RESOURCE_DROP_RETARGETS = 1;
    /** 单一掉落实体的局部收集导航不应占满整个生产节点预算。 */
    private static final int MAXIMUM_RESOURCE_DROP_NAVIGATION_TICKS = 240;
    /** 到达精确掉落实体附近后，仅允许有限 collision-driven PickupWait。 */
    private static final int MAXIMUM_RESOURCE_DROP_PICKUP_TICKS = 80;
    /** PickupWait reaches verification at its wait bound, before this envelope expires. */
    private static final int MAXIMUM_RESOURCE_DROP_PICKUP_ACTION_TICKS =
            MAXIMUM_RESOURCE_DROP_PICKUP_TICKS + 1;
    /** Exact UUID-drop goals are deliberately approached without sprint overshoot. */
    private static final NavigationPolicy RESOURCE_DROP_NAVIGATION_POLICY =
            NavigationPolicy.safeDefault().withSprint(false);
    private static final String DROP_NAVIGATION_EVIDENCE_KEY =
            "navigation.resource-drop";
    private static final String BREAK_DROP_ENTITY_ID_EVIDENCE_KEY =
            "block.drop.entity.id";
    private static final String BREAK_DROP_ITEM_EVIDENCE_KEY =
            "block.drop.item";
    private static final String BREAK_DROP_COUNT_EVIDENCE_KEY =
            "block.drop.count";
    private static final String DROP_NAVIGATION_CANCELLATION_REASON =
            "P5A 资源掉落收集节点已被取消";
    /** A single fresh observation is permitted only after a marked no-packet facing fence. */
    private static final int MAXIMUM_WORKSTATION_PRE_DISPATCH_REPLANS = 1;

    private final ActiveBotResolver bots;
    private final ProductionObservationPort observations;
    private final TaskSensorService taskSensors;
    private final ResourceAcquisitionActionPort acquisitionActions;
    private final MenuActionPort menuActions;
    private final ProductionPreconditionValidator preconditions =
            new ProductionPreconditionValidator();
    private final ActionBackedSkillNodeHandler actionDelegate;
    private final ActionBackedSkillNodeHandler pickupActionDelegate;
    private final ResourceDropNavigationGateway resourceDropNavigation;
    private final ActionBackedSkillNodeHandler.SignalSink signals;
    private final Map<UUID, ResourceDropCollection> resourceDropCollections =
            new LinkedHashMap<>();
    private final Map<UUID, WorkstationPlacementReplan>
            workstationPlacementReplans = new LinkedHashMap<>();
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
        this(bots, observations, taskSensors, acquisitionActions, menuActions,
                unavailableResourceDropNavigation(), actions, signals);
    }

    /**
     * 生命周期构造器额外注入受控导航服务，用于破块后的短暂掉落实体收集；计划本身仍不
     * 保存实体 UUID 或坐标。
     */
    public MinecraftProductionSkillNodeHandler(
            ActiveBotResolver bots,
            ProductionObservationPort observations,
            TaskSensorService taskSensors,
            ResourceAcquisitionActionPort acquisitionActions,
            MenuActionPort menuActions,
            NavigationService navigationService,
            ActionBackedSkillNodeHandler.ActionGateway actions,
            ActionBackedSkillNodeHandler.SignalSink signals) {
        this(bots, observations, taskSensors, acquisitionActions, menuActions,
                resourceDropNavigationGateway(navigationService), actions,
                signals);
    }

    MinecraftProductionSkillNodeHandler(
            ActiveBotResolver bots,
            ProductionObservationPort observations,
            TaskSensorService taskSensors,
            ResourceAcquisitionActionPort acquisitionActions,
            MenuActionPort menuActions,
            ResourceDropNavigationGateway resourceDropNavigation,
            ActionBackedSkillNodeHandler.ActionGateway actions,
            ActionBackedSkillNodeHandler.SignalSink signals) {
        this.bots = Objects.requireNonNull(bots, "bots");
        this.observations = Objects.requireNonNull(
                observations, "observations");
        this.taskSensors = Objects.requireNonNull(taskSensors, "taskSensors");
        this.acquisitionActions = Objects.requireNonNull(
                acquisitionActions, "acquisitionActions");
        this.menuActions = Objects.requireNonNull(menuActions, "menuActions");
        this.resourceDropNavigation = Objects.requireNonNull(
                resourceDropNavigation, "resourceDropNavigation");
        ActionBackedSkillNodeHandler.ActionGateway actionGateway =
                Objects.requireNonNull(actions, "actions");
        ActionBackedSkillNodeHandler.SignalSink signalSink =
                Objects.requireNonNull(signals, "signals");
        this.signals = signalSink;
        actionDelegate = new ActionBackedSkillNodeHandler(
                this::planAction,
                actionGateway,
                signalSink);
        pickupActionDelegate = new ActionBackedSkillNodeHandler(
                this::planPickupAction,
                actionGateway,
                signalSink);
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
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(signal, "signal");
        ResourceDropCollection collection = resourceDropCollections.get(
                context.runId());
        if (collection instanceof PendingResourceDropNavigation pending) {
            return handleResourceDropNavigationSignal(context, signal, pending);
        }
        if (collection instanceof PendingResourceDropPickup pending) {
            if (signal.type() != SkillSignalType.ACTION) {
                resourceDropCollections.remove(context.runId(), pending);
                return SkillNodeDirective.fail(
                        SkillFailureCode.INTERNAL_ERROR,
                        "资源掉落收集等待原版拾取动作时收到了错误回执类型");
            }
            SkillNodeDirective result = pickupActionDelegate.signal(context,
                    signal);
            if (result.kind() == SkillNodeDirective.Kind.COMPLETE
                    || result.kind() == SkillNodeDirective.Kind.FAIL) {
                resourceDropCollections.remove(context.runId(), pending);
            }
            return result;
        }
        SkillNodeDirective result = actionDelegate.signal(context, signal);
        if (signal.status() == SkillSignalStatus.SUCCEEDED) {
            workstationPlacementReplans.remove(context.runId());
        }
        return result;
    }

    /**
     * The sole P5A action-replan exception.  It is intentionally narrower than
     * a generic retry: the backend must have returned exactly the reviewed
     * no-native-packet facing-drift receipt, and the action bridge must consume
     * its private completion capability before the runtime releases the node.
     */
    @Override
    public FailedSignalDisposition failed(
            SkillNodeContext context, SkillSignal signal) {
        requireOwnerThread();
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(signal, "signal");
        ApprovedOperation approved = approvedOperation(
                context.node().parameters()).orElse(null);
        if (approved == null
                || !matchesBootstrapDescriptor(context)
                || !(approved.resolved().node().operation()
                        instanceof PlaceWorkstation placement)
                || placement.workstation().furnaceKind().isEmpty()
                || !isMarkedPreDispatchFacingDrift(signal)) {
            return FailedSignalDisposition.terminate();
        }
        WorkstationPlacementReplan existing = workstationPlacementReplans.get(
                context.runId());
        int nextAttempt = existing == null ? 1
                : Math.incrementExact(existing.retryAttempt());
        if (nextAttempt > MAXIMUM_WORKSTATION_PRE_DISPATCH_REPLANS
                || (existing != null && !existing.matches(context,
                        approved.operationId()))) {
            return FailedSignalDisposition.terminate();
        }
        FailedSignalDisposition authorization =
                actionDelegate.consumeMarkedNoPacketPlaceBlockReplan(
                        context, signal);
        if (!authorization.permitsCurrentNodeReplan()) {
            return FailedSignalDisposition.terminate();
        }
        workstationPlacementReplans.put(context.runId(),
                new WorkstationPlacementReplan(
                        context.node().nodeId(), approved.operationId(),
                        nextAttempt));
        return authorization;
    }

    /**
     * {@link WorldInteractionActionSpec.BreakBlock} 只证明方块已经由原版破坏。掉落实体并不
     * 保证已在身体碰撞范围内：在原账本仍未变化时，节点只会短暂观察一枚新鲜、精确匹配的
     * {@code ItemEntity}，导航到其格点后走 UUID 绑定的 {@code PickupWait}。任何候选、world
     * binding、菜单或账本漂移都会失败，绝不把等待当成补发物品的授权。
     */
    @Override
    public SkillNodeDirective tick(SkillNodeContext context) {
        requireOwnerThread();
        Objects.requireNonNull(context, "context");
        ResourceDropCollection collection = resourceDropCollections.get(
                context.runId());
        if (collection == null) {
            return SkillNodeDirective.continueRunning("生产节点继续运行");
        }
        if (!collection.matches(context)) {
            resourceDropCollections.remove(context.runId(), collection);
            return SkillNodeDirective.fail(
                    SkillFailureCode.WORLD_CHANGED,
                    "资源掉落收集期间节点身份或 operation 已变化");
        }
        if (collection instanceof AwaitingResourceDrop awaiting) {
            return advanceResourceDropObservation(context, awaiting);
        }
        if (collection instanceof ReadyResourceDropPickup ready) {
            return advanceReadyResourceDropPickup(context, ready);
        }
        resourceDropCollections.remove(context.runId(), collection);
        return SkillNodeDirective.fail(
                SkillFailureCode.INTERNAL_ERROR,
                "资源掉落收集状态在非等待阶段被运行时推进");
    }

    @Override
    public void cancelled(SkillNodeContext context, String reason) {
        requireOwnerThread();
        workstationPlacementReplans.remove(context.runId());
        ResourceDropCollection collection = resourceDropCollections.remove(
                context.runId());
        if (collection instanceof PendingResourceDropNavigation pending) {
            try {
                resourceDropNavigation.cancel(pending.navigationId(),
                        context.currentTick(), DROP_NAVIGATION_CANCELLATION_REASON);
            } catch (RuntimeException ignored) {
                // 生命周期关闭 generation 时也会回收导航会话；不可因取消异常遗留 reservation。
            }
        }
        actionDelegate.cancelled(context, reason);
        pickupActionDelegate.cancelled(context, reason);
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
            SkillNodeDirective failure = preparation.failure().orElseThrow();
            logActionPlanningRejection(context, "re-preflight",
                    failure.failureCode().orElseThrow().name()
                            + ":" + failure.safeSummary());
            throw new ActionBackedSkillNodeHandler.PlanningFailure(
                    failure.failureCode().orElseThrow(),
                    failure.safeSummary());
        }
        ExecutionTicket ticket = preparation.ticket().orElseThrow();
        ProductionAction action = planConcreteAction(ticket).orElse(null);
        if (action == null) {
            logActionPlanningRejection(context, "concrete-action",
                    "port-returned-empty");
            throw new ActionBackedSkillNodeHandler.PlanningFailure(
                    SkillFailureCode.ACTION_REJECTED,
                    concreteActionUnavailableSummary(ticket));
        }
        if (!actionMatchesOperation(ticket, action)) {
            logActionPlanningRejection(context, "action-kind",
                    action.action().spec().kind().name());
            throw new ActionBackedSkillNodeHandler.PlanningFailure(
                    SkillFailureCode.ACTION_REJECTED,
                    "生产节点端口返回的原版动作不符合已审核 operation 合同");
        }
        boolean menuOperation = ticket.resolved().menuContract().isPresent();
        return Optional.of(new ActionBackedSkillNodeHandler.Operation(
                operationKey(context, ticket),
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

    private static String concreteActionUnavailableSummary(
            ExecutionTicket ticket) {
        ProductionOperation operation = ticket.resolved().node().operation();
        if (operation instanceof ResourceAcquisition) {
            return "资源采集端口未能冻结当前 tick 的原版 BREAK_BLOCK 动作";
        }
        if (operation instanceof RecipeExecution) {
            return "配方端口未能冻结当前 tick 的原版菜单动作";
        }
        if (operation instanceof SingleChestTransfer) {
            return "箱子转移端口未能冻结当前 tick 的原版菜单动作";
        }
        if (operation instanceof PlaceWorkstation) {
            return "工作站端口未能冻结当前 tick 的原版 PLACE_BLOCK 动作";
        }
        return "生产节点端口未能冻结当前 tick 的已审核原版动作";
    }

    /**
     * 只有在本节点已经破坏资源、观察到一枚新鲜 UUID 且导航回执成功后，才允许提交第二条
     * {@link WorldInteractionActionSpec.PickupWait}。这条 action 不能替代原始 BREAK_BLOCK，
     * 也不能由端口在其他 production operation 中伪造。
     */
    private Optional<ActionBackedSkillNodeHandler.Operation> planPickupAction(
            SkillNodeContext context) {
        ResourceDropCollection collection = resourceDropCollections.get(
                context.runId());
        if (!(collection instanceof ReadyResourceDropPickup ready)) {
            throw new ActionBackedSkillNodeHandler.PlanningFailure(
                    SkillFailureCode.INTERNAL_ERROR,
                    "资源掉落实体拾取开始时没有可验证的就绪状态");
        }
        if (!ready.matches(context)) {
            throw new ActionBackedSkillNodeHandler.PlanningFailure(
                    SkillFailureCode.WORLD_CHANGED,
                    "资源掉落实体拾取开始时节点身份或 operation 已变化");
        }
        if (!ready.provenance().matches(ready.candidate())) {
            throw new ActionBackedSkillNodeHandler.PlanningFailure(
                    SkillFailureCode.WORLD_CHANGED,
                    "资源掉落实体来源凭据在拾取前不再匹配");
        }
        ProductionAction action;
        try {
            action = acquisitionActions.planResourceDropPickup(
                    ready.ticket(), context, ready.candidate()).orElse(null);
        } catch (ActionBackedSkillNodeHandler.PlanningFailure failure) {
            throw failure;
        } catch (RuntimeException exception) {
            throw new ActionBackedSkillNodeHandler.PlanningFailure(
                    SkillFailureCode.WORLD_CHANGED,
                    "资源掉落实体拾取端口冻结动作时发生异常");
        }
        if (action == null) {
            throw new ActionBackedSkillNodeHandler.PlanningFailure(
                    SkillFailureCode.ACTION_REJECTED,
                    "资源掉落实体拾取端口未能冻结当前 tick 的 UUID PickupWait 动作");
        }
        if (!matchesResourceDropPickup(action, ready.candidate())) {
            throw new ActionBackedSkillNodeHandler.PlanningFailure(
                    SkillFailureCode.ACTION_REJECTED,
                    "资源掉落实体拾取端口返回的动作不符合 UUID PickupWait 合同");
        }
        return Optional.of(new ActionBackedSkillNodeHandler.Operation(
                "production-pickup",
                action.action(),
                ActionPriority.AUTONOMOUS,
                action.maximumTicks(),
                SkillNodeDirective.Kind.WAIT_ACTION,
                action.waitingSummary(),
                (signalContext, signal) -> verifyResourceDropPickup(
                        ready.ticket(), ready.candidate(), signalContext,
                        signal)));
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
            BotPlayer.LOGGER.warn(
                    "P5A production concrete action planning failed: operation={}, bot={}, generation={}, tick={}",
                    operation.getClass().getSimpleName(),
                    ticket.bot().botId(),
                    ticket.bot().generation(),
                    ticket.before().observedAtTick(),
                    exception);
            return Optional.empty();
        }
    }

    private static void logActionPlanningRejection(
            SkillNodeContext context, String stage, String detail) {
        BotPlayer.LOGGER.warn(
                "P5A production action planning rejected: stage={}, node={}, run={}, revision={}, tick={}, detail={}",
                Objects.requireNonNull(stage, "stage"),
                context.node().nodeId(),
                context.runId(),
                context.stateRevision(),
                context.currentTick(),
                Objects.requireNonNull(detail, "detail"));
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

    private static boolean matchesResourceDropPickup(
            ProductionAction action, ResourceDropCandidate candidate) {
        if (action.maximumTicks()
                > MAXIMUM_RESOURCE_DROP_PICKUP_ACTION_TICKS
                || !(action.action().spec()
                        instanceof WorldInteractionActionSpec.PickupWait pickup)
                || pickup.expectedItemEntityId().isEmpty()
                || !pickup.expectedItemEntityId().orElseThrow().equals(
                        candidate.entityId())) {
            return false;
        }
        return pickup.ticks() >= 1
                && pickup.ticks() <= MAXIMUM_RESOURCE_DROP_PICKUP_TICKS
                && action.maximumTicks() > pickup.ticks();
    }

    private SkillNodeDirective verifyResourceAction(
            ExecutionTicket ticket,
            SkillNodeContext context,
            SkillSignal signal) {
        SkillNodeDirective verified = verify(ticket, context, signal);
        if (!mayAwaitResourcePickup(ticket, context, signal, verified)) {
            return verified;
        }
        ResourceDropProvenance provenance = resourceDropProvenance(ticket,
                signal).orElse(null);
        if (provenance == null) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.WORLD_CHANGED,
                    "原版破块未提供精确资源掉落实体来源凭据");
        }
        resourceDropCollections.put(context.runId(), new AwaitingResourceDrop(
                ticket,
                signal,
                context.node().nodeId(),
                provenance,
                MAX_RESOURCE_DROP_OBSERVATIONS));
        return SkillNodeDirective.continueRunning(
                "方块已由原版破坏，正在受限观察资源掉落实体");
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

    /**
     * The active collection branch may only follow a UUID emitted by the exact synchronous
     * {@code BlockDropsEvent} capture for this break. A spatial TaskSensor delta alone is not
     * sufficient provenance: an outside actor could add an otherwise identical item entity
     * between preflight and observation.
     */
    private static Optional<ResourceDropProvenance> resourceDropProvenance(
            ExecutionTicket ticket, SkillSignal signal) {
        Objects.requireNonNull(ticket, "ticket");
        Objects.requireNonNull(signal, "signal");
        if (!(ticket.resolved().node().operation()
                instanceof ResourceAcquisition acquisition)) {
            return Optional.empty();
        }
        Map<ProductionMaterial, Integer> expected = acquisition.expectedGain()
                .quantities();
        if (expected.size() != 1) {
            return Optional.empty();
        }
        Map.Entry<ProductionMaterial, Integer> entry = expected.entrySet()
                .iterator().next();
        if (entry.getValue() == null || entry.getValue() != 1) {
            return Optional.empty();
        }
        String entityId = null;
        String itemId = null;
        String countText = null;
        for (ActionEvidence evidence : signal.evidence()) {
            if (BREAK_DROP_ENTITY_ID_EVIDENCE_KEY.equals(evidence.key())) {
                if (entityId != null) {
                    return Optional.empty();
                }
                entityId = evidence.value();
            } else if (BREAK_DROP_ITEM_EVIDENCE_KEY.equals(evidence.key())) {
                if (itemId != null) {
                    return Optional.empty();
                }
                itemId = evidence.value();
            } else if (BREAK_DROP_COUNT_EVIDENCE_KEY.equals(evidence.key())) {
                if (countText != null) {
                    return Optional.empty();
                }
                countText = evidence.value();
            }
        }
        if (entityId == null || itemId == null || countText == null
                || !entry.getKey().id().value().equals(itemId)) {
            return Optional.empty();
        }
        try {
            ResourceDropProvenance provenance = new ResourceDropProvenance(
                    UUID.fromString(entityId), itemId,
                    Integer.parseInt(countText));
            return provenance.count() == entry.getValue()
                    ? Optional.of(provenance)
                    : Optional.empty();
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private SkillNodeDirective advanceResourceDropObservation(
            SkillNodeContext context, AwaitingResourceDrop awaiting) {
        SkillNodeDirective verified = verify(awaiting.ticket(), context,
                awaiting.signal());
        if (verified.kind() == SkillNodeDirective.Kind.COMPLETE) {
            resourceDropCollections.remove(context.runId(), awaiting);
            return verified;
        }
        if (!mayAwaitResourcePickup(awaiting.ticket(), context,
                awaiting.signal(), verified)) {
            resourceDropCollections.remove(context.runId(), awaiting);
            return verified;
        }
        ResourceDropObservation observation;
        try {
            observation = acquisitionActions.observeResourceDrop(
                    awaiting.ticket(), context);
        } catch (RuntimeException exception) {
            observation = ResourceDropObservation.unavailable();
        }
        if (observation == null) {
            resourceDropCollections.remove(context.runId(), awaiting);
            return SkillNodeDirective.fail(
                    SkillFailureCode.WORLD_CHANGED,
                    "资源掉落实体观察没有返回受限当前 tick 结果");
        }
        return switch (observation.status()) {
            case FOUND -> {
                ResourceDropCandidate candidate = observation.candidate()
                        .orElseThrow();
                if (!awaiting.provenance().matches(candidate)) {
                    resourceDropCollections.remove(context.runId(), awaiting);
                    yield SkillNodeDirective.fail(
                            SkillFailureCode.WORLD_CHANGED,
                            "受限掉落实体观察与原版破块来源凭据不一致");
                }
                yield beginResourceDropNavigation(context, awaiting, candidate);
            }
            case ABSENT -> {
                if (awaiting.remainingObservations() <= 1) {
                    resourceDropCollections.remove(context.runId(), awaiting);
                    yield SkillNodeDirective.fail(
                            SkillFailureCode.MISSING_ITEM,
                            "原版方块已破坏，但有限范围内未观察到可验证资源掉落实体");
                }
                resourceDropCollections.put(context.runId(), awaiting.next());
                yield SkillNodeDirective.continueRunning(
                        "等待受限范围内出现原版资源掉落实体（剩余 "
                                + (awaiting.remainingObservations() - 1)
                                + " tick）");
            }
            case AMBIGUOUS -> {
                resourceDropCollections.remove(context.runId(), awaiting);
                yield SkillNodeDirective.fail(
                        SkillFailureCode.WORLD_CHANGED,
                        "资源掉落实体候选不唯一，拒绝猜测要收集的实体");
            }
            case UNAVAILABLE -> {
                resourceDropCollections.remove(context.runId(), awaiting);
                yield SkillNodeDirective.fail(
                        SkillFailureCode.WORLD_CHANGED,
                        "资源掉落实体观察不完整或绑定已漂移");
            }
        };
    }

    private SkillNodeDirective beginResourceDropNavigation(
            SkillNodeContext context,
            AwaitingResourceDrop awaiting,
            ResourceDropCandidate candidate) {
        return beginResourceDropNavigation(context, awaiting,
                awaiting.provenance(), candidate,
                MAX_RESOURCE_DROP_RETARGETS);
    }

    /**
     * 每次都用当前运行态重新生成导航 request。重定向只保留 UUID/source provenance 和有界
     * 剩余额度；动态格点绝不写入 SkillPlan 或 checkpoint。
     */
    private SkillNodeDirective beginResourceDropNavigation(
            SkillNodeContext context,
            ResourceDropCollection active,
            ResourceDropProvenance provenance,
            ResourceDropCandidate candidate,
            int remainingRetargets) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(active, "active");
        Objects.requireNonNull(provenance, "provenance");
        Objects.requireNonNull(candidate, "candidate");
        if (!provenance.matches(candidate)
                || remainingRetargets < 0
                || remainingRetargets > MAX_RESOURCE_DROP_RETARGETS) {
            resourceDropCollections.remove(context.runId(), active);
            return SkillNodeDirective.fail(
                    SkillFailureCode.INTERNAL_ERROR,
                    "资源掉落实体重定位状态不满足受限 provenance 合同");
        }
        NavigationRequest request;
        try {
            request = resourceDropNavigationRequest(context, candidate);
        } catch (RuntimeException exception) {
            resourceDropCollections.remove(context.runId(), active);
            return SkillNodeDirective.fail(
                    SkillFailureCode.TIMEOUT,
                    "资源掉落实体收集没有足够的剩余导航预算");
        }
        NavigationSubmission submission;
        try {
            submission = resourceDropNavigation.submit(request,
                    context.currentTick());
        } catch (RuntimeException exception) {
            resourceDropCollections.remove(context.runId(), active);
            return SkillNodeDirective.fail(
                    SkillFailureCode.INTERNAL_ERROR,
                    "资源掉落实体导航提交抛出异常");
        }
        if (submission.status() != NavigationSubmission.Status.ENQUEUED) {
            resourceDropCollections.remove(context.runId(), active);
            return SkillNodeDirective.fail(
                    mapNavigationSubmissionFailure(submission.status()),
                    "资源掉落实体导航未入队："
                            + submission.status().name());
        }
        CompletionStage<NavigationOutcome> completion = submission.completion()
                .orElse(null);
        if (completion == null) {
            resourceDropCollections.remove(context.runId(), active);
            return SkillNodeDirective.fail(
                    SkillFailureCode.INTERNAL_ERROR,
                    "资源掉落实体导航入队结果缺少 completion 句柄");
        }
        PendingResourceDropNavigation pending =
                new PendingResourceDropNavigation(
                        active.ticket(), active.signal(),
                        active.nodeId(), provenance, candidate,
                        request.navigationId(),
                        context.nextStateRevision(), remainingRetargets);
        resourceDropCollections.put(context.runId(), pending);
        completion.whenComplete((outcome, throwable) ->
                offerResourceDropNavigationCompletion(context.runId(), pending,
                        outcome, throwable, context.currentTick()));
        return SkillNodeDirective.waitFor(
                SkillNodeDirective.Kind.WAIT_NAVIGATION,
                "正在导航到一枚已冻结 UUID 的原版资源掉落实体");
    }

    private SkillNodeDirective handleResourceDropNavigationSignal(
            SkillNodeContext context,
            SkillSignal signal,
            PendingResourceDropNavigation pending) {
        if (signal.type() != SkillSignalType.NAVIGATION
                || !pending.navigationId().equals(signal.operationId())
                || pending.runRevision() != signal.runRevision()
                || !pending.matches(context)
                || (signal.status() == SkillSignalStatus.SUCCEEDED
                        && !hasExactResourceDropNavigationEvidence(signal,
                                pending.candidate()))) {
            resourceDropCollections.remove(context.runId(), pending);
            return SkillNodeDirective.fail(
                    SkillFailureCode.INTERNAL_ERROR,
                    "资源掉落实体收集收到不属于当前导航的回执");
        }
        if (signal.status() != SkillSignalStatus.SUCCEEDED
                || signal.failureCode() != SkillFailureCode.NONE) {
            resourceDropCollections.remove(context.runId(), pending);
            return SkillNodeDirective.fail(
                    signal.failureCode() == SkillFailureCode.NONE
                            ? SkillFailureCode.NAVIGATION_FAILED
                            : signal.failureCode(),
                    "资源掉落实体导航没有成功到达冻结格点");
        }
        SkillNodeDirective verified = verify(pending.ticket(), context,
                pending.signal());
        if (verified.kind() == SkillNodeDirective.Kind.COMPLETE) {
            resourceDropCollections.remove(context.runId(), pending);
            return verified;
        }
        if (!mayAwaitResourcePickup(pending.ticket(), context,
                pending.signal(), verified)) {
            resourceDropCollections.remove(context.runId(), pending);
            return verified;
        }
        resourceDropCollections.put(context.runId(),
                new ReadyResourceDropPickup(
                        pending.ticket(), pending.signal(), pending.nodeId(),
                        pending.provenance(), pending.candidate(),
                        pending.remainingRetargets()));
        return SkillNodeDirective.continueRunning(
                "已到达资源掉落实体附近，准备以 UUID 绑定回读拾取");
    }

    /**
     * Navigation completion 与 PickupWait 入队之间可能跨一个 runtime tick。重新读取受限
     * UUID candidate：同格才允许交给 pickup action；跨格只可有界重定向一次，避免以旧坐标
     * 误判实体仍可达或无限追逐自然物理中的掉落物。
     */
    private SkillNodeDirective advanceReadyResourceDropPickup(
            SkillNodeContext context, ReadyResourceDropPickup ready) {
        if (!ready.provenance().matches(ready.candidate())) {
            resourceDropCollections.remove(context.runId(), ready);
            return SkillNodeDirective.fail(
                    SkillFailureCode.WORLD_CHANGED,
                    "资源掉落实体来源凭据在拾取前不再匹配");
        }
        SkillNodeDirective verified = verify(ready.ticket(), context,
                ready.signal());
        if (verified.kind() == SkillNodeDirective.Kind.COMPLETE) {
            resourceDropCollections.remove(context.runId(), ready);
            return verified;
        }
        if (!mayAwaitResourcePickup(ready.ticket(), context,
                ready.signal(), verified)) {
            resourceDropCollections.remove(context.runId(), ready);
            return verified;
        }
        ResourceDropObservation observation;
        try {
            observation = acquisitionActions.observeResourceDrop(
                    ready.ticket(), context);
        } catch (RuntimeException exception) {
            observation = ResourceDropObservation.unavailable();
        }
        if (observation == null) {
            resourceDropCollections.remove(context.runId(), ready);
            return SkillNodeDirective.fail(
                    SkillFailureCode.WORLD_CHANGED,
                    "资源掉落实体在 UUID 拾取前没有返回受限当前 tick 观察");
        }
        return switch (observation.status()) {
            case FOUND -> {
                ResourceDropCandidate candidate = observation.candidate()
                        .orElseThrow();
                if (!ready.provenance().matches(candidate)
                        || !ready.candidate().entityId().equals(
                                candidate.entityId())
                        || !ready.candidate().dimensionId().equals(
                                candidate.dimensionId())) {
                    resourceDropCollections.remove(context.runId(), ready);
                    yield SkillNodeDirective.fail(
                            SkillFailureCode.WORLD_CHANGED,
                            "资源掉落实体重观察与冻结 UUID 来源不一致");
                }
                if (!ready.candidate().position().equals(
                        candidate.position())) {
                    if (ready.remainingRetargets() <= 0) {
                        resourceDropCollections.remove(context.runId(), ready);
                        yield SkillNodeDirective.fail(
                                SkillFailureCode.WORLD_CHANGED,
                                "资源掉落实体在有界重定位次数内持续跨格");
                    }
                    yield beginResourceDropNavigation(context, ready,
                            ready.provenance(), candidate,
                            ready.remainingRetargets() - 1);
                }
                ReadyResourceDropPickup refreshed =
                        new ReadyResourceDropPickup(
                                ready.ticket(), ready.signal(),
                                ready.nodeId(), ready.provenance(), candidate,
                                ready.remainingRetargets());
                resourceDropCollections.put(context.runId(), refreshed);
                SkillNodeDirective result = pickupActionDelegate.begin(context);
                if (result.kind() == SkillNodeDirective.Kind.WAIT_ACTION) {
                    resourceDropCollections.put(context.runId(),
                            new PendingResourceDropPickup(
                                    refreshed.ticket(), refreshed.signal(),
                                    refreshed.nodeId(), refreshed.provenance(),
                                    refreshed.candidate()));
                } else {
                    resourceDropCollections.remove(context.runId(), refreshed);
                }
                yield result;
            }
            case ABSENT -> {
                resourceDropCollections.remove(context.runId(), ready);
                yield SkillNodeDirective.fail(
                        SkillFailureCode.MISSING_ITEM,
                        "资源掉落实体在 UUID PickupWait 入队前不再可观察");
            }
            case AMBIGUOUS -> {
                resourceDropCollections.remove(context.runId(), ready);
                yield SkillNodeDirective.fail(
                        SkillFailureCode.WORLD_CHANGED,
                        "资源掉落实体在 UUID PickupWait 前候选不唯一");
            }
            case UNAVAILABLE -> {
                resourceDropCollections.remove(context.runId(), ready);
                yield SkillNodeDirective.fail(
                        SkillFailureCode.WORLD_CHANGED,
                        "资源掉落实体在 UUID PickupWait 前观察不完整或绑定已漂移");
            }
        };
    }

    private SkillNodeDirective verifyResourceDropPickup(
            ExecutionTicket ticket,
            ResourceDropCandidate candidate,
            SkillNodeContext context,
            SkillSignal signal) {
        ResourceDropCollection collection = resourceDropCollections.get(
                context.runId());
        if (!(collection instanceof PendingResourceDropPickup pending)
                || !pending.ticket().equals(ticket)
                || !pending.candidate().equals(candidate)
                || !pending.provenance().matches(candidate)
                || !pending.matches(context)
                || !hasExactResourceDropPickupEvidence(signal, candidate)) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.INTERNAL_ERROR,
                    "资源掉落实体拾取回执未能匹配冻结 UUID");
        }
        return verify(ticket, context, signal);
    }

    private static boolean hasExactResourceDropNavigationEvidence(
            SkillSignal signal, ResourceDropCandidate candidate) {
        return signal.evidence().size() == 1
                && DROP_NAVIGATION_EVIDENCE_KEY.equals(
                        signal.evidence().get(0).key())
                && candidate.entityId().toString().equals(
                        signal.evidence().get(0).value());
    }

    private static boolean hasExactResourceDropPickupEvidence(
            SkillSignal signal, ResourceDropCandidate candidate) {
        return signal.evidence().stream().anyMatch(evidence ->
                "entity.id".equals(evidence.key())
                        && candidate.entityId().toString().equals(
                                evidence.value()));
    }

    private static NavigationRequest resourceDropNavigationRequest(
            SkillNodeContext context, ResourceDropCandidate candidate) {
        long remaining = Math.subtractExact(
                context.deadlineTick(), context.currentTick());
        long maximumDuration = Math.min(
                MAXIMUM_RESOURCE_DROP_NAVIGATION_TICKS, remaining - 1L);
        if (maximumDuration < 1L) {
            throw new IllegalArgumentException(
                    "resource drop navigation has no remaining tick budget");
        }
        return new NavigationRequest(
                UUID.randomUUID(),
                context.botId(),
                context.botGeneration(),
                new NavigationGoal.ExactPosition(candidate.dimensionId(),
                        candidate.position(), 0, 0),
                NavigationArrivalRequirement.GROUNDED_GRID_CELL,
                RESOURCE_DROP_NAVIGATION_POLICY,
                Math.addExact(context.currentTick(), maximumDuration),
                Math.toIntExact(maximumDuration),
                "p5a-drop-collect:"
                        + context.runId()
                        + ":"
                        + context.node().nodeId());
    }

    private void offerResourceDropNavigationCompletion(
            UUID runId,
            PendingResourceDropNavigation pending,
            NavigationOutcome outcome,
            Throwable throwable,
            long submittedTick) {
        SkillSignalStatus status;
        SkillFailureCode failureCode;
        List<ActionEvidence> evidence;
        String summary;
        long finishedTick;
        if (throwable != null || outcome == null
                || !pending.navigationId().equals(outcome.navigationId())) {
            status = SkillSignalStatus.FAILED;
            failureCode = SkillFailureCode.INTERNAL_ERROR;
            evidence = List.of();
            summary = "资源掉落实体导航 completion 无法核验";
            finishedTick = submittedTick;
        } else if (outcome.state()
                        == io.github.greytaiwolf.botplayer.navigation
                                .NavigationState.SUCCEEDED
                && outcome.failure() == NavigationFailure.NONE
                && pending.candidate().position().equals(
                        outcome.finalPosition())) {
            status = SkillSignalStatus.SUCCEEDED;
            failureCode = SkillFailureCode.NONE;
            evidence = List.of(new ActionEvidence(DROP_NAVIGATION_EVIDENCE_KEY,
                    pending.candidate().entityId().toString()));
            summary = outcome.safeSummary();
            finishedTick = outcome.finishedTick();
        } else {
            status = switch (outcome.state()) {
                case CANCELLED -> SkillSignalStatus.CANCELLED;
                case STALE -> SkillSignalStatus.STALE;
                case FAILED -> SkillSignalStatus.FAILED;
                case CREATED,
                        SNAPSHOTTING,
                        PLANNING,
                        FOLLOWING,
                        INTERACTING,
                        REPLANNING,
                        RECOVERING,
                        SUSPENDED_BY_SAFETY,
                        VERIFYING,
                        SUCCEEDED -> SkillSignalStatus.FAILED;
            };
            failureCode = outcome.state()
                            == io.github.greytaiwolf.botplayer.navigation
                                    .NavigationState.SUCCEEDED
                    ? SkillFailureCode.NAVIGATION_FAILED
                    : mapNavigationFailure(outcome.failure());
            evidence = List.of();
            summary = outcome.safeSummary();
            finishedTick = outcome.finishedTick();
        }
        signals.offer(new SkillSignal(
                UUID.randomUUID(), runId, pending.ticket().bot().botId(),
                pending.ticket().bot().generation(), pending.runRevision(),
                pending.navigationId(), SkillSignalType.NAVIGATION, status,
                failureCode, evidence, summary, finishedTick));
    }

    private static SkillFailureCode mapNavigationSubmissionFailure(
            NavigationSubmission.Status status) {
        return switch (Objects.requireNonNull(status, "status")) {
            case BOT_NOT_ACTIVE -> SkillFailureCode.BOT_NOT_ACTIVE;
            case STALE_GENERATION -> SkillFailureCode.STALE_GENERATION;
            case RUNTIME_CLOSED -> SkillFailureCode.RUNTIME_CLOSED;
            case BOT_BUSY -> SkillFailureCode.NAVIGATION_FAILED;
            case SUPPLY_REQUIRED -> SkillFailureCode.MISSING_ITEM;
            case GOAL_OUT_OF_RANGE,
                    WRONG_DIMENSION -> SkillFailureCode.WORLD_CHANGED;
            case DUPLICATE -> SkillFailureCode.INTERNAL_ERROR;
            case ENQUEUED -> throw new IllegalArgumentException(
                    "enqueued navigation is not a rejection");
        };
    }

    private static SkillFailureCode mapNavigationFailure(
            NavigationFailure failure) {
        return switch (Objects.requireNonNull(failure, "failure")) {
            case NONE -> SkillFailureCode.INTERNAL_ERROR;
            case BOT_NOT_ACTIVE -> SkillFailureCode.BOT_NOT_ACTIVE;
            case STALE_GENERATION -> SkillFailureCode.STALE_GENERATION;
            case NO_PATH -> SkillFailureCode.NO_PATH;
            case STUCK -> SkillFailureCode.STUCK;
            case DANGER_PREEMPTED -> SkillFailureCode.DANGER_PREEMPTED;
            case SUPPLY_REQUIRED -> SkillFailureCode.MISSING_ITEM;
            case SERVER_OVERLOADED,
                    BUDGET_EXHAUSTED -> SkillFailureCode.SERVER_OVERLOADED;
            case DEADLINE_EXCEEDED -> SkillFailureCode.TIMEOUT;
            case CANCELLED -> SkillFailureCode.DANGER_PREEMPTED;
            case INVALID_REQUEST,
                    WRONG_DIMENSION,
                    GOAL_OUT_OF_RANGE,
                    SNAPSHOT_INCOMPLETE,
                    UNLOADED_FRONTIER_TIMEOUT -> SkillFailureCode.WORLD_CHANGED;
            case POLICY_BLOCKED -> SkillFailureCode.NAVIGATION_FAILED;
            case ACTION_FAILED -> SkillFailureCode.ACTION_FAILED;
            case INTERNAL_ERROR -> SkillFailureCode.INTERNAL_ERROR;
        };
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

    private String operationKey(
            SkillNodeContext context, ExecutionTicket ticket) {
        ProductionOperation operation = ticket.resolved().node().operation();
        String base = operationKey(operation);
        if (!(operation instanceof PlaceWorkstation)) {
            return base;
        }
        WorkstationPlacementReplan replan = workstationPlacementReplans.get(
                context.runId());
        if (replan == null) {
            return base;
        }
        if (!replan.matches(context, ticket.operationId())) {
            workstationPlacementReplans.remove(context.runId(), replan);
            return base;
        }
        return base + "-r" + replan.retryAttempt();
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

    private static boolean isMarkedPreDispatchFacingDrift(
            SkillSignal signal) {
        return signal.type() == SkillSignalType.ACTION
                && signal.status() == SkillSignalStatus.FAILED
                && signal.failureCode() == SkillFailureCode.WORLD_CHANGED
                && signal.evidence().size() == 1
                && signal.evidence().get(0).key().equals(
                        WorldInteractionActionSpec.PlaceBlock
                                .PRE_DISPATCH_FACING_DRIFT_EVIDENCE_KEY)
                && signal.evidence().get(0).value().equals(
                        WorldInteractionActionSpec.PlaceBlock
                                .PRE_DISPATCH_FACING_DRIFT_EVIDENCE_VALUE);
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

    /**
     * 仅供节点内掉落收集使用的窄导航边界。completion 回调只会投递不可变
     * {@link SkillSignal}，不会在后台线程读取 Minecraft body。
     */
    interface ResourceDropNavigationGateway {
        NavigationSubmission submit(NavigationRequest request, long currentTick);

        boolean cancel(UUID navigationId, long currentTick, String reason);
    }

    private static ResourceDropNavigationGateway resourceDropNavigationGateway(
            NavigationService service) {
        NavigationService navigation = Objects.requireNonNull(service,
                "navigationService");
        return new ResourceDropNavigationGateway() {
            @Override
            public NavigationSubmission submit(
                    NavigationRequest request, long currentTick) {
                return navigation.submit(request, currentTick);
            }

            @Override
            public boolean cancel(
                    UUID navigationId, long currentTick, String reason) {
                return navigation.cancel(navigationId, currentTick, reason);
            }
        };
    }

    private static ResourceDropNavigationGateway unavailableResourceDropNavigation() {
        return new ResourceDropNavigationGateway() {
            @Override
            public NavigationSubmission submit(
                    NavigationRequest request, long currentTick) {
                return NavigationSubmission.rejected(
                        NavigationSubmission.Status.RUNTIME_CLOSED,
                        "资源掉落导航未接线");
            }

            @Override
            public boolean cancel(
                    UUID navigationId, long currentTick, String reason) {
                return false;
            }
        };
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

        /**
         * 破块完成且精确账本尚未变化时，观察一枚新鲜的、受 source binding 约束的掉落实体。
         * 成功的掉落导航后可为当前 tick 的 freshness/re-targeting 再次调用；实现必须无副作用。
         * 默认实现 fail closed，因此旧的测试 lambda 或未接线端口不能把被动等待当成收集。
         */
        default ResourceDropObservation observeResourceDrop(
                ExecutionTicket ticket, SkillNodeContext context) {
            return ResourceDropObservation.unavailable();
        }

        /**
         * 仅对 {@link ResourceDropCandidate} 所绑定的 UUID 规划一个 collision-driven 拾取动作。
         * 返回空表示 live entity、source binding 或原生菜单状态已不可证明。
         */
        default Optional<ProductionAction> planResourceDropPickup(
                ExecutionTicket ticket,
                SkillNodeContext context,
                ResourceDropCandidate candidate) {
            return Optional.empty();
        }
    }

    /** 受限掉落实体观察的四种结果；不传递 live Entity 或 ItemStack。 */
    public enum ResourceDropObservationStatus {
        ABSENT,
        FOUND,
        AMBIGUOUS,
        UNAVAILABLE
    }

    /**
     * 短暂收集状态绑定的纯值实体身份。它只在当前运行节点内保留，绝不进入 SkillPlan 或
     * checkpoint；中断后会被丢弃并由恢复边界 fail closed。
     */
    public record ResourceDropCandidate(
            UUID entityId,
            String dimensionId,
            GridPoint position,
            String itemId,
            int count) {
        public ResourceDropCandidate {
            requireNonZero(entityId, "entityId");
            new io.github.greytaiwolf.botplayer.action.interaction.ResourceId(
                    Objects.requireNonNull(dimensionId, "dimensionId"));
            position = Objects.requireNonNull(position, "position");
            new io.github.greytaiwolf.botplayer.action.interaction.ResourceId(
                    Objects.requireNonNull(itemId, "itemId"));
            if (count < 1 || count > 64) {
                throw new IllegalArgumentException(
                        "resource drop count must be within 1..64");
            }
        }
    }

    /**
     * Immutable action receipt for the one ItemEntity that NeoForge reported as the exact
     * native drop of the frozen source block. It is transient node state, never plan or
     * checkpoint data.
     */
    private record ResourceDropProvenance(
            UUID entityId, String itemId, int count) {
        private ResourceDropProvenance {
            requireNonZero(entityId, "entityId");
            new io.github.greytaiwolf.botplayer.action.interaction.ResourceId(
                    Objects.requireNonNull(itemId, "itemId"));
            if (count < 1 || count > 64) {
                throw new IllegalArgumentException(
                        "resource drop provenance count must be within 1..64");
            }
        }

        private boolean matches(ResourceDropCandidate candidate) {
            return entityId.equals(Objects.requireNonNull(candidate,
                    "candidate").entityId())
                    && itemId.equals(candidate.itemId())
                    && count == candidate.count();
        }
    }

    /** 不携带 live world 引用的受限掉落实体观察结果。 */
    public record ResourceDropObservation(
            ResourceDropObservationStatus status,
            Optional<ResourceDropCandidate> candidate) {
        public ResourceDropObservation {
            status = Objects.requireNonNull(status, "status");
            candidate = Objects.requireNonNull(candidate, "candidate");
            if ((status == ResourceDropObservationStatus.FOUND)
                    != candidate.isPresent()) {
                throw new IllegalArgumentException(
                        "only FOUND resource drop observations carry a candidate");
            }
        }

        public static ResourceDropObservation absent() {
            return new ResourceDropObservation(
                    ResourceDropObservationStatus.ABSENT, Optional.empty());
        }

        public static ResourceDropObservation found(
                ResourceDropCandidate candidate) {
            return new ResourceDropObservation(
                    ResourceDropObservationStatus.FOUND,
                    Optional.of(Objects.requireNonNull(candidate,
                            "candidate")));
        }

        public static ResourceDropObservation ambiguous() {
            return new ResourceDropObservation(
                    ResourceDropObservationStatus.AMBIGUOUS, Optional.empty());
        }

        public static ResourceDropObservation unavailable() {
            return new ResourceDropObservation(
                    ResourceDropObservationStatus.UNAVAILABLE, Optional.empty());
        }
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
     * Transient, per-run permission to plan one new frozen furnace placement
     * after the backend has proved the previous action never reached vanilla.
     * It is intentionally not plan/checkpoint state: lifecycle cancellation,
     * replacement, or restart must re-observe rather than inherit a retry.
     */
    private record WorkstationPlacementReplan(
            UUID nodeId, String operationId, int retryAttempt) {
        private WorkstationPlacementReplan {
            requireNonZero(nodeId, "nodeId");
            operationId = requireBinding(operationId, "operationId");
            if (retryAttempt < 1
                    || retryAttempt > MAXIMUM_WORKSTATION_PRE_DISPATCH_REPLANS) {
                throw new IllegalArgumentException(
                        "workstation placement retry attempt is outside its bound");
            }
        }

        private boolean matches(
                SkillNodeContext context, String currentOperationId) {
            return nodeId.equals(context.node().nodeId())
                    && operationId.equals(currentOperationId);
        }
    }

    private sealed interface ResourceDropCollection permits
            AwaitingResourceDrop,
            PendingResourceDropNavigation,
            ReadyResourceDropPickup,
            PendingResourceDropPickup {
        ExecutionTicket ticket();

        SkillSignal signal();

        UUID nodeId();

        default boolean matches(SkillNodeContext context) {
            return ticket().bot().botId().equals(context.botId())
                    && ticket().bot().generation() == context.botGeneration()
                    && nodeId().equals(context.node().nodeId())
                    && ticket().operationId().equals(
                            approvedOperation(context.node().parameters())
                                    .map(ApprovedOperation::operationId)
                                    .orElse(null));
        }
    }

    /** 破块后仅短暂观察新实体；到期或观察不完整均 fail closed。 */
    private record AwaitingResourceDrop(
            ExecutionTicket ticket,
            SkillSignal signal,
            UUID nodeId,
            ResourceDropProvenance provenance,
            int remainingObservations) implements ResourceDropCollection {
        private AwaitingResourceDrop {
            ticket = Objects.requireNonNull(ticket, "ticket");
            signal = Objects.requireNonNull(signal, "signal");
            nodeId = Objects.requireNonNull(nodeId, "nodeId");
            provenance = Objects.requireNonNull(provenance, "provenance");
            if (remainingObservations < 1
                    || remainingObservations > MAX_RESOURCE_DROP_OBSERVATIONS) {
                throw new IllegalArgumentException(
                        "resource drop observation count is outside its bound");
            }
        }

        private AwaitingResourceDrop next() {
            return new AwaitingResourceDrop(ticket, signal, nodeId, provenance,
                    remainingObservations - 1);
        }
    }

    /** 等待导航服务对冻结掉落实体格点的不可变回执。 */
    private record PendingResourceDropNavigation(
            ExecutionTicket ticket,
            SkillSignal signal,
            UUID nodeId,
            ResourceDropProvenance provenance,
            ResourceDropCandidate candidate,
            UUID navigationId,
            long runRevision,
            int remainingRetargets) implements ResourceDropCollection {
        private PendingResourceDropNavigation {
            ticket = Objects.requireNonNull(ticket, "ticket");
            signal = Objects.requireNonNull(signal, "signal");
            nodeId = Objects.requireNonNull(nodeId, "nodeId");
            provenance = Objects.requireNonNull(provenance, "provenance");
            candidate = Objects.requireNonNull(candidate, "candidate");
            if (!provenance.matches(candidate)) {
                throw new IllegalArgumentException(
                        "resource drop navigation candidate lacks exact provenance");
            }
            requireNonZero(navigationId, "navigationId");
            if (runRevision < 1L) {
                throw new IllegalArgumentException(
                        "resource drop navigation revision must be positive");
            }
            if (remainingRetargets < 0
                    || remainingRetargets > MAX_RESOURCE_DROP_RETARGETS) {
                throw new IllegalArgumentException(
                        "resource drop navigation retargets exceed their bound");
            }
        }
    }

    /** 导航成功后、拾取 action 入队前的一 tick transition 边界。 */
    private record ReadyResourceDropPickup(
            ExecutionTicket ticket,
            SkillSignal signal,
            UUID nodeId,
            ResourceDropProvenance provenance,
            ResourceDropCandidate candidate,
            int remainingRetargets) implements ResourceDropCollection {
        private ReadyResourceDropPickup {
            ticket = Objects.requireNonNull(ticket, "ticket");
            signal = Objects.requireNonNull(signal, "signal");
            nodeId = Objects.requireNonNull(nodeId, "nodeId");
            provenance = Objects.requireNonNull(provenance, "provenance");
            candidate = Objects.requireNonNull(candidate, "candidate");
            if (!provenance.matches(candidate)) {
                throw new IllegalArgumentException(
                        "resource drop pickup candidate lacks exact provenance");
            }
            if (remainingRetargets < 0
                    || remainingRetargets > MAX_RESOURCE_DROP_RETARGETS) {
                throw new IllegalArgumentException(
                        "resource drop pickup retargets exceed their bound");
            }
        }
    }

    /** PickupWait 已入队，后续只能接受它自己的 action 回执。 */
    private record PendingResourceDropPickup(
            ExecutionTicket ticket,
            SkillSignal signal,
            UUID nodeId,
            ResourceDropProvenance provenance,
            ResourceDropCandidate candidate) implements ResourceDropCollection {
        private PendingResourceDropPickup {
            ticket = Objects.requireNonNull(ticket, "ticket");
            signal = Objects.requireNonNull(signal, "signal");
            nodeId = Objects.requireNonNull(nodeId, "nodeId");
            provenance = Objects.requireNonNull(provenance, "provenance");
            candidate = Objects.requireNonNull(candidate, "candidate");
            if (!provenance.matches(candidate)) {
                throw new IllegalArgumentException(
                        "pending resource drop pickup lacks exact provenance");
            }
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
