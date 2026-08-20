package io.github.greytaiwolf.botplayer.technique.bridge;

import io.github.greytaiwolf.botplayer.action.ActionEnvelope;
import io.github.greytaiwolf.botplayer.action.ActionFailureCode;
import io.github.greytaiwolf.botplayer.action.ActionKind;
import io.github.greytaiwolf.botplayer.action.ActionPriority;
import io.github.greytaiwolf.botplayer.action.BotActionRuntime;
import io.github.greytaiwolf.botplayer.action.WorldInteractionAction;
import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import io.github.greytaiwolf.botplayer.action.interaction.WorldInteractionActionSpec;
import io.github.greytaiwolf.botplayer.technique.combat.ShieldHoldTechnique;
import io.github.greytaiwolf.botplayer.technique.core.TechniqueFailureCode;
import io.github.greytaiwolf.botplayer.technique.core.TechniqueParameters;
import io.github.greytaiwolf.botplayer.technique.runtime.PlayerTechnique;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueCancelReason;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueChildDispatcher;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueChildState;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueChildTicket;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueRunView;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueSignal;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueSignalStatus;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueStartRequest;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueState;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueSubmission;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Lifecycle-owned route for one fixed off-hand shield hold and release.
 *
 * <p>The lifecycle freezes an already-equipped shield before it calls
 * {@link #start}. The route then creates exactly one generic
 * {@code UseItem(OFF_HAND, RELEASE_AFTER_HOLD)} child. It deliberately has no
 * target, movement, equip, retry, counterattack, input, or generic Action
 * ingress. A durability-changing real block is outside this slice: the
 * generic Action backend fails closed when its frozen item fingerprint drifts.
 */
public final class ShieldHoldTechniqueBridge extends TechniqueRoute {
    /** The only exposed blocking interval for this narrow P5C slice. */
    public static final int HOLD_TICKS = 8;
    /** Includes the fixed hold plus bounded dispatch and cleanup headroom. */
    public static final int ACTION_MAXIMUM_TICKS = 20;
    private static final ResourceId VANILLA_SHIELD =
            new ResourceId("minecraft:shield");

    private final TechniqueLifecycleCoordinator coordinator;
    private final ShieldHoldTechnique technique;
    private final LifecycleTechniqueActionPort actions;
    private final Thread ownerThread;
    /* A completed child is released before its parent Technique finishes. */
    private final Map<UUID, RunBinding> runsByTechniqueId =
            new LinkedHashMap<>();
    private final Map<UUID, RunBinding> runsByBotId = new LinkedHashMap<>();
    private final Map<UUID, ChildBinding> childrenByTicketId =
            new LinkedHashMap<>();
    private PendingStart pendingStart;
    private long lastObservedTick = -1L;
    private boolean closed;

    public ShieldHoldTechniqueBridge(TechniqueLifecycleCoordinator coordinator,
            BotActionRuntime actionRuntime) {
        this(coordinator, TechniqueActionRuntimeGateway.from(
                Objects.requireNonNull(actionRuntime, "actionRuntime")));
    }

    /** Package-private test seam; it does not expose a raw Action API. */
    ShieldHoldTechniqueBridge(TechniqueLifecycleCoordinator coordinator,
            TechniqueActionRuntimeGateway actionRuntime) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        technique = new ShieldHoldTechnique();
        actions = new LifecycleTechniqueActionPort(this.coordinator, this,
                Objects.requireNonNull(actionRuntime, "actionRuntime"));
        ownerThread = Thread.currentThread();
        this.coordinator.register(this);
    }

    /**
     * Starts the sole route-owned child from one lifecycle-frozen off-hand
     * shield. The lifecycle manager keeps this bridge private and performs
     * all player/menu/active-body admission before invoking it.
     */
    public TechniqueSubmission start(UUID botId, long botGeneration,
            ItemStackFingerprint shield, long currentTick) {
        requireOwnerThread();
        UUID requiredBotId = requireNonZero(botId, "botId");
        ItemStackFingerprint requiredShield = Objects.requireNonNull(shield,
                "shield");
        if (botGeneration < 1L || currentTick < 0L) {
            throw new IllegalArgumentException(
                    "shield hold generation/tick is invalid");
        }
        if (requiredShield.isEmpty()) {
            return TechniqueSubmission.rejected(
                    TechniqueSubmission.Status.INVALID_REQUEST,
                    "Shield hold requires a frozen non-empty off-hand item");
        }
        if (!requiredShield.itemId().filter(VANILLA_SHIELD::equals)
                .isPresent()) {
            return TechniqueSubmission.rejected(
                    TechniqueSubmission.Status.INVALID_REQUEST,
                    "Shield hold requires an exact vanilla shield fingerprint");
        }
        observeTick(currentTick);
        if (closed) {
            return TechniqueSubmission.rejected(
                    TechniqueSubmission.Status.RUNTIME_CLOSED,
                    "Shield hold ingress is closed");
        }
        if (pendingStart != null || runsByBotId.containsKey(requiredBotId)
                || coordinator.inspect(requiredBotId).isPresent()) {
            return TechniqueSubmission.rejected(
                    TechniqueSubmission.Status.BOT_BUSY,
                    "Bot already owns a lifecycle technique");
        }

        PendingStart pending = new PendingStart(requiredBotId, botGeneration,
                requiredShield, currentTick, UUID.randomUUID());
        pendingStart = pending;
        try {
            TechniqueSubmission submission = coordinator.start(this,
                    new TechniqueStartRequest(pending.invocationId,
                            pending.botId, pending.botGeneration,
                            ShieldHoldTechnique.ID, ShieldHoldTechnique.VERSION,
                            TechniqueParameters.empty(), pending.currentTick));
            if (pending.rejectionSummary != null) {
                closePending(pending, currentTick);
                return TechniqueSubmission.rejected(
                        TechniqueSubmission.Status.INVALID_REQUEST,
                        pending.rejectionSummary);
            }
            if (submission.status() != TechniqueSubmission.Status.ACCEPTED) {
                return submission;
            }
            RunBinding run = pending.run;
            TechniqueRunView view = submission.techniqueRunId()
                    .flatMap(coordinator::inspectRun)
                    .orElse(null);
            if (run == null || runsByTechniqueId.get(
                    run.techniqueRunId()) != run
                    || !isExactStartingRun(run, view)) {
                closePending(pending, currentTick);
                return TechniqueSubmission.rejected(
                        TechniqueSubmission.Status.GENERATION_QUARANTINED,
                        "Shield hold child could not remain bound after Action ingress");
            }
            return submission;
        } catch (RuntimeException exception) {
            closePending(pending, currentTick);
            return TechniqueSubmission.rejected(
                    TechniqueSubmission.Status.INVALID_REQUEST,
                    "Shield hold lifecycle ingress failed closed");
        } finally {
            pendingStart = null;
        }
    }

    @Override
    PlayerTechnique technique() {
        return technique;
    }

    @Override
    void observeTick(long currentTick) {
        if (currentTick < 0L || currentTick < lastObservedTick) {
            throw new IllegalArgumentException(
                    "shield hold route tick must be monotonic and non-negative");
        }
        lastObservedTick = currentTick;
    }

    @Override
    TechniqueChildDispatcher.Submission submitChild(
            TechniqueChildTicket ticket, TechniqueRunView run) {
        requireOwnerThread();
        TechniqueChildTicket requiredTicket = Objects.requireNonNull(ticket,
                "ticket");
        TechniqueRunView requiredRun = Objects.requireNonNull(run, "run");
        PendingStart pending = pendingStart;
        if (pending == null || !isExactStartingChild(pending, requiredTicket,
                requiredRun)) {
            if (pending != null) {
                pending.rejectionSummary =
                        "Shield hold technique child identity was rejected";
            }
            return rejectedChild(TechniqueChildDispatcher.Status.REJECTED,
                    "Shield hold technique child identity was rejected");
        }
        if (runsByTechniqueId.containsKey(requiredRun.techniqueRunId())
                || runsByBotId.containsKey(requiredTicket.botId())
                || childrenByTicketId.containsKey(requiredTicket.ticketId())) {
            pending.rejectionSummary =
                    "Shield hold technique binding was already occupied";
            return rejectedChild(TechniqueChildDispatcher.Status.CHANNEL_BUSY,
                    "Shield hold technique binding is occupied");
        }

        final RunBinding runBinding;
        final ActionEnvelope envelope;
        try {
            runBinding = RunBinding.freeze(requiredRun, requiredTicket);
            envelope = actionEnvelope(requiredRun, requiredTicket,
                    pending.shield);
        } catch (RuntimeException exception) {
            pending.rejectionSummary =
                    "Shield hold Action binding could not be frozen";
            return rejectedChild(TechniqueChildDispatcher.Status.REJECTED,
                    "Shield hold Action binding could not be frozen");
        }

        /* prebindAction verifies the route, so make its immutable parent visible first. */
        pending.run = runBinding;
        runsByTechniqueId.put(runBinding.techniqueRunId(), runBinding);
        runsByBotId.put(runBinding.botId(), runBinding);

        final ChildBinding childBinding;
        try {
            TechniqueActionPermit permit = coordinator.prebindAction(this,
                    requiredRun, requiredTicket, envelope,
                    ActionPriority.OWNER_TASK);
            childBinding = ChildBinding.freeze(runBinding, requiredTicket,
                    permit, envelope, pending.shield);
        } catch (RuntimeException exception) {
            pending.rejectionSummary =
                    "Shield hold Action binding could not be frozen";
            return rejectedChild(TechniqueChildDispatcher.Status.REJECTED,
                    "Shield hold Action binding could not be frozen");
        }

        /* Every exact child identity is visible before Action ingress can re-enter. */
        pending.child = childBinding;
        childrenByTicketId.put(requiredTicket.ticketId(), childBinding);

        final TechniqueActionSubmission submission;
        try {
            submission = actions.submit(childBinding.permit());
        } catch (RuntimeException exception) {
            pending.rejectionSummary = "Shield hold Action ingress failed closed";
            return rejectedChild(TechniqueChildDispatcher.Status.REJECTED,
                    "Shield hold Action ingress failed closed");
        }
        if (submission.status() != TechniqueActionSubmission.Status.ENQUEUED) {
            pending.rejectionSummary = "Shield hold Action ingress was rejected: "
                    + submission.status().name();
            return rejectedChild(childStatus(submission.status()),
                    "Shield hold Action ingress was rejected");
        }
        TechniqueRunView current = coordinator.inspectRun(
                runBinding.techniqueRunId()).orElse(null);
        if (!isExactStartingRun(runBinding, current)
                || childrenByTicketId.get(requiredTicket.ticketId())
                        != childBinding) {
            requestContainment(childBinding,
                    TechniqueActionCancellationReason.GENERATION_CHANGED,
                    pending.currentTick);
            pending.rejectionSummary =
                    "Shield hold lifecycle changed during Action ingress";
            return rejectedChild(TechniqueChildDispatcher.Status.REJECTED,
                    "Shield hold lifecycle changed during Action ingress");
        }
        return TechniqueChildDispatcher.Submission.accepted(
                "Shield hold Action child accepted");
    }

    @Override
    boolean allowsActionKind(TechniqueChildTicket ticket, ActionKind kind) {
        TechniqueChildTicket required = Objects.requireNonNull(ticket,
                "ticket");
        return ShieldHoldTechnique.OPERATION_KEY.equals(
                required.operationKey())
                && ShieldHoldTechnique.CHANNELS.equals(required.channels())
                && kind == ActionKind.USE_ITEM;
    }

    /** There is no normal shield-stop command in this narrow slice. */
    @Override
    void cancelChild(TechniqueChildTicket ticket, TechniqueCancelReason reason) {
        requireOwnerThread();
        TechniqueChildTicket required = Objects.requireNonNull(ticket,
                "ticket");
        ChildBinding child = childrenByTicketId.get(required.ticketId());
        if (child != null && child.matchesTicket(required)) {
            requestContainment(child, cancellationReason(Objects.requireNonNull(
                    reason, "reason")), lastObservedTick);
        }
    }

    @Override
    void drainCompletedChildren(long currentTick,
            TechniqueLifecycleCoordinator.SignalSink signals) {
        requireOwnerThread();
        TechniqueLifecycleCoordinator.SignalSink requiredSignals =
                Objects.requireNonNull(signals, "signals");
        for (ChildBinding child : List.copyOf(childrenByTicketId.values())) {
            Optional<TechniqueActionTerminal> terminal;
            try {
                terminal = actions.completed(child.permit());
            } catch (RuntimeException exception) {
                coordinator.closeGeneration(child.run().botId(),
                        child.run().botGeneration(), currentTick);
                continue;
            }
            if (terminal.isEmpty()) {
                continue;
            }
            TechniqueSignalStatus status;
            try {
                status = requiredSignals.offer(this, signalFor(child,
                        terminal.orElseThrow()), currentTick);
            } catch (RuntimeException exception) {
                status = TechniqueSignalStatus.RUN_NOT_FOUND;
            }
            if (status == TechniqueSignalStatus.ACCEPTED) {
                /* Keep the parent binding until the next Technique tick reaps it. */
                releaseChild(child);
            } else {
                coordinator.closeGeneration(child.run().botId(),
                        child.run().botGeneration(), currentTick);
            }
        }
    }

    @Override
    void onGenerationClosing(UUID botId, long botGeneration,
            long currentTick) {
        requireOwnerThread();
        UUID requiredBotId = Objects.requireNonNull(botId, "botId");
        for (ChildBinding child : List.copyOf(childrenByTicketId.values())) {
            if (child.run().botId().equals(requiredBotId)
                    && child.run().botGeneration() == botGeneration) {
                /* Generation close remains fail-closed until a real terminal. */
                requestContainment(child,
                        TechniqueActionCancellationReason.GENERATION_CHANGED,
                        currentTick);
            }
        }
    }

    @Override
    void closeIngress() {
        requireOwnerThread();
        closed = true;
    }

    @Override
    boolean isRouteGenerationSafe(UUID botId, long botGeneration) {
        requireOwnerThread();
        UUID requiredBotId = Objects.requireNonNull(botId, "botId");
        return actions.isGenerationSafe(requiredBotId, botGeneration)
                && runsByTechniqueId.values().stream().noneMatch(run ->
                        run.botId().equals(requiredBotId)
                                && run.botGeneration() == botGeneration)
                && childrenByTicketId.values().stream().noneMatch(child ->
                        child.run().botId().equals(requiredBotId)
                                && child.run().botGeneration() == botGeneration)
                && (pendingStart == null
                        || !pendingStart.botId.equals(requiredBotId)
                        || pendingStart.botGeneration != botGeneration);
    }

    @Override
    void verifyRun(TechniqueRunView run) {
        requireOwnerThread();
        TechniqueRunView required = Objects.requireNonNull(run, "run");
        RunBinding binding = runsByTechniqueId.get(required.techniqueRunId());
        if (binding == null || !binding.matchesView(required)) {
            throw new IllegalStateException(
                    "shield hold technique runtime identity changed");
        }
        boolean activeChild = required.state() == TechniqueState.WAITING_CHILDREN
                && required.childTickets().getFirst().state()
                        == TechniqueChildState.ACTIVE;
        boolean pendingPrebind = pendingStart != null
                && pendingStart.run == binding && pendingStart.child == null;
        if (activeChild && !childrenByTicketId.containsKey(binding.ticketId())
                && !pendingPrebind) {
            throw new IllegalStateException(
                    "shield hold active child lost its Action binding");
        }
    }

    @Override
    void reapTerminalRun(UUID techniqueRunId) {
        requireOwnerThread();
        RunBinding run = runsByTechniqueId.remove(Objects.requireNonNull(
                techniqueRunId, "techniqueRunId"));
        if (run == null) {
            return;
        }
        runsByBotId.remove(run.botId(), run);
        ChildBinding child = childrenByTicketId.get(run.ticketId());
        if (child != null && child.run() == run) {
            releaseChild(child);
        }
    }

    private static ActionEnvelope actionEnvelope(TechniqueRunView run,
            TechniqueChildTicket ticket, ItemStackFingerprint shield) {
        long deadline = Math.addExact(ticket.submittedTick(),
                ACTION_MAXIMUM_TICKS);
        return new ActionEnvelope(UUID.randomUUID(), ticket.botId(),
                ticket.botGeneration(), TechniqueActionPermit.idempotencyKeyFor(
                        ticket), deadline, ACTION_MAXIMUM_TICKS,
                new WorldInteractionAction(new WorldInteractionActionSpec.UseItem(
                        WorldInteractionActionSpec.Hand.OFF_HAND, shield,
                        WorldInteractionActionSpec.ItemUseMode.RELEASE_AFTER_HOLD,
                        HOLD_TICKS)), TechniqueActionPermit.originFor(run,
                        ticket));
    }

    private static boolean isExactStartingChild(PendingStart pending,
            TechniqueChildTicket ticket, TechniqueRunView run) {
        return ticket.botId().equals(pending.botId)
                && ticket.botGeneration() == pending.botGeneration
                && ticket.submittedTick() == pending.currentTick
                && ticket.state() == TechniqueChildState.ACTIVE
                && ShieldHoldTechnique.OPERATION_KEY.equals(ticket.operationKey())
                && ShieldHoldTechnique.CHANNELS.equals(ticket.channels())
                && run.skillRunId().equals(pending.invocationId)
                && run.botId().equals(pending.botId)
                && run.botGeneration() == pending.botGeneration
                && run.techniqueId().equals(ShieldHoldTechnique.ID)
                && run.techniqueVersion().equals(ShieldHoldTechnique.VERSION)
                && run.state() == TechniqueState.WAITING_CHILDREN
                && run.childTickets().size() == 1
                && run.childTickets().getFirst().equals(ticket);
    }

    private static boolean isExactStartingRun(RunBinding binding,
            TechniqueRunView run) {
        return run != null && binding.matchesView(run)
                && run.state() == TechniqueState.WAITING_CHILDREN
                && run.childTickets().getFirst().state()
                        == TechniqueChildState.ACTIVE;
    }

    private static TechniqueSignal signalFor(ChildBinding child,
            TechniqueActionTerminal terminal) {
        if (terminal.permit() != child.permit()
                || terminal.kind() != ActionKind.USE_ITEM) {
            return failedSignal(child, TechniqueFailureCode.INTERNAL_ERROR,
                    "Shield hold Action terminal did not match its child");
        }
        ActionOutcomeMapping mapping = mapOutcome(terminal.outcome().state(),
                terminal.outcome().failureCode());
        return new TechniqueSignal(child.run().techniqueRunId(),
                child.ticket().ticketId(), child.run().botId(),
                child.run().botGeneration(), child.ticket().revision(),
                mapping.state(), mapping.failureCode(), nonEmpty(terminal
                        .outcome().safeSummary(), "Shield hold Action completed"));
    }

    private static TechniqueSignal failedSignal(ChildBinding child,
            TechniqueFailureCode failureCode, String summary) {
        return new TechniqueSignal(child.run().techniqueRunId(),
                child.ticket().ticketId(), child.run().botId(),
                child.run().botGeneration(), child.ticket().revision(),
                TechniqueChildState.FAILED, failureCode, summary);
    }

    private static ActionOutcomeMapping mapOutcome(
            io.github.greytaiwolf.botplayer.action.ActionState state,
            ActionFailureCode failureCode) {
        return switch (state) {
            case SUCCEEDED -> new ActionOutcomeMapping(
                    TechniqueChildState.SUCCEEDED, TechniqueFailureCode.NONE);
            case CANCELLED -> new ActionOutcomeMapping(
                    TechniqueChildState.CANCELLED, TechniqueFailureCode.CANCELLED);
            case PREEMPTED -> new ActionOutcomeMapping(
                    TechniqueChildState.PREEMPTED, TechniqueFailureCode.PREEMPTED);
            case STALE -> new ActionOutcomeMapping(TechniqueChildState.STALE,
                    TechniqueFailureCode.GENERATION_CHANGED);
            case FAILED -> new ActionOutcomeMapping(TechniqueChildState.FAILED,
                    failureFor(failureCode));
            case QUEUED, VALIDATING, RUNNING, VERIFYING ->
                    throw new IllegalArgumentException(
                            "shield hold child requires a terminal Action outcome");
        };
    }

    private static TechniqueFailureCode failureFor(ActionFailureCode failure) {
        return switch (failure) {
            case STALE_GENERATION -> TechniqueFailureCode.GENERATION_CHANGED;
            case DEADLINE_EXCEEDED, MAX_TICKS_EXCEEDED ->
                    TechniqueFailureCode.TIMEOUT;
            case CHANNEL_BUSY -> TechniqueFailureCode.CHANNEL_CONFLICT;
            case PERMISSION_DENIED -> TechniqueFailureCode.PERMISSION_DENIED;
            case UNSUPPORTED -> TechniqueFailureCode.UNSUPPORTED;
            case UNSAFE_CONTROL_STATE ->
                    TechniqueFailureCode.ACTION_CLEANUP_UNSAFE;
            case INVALID_REQUEST -> TechniqueFailureCode.INVALID_REQUEST;
            case TARGET_UNAVAILABLE, BOT_NOT_ACTIVE, DUPLICATE_IN_PROGRESS,
                    IDEMPOTENCY_CONFLICT, LEDGER_CAPACITY_EXCEEDED,
                    ACTION_ALIAS_CAPACITY_EXCEEDED,
                    RUNTIME_CAPACITY_EXCEEDED, BACKEND_RESULT_MISMATCH,
                    PRECONDITION_FAILED, INTERNAL_ERROR ->
                    TechniqueFailureCode.ACTION_FAILED;
            case NONE, CANCELLED, PREEMPTED -> throw new IllegalArgumentException(
                    "incoherent failed shield hold Action outcome");
        };
    }

    private static TechniqueChildDispatcher.Submission rejectedChild(
            TechniqueChildDispatcher.Status status, String summary) {
        return TechniqueChildDispatcher.Submission.rejected(
                Objects.requireNonNull(status, "status"), summary);
    }

    private static TechniqueChildDispatcher.Status childStatus(
            TechniqueActionSubmission.Status status) {
        return switch (Objects.requireNonNull(status, "status")) {
            case MAILBOX_FULL, COMPLETION_BACKPRESSURE ->
                    TechniqueChildDispatcher.Status.CAPACITY_EXCEEDED;
            case BOT_GENERATION_CLOSED, RUNTIME_CLOSED, ALREADY_CONSUMED,
                    REJECTED_PREBINDING -> TechniqueChildDispatcher.Status.REJECTED;
            case ENQUEUED -> throw new IllegalArgumentException(
                    "enqueued shield hold Action cannot be rejected");
        };
    }

    private static TechniqueActionCancellationReason cancellationReason(
            TechniqueCancelReason reason) {
        return switch (reason) {
            case REQUESTED -> TechniqueActionCancellationReason.REQUESTED;
            case SAFETY_PREEMPTION ->
                    TechniqueActionCancellationReason.SAFETY_PREEMPTION;
            case GENERATION_CHANGED ->
                    TechniqueActionCancellationReason.GENERATION_CHANGED;
            case SERVER_STOP ->
                    TechniqueActionCancellationReason.RUNTIME_SHUTDOWN;
        };
    }

    private void closePending(PendingStart pending, long currentTick) {
        if (pending.child != null && childrenByTicketId.get(
                pending.child.ticket().ticketId()) == pending.child) {
            requestContainment(pending.child,
                    TechniqueActionCancellationReason.GENERATION_CHANGED,
                    currentTick);
        }
        if (pending.run != null && runsByTechniqueId.get(
                pending.run.techniqueRunId()) == pending.run) {
            try {
                coordinator.closeGeneration(pending.botId, pending.botGeneration,
                        currentTick);
            } catch (RuntimeException ignored) {
                /* The retained binding remains unsafe until normal lifecycle reaping. */
            }
        }
    }

    private void requestContainment(ChildBinding child,
            TechniqueActionCancellationReason reason, long currentTick) {
        try {
            actions.cancelOrContain(child.permit(), reason, currentTick);
        } catch (RuntimeException ignored) {
            /* The port's retained binding keeps the generation fail-closed. */
        }
    }

    private void releaseChild(ChildBinding child) {
        if (childrenByTicketId.remove(child.ticket().ticketId(), child)) {
            actions.release(child.permit());
        }
    }

    private void requireOwnerThread() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException(
                    "shield hold technique bridge requires the owner server thread");
        }
    }

    private static UUID requireNonZero(UUID value, String name) {
        UUID required = Objects.requireNonNull(value, name);
        if (required.getMostSignificantBits() == 0L
                && required.getLeastSignificantBits() == 0L) {
            throw new IllegalArgumentException(name + " must not be zero UUID");
        }
        return required;
    }

    private static String nonEmpty(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static final class PendingStart {
        private final UUID botId;
        private final long botGeneration;
        private final ItemStackFingerprint shield;
        private final long currentTick;
        private final UUID invocationId;
        private RunBinding run;
        private ChildBinding child;
        private String rejectionSummary;

        private PendingStart(UUID botId, long botGeneration,
                ItemStackFingerprint shield, long currentTick,
                UUID invocationId) {
            this.botId = botId;
            this.botGeneration = botGeneration;
            this.shield = shield;
            this.currentTick = currentTick;
            this.invocationId = invocationId;
        }
    }

    /** Immutable parent identity retained until TechniqueRuntime reaps it. */
    private record RunBinding(UUID techniqueRunId, UUID skillRunId, UUID botId,
            long botGeneration, UUID ticketId, long ticketRevision) {
        private RunBinding {
            requireNonZero(techniqueRunId, "techniqueRunId");
            requireNonZero(skillRunId, "skillRunId");
            requireNonZero(botId, "botId");
            requireNonZero(ticketId, "ticketId");
            if (botGeneration < 1L || ticketRevision < 1L) {
                throw new IllegalArgumentException(
                        "shield hold binding generation/revision is invalid");
            }
        }

        private static RunBinding freeze(TechniqueRunView run,
                TechniqueChildTicket ticket) {
            TechniqueRunView requiredRun = Objects.requireNonNull(run, "run");
            TechniqueChildTicket requiredTicket = Objects.requireNonNull(ticket,
                    "ticket");
            if (!requiredRun.techniqueRunId().equals(
                    requiredTicket.techniqueRunId())
                    || !requiredRun.botId().equals(requiredTicket.botId())
                    || requiredRun.botGeneration()
                            != requiredTicket.botGeneration()
                    || requiredRun.childTickets().size() != 1
                    || !requiredRun.childTickets().getFirst().equals(
                            requiredTicket)) {
                throw new IllegalArgumentException(
                        "shield hold ticket did not exactly belong to its run");
            }
            return new RunBinding(requiredRun.techniqueRunId(),
                    requiredRun.skillRunId(), requiredRun.botId(),
                    requiredRun.botGeneration(), requiredTicket.ticketId(),
                    requiredTicket.revision());
        }

        private boolean matchesView(TechniqueRunView view) {
            if (!techniqueRunId.equals(view.techniqueRunId())
                    || !skillRunId.equals(view.skillRunId())
                    || !botId.equals(view.botId())
                    || botGeneration != view.botGeneration()
                    || !view.techniqueId().equals(ShieldHoldTechnique.ID)
                    || !view.techniqueVersion().equals(
                            ShieldHoldTechnique.VERSION)
                    || view.childTickets().size() != 1) {
                return false;
            }
            TechniqueChildTicket ticket = view.childTickets().getFirst();
            return ticketId.equals(ticket.ticketId())
                    && techniqueRunId.equals(ticket.techniqueRunId())
                    && botId.equals(ticket.botId())
                    && botGeneration == ticket.botGeneration()
                    && ticketRevision == ticket.revision()
                    && ShieldHoldTechnique.OPERATION_KEY.equals(
                            ticket.operationKey())
                    && ShieldHoldTechnique.CHANNELS.equals(ticket.channels());
        }
    }

    /** Immutable Action child identity; it may be released before its parent. */
    private record ChildBinding(RunBinding run, TechniqueChildTicket ticket,
            TechniqueActionPermit permit, ActionEnvelope envelope) {
        private ChildBinding {
            run = Objects.requireNonNull(run, "run");
            ticket = Objects.requireNonNull(ticket, "ticket");
            permit = Objects.requireNonNull(permit, "permit");
            envelope = Objects.requireNonNull(envelope, "envelope");
        }

        private static ChildBinding freeze(RunBinding run,
                TechniqueChildTicket ticket, TechniqueActionPermit permit,
                ActionEnvelope envelope, ItemStackFingerprint shield) {
            RunBinding requiredRun = Objects.requireNonNull(run, "run");
            TechniqueChildTicket requiredTicket = Objects.requireNonNull(ticket,
                    "ticket");
            TechniqueActionPermit requiredPermit = Objects.requireNonNull(permit,
                    "permit");
            ActionEnvelope requiredEnvelope = Objects.requireNonNull(envelope,
                    "envelope");
            ItemStackFingerprint requiredShield = Objects.requireNonNull(shield,
                    "shield");
            if (!requiredRun.ticketId().equals(requiredTicket.ticketId())
                    || requiredRun.ticketRevision() != requiredTicket.revision()
                    || !requiredPermit.techniqueRunId().equals(
                            requiredRun.techniqueRunId())
                    || !requiredPermit.techniqueChildTicketId().equals(
                            requiredTicket.ticketId())
                    || requiredPermit.techniqueChildRevision()
                            != requiredTicket.revision()
                    || requiredPermit.kind() != ActionKind.USE_ITEM
                    || !ShieldHoldTechnique.CHANNELS.equals(
                            requiredPermit.channels())
                    || requiredPermit.priority() != ActionPriority.OWNER_TASK
                    || !requiredPermit.envelope().equals(requiredEnvelope)
                    || !isExactShieldEnvelope(requiredEnvelope,
                            requiredShield)) {
                throw new IllegalArgumentException(
                        "shield hold Action binding was not exact");
            }
            return new ChildBinding(requiredRun, requiredTicket,
                    requiredPermit, requiredEnvelope);
        }

        private boolean matchesTicket(TechniqueChildTicket candidate) {
            return ticket.equals(candidate)
                    && run.ticketId().equals(candidate.ticketId())
                    && run.ticketRevision() == candidate.revision();
        }
    }

    private static boolean isExactShieldEnvelope(ActionEnvelope envelope,
            ItemStackFingerprint shield) {
        if (!(envelope.action() instanceof WorldInteractionAction interaction)
                || !(interaction.spec() instanceof WorldInteractionActionSpec.UseItem
                        use)) {
            return false;
        }
        return use.hand() == WorldInteractionActionSpec.Hand.OFF_HAND
                && use.expectedHeldItem().equals(shield)
                && use.mode() == WorldInteractionActionSpec.ItemUseMode
                        .RELEASE_AFTER_HOLD
                && use.holdTicks() == HOLD_TICKS
                && use.strictPreconditions().isEmpty()
                && ShieldHoldTechnique.CHANNELS.equals(interaction.channels())
                && envelope.maxTicks() == ACTION_MAXIMUM_TICKS;
    }

    private record ActionOutcomeMapping(TechniqueChildState state,
            TechniqueFailureCode failureCode) {
    }
}
