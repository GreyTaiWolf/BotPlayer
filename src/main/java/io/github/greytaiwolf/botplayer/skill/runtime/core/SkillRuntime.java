package io.github.greytaiwolf.botplayer.skill.runtime.core;

import io.github.greytaiwolf.botplayer.skill.core.SkillDescriptor;
import io.github.greytaiwolf.botplayer.skill.core.SkillFailureCode;
import io.github.greytaiwolf.botplayer.skill.core.SkillId;
import io.github.greytaiwolf.botplayer.skill.core.SkillRegistry;
import io.github.greytaiwolf.botplayer.skill.core.SkillRunState;
import io.github.greytaiwolf.botplayer.skill.core.SkillSignal;
import io.github.greytaiwolf.botplayer.skill.core.SkillSignalInbox;
import io.github.greytaiwolf.botplayer.skill.core.SkillSignalStatus;
import io.github.greytaiwolf.botplayer.skill.core.SkillSignalType;
import io.github.greytaiwolf.botplayer.skill.core.SkillVersion;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlan;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanNode;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanValidation;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanValidator;
import io.github.greytaiwolf.botplayer.skill.reservation.ResourceReservationService;
import io.github.greytaiwolf.botplayer.skill.reservation.ReservationKey;
import io.github.greytaiwolf.botplayer.skill.reservation.ReservationRequest;
import io.github.greytaiwolf.botplayer.skill.reservation.ReservationToken;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * P5A 的通用、串行 DAG 运行时。
 *
 * <p>该类只在服务器主线程推进状态；异步边界只能以 {@link SkillSignal} 投递。
 * 计划在提交前经过静态校验，所有 terminal 路径均解绑信号并释放该 run 的资源预约。
 */
public final class SkillRuntime implements AutoCloseable {
    private static final int MAXIMUM_ID_ATTEMPTS = 8;
    private static final int MAXIMUM_NODE_RESERVATIONS = 64;
    private static final int RESERVATION_LEASE_TICKS = 100;
    private static final UUID ZERO_UUID = new UUID(0L, 0L);

    private final Thread ownerThread;
    private final SkillRegistry registry;
    private final SkillPlanValidator planValidator;
    private final ResourceReservationService reservations;
    private final SkillRuntimeBudget budget;
    private final Supplier<UUID> runIdSupplier;
    private final SkillRuntimeDispatchFence dispatchFence;
    private final SkillSignalInbox signals;
    private final Map<HandlerKey, SkillNodeHandler> handlers =
            new LinkedHashMap<>();
    private final Map<UUID, ActiveRun> activeByBot =
            new LinkedHashMap<>();
    private final Map<UUID, ActiveRun> activeByRun =
            new LinkedHashMap<>();
    private final Map<UUID, SkillRunView> latestByBot =
            new LinkedHashMap<>();
    private final Map<UUID, SkillRunView> retainedByRun =
            new LinkedHashMap<>();
    private boolean closed;
    private long lastObservedTick = -1L;

    public SkillRuntime(
            SkillRegistry registry,
            SkillPlanValidator planValidator,
            ResourceReservationService reservations,
            SkillRuntimeBudget budget) {
        this(
                registry,
                planValidator,
                reservations,
                budget,
                UUID::randomUUID,
                SkillRuntimeDispatchFence.permissive());
    }

    /**
     * 构造带真实派发耐久 fence 的 runtime。Minecraft 生命周期使用此重载；纯逻辑调用方
     * 可继续使用四参数构造器得到显式 permissive 行为。
     */
    public SkillRuntime(
            SkillRegistry registry,
            SkillPlanValidator planValidator,
            ResourceReservationService reservations,
            SkillRuntimeBudget budget,
            SkillRuntimeDispatchFence dispatchFence) {
        this(
                registry,
                planValidator,
                reservations,
                budget,
                UUID::randomUUID,
                dispatchFence);
    }

    SkillRuntime(
            SkillRegistry registry,
            SkillPlanValidator planValidator,
            ResourceReservationService reservations,
            SkillRuntimeBudget budget,
            Supplier<UUID> runIdSupplier) {
        this(
                registry,
                planValidator,
                reservations,
                budget,
                runIdSupplier,
                SkillRuntimeDispatchFence.permissive());
    }

