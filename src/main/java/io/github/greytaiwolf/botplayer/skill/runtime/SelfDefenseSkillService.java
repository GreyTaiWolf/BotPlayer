package io.github.greytaiwolf.botplayer.skill.runtime;

import io.github.greytaiwolf.botplayer.action.ActionEvidence;
import io.github.greytaiwolf.botplayer.action.ActionCancellationReceipt;
import io.github.greytaiwolf.botplayer.action.ActionOutcome;
import io.github.greytaiwolf.botplayer.action.ActionRequest;
import io.github.greytaiwolf.botplayer.action.ActionState;
import io.github.greytaiwolf.botplayer.safety.HazardType;
import io.github.greytaiwolf.botplayer.safety.SafetyFrame;
import io.github.greytaiwolf.botplayer.safety.SafetyHandoff;
import io.github.greytaiwolf.botplayer.safety.SafetyHandoffDecision;
import io.github.greytaiwolf.botplayer.safety.SafetyHandoffRequest;
import io.github.greytaiwolf.botplayer.safety.ThreatSummary;
import io.github.greytaiwolf.botplayer.skill.builtin.defense.DefenseActionKind;
import io.github.greytaiwolf.botplayer.skill.builtin.defense.DefenseActionOutcome;
import io.github.greytaiwolf.botplayer.skill.builtin.defense.DefenseActionReceipt;
import io.github.greytaiwolf.botplayer.skill.builtin.defense.DefenseActionRequest;
import io.github.greytaiwolf.botplayer.skill.builtin.defense.DefenseDecision;
import io.github.greytaiwolf.botplayer.skill.builtin.defense.DefenseObservation;
import io.github.greytaiwolf.botplayer.skill.builtin.defense.DefensePolicy;
import io.github.greytaiwolf.botplayer.skill.builtin.defense.DefenseReason;
import io.github.greytaiwolf.botplayer.skill.builtin.defense.DefenseReceiptStatus;
import io.github.greytaiwolf.botplayer.skill.builtin.defense.DefenseRequest;
import io.github.greytaiwolf.botplayer.skill.builtin.defense.DefenseState;
import io.github.greytaiwolf.botplayer.skill.builtin.defense.DefenseTarget;
import io.github.greytaiwolf.botplayer.skill.builtin.defense.LimitedSelfDefenseSession;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 将 L0 的单一 hostile threat 移交给 P5A 有限自卫状态机的主线程服务。
 *
 * <p>这个类刻意不知道 Minecraft 实体和 {@code ActionEnvelope} 的细节。目标读取、
 * {@link ActionRequest} 构造、动作提交和取消均经过端口。动作完成回调唯一允许的
 * 副作用是写入有界线程安全队列；状态机、世界读取和后续动作签发只在 {@link #tick}
 * 所在的创建线程执行。
 */
public final class SelfDefenseSkillService implements SafetyHandoff, AutoCloseable {
    private static final int MAXIMUM_RUN_ID_ATTEMPTS = 8;
    private static final int RETAINED_VIEW_MULTIPLIER = 4;
    /* One hostile incident owns one bounded self-defense session. */
    private static final int MAXIMUM_INCIDENT_ATTEMPTS = 1;

    private final TargetResolver targetResolver;
    private final ActionFactory actionFactory;
    private final ActionSubmitter actionSubmitter;
    private final ActionCanceller actionCanceller;
    private final DefensePolicy policy;
    private final Limits limits;
    private final Supplier<UUID> runIdSupplier;
    private final Thread ownerThread;
    private final ArrayBlockingQueue<CompletionEvent> completions;
    private final Map<UUID, ActiveRun> activeByBot = new LinkedHashMap<>();
    private final Map<UUID, RunView> latestByBot = new LinkedHashMap<>();
    private final SkillIncidentAttemptLedger incidentAttempts;
    private volatile boolean closed;
    private long lastObservedTick = -1L;

    public SelfDefenseSkillService(
            TargetResolver targetResolver,
            ActionFactory actionFactory,
            ActionSubmitter actionSubmitter,
            ActionCanceller actionCanceller) {
        this(
                targetResolver,
                actionFactory,
                actionSubmitter,
                actionCanceller,
                DefensePolicy.p5aDefault(),
                Limits.p5aDefault(),
                UUID::randomUUID);
    }

    public SelfDefenseSkillService(
            TargetResolver targetResolver,
            ActionFactory actionFactory,
            ActionSubmitter actionSubmitter,
            ActionCanceller actionCanceller,
            DefensePolicy policy,
            Limits limits) {
        this(
                targetResolver,
                actionFactory,
                actionSubmitter,
                actionCanceller,
                policy,
                limits,
                UUID::randomUUID);
    }

    SelfDefenseSkillService(
            TargetResolver targetResolver,
            ActionFactory actionFactory,
            ActionSubmitter actionSubmitter,
            ActionCanceller actionCanceller,
            DefensePolicy policy,
            Limits limits,
            Supplier<UUID> runIdSupplier) {
        this.targetResolver = Objects.requireNonNull(
                targetResolver, "targetResolver");
        this.actionFactory = Objects.requireNonNull(
                actionFactory, "actionFactory");
        this.actionSubmitter = Objects.requireNonNull(
                actionSubmitter, "actionSubmitter");
        this.actionCanceller = Objects.requireNonNull(
                actionCanceller, "actionCanceller");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.runIdSupplier = Objects.requireNonNull(
                runIdSupplier, "runIdSupplier");
        this.ownerThread = Thread.currentThread();
        this.completions = new ArrayBlockingQueue<>(
                limits.completionQueueCapacity());
        this.incidentAttempts = new SkillIncidentAttemptLedger(
                Math.multiplyExact(
                        limits.maximumActiveRuns(), RETAINED_VIEW_MULTIPLIER),
                MAXIMUM_INCIDENT_ATTEMPTS,
                limits.runDeadlineTicks());
    }

    /**
     * 仅接收带 source entity 的 hostile-targeting 交接。
     *
     * <p>玩家、友军、未知分类、无 source、已关闭代际和容量不足均回退到 L0，
     * 而不会猜测或升级为战斗行为。
     */
    @Override
    public SafetyHandoffDecision request(SafetyHandoffRequest request) {
        Objects.requireNonNull(request, "request");
        requireOwnerThread();
        observeTick(request.currentTick());
        if (closed || !allowsLimitedSelfDefense(request)) {
            preemptUnsafeRun(request);
            return SafetyHandoffDecision.FALLBACK;
        }

        UUID sourceEntityId = request.hazard().sourceEntityId().orElseThrow();
        ActiveRun existing = activeByBot.get(request.botId());
        if (existing != null) {
            return existing.generation == request.botGeneration()
                    && existing.incidentId.equals(request.incidentId())
                    && existing.target.entityId().equals(sourceEntityId)
                    ? SafetyHandoffDecision.ALREADY_DELEGATED
                    : SafetyHandoffDecision.FALLBACK;
        }
        if (activeByBot.size() >= limits.maximumActiveRuns()) {
            return SafetyHandoffDecision.FALLBACK;
        }

        DefenseTarget target;
        try {
            target = targetResolver.resolveTarget(request).orElse(null);
        } catch (RuntimeException exception) {
            return SafetyHandoffDecision.FALLBACK;
        }
        if (target == null
                || !target.entityId().equals(sourceEntityId)
                || !target.alive()
                || !target.targetClass().isExplicitHostile()) {
            return SafetyHandoffDecision.FALLBACK;
        }
        UUID runId = nextRunId();
        if (runId == null) {
            return SafetyHandoffDecision.FALLBACK;
        }
        long deadlineTick;
        try {
            deadlineTick = Math.addExact(
                    request.currentTick(), limits.runDeadlineTicks());
        } catch (ArithmeticException exception) {
            return SafetyHandoffDecision.FALLBACK;
        }
        try {
            if (incidentAttempts.allow(
                            request.botId(),
                            request.botGeneration(),
                            request.incidentId(),
                            request.currentTick())
                    != SkillIncidentAttemptLedger.AllowStatus.ALLOWED) {
                return SafetyHandoffDecision.FALLBACK;
            }
        } catch (ArithmeticException exception) {
            return SafetyHandoffDecision.FALLBACK;
        }
        ActiveRun run = new ActiveRun(
                runId,
                request.botId(),
                request.botGeneration(),
                request.incidentId(),
                target,
                request.currentTick(),
                deadlineTick,
                new LimitedSelfDefenseSession(
                        new DefenseRequest(runId, target), policy));
        if (activeByBot.putIfAbsent(request.botId(), run) != null) {
            throw new IllegalStateException(
                    "active self-defense map changed on owner thread");
        }
        remember(run, RunStatus.ACTIVE, Optional.empty(),
                "有限自卫已接受");
        return SafetyHandoffDecision.DELEGATED;
    }

    /**
     * 由 L0 的下一份安全帧撤销不再满足近战边界的活动会话。
     *
     * <p>这不是普通生命周期关闭：只取消同一 Bot/代际的活动 run，迟到动作回执仍按原 run
     * 身份被忽略。
     */
    @Override
    public boolean preempt(SafetyHandoffRequest request) {
        Objects.requireNonNull(request, "request");
        requireOwnerThread();
        observeTick(request.currentTick());
        return preemptUnsafeRun(request);
    }

    /**
     * 处理有界回执并推进所有活动自卫会话。
     *
     * <p>调用方必须在服务器主线程每 Tick 调用一次；时间倒退被视为编排错误。
     */
    public void tick(long currentTick) {
        requireOwnerThread();
        observeTick(currentTick);
        if (closed) {
            return;
        }
        drainCompletions(currentTick);
        for (ActiveRun run : List.copyOf(activeByBot.values())) {
            if (activeByBot.get(run.botId) != run) {
                continue;
            }
            if (run.completionQueueOverflowed.get()) {
                fail(run, Failure.COMPLETION_QUEUE_OVERFLOW,
                        "动作完成队列已满", currentTick);
                continue;
            }
            if (currentTick >= run.deadlineTick) {
                fail(run, Failure.TIMEOUT,
                        "有限自卫超过总时限", currentTick);
                continue;
            }
            if (run.dispatch != null) {
                continue;
            }
            progress(run, currentTick);
        }
    }

    /**
     * 供 L0 在更高优先级危险出现时抢占当前 run。
     *
     * @return 是否确实抢占了同一 Bot、同一代际的活动会话
     */
    public boolean preempt(
            UUID botId, long generation, long currentTick) {
        Objects.requireNonNull(botId, "botId");
        requireGeneration(generation);
        requireOwnerThread();
        observeTick(currentTick);
        ActiveRun run = activeByBot.get(botId);
        if (run == null || run.generation != generation) {
            incidentAttempts.closeGeneration(botId, generation);
            return false;
        }
        preemptActiveRun(run, "L0 安全层已抢占", currentTick);
        return true;
    }

    /** 关闭一个代际时取消其有限自卫，旧回执会按 run 身份被忽略。 */
    public boolean closeGeneration(
            UUID botId, long generation, long currentTick) {
        Objects.requireNonNull(botId, "botId");
        requireGeneration(generation);
        requireOwnerThread();
        observeTick(currentTick);
        ActiveRun run = activeByBot.get(botId);
        if (run == null || run.generation != generation) {
            return false;
        }
        Optional<ActionCancellationReceipt> cancellation = cancelOutstanding(
                run, AuthorizationRevocation.GENERATION_CLOSED);
        if (finishUnsafeCancellation(run, cancellation, currentTick)) {
            incidentAttempts.closeGeneration(botId, generation);
            return true;
        }
        finish(run, RunStatus.CLOSED, Optional.of(Failure.GENERATION_CLOSED),
                "Bot 代际已关闭", currentTick);
        incidentAttempts.closeGeneration(botId, generation);
        return true;
    }

    public Optional<RunView> latestView(UUID botId) {
        Objects.requireNonNull(botId, "botId");
        requireOwnerThread();
        return Optional.ofNullable(latestByBot.get(botId));
    }

    public int activeRunCount() {
        requireOwnerThread();
        return activeByBot.size();
    }

    @Override
    public void close() {
        requireOwnerThread();
        if (closed) {
            return;
        }
        closed = true;
        long closingTick = Math.max(0L, lastObservedTick);
        for (ActiveRun run : List.copyOf(activeByBot.values())) {
            Optional<ActionCancellationReceipt> cancellation =
                    cancelOutstanding(run, AuthorizationRevocation.SERVER_STOP);
            if (finishUnsafeCancellation(run, cancellation, closingTick)) {
                continue;
            }
            finish(run, RunStatus.CLOSED,
                    Optional.of(Failure.RUNTIME_CLOSED),
                    "有限自卫服务已关闭", closingTick);
        }
        completions.clear();
        incidentAttempts.clear();
    }

    /**
     * P0-2 的单一近战资格门：完整安全帧、健康状态、单个敌对目标和已验证的撤退输入缺一不可。
     */
    private boolean allowsLimitedSelfDefense(
            SafetyHandoffRequest request) {
        if (request.hazard().type() != HazardType.HOSTILE_TARGETING
                || request.hazard().sourceEntityId().isEmpty()) {
            return false;
        }
        SafetyFrame frame = request.frame();
        if (frame.threatCoverageIncomplete()
                || frame.safeRetreat().isEmpty()
                || frame.maximumHealth() <= 0.0F
                || frame.health() / frame.maximumHealth()
                        <= policy.retreatHealthFraction()
                || frame.authoritativeVitalLoss() > 0.0F
                || frame.voidExposure()
                || frame.inLava()
                || frame.onFire()
                || frame.suffocating()
                || frame.underWater()
                || frame.unsafeForwardSupport()) {
            return false;
        }
        UUID source = request.hazard().sourceEntityId().orElseThrow();
        long hostileCount = frame.threats().stream()
                .filter(threat -> threat.kind()
                        == ThreatSummary.Kind.HOSTILE)
                .count();
        if (hostileCount != 1L) {
            return false;
        }
        boolean sourceIsSingleTargetingHostile = frame.threats().stream()
                .anyMatch(threat -> threat.entityId().equals(source)
                        && threat.kind() == ThreatSummary.Kind.HOSTILE
                        && threat.targetingBot());
        if (!sourceIsSingleTargetingHostile) {
            return false;
        }
        return frame.threats().stream().noneMatch(threat ->
                threat.kind() == ThreatSummary.Kind.EXPLOSIVE
                        || (threat.kind() == ThreatSummary.Kind.PROJECTILE
                                && threat.approachScore() > 0.0D));
    }

    /** 只有当前 run 与这一帧仍是同一安全交接，才允许它继续执行。 */
    private boolean preemptUnsafeRun(SafetyHandoffRequest request) {
        ActiveRun run = activeByBot.get(request.botId());
        if (run == null || run.generation != request.botGeneration()) {
            return false;
        }
        if (!request.frame().threatCoverageIncomplete()
                && request.frame().threats().stream().noneMatch(threat ->
                        threat.kind() == ThreatSummary.Kind.HOSTILE)
                && run.dispatch == null) {
            return false;
        }
        boolean sameSecureHandoff = allowsLimitedSelfDefense(request)
                && run.incidentId.equals(request.incidentId())
                && request.hazard().sourceEntityId()
                        .filter(run.target.entityId()::equals)
                        .isPresent()
                && outstandingRetreatStillVerified(run, request.frame());
        if (sameSecureHandoff) {
            return false;
        }
        preemptActiveRun(run, "L0 新安全帧撤销了有限自卫资格",
                request.currentTick());
        return true;
    }

    /** 撤退输入一旦不再等于当前 L0 候选，就取消旧输入而不是赌它仍然安全。 */
    private static boolean outstandingRetreatStillVerified(
            ActiveRun run, SafetyFrame frame) {
        ActionDispatch dispatch = run.dispatch;
        if (dispatch == null
                || dispatch.defenseAction().kind()
                        != DefenseActionKind.RETREAT) {
            return true;
        }
        return dispatch.defenseAction().safeRetreat()
                .filter(retreat -> frame.safeRetreat()
                        .filter(retreat::equals)
                        .isPresent())
                .isPresent();
    }

    private void preemptActiveRun(
            ActiveRun run, String summary, long currentTick) {
        run.session.preemptBySafety();
        Optional<ActionCancellationReceipt> cancellation = cancelOutstanding(
                run, AuthorizationRevocation.SAFETY_PREEMPTION);
        if (finishUnsafeCancellation(run, cancellation, currentTick)) {
            return;
        }
        finish(run, RunStatus.PREEMPTED, Optional.empty(),
                summary, currentTick);
    }

    private void drainCompletions(long currentTick) {
        List<CompletionEvent> drained = new ArrayList<>(
                limits.maximumCompletionsPerTick());
        completions.drainTo(drained, limits.maximumCompletionsPerTick());
        for (CompletionEvent event : drained) {
            ActiveRun current = activeByBot.get(event.run.botId);
            if (current != event.run
                    || current.dispatch == null
                    || !current.dispatch.equals(event.dispatch)) {
                continue;
            }
            revokeOutstanding(current, AuthorizationRevocation.COMPLETED);
            if (event.throwable != null
                    || event.outcome == null
                    || !event.dispatch.actionId().equals(
                            event.outcome.actionId())) {
                fail(current, Failure.ACTION_COMPLETION_INVALID,
                        "动作完成回执无效", currentTick);
                continue;
            }
            if (event.outcome.state() == ActionState.PREEMPTED) {
                current.session.preemptBySafety();
                finish(current, RunStatus.PREEMPTED, Optional.empty(),
                        "动作层已被安全抢占", currentTick);
                continue;
            }
            if (event.outcome.state() == ActionState.STALE) {
                fail(current, Failure.ACTION_STALE,
                        "动作层回执已过期", currentTick);
                continue;
            }
            boolean verifiedTargetElimination = verifiedTargetElimination(
                    event.dispatch, event.outcome);
            DefenseReceiptStatus status = current.session.acknowledge(
                    new DefenseActionReceipt(
                            event.dispatch.defenseAction(),
                            mapActionOutcome(event.outcome.state())),
                    verifiedTargetElimination);
            if (status != DefenseReceiptStatus.ACCEPTED) {
                fail(current, Failure.ACTION_COMPLETION_INVALID,
                        "动作完成回执不属于当前会话", currentTick);
                continue;
            }
            DefenseDecision decision = current.session.decision();
            if (decision.state().terminal()) {
                settleTerminalDecision(current, decision, currentTick);
            }
        }
    }

    private void progress(ActiveRun run, long currentTick) {
        DefenseObservation observation;
        try {
            observation = targetResolver.observe(
                    run.botId, run.generation, run.target).orElse(null);
        } catch (RuntimeException exception) {
            fail(run, Failure.TARGET_UNAVAILABLE,
                    "目标观测器异常", currentTick);
            return;
        }
        if (observation == null) {
            fail(run, Failure.TARGET_UNAVAILABLE,
                    "目标不再可观测", currentTick);
            return;
        }
        DefenseDecision decision = run.session.next(observation);
        if (decision.state().terminal()) {
            settleTerminalDecision(run, decision, currentTick);
            return;
        }
        DefenseActionRequest instruction = decision.action().orElse(null);
        if (instruction == null) {
            fail(run, Failure.ACTION_COMPLETION_INVALID,
                    "有限自卫未产生动作也未结束", currentTick);
            return;
        }
        ActionRequest action;
        try {
            action = actionFactory.create(instruction, observation).orElse(null);
        } catch (RuntimeException exception) {
            fail(run, Failure.ACTION_FACTORY_REJECTED,
                    "动作工厂异常", currentTick);
            return;
        }
        if (action == null) {
            fail(run, Failure.ACTION_FACTORY_REJECTED,
                    "动作工厂拒绝有限自卫请求", currentTick);
            return;
        }
        ActionDispatch dispatch = newDispatch(run, instruction, action,
                currentTick);
        AuthorizedActionDispatch authorization =
                new AuthorizedActionDispatch(this, run, dispatch);
        /*
         * Install the exact run-bound authority before invoking the external
         * submitter. The submitter is allowed to synchronously trigger L0 or
         * lifecycle work; those paths must see and revoke this same object.
         */
        run.dispatch = dispatch;
        run.authorization = authorization;
        CompletionStage<ActionOutcome> completion;
        try {
            completion = Objects.requireNonNull(
                    actionSubmitter.submit(authorization), "completion");
        } catch (RuntimeException exception) {
            /*
             * A submitter may synchronously run lifecycle/L0 code and throw
             * afterwards. Do not overwrite the newer terminal view (or a new
             * generation's run) while reporting the old submission failure.
             */
            if (activeByBot.get(run.botId) == run) {
                failSubmission(run, "动作提交器拒绝有限自卫请求", currentTick);
            }
            return;
        }
        if (!authorization.wasClaimed()) {
            if (activeByBot.get(run.botId) == run) {
                failSubmission(run, "动作提交器未领取有限自卫授权", currentTick);
            }
            return;
        }
        if (!authorization.isAuthorityCurrent()) {
            /* A re-entrant preempt/close already removed this exact run. */
            if (activeByBot.get(run.botId) == run) {
                failSubmission(run, "有限自卫授权在提交期间已失效", currentTick);
            }
            return;
        }
        remember(run, RunStatus.ACTIVE, Optional.empty(),
                "等待有限自卫动作完成");
        try {
            completion.whenComplete((outcome, throwable) -> enqueueCompletion(
                    run, dispatch, outcome, throwable));
        } catch (RuntimeException exception) {
            if (activeByBot.get(run.botId) == run) {
                failSubmission(run, "动作完成回执无法注册", currentTick);
            }
        }
    }

    private ActionDispatch newDispatch(
            ActiveRun run,
            DefenseActionRequest instruction,
            ActionRequest action,
            long currentTick) {
        long actionDeadline;
        try {
            actionDeadline = Math.min(
                    run.deadlineTick,
                    Math.addExact(currentTick, limits.actionMaximumTicks()));
        } catch (ArithmeticException exception) {
            actionDeadline = run.deadlineTick;
        }
        return new ActionDispatch(
                UUID.randomUUID(),
                run.botId,
                run.generation,
                run.runId,
                actionDeadline,
                limits.actionMaximumTicks(),
                "selfdefense:"
                        + run.runId
                        + ":"
                        + instruction.sequence()
                        + ":"
                        + instruction.kind().name().toLowerCase(Locale.ROOT),
                instruction,
                action);
    }

    private void enqueueCompletion(
            ActiveRun run,
            ActionDispatch dispatch,
            ActionOutcome outcome,
            Throwable throwable) {
        if (closed) {
            return;
        }
        if (!completions.offer(new CompletionEvent(
                run, dispatch, outcome, throwable))) {
            run.completionQueueOverflowed.set(true);
        }
    }

    private void settleTerminalDecision(
            ActiveRun run, DefenseDecision decision, long currentTick) {
        switch (decision.state()) {
            case COMPLETED -> finish(run, RunStatus.COMPLETED,
                    Optional.empty(), "有限自卫已完成", currentTick);
            case PREEMPTED -> finish(run, RunStatus.PREEMPTED,
                    Optional.empty(), "有限自卫已被抢占", currentTick);
            case REJECTED -> fail(run, Failure.TARGET_REJECTED,
                    "目标不再满足有限自卫条件", currentTick);
            case EXHAUSTED -> fail(run, Failure.BUDGET_EXHAUSTED,
                    "有限自卫动作预算已耗尽", currentTick);
            case READY, ATTACK_IN_FLIGHT, RETREAT_IN_FLIGHT ->
                    throw new IllegalStateException(
                            "non-terminal defense decision passed to settlement");
        }
    }

    private void fail(
            ActiveRun run, Failure failure, String summary,
            long currentTick) {
        Optional<ActionCancellationReceipt> cancellation = cancelOutstanding(
                run, AuthorizationRevocation.CANCELLED);
        if (finishUnsafeCancellation(run, cancellation, currentTick)) {
            return;
        }
        finish(run, RunStatus.FAILED, Optional.of(failure), summary,
                currentTick);
    }

    private void failSubmission(
            ActiveRun run, String summary, long currentTick) {
        Optional<ActionCancellationReceipt> cancellation = cancelOutstanding(
                run, AuthorizationRevocation.SUBMISSION_REJECTED);
        if (finishUnsafeCancellation(run, cancellation, currentTick)) {
            return;
        }
        finish(run, RunStatus.FAILED,
                Optional.of(Failure.ACTION_SUBMISSION_REJECTED), summary,
                currentTick);
    }

    private void finish(
            ActiveRun run,
            RunStatus status,
            Optional<Failure> failure,
            String summary,
            long currentTick) {
        if (run.unsafeControlState) {
            return;
        }
        if (currentTick < run.updatedTick) {
            throw new IllegalArgumentException(
                    "self-defense terminal tick must not move backwards");
        }
        activeByBot.remove(run.botId, run);
        revokeOutstanding(run, AuthorizationRevocation.TERMINAL);
        run.updatedTick = currentTick;
        remember(run, status, failure, summary);
    }

    private Optional<ActionCancellationReceipt> cancelOutstanding(
            ActiveRun run, AuthorizationRevocation revocation) {
        AuthorizedActionDispatch authorization = run.authorization;
        revokeOutstanding(run, revocation);
        if (authorization != null) {
            return Optional.of(safeCancel(authorization));
        }
        return Optional.empty();
    }

    private void revokeOutstanding(
            ActiveRun run, AuthorizationRevocation revocation) {
        AuthorizedActionDispatch authorization = run.authorization;
        run.dispatch = null;
        run.authorization = null;
        if (authorization != null) {
            authorization.revoke(revocation);
        }
    }

    private ActionCancellationReceipt safeCancel(
            AuthorizedActionDispatch authorization) {
        AuthorizedActionDispatch required = Objects.requireNonNull(
                authorization, "authorization");
        try {
            ActionCancellationReceipt receipt = Objects.requireNonNull(
                    actionCanceller.cancel(required), "cancellation receipt");
            if (receipt.matches(required.botId(), required.botGeneration(),
                    required.actionId())) {
                return receipt;
            }
        } catch (RuntimeException ignored) {
            // An exception is an unknown physical endpoint, never a safe no-op.
        }
        return ActionCancellationReceipt.unsafe(required.botId(),
                required.botGeneration(), required.actionId(),
                ActionCancellationReceipt.Disposition.UNKNOWN);
    }

    private boolean finishUnsafeCancellation(ActiveRun run,
            Optional<ActionCancellationReceipt> cancellation,
            long currentTick) {
        if (cancellation.isEmpty()
                || cancellation.orElseThrow().safelyRetracted()) {
            return false;
        }
        run.unsafeControlState = true;
        activeByBot.remove(run.botId, run);
        revokeOutstanding(run, AuthorizationRevocation.TERMINAL);
        run.updatedTick = currentTick;
        remember(run, RunStatus.FAILED,
                Optional.of(Failure.UNSAFE_CONTROL_STATE),
                "有限自卫动作取消无法证明物理安全端点");
        return true;
    }

    private void remember(
            ActiveRun run,
            RunStatus status,
            Optional<Failure> failure,
            String summary) {
        latestByBot.remove(run.botId);
        latestByBot.put(run.botId, new RunView(
                run.runId,
                run.botId,
                run.generation,
                run.target.entityId(),
                status,
                run.startedTick,
                run.updatedTick,
                run.deadlineTick,
                run.session.decision(),
                Optional.ofNullable(run.dispatch)
                        .map(ActionDispatch::actionId),
                failure,
                requireSummary(summary)));
        int maximumRetained = Math.multiplyExact(
                limits.maximumActiveRuns(), RETAINED_VIEW_MULTIPLIER);
        while (latestByBot.size() > maximumRetained) {
            UUID oldest = latestByBot.keySet().iterator().next();
            latestByBot.remove(oldest);
        }
    }

    private UUID nextRunId() {
        for (int attempt = 0;
                attempt < MAXIMUM_RUN_ID_ATTEMPTS; attempt++) {
            UUID candidate = runIdSupplier.get();
            if (candidate == null
                    || (candidate.getMostSignificantBits() == 0L
                            && candidate.getLeastSignificantBits() == 0L)) {
                continue;
            }
            boolean collision = activeByBot.values().stream()
                    .anyMatch(run -> run.runId.equals(candidate));
            if (!collision) {
                return candidate;
            }
        }
        return null;
    }

    private void observeTick(long currentTick) {
        if (currentTick < 0L || currentTick < lastObservedTick) {
            throw new IllegalArgumentException(
                    "self-defense tick must be monotonic and non-negative");
        }
        lastObservedTick = currentTick;
    }

    private void requireOwnerThread() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException(
                    "self-defense service requires the owner server thread");
        }
    }

    private static void requireGeneration(long generation) {
        if (generation <= 0L) {
            throw new IllegalArgumentException("generation must be positive");
        }
    }

    private static DefenseActionOutcome mapActionOutcome(ActionState state) {
        return switch (state) {
            case SUCCEEDED -> DefenseActionOutcome.SUCCEEDED;
            case FAILED -> DefenseActionOutcome.FAILED;
            case CANCELLED -> DefenseActionOutcome.CANCELLED;
            case PREEMPTED, STALE, QUEUED, VALIDATING, RUNNING, VERIFYING ->
                    throw new IllegalArgumentException(
                            "terminal action state was required");
        };
    }

    /**
     * {@code AttackEntity} 后端只有在同一 UUID 的精确动作已经验证目标移除时才会给出这对
     * evidence。缺失、重复或矛盾 evidence 都不被当作消灭，随后仍由普通目标观察决定。
     */
    private static boolean verifiedTargetElimination(
            ActionDispatch dispatch, ActionOutcome outcome) {
        if (dispatch.defenseAction().kind() != DefenseActionKind.MELEE_ATTACK
                || outcome.state() != ActionState.SUCCEEDED) {
            return false;
        }
        String targetId = dispatch.defenseAction().targetId().toString();
        boolean matchingEntity = false;
        boolean removed = false;
        boolean entityIdSeen = false;
        boolean removedSeen = false;
        for (ActionEvidence evidence : outcome.evidence()) {
            if ("entity.id".equals(evidence.key())) {
                if (entityIdSeen || !targetId.equals(evidence.value())) {
                    return false;
                }
                entityIdSeen = true;
                matchingEntity = true;
            } else if ("entity.removed".equals(evidence.key())) {
                if (removedSeen || !"true".equals(evidence.value())) {
                    return false;
                }
                removedSeen = true;
                removed = true;
            }
        }
        return matchingEntity && removed;
    }

    private Optional<ClaimedActionDispatch> claim(
            AuthorizedActionDispatch authorization) {
        Objects.requireNonNull(authorization, "authorization");
        requireOwnerThread();
        if (!isAuthorizationCurrent(authorization)) {
            if (authorization.revocation == null) {
                authorization.revoke(AuthorizationRevocation.TERMINAL);
            }
            return Optional.empty();
        }
        if (!authorization.claimed.compareAndSet(false, true)) {
            return Optional.empty();
        }
        /* Guard against a same-thread re-entrant lifecycle change during claim setup. */
        if (!isAuthorizationCurrent(authorization)) {
            if (authorization.revocation == null) {
                authorization.revoke(AuthorizationRevocation.TERMINAL);
            }
            return Optional.empty();
        }
        return Optional.of(new ClaimedActionDispatch(authorization));
    }

    private boolean isClaimCurrent(
            AuthorizedActionDispatch authorization,
            ClaimedActionDispatch claim) {
        Objects.requireNonNull(authorization, "authorization");
        Objects.requireNonNull(claim, "claim");
        requireOwnerThread();
        return claim.authorization == authorization
                && authorization.wasClaimed()
                && isAuthorizationCurrent(authorization);
    }

    private boolean isAuthorizationCurrent(
            AuthorizedActionDispatch authorization) {
        Objects.requireNonNull(authorization, "authorization");
        requireOwnerThread();
        return !closed
                && authorization.revocation == null
                && activeByBot.get(authorization.run.botId)
                        == authorization.run
                && authorization.run.dispatch == authorization.dispatch
                && authorization.run.authorization == authorization;
    }

    private static String requireSummary(String value) {
        Objects.requireNonNull(value, "summary");
        String trimmed = value.strip();
        if (trimmed.isEmpty()
                || trimmed.length() > 256
                || trimmed.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    "summary must be a safe non-empty 1-256 character string");
        }
        return trimmed;
    }

    /**
     * 目标端口在服务器主线程返回纯快照，不能把活 Minecraft 对象泄漏到状态机。
     */
    public interface TargetResolver {
        Optional<DefenseTarget> resolveTarget(SafetyHandoffRequest request);

        Optional<DefenseObservation> observe(
                UUID botId, long generation, DefenseTarget target);
    }

    /** 将一次状态机指令翻译为真实的、已受动作层约束的 {@link ActionRequest}。 */
    @FunctionalInterface
    public interface ActionFactory {
        Optional<ActionRequest> create(
                DefenseActionRequest instruction,
                DefenseObservation observation);
    }

    /**
     * 提交端口只接收由本服务创建的一次性授权。生命周期适配器必须先领取授权，才能取得
     * 提交真实 Action 所需的只读字段；它不能由一个看起来相同的普通 DTO 代替。
     */
    @FunctionalInterface
    public interface ActionSubmitter {
        CompletionStage<ActionOutcome> submit(
                AuthorizedActionDispatch authorization);
    }

    /** 取消端口只接收本服务最初签发的授权身份，而不接收可伪造的动作 DTO。 */
    @FunctionalInterface
    public interface ActionCanceller {
        /**
         * Returns the exact physical cancellation receipt. A missing,
         * mismatched, or unsafe receipt fails the originating service run
         * closed instead of silently publishing PREEMPTED or CLOSED.
         */
        ActionCancellationReceipt cancel(AuthorizedActionDispatch authorization);
    }

    /**
     * 一次性、服务创建的 Action 提交能力。
     *
     * <p>构造器不公开，且此对象没有原始 {@link ActionDispatch} 的 getter。领取必须发生在
     * 创建服务的 owner thread；领取时会重新确认当前 active run、原始 dispatch 对象身份和
     * 尚未撤销状态。即使调用者保留能力对象，第二次或终态后的领取也会失败关闭。
     */
    public static final class AuthorizedActionDispatch {
        private final SelfDefenseSkillService service;
        private final ActiveRun run;
        private final ActionDispatch dispatch;
        private final AtomicBoolean claimed = new AtomicBoolean();
        private volatile AuthorizationRevocation revocation;

        private AuthorizedActionDispatch(
                SelfDefenseSkillService service,
                ActiveRun run,
                ActionDispatch dispatch) {
            this.service = Objects.requireNonNull(service, "service");
            this.run = Objects.requireNonNull(run, "run");
            this.dispatch = Objects.requireNonNull(dispatch, "dispatch");
        }

        public DefenseActionKind kind() {
            return dispatch.defenseAction().kind();
        }

        public UUID actionId() {
            return dispatch.actionId();
        }

        public UUID botId() {
            return dispatch.botId();
        }

        /** Exact immutable generation needed only by lifecycle cancellation containment. */
        public long botGeneration() {
            return dispatch.botGeneration();
        }

        public Optional<AuthorizationRevocation> revocation() {
            return Optional.ofNullable(revocation);
        }

        /** Returns the single immutable claim or empty when it is stale/reused/revoked. */
        public Optional<ClaimedActionDispatch> claim() {
            return service.claim(this);
        }

        private boolean wasClaimed() {
            return claimed.get();
        }

        private boolean isAuthorityCurrent() {
            return service.isAuthorizationCurrent(this);
        }

        private void revoke(AuthorizationRevocation candidate) {
            Objects.requireNonNull(candidate, "candidate");
            AuthorizationRevocation previous = revocation;
            if (previous == null || candidate.priority() > previous.priority()) {
                revocation = candidate;
            }
        }
    }

    /**
     * The one-shot read view obtained from {@link AuthorizedActionDispatch#claim()}.
     * It exposes only immutable Action envelope fields; the raw dispatch object remains private to
     * this service package and cannot be reconstructed or reused as a bridge ingress token.
     */
    public static final class ClaimedActionDispatch {
        private final AuthorizedActionDispatch authorization;

        private ClaimedActionDispatch(AuthorizedActionDispatch authorization) {
            this.authorization = Objects.requireNonNull(
                    authorization, "authorization");
        }

        public UUID actionId() {
            return authorization.dispatch.actionId();
        }

        public UUID botId() {
            return authorization.dispatch.botId();
        }

        public long botGeneration() {
            return authorization.dispatch.botGeneration();
        }

        public UUID selfDefenseRunId() {
            return authorization.dispatch.selfDefenseRunId();
        }

        public long deadlineTick() {
            return authorization.dispatch.deadlineTick();
        }

        public int maximumTicks() {
            return authorization.dispatch.maximumTicks();
        }

        public String idempotencyKey() {
            return authorization.dispatch.idempotencyKey();
        }

        public DefenseActionRequest defenseAction() {
            return authorization.dispatch.defenseAction();
        }

        public ActionRequest action() {
            return authorization.dispatch.action();
        }

        public DefenseActionKind kind() {
            return authorization.dispatch.defenseAction().kind();
        }

        /** Identity-only check for lifecycle adapters; it reveals no raw dispatch. */
        public boolean isClaimOf(AuthorizedActionDispatch candidate) {
            return authorization == Objects.requireNonNull(candidate,
                    "candidate");
        }

        /** Rechecks the original service authority after an external ingress call. */
        public boolean isAuthorityCurrent() {
            return authorization.service.isClaimCurrent(authorization, this);
        }

        public Optional<AuthorizationRevocation> revocation() {
            return Optional.ofNullable(authorization.revocation);
        }

        /* Package-private regression seam; it is unavailable to lifecycle/bridge callers. */
        ActionDispatch rawDispatch() {
            return authorization.dispatch;
        }
    }

    /**
     * Internal raw dispatch. It is intentionally package-private with a package-private constructor:
     * callers outside {@code skill.runtime} cannot manufacture one, and no public bridge method
     * accepts it.
     */
    static final class ActionDispatch {
        private final UUID actionId;
        private final UUID botId;
        private final long botGeneration;
        private final UUID selfDefenseRunId;
        private final long deadlineTick;
        private final int maximumTicks;
        private final String idempotencyKey;
        private final DefenseActionRequest defenseAction;
        private final ActionRequest action;

        ActionDispatch(
                UUID actionId,
                UUID botId,
                long botGeneration,
                UUID selfDefenseRunId,
                long deadlineTick,
                int maximumTicks,
                String idempotencyKey,
                DefenseActionRequest defenseAction,
                ActionRequest action) {
            requireNonZero(actionId, "actionId");
            requireNonZero(botId, "botId");
            requireGeneration(botGeneration);
            requireNonZero(selfDefenseRunId, "selfDefenseRunId");
            if (deadlineTick < 0L || maximumTicks < 1
                    || maximumTicks > 6_000) {
                throw new IllegalArgumentException(
                        "action dispatch timing is outside safe bounds");
            }
            this.actionId = actionId;
            this.botId = botId;
            this.botGeneration = botGeneration;
            this.selfDefenseRunId = selfDefenseRunId;
            this.deadlineTick = deadlineTick;
            this.maximumTicks = maximumTicks;
            this.idempotencyKey = requireIdempotencyKey(idempotencyKey);
            this.defenseAction = Objects.requireNonNull(
                    defenseAction, "defenseAction");
            this.action = Objects.requireNonNull(action, "action");
            if (!selfDefenseRunId.equals(this.defenseAction.runId())) {
                throw new IllegalArgumentException(
                        "defense action must belong to self-defense run");
            }
        }

        UUID actionId() {
            return actionId;
        }

        UUID botId() {
            return botId;
        }

        long botGeneration() {
            return botGeneration;
        }

        UUID selfDefenseRunId() {
            return selfDefenseRunId;
        }

        long deadlineTick() {
            return deadlineTick;
        }

        int maximumTicks() {
            return maximumTicks;
        }

        String idempotencyKey() {
            return idempotencyKey;
        }

        DefenseActionRequest defenseAction() {
            return defenseAction;
        }

        ActionRequest action() {
            return action;
        }

        private static void requireNonZero(UUID value, String name) {
            Objects.requireNonNull(value, name);
            if (value.getMostSignificantBits() == 0L
                    && value.getLeastSignificantBits() == 0L) {
                throw new IllegalArgumentException(name + " must not be zero");
            }
        }

        private static String requireIdempotencyKey(String value) {
            Objects.requireNonNull(value, "idempotencyKey");
            if (value.length() > 128 || value.isBlank()
                    || !value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")) {
                throw new IllegalArgumentException(
                        "idempotencyKey is unsafe");
            }
            return value;
        }
    }

    /** Reason retained for re-entrant post-submit retraction diagnostics. */
    public enum AuthorizationRevocation {
        COMPLETED(0),
        TERMINAL(1),
        CANCELLED(2),
        SUBMISSION_REJECTED(3),
        SAFETY_PREEMPTION(4),
        GENERATION_CLOSED(5),
        SERVER_STOP(6);

        private final int priority;

        AuthorizationRevocation(int priority) {
            this.priority = priority;
        }

        private int priority() {
            return priority;
        }
    }

    /** 可调测试与生产边界；所有上限均是硬上限。 */
    public record Limits(
            int maximumActiveRuns,
            int completionQueueCapacity,
            int maximumCompletionsPerTick,
            int runDeadlineTicks,
            int actionMaximumTicks) {
        public Limits {
            requirePositive(maximumActiveRuns, "maximumActiveRuns", 512);
            requirePositive(completionQueueCapacity,
                    "completionQueueCapacity", 4_096);
            requirePositive(maximumCompletionsPerTick,
                    "maximumCompletionsPerTick", completionQueueCapacity);
            requirePositive(runDeadlineTicks, "runDeadlineTicks", 6_000);
            requirePositive(actionMaximumTicks,
                    "actionMaximumTicks", runDeadlineTicks);
        }

        public static Limits p5aDefault() {
            return new Limits(128, 256, 16, 160, 40);
        }

        private static void requirePositive(
                int value, String name, int maximum) {
            if (value < 1 || value > maximum) {
                throw new IllegalArgumentException(
                        name + " must be in [1, " + maximum + "]");
            }
        }
    }

    public enum RunStatus {
        ACTIVE,
        COMPLETED,
        FAILED,
        PREEMPTED,
        CLOSED;

        public boolean terminal() {
            return this != ACTIVE;
        }
    }

    public enum Failure {
        TARGET_UNAVAILABLE,
        TARGET_REJECTED,
        BUDGET_EXHAUSTED,
        ACTION_FACTORY_REJECTED,
        ACTION_SUBMISSION_REJECTED,
        ACTION_COMPLETION_INVALID,
        ACTION_STALE,
        TIMEOUT,
        COMPLETION_QUEUE_OVERFLOW,
        GENERATION_CLOSED,
        RUNTIME_CLOSED,
        UNSAFE_CONTROL_STATE
    }

    /** 对运维和后续生命周期接线公开的不可变运行快照。 */
    public record RunView(
            UUID runId,
            UUID botId,
            long generation,
            UUID targetId,
            RunStatus status,
            long startedTick,
            long updatedTick,
            long deadlineTick,
            DefenseDecision decision,
            Optional<UUID> activeActionId,
            Optional<Failure> failure,
            String safeSummary) {
        public RunView {
            ActionDispatch.requireNonZero(runId, "runId");
            ActionDispatch.requireNonZero(botId, "botId");
            requireGeneration(generation);
            ActionDispatch.requireNonZero(targetId, "targetId");
            status = Objects.requireNonNull(status, "status");
            if (startedTick < 0L || updatedTick < startedTick
                    || deadlineTick <= startedTick) {
                throw new IllegalArgumentException(
                        "run view ticks are invalid");
            }
            decision = Objects.requireNonNull(decision, "decision");
            activeActionId = Objects.requireNonNull(
                    activeActionId, "activeActionId");
            failure = Objects.requireNonNull(failure, "failure");
            if ((status == RunStatus.FAILED && failure.isEmpty())
                    || (failure.isPresent()
                            && status != RunStatus.FAILED
                            && status != RunStatus.CLOSED)) {
                throw new IllegalArgumentException(
                        "only failed or lifecycle-closed views may carry a failure");
            }
            safeSummary = requireSummary(safeSummary);
        }
    }

    private record CompletionEvent(
            ActiveRun run,
            ActionDispatch dispatch,
            ActionOutcome outcome,
            Throwable throwable) {
        private CompletionEvent {
            run = Objects.requireNonNull(run, "run");
            dispatch = Objects.requireNonNull(dispatch, "dispatch");
        }
    }

    private static final class ActiveRun {
        private final UUID runId;
        private final UUID botId;
        private final long generation;
        private final UUID incidentId;
        private final DefenseTarget target;
        private final long startedTick;
        private final long deadlineTick;
        private final LimitedSelfDefenseSession session;
        private final AtomicBoolean completionQueueOverflowed =
                new AtomicBoolean();
        private long updatedTick;
        private ActionDispatch dispatch;
        private AuthorizedActionDispatch authorization;
        private boolean unsafeControlState;

        private ActiveRun(
                UUID runId,
                UUID botId,
                long generation,
                UUID incidentId,
                DefenseTarget target,
                long startedTick,
                long deadlineTick,
                LimitedSelfDefenseSession session) {
            this.runId = Objects.requireNonNull(runId, "runId");
            this.botId = Objects.requireNonNull(botId, "botId");
            requireGeneration(generation);
            this.generation = generation;
            this.incidentId = Objects.requireNonNull(incidentId, "incidentId");
            this.target = Objects.requireNonNull(target, "target");
            if (startedTick < 0L || deadlineTick <= startedTick) {
                throw new IllegalArgumentException(
                        "self-defense run timing is invalid");
            }
            this.startedTick = startedTick;
            this.updatedTick = startedTick;
            this.deadlineTick = deadlineTick;
            this.session = Objects.requireNonNull(session, "session");
        }
    }
}
