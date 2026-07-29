package io.github.greytaiwolf.botplayer.navigation;

import io.github.greytaiwolf.botplayer.action.ActionCancellationReason;
import io.github.greytaiwolf.botplayer.action.ActionEnvelope;
import io.github.greytaiwolf.botplayer.action.ActionMailbox;
import io.github.greytaiwolf.botplayer.action.ActionOrigin;
import io.github.greytaiwolf.botplayer.action.ActionOutcome;
import io.github.greytaiwolf.botplayer.action.ActionPriority;
import io.github.greytaiwolf.botplayer.action.ActionRequest;
import io.github.greytaiwolf.botplayer.action.ActionState;
import io.github.greytaiwolf.botplayer.action.ClimbInputAction;
import io.github.greytaiwolf.botplayer.action.ControllerKind;
import io.github.greytaiwolf.botplayer.action.JumpAction;
import io.github.greytaiwolf.botplayer.action.LookAtAction;
import io.github.greytaiwolf.botplayer.action.MoveInputAction;
import io.github.greytaiwolf.botplayer.action.WorldInteractionAction;
import io.github.greytaiwolf.botplayer.action.interaction.BlockHitTarget;
import io.github.greytaiwolf.botplayer.action.interaction.WorldInteractionActionSpec;
import io.github.greytaiwolf.botplayer.action.minecraft.MinecraftActionSnapshot;
import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
import io.github.greytaiwolf.botplayer.navigation.path.BoundedAStarPlanner;
import io.github.greytaiwolf.botplayer.navigation.path.NavigationSnapshot;
import io.github.greytaiwolf.botplayer.navigation.path.RouteNode;
import io.github.greytaiwolf.botplayer.navigation.path.RoutePlan;
import io.github.greytaiwolf.botplayer.navigation.path.RoutePlanStatus;
import io.github.greytaiwolf.botplayer.navigation.path.TraversalKind;
import io.github.greytaiwolf.botplayer.navigation.snapshot.NavigationSnapshotBuilder;
import io.github.greytaiwolf.botplayer.navigation.snapshot.SnapshotBuildCursor;
import io.github.greytaiwolf.botplayer.navigation.snapshot.SnapshotBuildProgress;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * P4 的 generation-bound 导航会话。所有状态迁移发生在服务端主线程。
 *
 * <p>后台线程只接收不可变 NavigationSnapshot，并把不可变 RoutePlan 投递回有界 inbox。
 */
public final class NavigationService implements AutoCloseable {
    private static final int RESULT_INBOX_CAPACITY = 512;
    private static final int TERMINAL_SESSION_CAPACITY = 1_024;
    private static final int MAXIMUM_WAYPOINT_ATTEMPTS = 12;

    private final NavigationSettings settings;
    private final NavigationSnapshotBuilder snapshotBuilder;
    private final BiFunction<UUID, Long, Optional<BotServerPlayer>>
            activeBotResolver;
    private final ActionSubmitter actionSubmitter;
    private final ActionCanceller actionCanceller;
    private final ThreadPoolExecutor plannerExecutor;
    private final ArrayBlockingQueue<PlanningResult> planningResults =
            new ArrayBlockingQueue<>(RESULT_INBOX_CAPACITY);
    private final ArrayBlockingQueue<CompletedAction> actionResults =
            new ArrayBlockingQueue<>(RESULT_INBOX_CAPACITY);
    private final Map<UUID, Session> sessionsByBot =
            new LinkedHashMap<>();
    private final Map<UUID, Session> sessionsById =
            new LinkedHashMap<>();
    private final Deque<UUID> terminalSessionOrder =
            new ArrayDeque<>(TERMINAL_SESSION_CAPACITY);
    private boolean closed;