    SkillRuntime(
            SkillRegistry registry,
            SkillPlanValidator planValidator,
            ResourceReservationService reservations,
            SkillRuntimeBudget budget,
            Supplier<UUID> runIdSupplier,
            SkillRuntimeDispatchFence dispatchFence) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.planValidator = Objects.requireNonNull(
                planValidator, "planValidator");
        this.reservations = Objects.requireNonNull(
                reservations, "reservations");
        this.budget = Objects.requireNonNull(budget, "budget");
        this.runIdSupplier = Objects.requireNonNull(
                runIdSupplier, "runIdSupplier");
        this.dispatchFence = Objects.requireNonNull(
                dispatchFence, "dispatchFence");
        this.signals = new SkillSignalInbox(
                Math.min(
                        SkillSignalInbox.MAX_CAPACITY,
                        Math.multiplyExact(
                                budget.maximumActiveRuns(),
                                budget.maximumSignalsPerRunTick() * 4)),
                budget.maximumActiveRuns());
        this.ownerThread = Thread.currentThread();
    }

    public HandlerRegistrationStatus registerHandler(
            SkillId skillId,
            SkillVersion version,
            SkillNodeHandler handler) {
        requireOwnerThread();
        Objects.requireNonNull(skillId, "skillId");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(handler, "handler");
        HandlerKey key = new HandlerKey(skillId, version);
        SkillNodeHandler existing = handlers.get(key);
        if (existing == null) {
            handlers.put(key, handler);
            return HandlerRegistrationStatus.REGISTERED;
        }
        return existing == handler
                ? HandlerRegistrationStatus.ALREADY_REGISTERED
                : HandlerRegistrationStatus.CONFLICT;
    }

    public SkillRunSubmission submit(SkillRunRequest request) {
        requireOwnerThread();
        Objects.requireNonNull(request, "request");
        observeTick(request.submittedTick());
        if (closed) {
            return SkillRunSubmission.rejected(
                    SkillRunSubmission.Status.RUNTIME_CLOSED,
                    "技能运行时已经关闭");
        }
        SkillPlanValidation validation = planValidator.validate(
                request.plan());
        if (!validation.valid()) {
            return SkillRunSubmission.invalid(validation);
        }
        if (activeByBot.containsKey(request.botId())) {
            return SkillRunSubmission.rejected(
                    SkillRunSubmission.Status.BOT_BUSY,
                    "该 Bot 已有活动技能计划");
        }
        if (activeByRun.size() >= budget.maximumActiveRuns()) {
            return SkillRunSubmission.rejected(
                    SkillRunSubmission.Status.RUNTIME_CAPACITY_EXCEEDED,
                    "技能运行时活动计划已达上限");
        }
        for (SkillPlanNode node : request.plan().nodes()) {
            if (!handlers.containsKey(new HandlerKey(
                    node.skillId(), node.skillVersion()))) {
                return SkillRunSubmission.rejected(
                        SkillRunSubmission.Status.HANDLER_UNAVAILABLE,
                        "计划引用了未接线的技能处理器");
            }
        }
        UUID runId = nextRunId();
        if (runId == null) {
            return SkillRunSubmission.rejected(
                    SkillRunSubmission.Status.RUN_ID_UNAVAILABLE,
                    "无法分配唯一技能运行标识");
        }
        long deadlineTick;
        try {
            deadlineTick = Math.addExact(
                    request.submittedTick(),
                    deadlineBudget(request.plan()));
        } catch (ArithmeticException exception) {
            return SkillRunSubmission.rejected(
                    SkillRunSubmission.Status.RUNTIME_CAPACITY_EXCEEDED,
                    "技能计划截止时间超出安全范围");
        }
        SkillSignalInbox.BindStatus binding = signals.bind(
                runId, request.botId(), request.botGeneration());
        if (binding != SkillSignalInbox.BindStatus.BOUND) {
            return SkillRunSubmission.rejected(
                    SkillRunSubmission.Status.RUNTIME_CAPACITY_EXCEEDED,
                    "技能回执队列无法绑定新的运行");
        }
        ActiveRun run = new ActiveRun(
                runId,
                request,
                validation.serialExecutionOrder(),
                deadlineTick);
        if (activeByBot.putIfAbsent(request.botId(), run) != null) {
            signals.unbind(runId);
            throw new IllegalStateException(
                    "active run map changed while on owner thread");
        }
        activeByRun.put(runId, run);
        remember(run);
        return SkillRunSubmission.accepted(runId);
    }

    public SkillSignalInbox.OfferStatus offerSignal(SkillSignal signal) {
        Objects.requireNonNull(signal, "signal");
        /*
         * Action/导航 completion 由受限的异步 dispatcher 投递。这里故意不触碰
         * active run、状态机或 Minecraft 对象；SkillSignalInbox 自己以同步 binding
         * 栅栏拒绝旧 run/generation，真正的状态推进仍只发生在服务器线程 tick()。
         */
        return signals.offer(signal);
    }

    public void tick(long currentTick) {
        requireOwnerThread();
        observeTick(currentTick);
        if (closed) {
            return;
        }
        for (ActiveRun run : List.copyOf(activeByRun.values())) {
            if (activeByRun.get(run.runId) != run) {
                continue;
            }
            if (currentTick >= run.deadlineTick) {
                finishFailed(
                        run,
                        SkillFailureCode.TIMEOUT,
                        "技能计划超过总运行时限",
                        currentTick);
                continue;
            }
            if (run.state == SkillRunState.PAUSED) {
                continue;
            }
            if (!renewReservations(run, currentTick)) {
                finishFailed(
                        run,
                        SkillFailureCode.RESERVATION_EXPIRED,
                        "技能节点资源预约在续租时失效",
                        currentTick);
                continue;
            }
            boolean processedSignal = processSignals(run, currentTick);
            if (activeByRun.get(run.runId) != run
                    || processedSignal) {
                continue;
            }
            progress(run, currentTick);
        }
    }

    public CancelStatus cancel(
            UUID runId, long currentTick, String safeReason) {
        requireOwnerThread();
        Objects.requireNonNull(runId, "runId");
        observeTick(currentTick);
        ActiveRun run = activeByRun.get(runId);
        if (run == null) {
            return CancelStatus.NOT_ACTIVE;
        }
        notifyCancelled(run, safeReason, currentTick);
        finish(run, SkillRunState.CANCELLED, null, safeReason, currentTick);
        return CancelStatus.CANCELLED;
    }

    public CancelStatus preempt(
            UUID runId, long currentTick, String safeReason) {
        requireOwnerThread();
        Objects.requireNonNull(runId, "runId");
        observeTick(currentTick);
        ActiveRun run = activeByRun.get(runId);
        if (run == null) {
            return CancelStatus.NOT_ACTIVE;
        }
        notifyCancelled(run, safeReason, currentTick);
        finish(run, SkillRunState.PREEMPTED, null, safeReason, currentTick);
        return CancelStatus.PREEMPTED;
    }

    /**
     * 由 L0 Safety 发起的可恢复暂停。
     *
     * <p>这与 {@link #preempt(UUID, long, String)} 的终止语义刻意不同：普通
     * DAG 仍保留其 run identity、deadline 和计划，但当前节点先取消自己拥有的受控
     * action/navigation/menu 工作，释放短期 reservation，并令所有旧 revision 的回执
     * 在恢复后失效。调用方必须在风险解除且原版控制面已静止后显式 {@link #resume(UUID,
     * long)}；恢复会从同一节点重新进入 {@code begin()}，以重新观察当前世界，而不会
     * 复活旧 action、menu 或 lease。</p>
     */
    public PauseStatus pauseForSafety(
            UUID runId, long currentTick, String safeReason) {
        requireOwnerThread();
        Objects.requireNonNull(runId, "runId");
        observeTick(currentTick);
        ActiveRun run = activeByRun.get(runId);
        if (run == null) {
            return PauseStatus.NOT_ACTIVE;
        }
        if (run.state == SkillRunState.PAUSED) {
            return PauseStatus.ALREADY_PAUSED;
        }
        if (run.state == SkillRunState.PAUSING) {
            return PauseStatus.PAUSING;
        }
        if (!run.state.canTransitionTo(SkillRunState.PAUSING)) {
            return PauseStatus.NOT_PAUSABLE;
        }
        transition(run, SkillRunState.PAUSING, currentTick, safeReason);
        notifyCancelled(run, safeReason, currentTick);
        releaseReservations(run);
        transition(run, SkillRunState.PAUSED, currentTick,
                "L0 安全暂停清理完成，等待重新观察后恢复");
        return PauseStatus.PAUSED;
    }

    public ResumeStatus resume(UUID runId, long currentTick) {
        requireOwnerThread();
        Objects.requireNonNull(runId, "runId");
        observeTick(currentTick);
        ActiveRun run = activeByRun.get(runId);
        if (run == null) {
            return ResumeStatus.NOT_ACTIVE;
        }
        if (run.state != SkillRunState.PAUSED) {
            return ResumeStatus.NOT_PAUSED;
        }
        transition(run, SkillRunState.RESUMING, currentTick,
                "技能计划恢复请求已接受");
        return ResumeStatus.RESUMING;
    }

    public int closeGeneration(
            UUID botId, long generation, long currentTick, String reason) {
        requireOwnerThread();
        Objects.requireNonNull(botId, "botId");
        if (generation <= 0L) {
            throw new IllegalArgumentException(
                    "generation must be positive");
        }
        observeTick(currentTick);
        int closedRuns = 0;
        for (ActiveRun run : List.copyOf(activeByRun.values())) {
            if (!run.botId.equals(botId) || run.generation != generation) {
                continue;
            }
            notifyCancelled(run, reason, currentTick);
            finish(run, SkillRunState.CANCELLED, null, reason, currentTick);
            closedRuns++;
        }
        return closedRuns;
    }

    public Optional<SkillRunView> inspect(UUID botId) {
        requireOwnerThread();
        return Optional.ofNullable(latestByBot.get(
                Objects.requireNonNull(botId, "botId")));
    }

    public Optional<SkillRunView> inspectRun(UUID runId) {
        requireOwnerThread();
        return Optional.ofNullable(retainedByRun.get(
                Objects.requireNonNull(runId, "runId")));
    }

    /**
     * 导出活动 run 的纯检查点来源。调用方应只在安全状态落盘；等待动作或菜单时不应
     * 把这个结果当作可直接恢复的现场。
     */
    public Optional<SkillRuntimeCheckpoint> checkpoint(UUID botId) {
        requireOwnerThread();
        ActiveRun run = activeByBot.get(
                Objects.requireNonNull(botId, "botId"));
        return run == null
                ? Optional.empty()
                : Optional.of(runtimeCheckpoint(run));
    }

    public int activeRunCount() {
        requireOwnerThread();
        return activeByRun.size();
    }

    public boolean isClosed() {
        requireOwnerThread();
        return closed;
    }

    @Override
    public void close() {
        requireOwnerThread();
        if (closed) {
            return;
        }
        long currentTick = Math.max(0L, lastObservedTick);
        for (ActiveRun run : List.copyOf(activeByRun.values())) {
            notifyCancelled(run, "服务器正在关闭", currentTick);
            finish(run, SkillRunState.CANCELLED, null,
                    "服务器正在关闭", currentTick);
        }
        signals.close();
        closed = true;
    }

    private boolean processSignals(ActiveRun run, long currentTick) {
        List<SkillSignal> completed = signals.drain(
                run.runId,
                run.generation,
                budget.maximumSignalsPerRunTick());
        boolean processed = false;
        for (SkillSignal signal : completed) {
            if (activeByRun.get(run.runId) != run) {
                break;
            }
            if (signal.runRevision() != run.stateRevision) {
                continue;
            }
            processed = true;
            try {
                switch (signal.status()) {
                    case SUCCEEDED -> apply(
                            run,
                            handler(run).signal(
                                    context(run, currentTick), signal),
                            currentTick);
                    case FAILED -> {
                        SkillNodeContext signalContext = context(run, currentTick);
                        SkillNodeHandler.FailedSignalDisposition disposition =
                                handler(run).failed(
                                        signalContext, signal);
                        if (ActionBackedSkillNodeHandler
                                        .consumeNoSideEffectReplan(
                                                disposition, signalContext, signal)
                                && replanCurrentNodeAfterProvenNoSideEffect(
                                        run, signal, currentTick)) {
                            continue;
                        }
                        finishFailed(
                                run,
                                signal.failureCode(),
                                signal.safeSummary(),
                                currentTick);
                    }
                    case CANCELLED -> {
                        notifyCancelled(
                                run, signal.safeSummary(), currentTick);
                        finish(run, SkillRunState.CANCELLED, null,
                                signal.safeSummary(), currentTick);
                    }
                    case PREEMPTED -> {
                        notifyCancelled(
                                run, signal.safeSummary(), currentTick);
                        finish(run, SkillRunState.PREEMPTED, null,
                                signal.safeSummary(), currentTick);
                    }
                    case STALE -> {
                        /*
                         * 旧回执不是“可以继续等”的成功结果。保留它会使一个正在等待
                         * 菜单/动作的节点永久卡住，或在下一次重试时错误复用旧观察。
                         * identity 不一致的信号会更早被 inbox 拒绝；能到这里的 stale
                         * 是当前 run 已无法证明其等待结果，必须 fail closed。
                         */
                        finishFailed(
                                run,
                                SkillFailureCode.WORLD_CHANGED,
                                "技能异步回执已失效，拒绝继续旧世界状态",
                                currentTick);
                    }
                }
            } catch (RuntimeException exception) {
                finishFailed(
                        run,
                        SkillFailureCode.INTERNAL_ERROR,
                        "技能回执处理器抛出 "
                                + exception.getClass().getSimpleName(),
                        currentTick);
            }
        }
        return processed;
    }

    private void progress(ActiveRun run, long currentTick) {
        try {
            if (run.state == SkillRunState.CREATED) {
                transition(run, SkillRunState.PREPARING, currentTick,
                        "技能节点准备开始");
            }
            if (run.state == SkillRunState.RESUMING
                    || run.state == SkillRunState.RECOVERING) {
                run.nodeStarted = false;
                transition(run, SkillRunState.PREPARING, currentTick,
                        run.state == SkillRunState.RESUMING
                                ? "技能节点正在从安全检查点恢复"
                                : "技能节点正在重新观察无副作用失败后的当前状态");
            }
            if (run.state == SkillRunState.PREPARING
                    && !run.nodeStarted) {
                if (!acquireNodeReservations(run, currentTick)) {
                    return;
                }
                SkillRuntimeDispatchFence.Result fence = dispatchFence
                        .beforeNodeDispatch(
                                runtimeCheckpoint(run), currentTick);
                if (!fence.permitted()) {
                    finishFailed(
                            run,
                            SkillFailureCode.INVALID_CHECKPOINT,
                            fence.safeSummary(),
                            currentTick);
                    return;
                }
                run.nodeStarted = true;
                apply(run, handler(run).begin(context(run, currentTick)),
                        currentTick);
                return;
            }
            if (run.state == SkillRunState.RUNNING
                    || run.state == SkillRunState.VERIFYING) {
                apply(run, handler(run).tick(context(run, currentTick)),
                        currentTick);
            }
        } catch (RuntimeException exception) {
            finishFailed(
                    run,
                    SkillFailureCode.INTERNAL_ERROR,
                    "技能节点处理器抛出 "
                            + exception.getClass().getSimpleName(),
                    currentTick);
        }
    }

    private void apply(
            ActiveRun run,
            SkillNodeDirective directive,
            long currentTick) {
        Objects.requireNonNull(directive, "directive");
        switch (directive.kind()) {
            case CONTINUE -> {
                if (run.state != SkillRunState.RUNNING) {
                    transition(run, SkillRunState.RUNNING, currentTick,
                            directive.safeSummary());
                } else {
                    updateSummary(run, directive.safeSummary(), currentTick);
                }
            }
            case WAIT_ACTION,
                    WAIT_NAVIGATION,
                    WAIT_MENU,
                    WAIT_QUERY,
                    WAIT_TIMER -> transition(
                            run,
                            waitingState(directive.kind()),
                            currentTick,
                            directive.safeSummary());
            case VERIFY -> {
                if (run.state != SkillRunState.VERIFYING) {
                    transition(run, SkillRunState.VERIFYING, currentTick,
                            directive.safeSummary());
                } else {
                    updateSummary(run, directive.safeSummary(), currentTick);
                }
            }
            case COMPLETE -> {
                if (run.state != SkillRunState.VERIFYING) {
                    transition(run, SkillRunState.VERIFYING, currentTick,
                            directive.safeSummary());
                }
                completeNode(run, directive.safeSummary(), currentTick);
            }
            case FAIL -> finishFailed(
                    run,
                    directive.failureCode().orElseThrow(),
                    directive.safeSummary(),
                    currentTick);
            case PAUSE -> {
                releaseReservations(run);
                transition(run, SkillRunState.PAUSING, currentTick,
                        directive.safeSummary());
                transition(run, SkillRunState.PAUSED, currentTick,
                        "技能计划已暂停并保留检查点");
            }
            case PREEMPT -> {
                notifyCancelled(run, directive.safeSummary(), currentTick);
                finish(run, SkillRunState.PREEMPTED, null,
                        directive.safeSummary(), currentTick);
            }
        }
    }

    private void completeNode(
            ActiveRun run, String summary, long currentTick) {
        releaseReservations(run);
        run.nodeIndex++;
        run.nodeStarted = false;
        if (run.nodeIndex >= run.order.size()) {
            finish(run, SkillRunState.SUCCEEDED, null, summary,
                    currentTick);
            return;
        }
        transition(run, SkillRunState.PREPARING, currentTick,
                summary + "；开始下一个 DAG 节点");
    }

    private void finishFailed(
            ActiveRun run,
            SkillFailureCode code,
            String summary,
            long currentTick) {
        SkillFailureCode supplied = Objects.requireNonNull(code, "code");
        SkillFailureCode resolved = supplied == SkillFailureCode.NONE
                ? SkillFailureCode.INTERNAL_ERROR
                : supplied;
        notifyCancelled(run, summary, currentTick);
        finish(run, SkillRunState.FAILED, resolved, summary, currentTick);
    }

    /**
     * A handler can reach this path only through its explicit failed-signal
     * hook.  Keep the core guard deliberately narrow: the completed receipt
     * must have belonged to an action wait, the node remains the same, its
     * deadline is unchanged, and the next tick must reacquire reservations and
     * execute the normal dispatch fence before a fresh begin().
     */
    private boolean replanCurrentNodeAfterProvenNoSideEffect(
            ActiveRun run, SkillSignal signal, long currentTick) {
        if (signal.type() != SkillSignalType.ACTION
                || run.state != SkillRunState.WAITING_ACTION
                || !run.nodeStarted) {
            return false;
        }
        releaseReservations(run);
        run.nodeStarted = false;
        transition(run, SkillRunState.RECOVERING, currentTick,
                "原版动作在无副作用围栏处被拒绝，正在重新观察当前节点");
        return true;
    }

    private void finish(
            ActiveRun run,
            SkillRunState terminal,
            SkillFailureCode failureCode,
            String summary,
            long currentTick) {
        if (!terminal.isTerminal()) {
            throw new IllegalArgumentException(
                    "finish requires a terminal state");
        }
        run.failureCode = failureCode;
        transition(run, terminal, currentTick, summary);
        activeByRun.remove(run.runId, run);
        activeByBot.remove(run.botId, run);
        signals.unbind(run.runId);
        releaseReservations(run);
        remember(run);
    }

    private boolean acquireNodeReservations(
            ActiveRun run, long currentTick) {
        if (!run.reservationsByKey.isEmpty()) {
            throw new IllegalStateException(
                    "new skill node started while previous reservations remain held");
        }
        SkillNodeHandler handler = handler(run);
        List<ReservationRequest> requests = List.copyOf(
                Objects.requireNonNull(
                        handler.requiredReservations(context(run, currentTick)),
                        "skill node reservation requests"));
        if (requests.isEmpty()) {
            return true;
        }
        if (requests.size() > MAXIMUM_NODE_RESERVATIONS) {
            finishFailed(
                    run,
                    SkillFailureCode.SERVER_OVERLOADED,
                    "技能节点请求的资源预约超过安全上限",
                    currentTick);
            return false;
        }
        ResourceReservationService.AcquireAllResult acquired =
                reservations.acquireAll(
                        run.botId,
                        run.generation,
                        run.runId,
                        requests,
                        currentTick,
                        RESERVATION_LEASE_TICKS);
        if (!acquired.acquired()) {
            SkillFailureCode failure = switch (acquired.status()) {
                case CONFLICT -> SkillFailureCode.RESOURCE_RESERVED;
                case CAPACITY_EXHAUSTED,
                        ID_UNAVAILABLE -> SkillFailureCode.SERVER_OVERLOADED;
                case EMPTY_REQUEST,
                        DUPLICATE_KEY -> SkillFailureCode.INTERNAL_ERROR;
                case ACQUIRED,
                        ALREADY_HELD -> throw new IllegalStateException(
                                "successful reservation result was not accepted");
            };
            finishFailed(
                    run,
                    failure,
                    "技能节点无法原子取得所需资源预约："
                            + acquired.status().name(),
                    currentTick);
            return false;
        }
        for (ResourceReservationService.AcquireAllEntry entry :
                acquired.entries()) {
            ReservationToken previous = run.reservationsByKey.put(
                    entry.key(), entry.token());
            if (previous != null) {
                throw new IllegalStateException(
                        "reservation batch returned duplicate resource key");
            }
        }
        return true;
    }

    private boolean renewReservations(ActiveRun run, long currentTick) {
        if (run.reservationsByKey.isEmpty()) {
            return true;
        }
        Map<ReservationKey, ReservationToken> renewed =
                new LinkedHashMap<>();
        for (ReservationToken token : run.reservationsByKey.values()) {
            ResourceReservationService.RenewResult result =
                    reservations.renew(
                            token, currentTick, RESERVATION_LEASE_TICKS);
            if (result.status()
                    != ResourceReservationService.RenewStatus.RENEWED) {
                return false;
            }
            ReservationToken replacement = result.token().orElseThrow();
            renewed.put(replacement.key(), replacement);
        }
        run.reservationsByKey.clear();
        run.reservationsByKey.putAll(renewed);
        return true;
    }

    private void releaseReservations(ActiveRun run) {
        reservations.releaseRun(run.botId, run.generation, run.runId);
        run.reservationsByKey.clear();
    }

    private void notifyCancelled(
            ActiveRun run, String reason, long currentTick) {
        if (run.nodeIndex >= run.order.size()) {
            return;
        }
        try {
            handler(run).cancelled(context(run, currentTick),
                    requireSummary(reason));
        } catch (RuntimeException ignored) {
            // 取消不能阻止运行时释放 reservation 和拒绝迟到回执。
        }
    }

    private void transition(
            ActiveRun run,
            SkillRunState next,
            long currentTick,
            String summary) {
        if (currentTick < run.updatedTick) {
            throw new IllegalArgumentException(
                    "skill transition tick must not move backwards");
        }
        run.state.requireTransitionTo(next);
        run.state = next;
        run.stateRevision = Math.incrementExact(run.stateRevision);
        run.updatedTick = currentTick;
        run.safeSummary = requireSummary(summary);
        remember(run);
    }

    private void updateSummary(
            ActiveRun run, String summary, long currentTick) {
        if (currentTick < run.updatedTick) {
            throw new IllegalArgumentException(
                    "skill summary tick must not move backwards");
        }
        run.updatedTick = currentTick;
        run.safeSummary = requireSummary(summary);
        remember(run);
    }

    private SkillNodeContext context(ActiveRun run, long currentTick) {
        SkillPlanNode node = run.nodesById.get(run.order.get(run.nodeIndex));
        if (node == null) {
            throw new IllegalStateException(
                    "validated plan node is missing from runtime index");
        }
        return new SkillNodeContext(
                run.runId,
                run.botId,
                run.generation,
                run.plan.revision(),
                run.stateRevision,
                Math.incrementExact(run.stateRevision),
                node,
                run.nodeIndex,
                currentTick,
                run.deadlineTick,
                reservations);
    }

    private SkillNodeHandler handler(ActiveRun run) {
        SkillPlanNode node = run.nodesById.get(run.order.get(run.nodeIndex));
        if (node == null) {
            throw new IllegalStateException(
                    "validated plan node is missing from runtime index");
        }
        SkillNodeHandler handler = handlers.get(new HandlerKey(
                node.skillId(), node.skillVersion()));
        if (handler == null) {
            throw new IllegalStateException(
                    "registered handler disappeared while a run was active");
        }
        return handler;
    }

    private SkillRunView view(ActiveRun run) {
        boolean terminal = run.state.isTerminal();
        SkillPlanNode activeNode = terminal ? null
                : run.nodesById.get(run.order.get(run.nodeIndex));
        return new SkillRunView(
                run.runId,
                run.botId,
                run.generation,
                run.plan.planId(),
                run.plan.revision(),
                run.state,
                run.stateRevision,
                Optional.ofNullable(activeNode).map(SkillPlanNode::nodeId),
                Optional.ofNullable(activeNode).map(SkillPlanNode::skillId),
                run.nodeIndex,
                run.order.size(),
                run.startedTick,
                run.updatedTick,
                run.deadlineTick,
                Optional.ofNullable(run.failureCode),
                run.safeSummary);
    }

    private SkillRuntimeCheckpoint runtimeCheckpoint(ActiveRun run) {
        List<SkillRuntimeCheckpoint.Node> nodes = new ArrayList<>(
                run.order.size());
        for (int index = 0; index < run.order.size(); index++) {
            SkillRuntimeCheckpoint.State state;
            if (index < run.nodeIndex) {
                state = SkillRuntimeCheckpoint.State.SUCCEEDED;
            } else if (index > run.nodeIndex) {
                state = SkillRuntimeCheckpoint.State.PENDING;
            } else {
                state = run.state == SkillRunState.FAILED
                        ? SkillRuntimeCheckpoint.State.FAILED
                        : SkillRuntimeCheckpoint.State.IN_PROGRESS;
            }
            nodes.add(new SkillRuntimeCheckpoint.Node(
                    run.order.get(index), state));
        }
        return new SkillRuntimeCheckpoint(view(run), run.plan, nodes);
    }

    private void remember(ActiveRun run) {
        SkillRunView view = view(run);
        latestByBot.put(run.botId, view);
        retainedByRun.remove(run.runId);
        retainedByRun.put(run.runId, view);
        while (retainedByRun.size() > budget.maximumRetainedViews()) {
            UUID oldest = retainedByRun.keySet().iterator().next();
            retainedByRun.remove(oldest);
        }
    }

    private long deadlineBudget(SkillPlan plan) {
        long total = 0L;
        for (SkillPlanNode node : plan.nodes()) {
            SkillDescriptor descriptor = registry.find(
                    node.skillId(), node.skillVersion()).orElseThrow(
                            () -> new IllegalStateException(
                                    "validated descriptor disappeared"));
            total = Math.addExact(total, descriptor.maximumRunTicks());
            if (total >= budget.maximumRunTicks()) {
                return budget.maximumRunTicks();
            }
        }
        return Math.max(1L, total);
    }

    private UUID nextRunId() {
        for (int attempt = 0; attempt < MAXIMUM_ID_ATTEMPTS; attempt++) {
            UUID candidate = runIdSupplier.get();
            if (candidate != null
                    && !ZERO_UUID.equals(candidate)
                    && !activeByRun.containsKey(candidate)
                    && !retainedByRun.containsKey(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private void observeTick(long currentTick) {
        if (currentTick < 0L || currentTick < lastObservedTick) {
            throw new IllegalArgumentException(
                    "skill runtime tick must be monotonic and non-negative");
        }
        lastObservedTick = currentTick;
    }

    private void requireOwnerThread() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException(
                    "skill runtime requires the owner server thread");
        }
    }

    private static SkillRunState waitingState(SkillNodeDirective.Kind kind) {
        return switch (kind) {
            case WAIT_ACTION -> SkillRunState.WAITING_ACTION;
            case WAIT_NAVIGATION -> SkillRunState.WAITING_NAVIGATION;
            case WAIT_MENU -> SkillRunState.WAITING_MENU;
            case WAIT_QUERY -> SkillRunState.WAITING_QUERY;
            case WAIT_TIMER -> SkillRunState.WAITING_TIMER;
            case CONTINUE,
                    VERIFY,
                    COMPLETE,
                    FAIL,
                    PAUSE,
                    PREEMPT -> throw new IllegalArgumentException(
                            "directive is not a waiting directive");
        };
    }

    private static String requireSummary(String value) {
        Objects.requireNonNull(value, "safeSummary");
        if (value.length() > SkillRunView.MAX_SUMMARY_LENGTH
                || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    "safeSummary must be a trimmed 0-256 character string");
        }
        return value;
    }

    private record HandlerKey(SkillId skillId, SkillVersion version) {
        private HandlerKey {
            Objects.requireNonNull(skillId, "skillId");
            Objects.requireNonNull(version, "version");
        }
    }

    private static final class ActiveRun {
        private final UUID runId;
        private final UUID botId;
        private final long generation;
        private final SkillPlan plan;
        private final List<UUID> order;
        private final Map<UUID, SkillPlanNode> nodesById;
        private final long startedTick;
        private final long deadlineTick;
        private SkillRunState state = SkillRunState.CREATED;
        private long stateRevision;
        private long updatedTick;
        private int nodeIndex;
        private boolean nodeStarted;
        private final Map<ReservationKey, ReservationToken>
                reservationsByKey = new LinkedHashMap<>();
        private SkillFailureCode failureCode;
        private String safeSummary = "技能计划已创建";

        private ActiveRun(
                UUID runId,
                SkillRunRequest request,
                List<UUID> order,
                long deadlineTick) {
            this.runId = Objects.requireNonNull(runId, "runId");
            this.botId = request.botId();
            this.generation = request.botGeneration();
            this.plan = request.plan();
            this.order = List.copyOf(order);
            Map<UUID, SkillPlanNode> indexed = new LinkedHashMap<>();
            this.plan.nodes().forEach(node -> indexed.put(
                    node.nodeId(), node));
            this.nodesById = Map.copyOf(indexed);
            this.startedTick = request.submittedTick();
            this.updatedTick = request.submittedTick();
            if (this.order.isEmpty() || deadlineTick <= startedTick) {
                throw new IllegalArgumentException(
                        "active run bounds are invalid");
            }
            this.deadlineTick = deadlineTick;
        }
    }

    public enum HandlerRegistrationStatus {
        REGISTERED,
        ALREADY_REGISTERED,
        CONFLICT
    }

    public enum CancelStatus {
        CANCELLED,
        PREEMPTED,
        NOT_ACTIVE
    }

    public enum ResumeStatus {
        RESUMING,
        NOT_ACTIVE,
        NOT_PAUSED
    }

    /** L0 Safety 暂停请求的同步结果；非暂停类终止仍使用 {@link CancelStatus}。 */
    public enum PauseStatus {
        PAUSED,
        ALREADY_PAUSED,
        PAUSING,
        NOT_ACTIVE,
        NOT_PAUSABLE
    }
}
