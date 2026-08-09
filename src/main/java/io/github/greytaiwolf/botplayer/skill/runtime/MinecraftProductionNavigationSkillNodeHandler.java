package io.github.greytaiwolf.botplayer.skill.runtime;

import io.github.greytaiwolf.botplayer.action.ActionEvidence;
import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
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
import io.github.greytaiwolf.botplayer.skill.builtin.production.ProductionSkillPlanCompiler;
import io.github.greytaiwolf.botplayer.skill.core.SkillFailureCode;
import io.github.greytaiwolf.botplayer.skill.core.SkillParameters;
import io.github.greytaiwolf.botplayer.skill.core.SkillSignal;
import io.github.greytaiwolf.botplayer.skill.core.SkillSignalInbox;
import io.github.greytaiwolf.botplayer.skill.core.SkillSignalStatus;
import io.github.greytaiwolf.botplayer.skill.core.SkillSignalType;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillNodeContext;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillNodeDirective;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillNodeHandler;
import io.github.greytaiwolf.botplayer.skill.task.MinecraftTaskSensorAdapter;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorAvailability;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorBudget;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorQuery;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorQueryType;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorResourceFilter;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorResponse;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorRunIdentity;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorSampler;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorScope;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorService;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorSnapshot;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;

/**
 * P5A 生产 DAG 中每个资源 physical fragment 前的有界局部导航门。
 *
 * <p>计划只包含 compiler 审核的精确资源方块 id。每次 {@link #begin(SkillNodeContext)} 都在
 * 当前服务器 tick 从同一 run 身份的 {@link TaskSensorService} 查询有限局部候选，并只把其中
 * 一个精确匹配的坐标用于这一次 {@link NavigationRequest}。目标坐标不会写入 {@code SkillPlan}
 * 或跨节点缓存；完成后的 production fragment 必须自己重新观察、冻结并执行真正的挖掘动作。
 */