    public NavigationService(
            NavigationSettings settings,
            BiFunction<UUID, Long, Optional<BotServerPlayer>>
                    activeBotResolver,
            ActionSubmitter actionSubmitter,
            ActionCanceller actionCanceller) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.snapshotBuilder = new NavigationSnapshotBuilder(settings);
        this.activeBotResolver =
                Objects.requireNonNull(activeBotResolver, "activeBotResolver");
        this.actionSubmitter =
                Objects.requireNonNull(actionSubmitter, "actionSubmitter");
        this.actionCanceller =
                Objects.requireNonNull(actionCanceller, "actionCanceller");
        this.plannerExecutor = new ThreadPoolExecutor(
                settings.maximumConcurrentPlans(),
                settings.maximumConcurrentPlans(),
                30L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(settings.maximumQueuedPlans()),
                runnable -> {
                    Thread thread =
                            new Thread(runnable, "BotPlayer-P4-PathPlanner");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        this.plannerExecutor.allowCoreThreadTimeOut(true);
    }

    public NavigationSubmission submit(
            NavigationRequest request, long currentTick) {
        Objects.requireNonNull(request, "request");
        if (closed) {
            return NavigationSubmission.rejected(
                    NavigationSubmission.Status.RUNTIME_CLOSED,
                    "导航运行期已经关闭");
        }
        Session duplicate = sessionsById.get(request.navigationId());
        if (duplicate != null) {
            return duplicate.request.equals(request)
                    ? NavigationSubmission.enqueued(
                            duplicate.completion.minimalCompletionStage())
                    : NavigationSubmission.rejected(
                            NavigationSubmission.Status.DUPLICATE,
                            "navigationId 已被不同请求使用");
        }
        Session current = sessionsByBot.get(request.botId());
        if (current != null && !current.state.isTerminal()) {
            return NavigationSubmission.rejected(
                    NavigationSubmission.Status.BOT_BUSY,
                    "该 bot 已有活动导航会话");
        }
        Optional<BotServerPlayer> resolved =
                activeBotResolver.apply(
                        request.botId(), request.botGeneration());
        if (resolved.isEmpty()) {
            return NavigationSubmission.rejected(
                    NavigationSubmission.Status.BOT_NOT_ACTIVE,
                    "bot 或 generation 当前不可用");
        }
        BotServerPlayer player = resolved.orElseThrow();
        String dimension =
                player.serverLevel().dimension().location().toString();
        if (!dimension.equals(request.goal().dimension())) {
            return NavigationSubmission.rejected(
                    NavigationSubmission.Status.WRONG_DIMENSION,
                    "导航目标不在 bot 当前维度");
        }
        GridPoint start = GridPoint.from(player.blockPosition());
        long distanceSquared =
                start.horizontalDistanceSquared(request.goal().center());
        long maximumDistance = settings.maximumGoalDistance();
        if (distanceSquared > maximumDistance * maximumDistance) {
            return NavigationSubmission.rejected(
                    NavigationSubmission.Status.GOAL_OUT_OF_RANGE,
                    "导航目标超过服务端距离上限");
        }
        if (supplyBlocked(player, request, distanceSquared)) {
            return NavigationSubmission.rejected(
                    NavigationSubmission.Status.SUPPLY_REQUIRED,
                    "生命或食物不足以开始普通远行");
        }

        Session session = new Session(request, currentTick, start);
        sessionsByBot.put(request.botId(), session);
        sessionsById.put(request.navigationId(), session);
        return NavigationSubmission.enqueued(
                session.completion.minimalCompletionStage());
    }

    public boolean cancel(
            UUID navigationId, long currentTick, String reason) {
        Session session = sessionsById.get(
                Objects.requireNonNull(navigationId, "navigationId"));
        if (session == null || session.state.isTerminal()) {
            return false;
        }
        terminate(
                session,
                NavigationState.CANCELLED,
                NavigationFailure.CANCELLED,
                currentTick,
                reason);
        return true;
    }

    public boolean suspendForSafety(
            UUID botId, long generation, long currentTick) {
        Session session = sessionsByBot.get(
                Objects.requireNonNull(botId, "botId"));
        if (session == null
                || session.request.botGeneration() != generation
                || session.state.isTerminal()) {
            return false;
        }
        session.snapshotCursor =
                cancelCursor(session.snapshotCursor);
        cancelPlanning(session);
        cancelActiveAction(session);
        session.route = List.of();
        session.routeIndex = 0;
        session.state = NavigationState.SUSPENDED_BY_SAFETY;
        session.safeSummary = "导航已由 L0 安全反射挂起";
        session.lastStateTick = currentTick;
        return true;
    }

    public boolean resumeAfterSafety(
            UUID botId, long generation, long currentTick) {
        Session session = sessionsByBot.get(
                Objects.requireNonNull(botId, "botId"));
        if (session == null
                || session.request.botGeneration() != generation
                || session.state != NavigationState.SUSPENDED_BY_SAFETY) {
            return false;
        }
        session.replans++;
        session.state = NavigationState.REPLANNING;
        session.safeSummary = "危险稳定解除，导航将从真实位置重新规划";
        session.lastStateTick = currentTick;
        return true;
    }

    public void closeGeneration(
            UUID botId, long generation, long currentTick) {
        Session session = sessionsByBot.get(
                Objects.requireNonNull(botId, "botId"));
        if (session == null
                || session.request.botGeneration() != generation
                || session.state.isTerminal()) {
            return;
        }
        terminate(
                session,
                NavigationState.STALE,
                NavigationFailure.STALE_GENERATION,
                currentTick,
                "bot generation 已关闭");
    }

    public Optional<NavigationSessionView> inspect(UUID botId) {
        Session session = sessionsByBot.get(
                Objects.requireNonNull(botId, "botId"));
        return Optional.ofNullable(session).map(Session::view);
    }

    public void tick(long currentTick) {
        if (closed) {
            return;
        }
        drainPlanningResults(currentTick);
        drainActionResults(currentTick);
        int remainingSnapshotCells =
                settings.snapshotGlobalCellsPerTick();
        for (Session session :
                new ArrayList<>(sessionsByBot.values())) {
            if (session.state.isTerminal()) {
                continue;
            }
            Optional<BotServerPlayer> resolved =
                    activeBotResolver.apply(
                            session.request.botId(),
                            session.request.botGeneration());
            if (resolved.isEmpty()) {
                terminate(
                        session,
                        NavigationState.STALE,
                        NavigationFailure.STALE_GENERATION,
                        currentTick,
                        "bot generation 不再活动");
                continue;
            }
            BotServerPlayer player = resolved.orElseThrow();
            session.lastKnownPosition =
                    GridPoint.from(player.blockPosition());
            if (currentTick >= session.request.deadlineTick()
                    || currentTick - session.startedTick
                            >= session.request.maximumDurationTicks()) {
                terminate(
                        session,
                        NavigationState.FAILED,
                        NavigationFailure.DEADLINE_EXCEEDED,
                        currentTick,
                        "导航超过请求 Tick 上限");
                continue;
            }
            if (session.request.goal().reached(
                    GridPoint.from(player.blockPosition()))) {
                succeed(session, player, currentTick);
                continue;
            }
            if (session.state == NavigationState.SUSPENDED_BY_SAFETY) {
                continue;
            }
            if (supplyBlocked(
                    player,
                    session.request,
                    player.blockPosition()
                            .distSqr(
                                    session.request
                                            .goal()
                                            .center()
                                            .toBlockPos()))) {
                terminate(
                        session,
                        NavigationState.FAILED,
                        NavigationFailure.SUPPLY_REQUIRED,
                        currentTick,
                        "生命或食物不足，导航停止在当前位置");
                continue;
            }
            switch (session.state) {
                case CREATED, REPLANNING ->
                        beginSnapshot(session, player, currentTick);
                case SNAPSHOTTING -> {
                    if (remainingSnapshotCells > 0) {
                        int used = advanceSnapshot(
                                session,
                                player,
                                currentTick,
                                remainingSnapshotCells);
                        remainingSnapshotCells -= used;
                    }
                }
                case FOLLOWING -> follow(session, player, currentTick);
                case VERIFYING -> succeed(session, player, currentTick);
                case PLANNING, INTERACTING, RECOVERING -> {
                    // 等待有界 inbox 中的规划或动作结果。
                }
                case SUSPENDED_BY_SAFETY,
                        SUCCEEDED,
                        CANCELLED,
                        FAILED,
                        STALE -> {
                    // 上方已处理。
                }
            }
        }
    }

    private void beginSnapshot(
            Session session,
            BotServerPlayer player,
            long currentTick) {
        if (session.replans > session.request.policy().maximumReplans()) {
            terminate(
                    session,
                    NavigationState.FAILED,
                    NavigationFailure.BUDGET_EXHAUSTED,
                    currentTick,
                    "导航重算次数超过 policy 上限");
            return;
        }
        try {
            session.snapshotCursor = snapshotBuilder.begin(
                    player,
                    session.request.botGeneration(),
                    session.request.goal(),
                    currentTick);
            session.state = NavigationState.SNAPSHOTTING;
            session.lastStateTick = currentTick;
            session.safeSummary = "正在采样已加载局部运动快照";
        } catch (RuntimeException exception) {
            terminate(
                    session,
                    NavigationState.FAILED,
                    NavigationFailure.INTERNAL_ERROR,
                    currentTick,
                    "无法创建导航快照");
        }
    }

    private int advanceSnapshot(
            Session session,
            BotServerPlayer player,
            long currentTick,
            int remainingGlobalBudget) {
        SnapshotBuildCursor cursor =
                Objects.requireNonNull(
                        session.snapshotCursor, "snapshotCursor");
        SnapshotBuildProgress progress = snapshotBuilder.advance(
                cursor,
                player,
                session.request.botGeneration(),
                currentTick,
                Math.min(
                        remainingGlobalBudget,
                        settings.snapshotCellsPerBotTick()),
                0L);
        if (progress.status()
                == SnapshotBuildProgress.Status.COMPLETE) {
            session.snapshotCursor = null;
            submitPlanning(
                    session,
                    progress.snapshot().orElseThrow(),
                    GridPoint.from(player.blockPosition()),
                    currentTick);
        } else if (progress.status()
                != SnapshotBuildProgress.Status.BUILDING) {
            NavigationFailure failure = switch (progress.status()) {
                case TIMED_OUT ->
                        NavigationFailure.UNLOADED_FRONTIER_TIMEOUT;
                case STALE -> NavigationFailure.STALE_GENERATION;
                case CANCELLED -> NavigationFailure.CANCELLED;
                case BUILDING, COMPLETE ->
                        throw new IllegalStateException(
                                "non-terminal snapshot status");
            };
            terminate(
                    session,
                    failure == NavigationFailure.STALE_GENERATION
                            ? NavigationState.STALE
                            : NavigationState.FAILED,
                    failure,
                    currentTick,
                    progress.safeSummary());
        }
        return progress.sampledThisTick();
    }

    private void submitPlanning(
            Session session,
            NavigationSnapshot snapshot,
            GridPoint start,
            long currentTick) {
        Optional<GridPoint> normalizedStart =
                normalizePlanningStart(snapshot, start);
        if (normalizedStart.isEmpty()) {
            terminate(
                    session,
                    NavigationState.FAILED,
                    NavigationFailure.SNAPSHOT_INCOMPLETE,
                    currentTick,
                    "真实脚位及相邻接地层均不在可通行快照中");
            return;
        }
        GridPoint planningStart = normalizedStart.orElseThrow();
        AtomicBoolean cancelled = new AtomicBoolean();
        session.planningCancelled = cancelled;
        session.planningSnapshotId = snapshot.snapshotId();
        session.state = NavigationState.PLANNING;
        session.lastStateTick = currentTick;
        session.safeSummary = "正在后台计算有界局部路线";
        try {
            UUID navigationId = session.request.navigationId();
            NavigationGoal goal = session.request.goal();
            NavigationPolicy policy = session.request.policy();
            plannerExecutor.execute(() -> {
                RoutePlan plan;
                try {
                    plan = new BoundedAStarPlanner(
                                    settings.maximumExpansions())
                            .plan(
                                    snapshot,
                                    planningStart,
                                    goal,
                                    policy,
                                    cancelled::get);
                } catch (RuntimeException exception) {
                    planningResults.offer(
                            PlanningResult.failed(
                                    navigationId,
                                    snapshot.snapshotId()));
                    return;
                }
                planningResults.offer(new PlanningResult(
                        navigationId,
                        snapshot.snapshotId(),
                        Optional.of(plan)));
            });
        } catch (RejectedExecutionException exception) {
            session.planningCancelled = null;
            terminate(
                    session,
                    NavigationState.FAILED,
                    NavigationFailure.SERVER_OVERLOADED,
                    currentTick,
                    "路径规划队列已满");
        }
    }

    /**
     * 玩家落在负坐标方块边界或刚结束跳跃时，浮点脚位可能短暂映射到支撑方块本身。
     *
     * <p>这里只在同一 X/Z 的脚位、上一格和下一格中选择最近可通行单元，不改变玩家位置，也不
     * 把更远位置冒充起点。
     */
    private static Optional<GridPoint> normalizePlanningStart(
            NavigationSnapshot snapshot, GridPoint liveStart) {
        for (int verticalOffset : new int[] {0, 1, -1}) {
            GridPoint candidate = new GridPoint(
                    liveStart.x(),
                    liveStart.y() + verticalOffset,
                    liveStart.z());
            if (snapshot.contains(candidate)
                    && snapshot.cell(candidate).traversable()) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private void drainPlanningResults(long currentTick) {
        PlanningResult result;
        while ((result = planningResults.poll()) != null) {
            Session session = sessionsById.get(result.navigationId());
            if (session == null
                    || session.state != NavigationState.PLANNING
                    || !Objects.equals(
                            session.planningSnapshotId,
                            result.snapshotId())) {
                continue;
            }
            session.planningCancelled = null;
            session.planningSnapshotId = null;
            if (result.plan().isEmpty()) {
                terminate(
                        session,
                        NavigationState.FAILED,
                        NavigationFailure.INTERNAL_ERROR,
                        currentTick,
                        "路径规划器发生受控异常");
                continue;
            }
            applyPlan(session, result.plan().orElseThrow(), currentTick);
        }
    }

    private void applyPlan(
            Session session, RoutePlan plan, long currentTick) {
        session.expandedNodes =
                Math.addExact(session.expandedNodes, plan.expandedNodes());
        if (plan.status() == RoutePlanStatus.COMPLETE
                || plan.status() == RoutePlanStatus.PARTIAL_FRONTIER) {
            session.route = plan.nodes();
            session.routeIndex =
                    Math.min(1, session.route.size());
            session.routeStatus = plan.status();
            session.waypointAttempts = 0;
            session.actionPhase = ActionPhase.LOOK;
            session.state = NavigationState.FOLLOWING;
            session.safeSummary =
                    plan.status() == RoutePlanStatus.COMPLETE
                            ? "局部完整路线已就绪"
                            : "局部 frontier 路线已就绪";
            session.lastStateTick = currentTick;
            return;
        }
        NavigationFailure failure = switch (plan.status()) {
            case NO_PATH -> plan.touchedUnknownBoundary()
                    ? NavigationFailure.UNLOADED_FRONTIER_TIMEOUT
                    : NavigationFailure.NO_PATH;
            case BUDGET_EXHAUSTED -> NavigationFailure.BUDGET_EXHAUSTED;
            case CANCELLED -> NavigationFailure.CANCELLED;
            case STALE_SNAPSHOT -> NavigationFailure.SNAPSHOT_INCOMPLETE;
            case INVALID_SNAPSHOT -> NavigationFailure.SNAPSHOT_INCOMPLETE;
            case COMPLETE, PARTIAL_FRONTIER ->
                    throw new IllegalStateException("route status");
        };
        terminate(
                session,
                failure == NavigationFailure.CANCELLED
                        ? NavigationState.CANCELLED
                        : NavigationState.FAILED,
                failure,
                currentTick,
                "局部路径规划未产生可执行路线");
    }

    private void follow(
            Session session,
            BotServerPlayer player,
            long currentTick) {
        if (session.activeActionId != null) {
            return;
        }
        if (session.routeIndex >= session.route.size()) {
            if (session.routeStatus == RoutePlanStatus.PARTIAL_FRONTIER) {
                session.segmentsCompleted++;
                session.replans++;
                session.state = NavigationState.REPLANNING;
                session.safeSummary = "已到达局部 frontier，继续滚动重算";
                return;
            }
            session.state = NavigationState.VERIFYING;
            return;
        }

        RouteNode node = session.route.get(session.routeIndex);
        if (waypointReached(player, node.point())) {
            session.routeIndex++;
            session.waypointAttempts = 0;
            session.actionPhase = ActionPhase.LOOK;
            follow(session, player, currentTick);
            return;
        }
        if (session.waypointAttempts >= MAXIMUM_WAYPOINT_ATTEMPTS) {
            recoverOrFail(session, currentTick, "路线节点持续无进展");
            return;
        }
        if (session.actionPhase == ActionPhase.LOOK) {
            submitAction(
                    session,
                    new LookAtAction(
                            node.point().x() + 0.5D,
                            node.point().y() + 0.75D,
                            node.point().z() + 0.5D),
                    ActionPhase.LOOK,
                    ActionPriority.AUTONOMOUS,
                    currentTick,
                    5);
            return;
        }
        if (node.traversalKind() == TraversalKind.OPEN_DOOR) {
            if (!session.request.policy().allowOpenWoodenDoor()) {
                recoverOrFail(
                        session, currentTick, "policy 禁止开门");
                return;
            }
            submitDoorAction(session, player, node, currentTick);
            return;
        }
        ActionRequest movement =
                movementFor(session, player, node);
        submitAction(
                session,
                movement,
                ActionPhase.MOVE,
                ActionPriority.AUTONOMOUS,
                currentTick,
                Math.max(12, settings.followerInputTicks() + 5));
    }

    private ActionRequest movementFor(
            Session session,
            BotServerPlayer player,
            RouteNode node) {
        return switch (node.traversalKind()) {
            case JUMP_UP_ONE, STEP_UP -> new JumpAction(
                    1.0F,
                    0.0F,
                    sprintAllowed(session, player, node),
                    4);
            case CLIMB_UP -> new ClimbInputAction(
                    0.3F, 0.0F, true, false, 4);
            case CLIMB_DOWN -> new ClimbInputAction(
                    0.0F, 0.0F, false, true, 4);
            case SWIM_UP -> new JumpAction(
                    0.4F, 0.0F, false, 4);
            case SWIM_DOWN -> new MoveInputAction(
                    0.4F,
                    0.0F,
                    false,
                    true,
                    false,
                    settings.followerInputTicks(),
                    Math.min(
                            settings.stuckWindowTicks(),
                            settings.followerInputTicks()));
            case START,
                    WALK_CARDINAL,
                    WALK_DIAGONAL,
                    DROP_SAFE,
                    SWIM_HORIZONTAL,
                    WAIT_FOR_OBSTACLE -> new MoveInputAction(
                            1.0F,
                            0.0F,
                            sprintAllowed(session, player, node),
                            false,
                            node.locomotionMode()
                                            == io.github.greytaiwolf.botplayer
                                                    .navigation.path
                                                    .LocomotionMode.WATER
                                    && player.isUnderWater(),
                            settings.followerInputTicks(),
                            Math.min(
                                    settings.stuckWindowTicks(),
                                    settings.followerInputTicks()));
            case OPEN_DOOR ->
                    throw new IllegalArgumentException(
                            "door movement requires an interaction action");
        };
    }

    private void submitDoorAction(
            Session session,
            BotServerPlayer player,
            RouteNode node,
            long currentTick) {
        BlockPos position = node.point().toBlockPos();
        Vec3 eye = player.getEyePosition();
        Vec3 center = Vec3.atCenterOf(position);
        Direction face = Direction.getNearest(
                eye.x - center.x,
                eye.y - center.y,
                eye.z - center.z);
        BlockHitTarget hit = MinecraftActionSnapshot.blockHit(
                player,
                new BlockHitResult(center, face, position, false));
        WorldInteractionAction action = new WorldInteractionAction(
                new WorldInteractionActionSpec.UseOnBlock(
                        WorldInteractionActionSpec.Hand.MAIN_HAND,
                        hit,
                        MinecraftActionSnapshot.selectedItem(player)));
        submitAction(
                session,
                action,
                ActionPhase.INTERACT,
                ActionPriority.AUTONOMOUS,
                currentTick,
                20);
        session.state = NavigationState.INTERACTING;
    }

    private void submitAction(
            Session session,
            ActionRequest action,
            ActionPhase purpose,
            ActionPriority priority,
            long currentTick,
            int maxTicks) {
        UUID actionId = UUID.randomUUID();
        long deadline = Math.min(
                session.request.deadlineTick(),
                currentTick + maxTicks + 10L);
        ActionEnvelope envelope = new ActionEnvelope(
                actionId,
                session.request.botId(),
                session.request.botGeneration(),
                "navigation:"
                        + session.request.navigationId()
                        + ":"
                        + session.actionSequence++,
                deadline,
                maxTicks,
                action,
                ActionOrigin.fromController(
                        ControllerKind.NAVIGATION,
                        session.request.navigationId()));
        ActionMailbox.Submission submission =
                actionSubmitter.submit(envelope, priority);
        if (submission.status()
                != ActionMailbox.SubmissionStatus.ENQUEUED) {
            recoverOrFail(
                    session,
                    currentTick,
                    "原子动作未进入运行期："
                            + submission.status().name());
            return;
        }
        session.activeActionId = actionId;
        session.activeActionPurpose = purpose;
        submission.completion()
                .orElseThrow()
                .whenComplete((outcome, throwable) ->
                        actionResults.offer(new CompletedAction(
                                session.request.navigationId(),
                                actionId,
                                Optional.ofNullable(outcome),
                                throwable != null)));
    }

    private void drainActionResults(long currentTick) {
        CompletedAction result;
        while ((result = actionResults.poll()) != null) {
            Session session = sessionsById.get(result.navigationId());
            if (session == null
                    || !Objects.equals(
                            session.activeActionId, result.actionId())) {
                continue;
            }
            ActionPhase purpose = session.activeActionPurpose;
            session.activeActionId = null;
            session.activeActionPurpose = null;
            if (result.callbackFailed() || result.outcome().isEmpty()) {
                recoverOrFail(
                        session, currentTick, "原子动作完成回调失败");
                continue;
            }
            ActionOutcome outcome = result.outcome().orElseThrow();
            if (outcome.state() == ActionState.SUCCEEDED) {
                onActionSucceeded(session, purpose, currentTick);
            } else if (outcome.state() == ActionState.PREEMPTED
                    && session.state
                            == NavigationState.SUSPENDED_BY_SAFETY) {
                session.safeSummary = "导航动作已由安全反射抢占";
            } else {
                recoverOrFail(
                        session,
                        currentTick,
                        "原子动作失败："
                                + outcome.failureCode().name());
            }
        }
    }

    private void onActionSucceeded(
            Session session, ActionPhase purpose, long currentTick) {
        switch (purpose) {
            case LOOK -> {
                session.actionPhase = ActionPhase.MOVE;
                session.state = NavigationState.FOLLOWING;
            }
            case MOVE -> {
                session.waypointAttempts++;
                session.actionPhase = ActionPhase.LOOK;
                session.state = NavigationState.FOLLOWING;
            }
            case INTERACT -> {
                session.replans++;
                session.route = List.of();
                session.routeIndex = 0;
                session.state = NavigationState.REPLANNING;
                session.safeSummary = "世界交互完成，重新采样真实方块状态";
            }
        }
        session.lastStateTick = currentTick;
    }

    private void recoverOrFail(
            Session session, long currentTick, String summary) {
        session.recoveryAttempts++;
        if (session.recoveryAttempts
                > session.request.policy().maximumRecoveryAttempts()) {
            terminate(
                    session,
                    NavigationState.FAILED,
                    NavigationFailure.STUCK,
                    currentTick,
                    summary);
            return;
        }
        cancelActiveAction(session);
        session.route = List.of();
        session.routeIndex = 0;
        session.replans++;
        session.state = NavigationState.REPLANNING;
        session.safeSummary = summary + "；将从真实位置重算";
        session.lastStateTick = currentTick;
    }

    private void succeed(
            Session session,
            BotServerPlayer player,
            long currentTick) {
        terminate(
                session,
                NavigationState.SUCCEEDED,
                NavigationFailure.NONE,
                currentTick,
                "已从真实玩家位置验证导航目标");
    }

    private void terminate(
            Session session,
            NavigationState terminalState,
            NavigationFailure failure,
            long currentTick,
            String summary) {
        if (session.state.isTerminal()) {
            return;
        }
        session.snapshotCursor = cancelCursor(session.snapshotCursor);
        cancelPlanning(session);
        cancelActiveAction(session);
        session.state = terminalState;
        session.failure = failure;
        session.safeSummary = summary;
        session.lastStateTick = currentTick;
        GridPoint finalPosition = activeBotResolver
                .apply(
                        session.request.botId(),
                        session.request.botGeneration())
                .map(player -> GridPoint.from(player.blockPosition()))
                .orElse(session.lastKnownPosition);
        NavigationOutcome outcome = new NavigationOutcome(
                session.request.navigationId(),
                terminalState,
                failure,
                finalPosition,
                session.startedTick,
                currentTick,
                session.segmentsCompleted,
                session.replans,
                session.recoveryAttempts,
                session.expandedNodes,
                summary);
        session.completion.complete(outcome);
        terminalSessionOrder.addLast(session.request.navigationId());
        while (terminalSessionOrder.size()
                > TERMINAL_SESSION_CAPACITY) {
            UUID evictedId = terminalSessionOrder.removeFirst();
            Session evicted = sessionsById.remove(evictedId);
            if (evicted != null) {
                sessionsByBot.remove(
                        evicted.request.botId(), evicted);
            }
        }
    }

    private boolean supplyBlocked(
            BotServerPlayer player,
            NavigationRequest request,
            double distanceSquared) {
        int minimumFood = Math.max(
                settings.minimumTravelFood(),
                request.policy().minimumFoodToContinue());
        double minimumHealth = Math.max(
                settings.minimumTravelHealth(),
                request.policy().minimumHealthToContinue());
        return distanceSquared > 64.0D
                && (player.getFoodData().getFoodLevel() < minimumFood
                        || player.getHealth() < minimumHealth);
    }

    private boolean sprintAllowed(
            Session session,
            BotServerPlayer player,
            RouteNode node) {
        return session.request.policy().allowSprint()
                && player.getFoodData().getFoodLevel()
                        >= settings.minimumSprintFood()
                && player.getHealth()
                        >= settings.minimumTravelHealth()
                && node.traversalKind()
                        != TraversalKind.DROP_SAFE
                && node.traversalKind()
                        != TraversalKind.WALK_DIAGONAL
                && node.locomotionMode()
                        == io.github.greytaiwolf.botplayer.navigation.path
                                .LocomotionMode.GROUND;
    }

    private boolean waypointReached(
            BotServerPlayer player, GridPoint point) {
        double dx = player.getX() - (point.x() + 0.5D);
        double dz = player.getZ() - (point.z() + 0.5D);
        return dx * dx + dz * dz
                        <= settings.waypointTolerance()
                                * settings.waypointTolerance()
                && Math.abs(player.getY() - point.y()) <= 1.1D;
    }

    private void cancelActiveAction(Session session) {
        if (session.activeActionId == null) {
            return;
        }
        actionCanceller.cancel(
                session.request.botId(),
                session.activeActionId,
                ActionCancellationReason.REQUESTED);
        session.activeActionId = null;
        session.activeActionPurpose = null;
    }

    private static SnapshotBuildCursor cancelCursor(
            SnapshotBuildCursor cursor) {
        if (cursor != null) {
            cursor.cancel();
        }
        return null;
    }

    private static void cancelPlanning(Session session) {
        if (session.planningCancelled != null) {
            session.planningCancelled.set(true);
        }
        session.planningCancelled = null;
        session.planningSnapshotId = null;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (Session session : sessionsByBot.values()) {
            if (!session.state.isTerminal()) {
                session.snapshotCursor =
                        cancelCursor(session.snapshotCursor);
                cancelPlanning(session);
                cancelActiveAction(session);
                session.state = NavigationState.CANCELLED;
                session.failure = NavigationFailure.CANCELLED;
                session.completion.cancel(false);
            }
        }
        plannerExecutor.shutdownNow();
        planningResults.clear();
        actionResults.clear();
    }

    @FunctionalInterface
    public interface ActionSubmitter {
        ActionMailbox.Submission submit(
                ActionEnvelope envelope, ActionPriority priority);
    }

    @FunctionalInterface
    public interface ActionCanceller {
        ActionMailbox.Cancellation cancel(
                UUID botId,
                UUID actionId,
                ActionCancellationReason reason);
    }

    private enum ActionPhase {
        LOOK,
        MOVE,
        INTERACT
    }

    private record PlanningResult(
            UUID navigationId,
            UUID snapshotId,
            Optional<RoutePlan> plan) {
        private PlanningResult {
            Objects.requireNonNull(navigationId, "navigationId");
            Objects.requireNonNull(snapshotId, "snapshotId");
            Objects.requireNonNull(plan, "plan");
        }

        private static PlanningResult failed(
                UUID navigationId, UUID snapshotId) {
            return new PlanningResult(
                    navigationId, snapshotId, Optional.empty());
        }
    }

    private record CompletedAction(
            UUID navigationId,
            UUID actionId,
            Optional<ActionOutcome> outcome,
            boolean callbackFailed) {
        private CompletedAction {
            Objects.requireNonNull(navigationId, "navigationId");
            Objects.requireNonNull(actionId, "actionId");
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    private static final class Session {
        private final NavigationRequest request;
        private final long startedTick;
        private final CompletableFuture<NavigationOutcome> completion =
                new CompletableFuture<>();
        private NavigationState state = NavigationState.CREATED;
        private NavigationFailure failure = NavigationFailure.NONE;
        private GridPoint lastKnownPosition;
        private SnapshotBuildCursor snapshotCursor;
        private AtomicBoolean planningCancelled;
        private UUID planningSnapshotId;
        private List<RouteNode> route = List.of();
        private RoutePlanStatus routeStatus = RoutePlanStatus.NO_PATH;
        private int routeIndex;
        private int segmentsCompleted;
        private int replans;
        private int recoveryAttempts;
        private long expandedNodes;
        private int waypointAttempts;
        private long actionSequence;
        private UUID activeActionId;
        private ActionPhase activeActionPurpose;
        private ActionPhase actionPhase = ActionPhase.LOOK;
        private long lastStateTick;
        private String safeSummary = "导航会话已创建";

        private Session(
                NavigationRequest request,
                long startedTick,
                GridPoint start) {
            this.request = request;
            this.startedTick = startedTick;
            this.lastStateTick = startedTick;
            this.lastKnownPosition = start;
        }

        private NavigationSessionView view() {
            Optional<GridPoint> nextWaypoint =
                    routeIndex < route.size()
                            ? Optional.of(route.get(routeIndex).point())
                            : Optional.empty();
            return new NavigationSessionView(
                    request.navigationId(),
                    request.botId(),
                    request.botGeneration(),
                    state,
                    request.goal(),
                    nextWaypoint,
                    routeIndex,
                    route.size(),
                    segmentsCompleted,
                    replans,
                    recoveryAttempts,
                    state.isTerminal()
                            ? Optional.of(failure)
                            : Optional.empty(),
                    safeSummary);
        }
    }
}