public final class MinecraftProductionNavigationSkillNodeHandler
        implements SkillNodeHandler {
    /** 与 production port 相同的局部候选范围，不能扩张为全局搜索。 */
    static final int RESOURCE_RADIUS = 8;
    static final int RESOURCE_MAX_CANDIDATES = 24;
    static final int RESOURCE_MAX_BLOCKS = 256;
    static final int RESOURCE_MAX_EVIDENCE = 24;
    /*
     * 资源导航只需到达资源格附近的一层接地点。破块后的掉落由 UUID 绑定的主动收集
     * 阶段处理，不能再把“必须站在资源顶部”当作前置条件；那会把正常的平地采集收窄
     * 成一格上跳目标。r=1 仍拒绝平面对角格（距离 sqrt(2)）。
     */
    static final int RESOURCE_GOAL_RADIUS = 1;
    /**
     * Resource acquisition must keep the navigation action alive until the
     * body has actually landed at a bounded, reachable resource approach cell.
     */
    static final NavigationArrivalRequirement RESOURCE_ARRIVAL_REQUIREMENT =
            NavigationArrivalRequirement.GROUNDED_GRID_CELL;
    /**
     * Sprinting can overshoot the bounded final approach, so this P5A-only request retains the
     * normal safe policy but uses controlled walking input for its final approach.
     */
    static final NavigationPolicy RESOURCE_NAVIGATION_POLICY =
            NavigationPolicy.safeDefault().withSprint(false);
    static final int MAXIMUM_NAVIGATION_TICKS = 1_200;

    private static final String NAVIGATION_EVIDENCE_KEY =
            "navigation.resource";
    private static final String CANCELLATION_REASON =
            "P5A 资源导航节点已被取消";

    private final PlayerResolver players;
    private final NavigationGateway navigation;
    private final TaskSensorService taskSensors;
    private final TaskSensorSampler taskSensorSampler;
    private final SignalSink signals;
    private final Map<UUID, PendingNavigation> pendingByRun =
            new LinkedHashMap<>();
    private final Thread ownerThread;

    /**
     * 生命周期使用的生产构造器。传入的 {@link NavigationService} 是唯一的导航边界；handler
     * 不会直接操控输入、路线或后台路径规划器。
     */
    public MinecraftProductionNavigationSkillNodeHandler(
            PlayerResolver players,
            NavigationService navigationService,
            TaskSensorService taskSensors,
            MinecraftTaskSensorAdapter taskSensorAdapter,
            SignalSink signals) {
        this(players, gateway(navigationService), taskSensors,
                taskSensorAdapter, signals);
    }

    MinecraftProductionNavigationSkillNodeHandler(
            PlayerResolver players,
            NavigationGateway navigation,
            TaskSensorService taskSensors,
            TaskSensorSampler taskSensorSampler,
            SignalSink signals) {
        this.players = Objects.requireNonNull(players, "players");
        this.navigation = Objects.requireNonNull(navigation, "navigation");
        this.taskSensors = Objects.requireNonNull(taskSensors, "taskSensors");
        this.taskSensorSampler = Objects.requireNonNull(
                taskSensorSampler, "taskSensorSampler");
        this.signals = Objects.requireNonNull(signals, "signals");
        ownerThread = Thread.currentThread();
    }

    @Override
    public SkillNodeDirective begin(SkillNodeContext context) {
        requireOwnerThread();
        Objects.requireNonNull(context, "context");
        if (pendingByRun.containsKey(context.runId())) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.INTERNAL_ERROR,
                    "资源导航节点重复提交了未完成导航");
        }
        String expectedBlockId = approvedResourceBlock(context).orElse(null);
        if (expectedBlockId == null) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.INVALID_PARAMETERS,
                    "资源导航节点参数不属于编译器审核白名单");
        }
        BotServerPlayer player = resolveCurrent(context).orElse(null);
        if (player == null) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.BOT_NOT_ACTIVE,
                    "资源导航开始时 BotPlayer 代际不可用");
        }
        if (!currentTick(player, context.currentTick())) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.WORLD_CHANGED,
                    "资源导航未在当前服务器 tick 取得局部世界观察");
        }

        TaskSensorQuery query;
        try {
            query = resourceQuery(player, context, expectedBlockId);
        } catch (RuntimeException exception) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.INVALID_PARAMETERS,
                    "资源导航无法构造受限 TaskSensor 查询");
        }
        TaskSensorSnapshot snapshot = query(query, context.currentTick())
                .orElse(null);
        if (snapshot == null) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.WORLD_CHANGED,
                    "资源导航没有当前 tick 的完整局部 TaskSensor 观察");
        }
        GridPoint target = selectGoal(snapshot, query, expectedBlockId)
                .orElse(null);
        if (target == null) {
            return noCurrentGoal(snapshot);
        }

        NavigationRequest request;
        try {
            request = navigationRequest(context, player, target);
        } catch (RuntimeException exception) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.TIMEOUT,
                    "资源导航没有足够的剩余 tick 预算");
        }
        NavigationSubmission submission;
        try {
            submission = navigation.submit(request, context.currentTick());
        } catch (RuntimeException exception) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.INTERNAL_ERROR,
                    "资源导航服务提交抛出异常");
        }
        if (submission.status() != NavigationSubmission.Status.ENQUEUED) {
            return SkillNodeDirective.fail(
                    submissionFailure(submission.status()),
                    "资源导航未入队：" + submission.status().name());
        }
        CompletionStage<NavigationOutcome> completion = submission.completion()
                .orElse(null);
        if (completion == null) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.INTERNAL_ERROR,
                    "资源导航入队结果缺少 completion 句柄");
        }

        PendingNavigation pending = new PendingNavigation(
                request.navigationId(),
                context.botId(),
                context.botGeneration(),
                /* WAIT_NAVIGATION 会消费这个 revision；completion 不能复用 begin 的旧值。 */
                context.nextStateRevision(),
                context.node().nodeId(),
                expectedBlockId,
                target);
        pendingByRun.put(context.runId(), pending);
        completion.whenComplete(
                (outcome, throwable) -> offerCompletion(
                        context.runId(), pending, outcome, throwable,
                        context.currentTick()));
        return SkillNodeDirective.waitFor(
                SkillNodeDirective.Kind.WAIT_NAVIGATION,
                "正在导航到当前局部精确资源候选附近");
    }

    @Override
    public SkillNodeDirective signal(
            SkillNodeContext context, SkillSignal signal) {
        requireOwnerThread();
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(signal, "signal");
        PendingNavigation pending = pendingByRun.get(context.runId());
        if (pending == null
                || signal.type() != SkillSignalType.NAVIGATION
                || !pending.navigationId().equals(signal.operationId())
                || pending.runRevision() != signal.runRevision()
                || !pending.matches(context)
                || !hasExactNavigationEvidence(signal,
                        pending.expectedBlockId())) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.INTERNAL_ERROR,
                    "资源导航节点收到不属于当前受限导航的回执");
        }
        pendingByRun.remove(context.runId(), pending);
        if (signal.status() != SkillSignalStatus.SUCCEEDED
                || signal.failureCode() != SkillFailureCode.NONE) {
            return SkillNodeDirective.fail(
                    signal.failureCode() == SkillFailureCode.NONE
                            ? SkillFailureCode.NAVIGATION_FAILED
                            : signal.failureCode(),
                    "资源导航没有成功到达候选附近");
        }
        BotServerPlayer player = resolveCurrent(context).orElse(null);
        if (player == null || !currentTick(player, context.currentTick())) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.WORLD_CHANGED,
                    "资源导航完成后 BotPlayer 代际或当前 tick 不可用");
        }
        if (!expectedResourceStillAt(player, pending)) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.TARGET_GONE,
                    "资源导航完成后原始资源候选已经变化");
        }
        if (!groundedAtResourceApproach(
                GridPoint.from(player.blockPosition()),
                player.onGround(),
                pending.resourceTarget())) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.NAVIGATION_FAILED,
                    "资源导航违反了稳定资源邻接落地合同");
        }
        if (!currentResourceReachable(player, pending.resourceTarget())) {
            return SkillNodeDirective.fail(
                    SkillFailureCode.NAVIGATION_FAILED,
                    "资源导航完成后原始资源候选不再可交互");
        }
        return SkillNodeDirective.complete(
                "已在资源可交互邻接格稳定落地；后续采集节点将重新观察并冻结目标");
    }

    @Override
    public void cancelled(SkillNodeContext context, String reason) {
        requireOwnerThread();
        Objects.requireNonNull(context, "context");
        PendingNavigation pending = pendingByRun.remove(context.runId());
        if (pending == null) {
            return;
        }
        try {
            navigation.cancel(pending.navigationId(), context.currentTick(),
                    CANCELLATION_REASON);
        } catch (RuntimeException ignored) {
            // NavigationService 在 generation 关闭时也会终止会话；取消失败不能妨碍 runtime
            // 释放 reservation 或拒绝迟到回执。
        }
    }

    /**
     * 仅接受精确单参数 schema；即使 descriptor 已先校验，这里仍重复封闭映射以防 handler 被
     * 直接调用或未来注册层发生漂移。
     */
    static Optional<String> approvedResourceBlock(SkillNodeContext context) {
        Objects.requireNonNull(context, "context");
        if (!context.node().skillId().equals(
                P5ABuiltinSkillIds.NAVIGATE_TO_RESOURCE)
                || !context.node().skillVersion().equals(
                        P5ABuiltinSkillIds.VERSION)) {
            return Optional.empty();
        }
        SkillParameters parameters = context.node().parameters();
        Map<String, Object> values = parameters.values();
        if (!values.keySet().equals(SetHolder.RESOURCE_BLOCK_PARAMETER_ONLY)) {
            return Optional.empty();
        }
        Object value = values.get(P5ABuiltinSkillIds
                .RESOURCE_NAVIGATION_BLOCK_ID_PARAMETER);
        return value instanceof String blockId
                ? ProductionSkillPlanCompiler
                        .approvedResourceNavigationBlockId(blockId)
                : Optional.empty();
    }

    private TaskSensorQuery resourceQuery(
            BotServerPlayer player,
            SkillNodeContext context,
            String expectedBlockId) {
        BlockPos center = BlockPos.containing(
                player.getX(), player.getY(), player.getZ());
        TaskSensorScope scope = new TaskSensorScope(
                player.serverLevel().dimension().location().toString(),
                center.getX(), center.getY(), center.getZ(),
                RESOURCE_RADIUS, context.nextStateRevision());
        return new TaskSensorQuery(
                new TaskSensorRunIdentity(
                        context.botId(),
                        context.botGeneration(),
                        context.runId(),
                        context.stateRevision()),
                TaskSensorQueryType.RESOURCE_CANDIDATES,
                scope,
                new TaskSensorBudget(
                        RESOURCE_MAX_CANDIDATES,
                        0,
                        RESOURCE_MAX_BLOCKS,
                        0,
                        RESOURCE_MAX_EVIDENCE,
                        0L),
                resourceFilter(expectedBlockId));
    }

    private Optional<TaskSensorSnapshot> query(
            TaskSensorQuery query, long currentTick) {
        try {
            TaskSensorResponse response = taskSensors.query(
                    query, currentTick, taskSensorSampler);
            if (response.status() != TaskSensorResponse.Status.SAMPLED
                    && response.status() != TaskSensorResponse.Status.CACHE_HIT) {
                return Optional.empty();
            }
            TaskSensorSnapshot snapshot = response.snapshot().orElse(null);
            if (snapshot == null
                    || !snapshot.query().equals(query)
                    || snapshot.sampledAtTick() != currentTick
                    || snapshot.availability()
                            != TaskSensorAvailability.AVAILABLE) {
                return Optional.empty();
            }
            return Optional.of(snapshot);
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    static Optional<GridPoint> selectGoal(
            TaskSensorSnapshot snapshot,
            TaskSensorQuery query,
            String expectedBlockId) {
        if (!snapshot.query().equals(query)
                || snapshot.availability()
                        != TaskSensorAvailability.AVAILABLE
                || query.type() != TaskSensorQueryType.RESOURCE_CANDIDATES
                || !query.resourceFilter().equals(
                        resourceFilter(expectedBlockId))) {
            return Optional.empty();
        }
        for (io.github.greytaiwolf.botplayer.skill.task.TaskSensorEvidence
                evidence : snapshot.evidence()) {
            MinecraftProductionSkillPorts.CandidateEvidence candidate =
                    MinecraftProductionSkillPorts.resourceCandidate(evidence)
                            .orElse(null);
            if (candidate == null
                    || !expectedBlockId.equals(candidate.blockId())
                    || !withinScope(candidate, query.scope())) {
                return Optional.empty();
            }
            return Optional.of(new GridPoint(
                    candidate.position().x(),
                    candidate.position().y(),
                    candidate.position().z()));
        }
        return Optional.empty();
    }

    /**
     * A truncated scan is a current-tick observation whose empty result is not authoritative.
     * It must never be represented as a vanished resource: the sampler may simply have reached
     * its reviewed read cap before seeing the requested exact block.
     */
    static SkillNodeDirective noCurrentGoal(TaskSensorSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return snapshot.truncated()
                ? SkillNodeDirective.fail(
                        SkillFailureCode.WORLD_CHANGED,
                        "资源导航的受限局部候选观察不完整")
                : SkillNodeDirective.fail(
                        SkillFailureCode.TARGET_GONE,
                        "局部精确资源候选不存在或不再可信");
    }

    private static boolean withinScope(
            MinecraftProductionSkillPorts.CandidateEvidence candidate,
            TaskSensorScope scope) {
        long dx = (long) candidate.position().x() - scope.centerX();
        long dy = (long) candidate.position().y() - scope.centerY();
        long dz = (long) candidate.position().z() - scope.centerZ();
        return Math.abs(dx) <= scope.radius()
                && Math.abs(dy) <= scope.radius()
                && Math.abs(dz) <= scope.radius();
    }

    private static NavigationRequest navigationRequest(
            SkillNodeContext context,
            BotServerPlayer player,
            GridPoint resourceTarget) {
        long remaining = Math.subtractExact(
                context.deadlineTick(), context.currentTick());
        long maximumDuration = Math.min(
                MAXIMUM_NAVIGATION_TICKS, remaining - 1L);
        if (maximumDuration < 1L) {
            throw new IllegalArgumentException(
                    "resource navigation has no remaining tick budget");
        }
        long deadline = Math.addExact(context.currentTick(),
                maximumDuration);
        UUID navigationId = UUID.randomUUID();
        return new NavigationRequest(
                navigationId,
                context.botId(),
                context.botGeneration(),
                new NavigationGoal.NearPosition(
                        player.serverLevel().dimension().location().toString(),
                        resourceTarget,
                        RESOURCE_GOAL_RADIUS),
                RESOURCE_ARRIVAL_REQUIREMENT,
                RESOURCE_NAVIGATION_POLICY,
                deadline,
                Math.toIntExact(maximumDuration),
                "p5a-resource-nav:"
                        + context.runId()
                        + ":"
                        + context.node().nodeId());
    }

    /**
     * Mirrors the resource {@link NavigationGoal.NearPosition}: the body must be grounded,
     * within one non-diagonal horizontal cell, and no more than one vertical cell from the
     * sensed resource. The later production port re-observes and freezes the exact break target.
     */
    static boolean groundedAtResourceApproach(
            GridPoint currentPosition,
            boolean onGround,
            GridPoint resourceTarget) {
        GridPoint current = Objects.requireNonNull(currentPosition,
                "currentPosition");
        GridPoint target = Objects.requireNonNull(resourceTarget,
                "resourceTarget");
        long horizontalTolerance = (long) RESOURCE_GOAL_RADIUS
                * RESOURCE_GOAL_RADIUS;
        return onGround
                && current.horizontalDistanceSquared(target)
                        <= horizontalTolerance
                && Math.abs((long) current.y() - target.y())
                        <= RESOURCE_GOAL_RADIUS;
    }

    private static boolean expectedResourceStillAt(
            BotServerPlayer player,
            PendingNavigation pending) {
        BlockPos resource = pending.resourceTarget().toBlockPos();
        return player.serverLevel().isLoaded(resource)
                && pending.expectedBlockId().equals(BuiltInRegistries.BLOCK
                        .getKey(player.serverLevel().getBlockState(resource)
                                .getBlock())
                        .toString());
    }

    private static boolean currentResourceReachable(
            BotServerPlayer player,
            GridPoint resourceTarget) {
        try {
            BlockPos resource = Objects.requireNonNull(resourceTarget,
                    "resourceTarget").toBlockPos();
            return player.serverLevel().isLoaded(resource)
                    && player.canInteractWithBlock(resource, 0.0D);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private Optional<BotServerPlayer> resolveCurrent(
            SkillNodeContext context) {
        try {
            BotServerPlayer player = players.resolve(
                    context.botId(), context.botGeneration()).orElse(null);
            if (player == null
                    || !context.botId().equals(player.getUUID())
                    || player.runtimeHandle().generation()
                            != context.botGeneration()) {
                return Optional.empty();
            }
            return Optional.of(player);
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private void offerCompletion(
            UUID runId,
            PendingNavigation pending,
            NavigationOutcome outcome,
            Throwable throwable,
            long submittedTick) {
        SignalProjection projection = projectOutcome(
                pending, outcome, throwable, submittedTick);
        signals.offer(new SkillSignal(
                UUID.randomUUID(),
                runId,
                pending.botId(),
                pending.generation(),
                pending.runRevision(),
                pending.navigationId(),
                SkillSignalType.NAVIGATION,
                projection.status(),
                projection.failureCode(),
                projection.evidence(),
                projection.summary(),
                projection.finishedTick()));
    }

    private static SignalProjection projectOutcome(
            PendingNavigation pending,
            NavigationOutcome outcome,
            Throwable throwable,
            long submittedTick) {
        if (throwable != null
                || outcome == null
                || !pending.navigationId().equals(outcome.navigationId())) {
            return SignalProjection.failed(
                    SkillFailureCode.INTERNAL_ERROR,
                    "资源导航 completion 无法核验",
                    submittedTick);
        }
        return switch (outcome.state()) {
            case SUCCEEDED -> outcome.failure() == NavigationFailure.NONE
                    ? SignalProjection.succeeded(
                            pending.expectedBlockId(), outcome.safeSummary(),
                            outcome.finishedTick())
                    : SignalProjection.failed(
                            SkillFailureCode.INTERNAL_ERROR,
                            "成功资源导航携带了非空 failure",
                            outcome.finishedTick());
            case CANCELLED -> SignalProjection.cancelled(
                    mapFailure(outcome.failure()), outcome.safeSummary(),
                    outcome.finishedTick());
            case STALE -> SignalProjection.stale(
                    mapFailure(outcome.failure()), outcome.safeSummary(),
                    outcome.finishedTick());
            case FAILED -> SignalProjection.failed(
                    mapFailure(outcome.failure()), outcome.safeSummary(),
                    outcome.finishedTick());
            case CREATED,
                    SNAPSHOTTING,
                    PLANNING,
                    FOLLOWING,
                    INTERACTING,
                    REPLANNING,
                    RECOVERING,
                    SUSPENDED_BY_SAFETY,
                    VERIFYING -> SignalProjection.failed(
                            SkillFailureCode.INTERNAL_ERROR,
                            "资源导航 completion 不是终态",
                            submittedTick);
        };
    }

    private static boolean hasExactNavigationEvidence(
            SkillSignal signal, String expectedBlockId) {
        return signal.evidence().size() == 1
                && NAVIGATION_EVIDENCE_KEY.equals(
                        signal.evidence().get(0).key())
                && expectedBlockId.equals(
                        signal.evidence().get(0).value());
    }

    private static boolean currentTick(
            BotServerPlayer player, long currentTick) {
        MinecraftServer server = player.getServer();
        return server != null
                && server.isSameThread()
                && server.getTickCount() == currentTick;
    }

    private static TaskSensorResourceFilter resourceFilter(
            String expectedBlockId) {
        return switch (Objects.requireNonNull(expectedBlockId,
                "expectedBlockId")) {
            case "minecraft:oak_log" -> TaskSensorResourceFilter.OAK_LOG;
            case "minecraft:cobblestone" ->
                    TaskSensorResourceFilter.COBBLESTONE;
            case "minecraft:iron_ore" -> TaskSensorResourceFilter.IRON_ORE;
            case "minecraft:coal_ore" -> TaskSensorResourceFilter.COAL_ORE;
            default -> throw new IllegalArgumentException(
                    "resource navigation has no reviewed filter for "
                            + expectedBlockId);
        };
    }

    private static SkillFailureCode submissionFailure(
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

    private static SkillFailureCode mapFailure(NavigationFailure failure) {
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

    private static NavigationGateway gateway(
            NavigationService navigationService) {
        NavigationService service = Objects.requireNonNull(
                navigationService, "navigationService");
        return new NavigationGateway() {
            @Override
            public NavigationSubmission submit(
                    NavigationRequest request, long currentTick) {
                return service.submit(request, currentTick);
            }

            @Override
            public boolean cancel(
                    UUID navigationId, long currentTick, String reason) {
                return service.cancel(navigationId, currentTick, reason);
            }
        };
    }

    private void requireOwnerThread() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException(
                    "production navigation skill node handler requires server thread");
        }
    }

    @FunctionalInterface
    public interface PlayerResolver {
        Optional<BotServerPlayer> resolve(UUID botId, long generation);
    }

    interface NavigationGateway {
        NavigationSubmission submit(NavigationRequest request, long currentTick);

        boolean cancel(UUID navigationId, long currentTick, String reason);
    }

    @FunctionalInterface
    public interface SignalSink {
        SkillSignalInbox.OfferStatus offer(SkillSignal signal);
    }

    private record PendingNavigation(
            UUID navigationId,
            UUID botId,
            long generation,
            long runRevision,
            UUID nodeId,
            String expectedBlockId,
            GridPoint resourceTarget) {
        private PendingNavigation {
            Objects.requireNonNull(navigationId, "navigationId");
            Objects.requireNonNull(botId, "botId");
            Objects.requireNonNull(nodeId, "nodeId");
            Objects.requireNonNull(expectedBlockId, "expectedBlockId");
            Objects.requireNonNull(resourceTarget, "resourceTarget");
            if (generation <= 0L || runRevision < 1L) {
                throw new IllegalArgumentException(
                        "pending resource navigation identity is invalid");
            }
        }

        private boolean matches(SkillNodeContext context) {
            return botId.equals(context.botId())
                    && generation == context.botGeneration()
                    && runRevision == context.stateRevision()
                    && nodeId.equals(context.node().nodeId())
                    && approvedResourceBlock(context).filter(
                            expectedBlockId::equals).isPresent();
        }
    }

    private record SignalProjection(
            SkillSignalStatus status,
            SkillFailureCode failureCode,
            List<ActionEvidence> evidence,
            String summary,
            long finishedTick) {
        private SignalProjection {
            status = Objects.requireNonNull(status, "status");
            failureCode = Objects.requireNonNull(failureCode, "failureCode");
            evidence = List.copyOf(Objects.requireNonNull(evidence,
                    "evidence"));
            summary = Objects.requireNonNull(summary, "summary");
            if (summary.length() > SkillSignal.MAX_SUMMARY_LENGTH
                    || !summary.equals(summary.strip())
                    || summary.codePoints().anyMatch(Character::isISOControl)
                    || finishedTick < 0L) {
                throw new IllegalArgumentException(
                        "navigation signal projection is invalid");
            }
        }

        private static SignalProjection succeeded(
                String expectedBlockId, String summary, long finishedTick) {
            return new SignalProjection(
                    SkillSignalStatus.SUCCEEDED,
                    SkillFailureCode.NONE,
                    List.of(new ActionEvidence(NAVIGATION_EVIDENCE_KEY,
                            expectedBlockId)),
                    summary,
                    finishedTick);
        }

        private static SignalProjection failed(
                SkillFailureCode failureCode,
                String summary,
                long finishedTick) {
            return new SignalProjection(
                    SkillSignalStatus.FAILED,
                    requireFailure(failureCode),
                    List.of(), summary, finishedTick);
        }

        private static SignalProjection cancelled(
                SkillFailureCode failureCode,
                String summary,
                long finishedTick) {
            return new SignalProjection(
                    SkillSignalStatus.CANCELLED,
                    requireFailure(failureCode),
                    List.of(), summary, finishedTick);
        }

        private static SignalProjection stale(
                SkillFailureCode failureCode,
                String summary,
                long finishedTick) {
            return new SignalProjection(
                    SkillSignalStatus.STALE,
                    requireFailure(failureCode),
                    List.of(), summary, finishedTick);
        }

        private static SkillFailureCode requireFailure(
                SkillFailureCode failureCode) {
            SkillFailureCode value = Objects.requireNonNull(failureCode,
                    "failureCode");
            return value == SkillFailureCode.NONE
                    ? SkillFailureCode.INTERNAL_ERROR
                    : value;
        }
    }

    private static final class SetHolder {
        private static final java.util.Set<String>
                RESOURCE_BLOCK_PARAMETER_ONLY = java.util.Set.of(
                        P5ABuiltinSkillIds
                                .RESOURCE_NAVIGATION_BLOCK_ID_PARAMETER);

        private SetHolder() {
        }
    }
}
