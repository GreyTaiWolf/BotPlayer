package io.github.greytaiwolf.botplayer.technique.runtime;

import io.github.greytaiwolf.botplayer.action.ActionChannel;
import io.github.greytaiwolf.botplayer.technique.core.TechniqueDescriptor;
import io.github.greytaiwolf.botplayer.technique.core.TechniqueFailureCode;
import io.github.greytaiwolf.botplayer.technique.core.TechniqueId;
import io.github.greytaiwolf.botplayer.technique.core.TechniqueOutcome;
import io.github.greytaiwolf.botplayer.technique.core.TechniqueParameters;
import io.github.greytaiwolf.botplayer.technique.core.TechniqueVersion;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Owner-thread, bounded runtime for short-lived body techniques.
 *
 * <p>The runtime does not know Minecraft or submit world operations itself.
 * It issues opaque child tickets through {@link TechniqueChildDispatcher},
 * accepts only exact terminal acknowledgements, and refuses normal terminal
 * completion while any child still needs cleanup. A missing acknowledgement
 * reaches a bounded unsafe terminal and quarantines that exact body generation.
 * The owner must call {@link #finishTick(UUID, long, long)} after it has drained
 * all externally delivered child signals for a server tick; this explicit
 * boundary is what makes the final representable tick deterministic without
 * rejecting a second valid same-tick child acknowledgement.
 */
public final class TechniqueRuntime {
    private static final UUID ZERO_UUID = new UUID(0L, 0L);
    /**
     * Mirrors the Action runtime's independent cleanup window.  A technique
     * has already reached its business terminal at this point; this bounded
     * interval exists solely to receive proof that its delegated children
     * reached a safe terminal state.
     */
    public static final int MAX_CLEANUP_TICKS = 256;
    /** Never discard an unresolved generation merely to reclaim memory. */
    public static final int MAX_QUARANTINED_GENERATIONS = 4_096;

    private final TechniqueRuntimeLimits limits;
    private final TechniqueChildDispatcher childDispatcher;
    private final Map<TechniqueKey, PlayerTechnique> techniques =
            new HashMap<>();
    private final Map<UUID, ActiveRun> activeByBot = new HashMap<>();
    private final Map<UUID, ActiveRun> activeByRun = new HashMap<>();
    private final LinkedHashMap<UUID, TechniqueOutcome> latestByBot =
            new LinkedHashMap<>();
    /*
     * A timed-out child is deliberately retained as ACTIVE in this quarantine
     * record.  The parent run may be reported as failed, but the runtime never
     * manufactures a child receipt or reuses that exact generation.
     */
    private final Map<GenerationKey, List<TechniqueChildTicket>>
            unresolvedChildrenByGeneration = new LinkedHashMap<>();
    private final Thread ownerThread;
    private boolean unsafeGenerationCapacityExhausted;
    /* Closing ingress must not strand already-running child cleanup. */
    private boolean closed;

    public TechniqueRuntime(TechniqueRuntimeLimits limits,
            TechniqueChildDispatcher childDispatcher) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.childDispatcher = Objects.requireNonNull(childDispatcher,
                "childDispatcher");
        ownerThread = Thread.currentThread();
    }

    public TechniqueRuntime(TechniqueChildDispatcher childDispatcher) {
        this(TechniqueRuntimeLimits.defaults(), childDispatcher);
    }

    public TechniqueRegistrationStatus register(PlayerTechnique technique) {
        requireOwnerThread();
        if (closed) {
            return TechniqueRegistrationStatus.RUNTIME_CLOSED;
        }
        PlayerTechnique required = Objects.requireNonNull(technique,
                "technique");
        TechniqueDescriptor descriptor = Objects.requireNonNull(
                required.descriptor(), "technique descriptor");
        TechniqueKey key = new TechniqueKey(descriptor.id(),
                descriptor.version());
        PlayerTechnique existing = techniques.get(key);
        if (existing != null) {
            return existing.descriptor().equals(descriptor)
                    ? TechniqueRegistrationStatus.ALREADY_REGISTERED
                    : TechniqueRegistrationStatus.VERSION_CONFLICT;
        }
        if (techniques.size() >= limits.maximumRegisteredTechniques()) {
            return TechniqueRegistrationStatus.CAPACITY_EXCEEDED;
        }
        techniques.put(key, required);
        return TechniqueRegistrationStatus.REGISTERED;
    }

    public TechniqueSubmission start(TechniqueStartRequest request) {
        requireOwnerThread();
        TechniqueStartRequest required = Objects.requireNonNull(request,
                "request");
        if (closed) {
            return TechniqueSubmission.rejected(
                    TechniqueSubmission.Status.RUNTIME_CLOSED,
                    "Technique runtime is closed");
        }
        PlayerTechnique technique = techniques.get(new TechniqueKey(
                required.techniqueId(), required.techniqueVersion()));
        if (technique == null) {
            return TechniqueSubmission.rejected(
                    TechniqueSubmission.Status.UNKNOWN_TECHNIQUE,
                    "Technique is not registered");
        }
        if (unsafeGenerationCapacityExhausted || unresolvedChildrenByGeneration.containsKey(
                new GenerationKey(required.botId(), required.botGeneration()))) {
            return TechniqueSubmission.rejected(
                    TechniqueSubmission.Status.GENERATION_QUARANTINED,
                    "Bot generation is quarantined after unsafe child cleanup");
        }
        if (activeByBot.containsKey(required.botId())) {
            return TechniqueSubmission.rejected(
                    TechniqueSubmission.Status.BOT_BUSY,
                    "Bot already owns an active technique");
        }
        if (activeByRun.size() >= limits.maximumActiveRuns()) {
            return TechniqueSubmission.rejected(
                    TechniqueSubmission.Status.RUNTIME_CAPACITY,
                    "Technique runtime is at active capacity");
        }
        long deadline;
        try {
            deadline = Math.addExact(required.currentTick(), technique
                    .descriptor().maximumTicks());
        } catch (ArithmeticException overflow) {
            return TechniqueSubmission.rejected(
                    TechniqueSubmission.Status.INVALID_REQUEST,
                    "Technique deadline is outside the server tick range");
        }
        ActiveRun run = new ActiveRun(UUID.randomUUID(), required, technique,
                deadline);
        activeByBot.put(run.botId, run);
        activeByRun.put(run.techniqueRunId, run);
        transition(run, TechniqueState.PREPARING, "preparing", required
                .currentTick());
        invokeStart(run, required.currentTick());
        return TechniqueSubmission.accepted(run.techniqueRunId,
                "Technique accepted by the bounded runtime");
    }

    /** Advances at most the caller-selected bot once for this server tick. */
    public void tick(UUID botId, long botGeneration, long currentTick) {
        requireOwnerThread();
        requireNonZero(botId, "botId");
        if (botGeneration < 1L || currentTick < 0L) {
            throw new IllegalArgumentException(
                    "technique tick generation/tick is invalid");
        }
        ActiveRun run = activeByBot.get(botId);
        if (run == null || run.state.isTerminal()) {
            return;
        }
        if (currentTick <= run.finishedThroughTick) {
            return;
        }
        if (currentTick < run.updatedTick) {
            throw new IllegalArgumentException(
                    "technique tick must not move backwards");
        }
        if (cleanupDeadlineElapsed(run, currentTick)
                && !run.cleanupDeadlineSaturated) {
            failCleanupUnsafe(run, currentTick);
            return;
        }
        if (run.botGeneration != botGeneration) {
            beginEnding(run, TechniqueState.FAILED,
                    TechniqueFailureCode.GENERATION_CHANGED,
                    "Bot generation changed while technique was active",
                    TechniqueCancelReason.GENERATION_CHANGED, currentTick);
            return;
        }
        if (currentTick >= run.deadlineTick) {
            beginEnding(run, TechniqueState.FAILED,
                    TechniqueFailureCode.TIMEOUT,
                    "Technique reached its bounded deadline",
                    TechniqueCancelReason.REQUESTED, currentTick);
            return;
        }
        switch (run.state) {
            case RUNNING -> invokeTick(run, currentTick);
            case VERIFYING -> invokeVerify(run, currentTick);
            case RECOVERING -> invokeStart(run, currentTick);
            case CANCELLING, PREEMPTING -> finishIfChildrenSettled(run,
                    currentTick);
            case CREATED, PREPARING, WAITING_CHILDREN, SUCCEEDED, FAILED,
                    CANCELLED, PREEMPTED -> {
                // Waiting is driven only by an exact child acknowledgement.
            }
        }
    }

    /**
     * Closes one owner-thread server tick after every external child signal
     * for this bot has been offered. In particular, this is the only ordinary
     * settlement point for a saturated cleanup deadline at
     * {@link Long#MAX_VALUE}: valid sibling acknowledgements may arrive first,
     * then any still-active child is quarantined fail-closed.
     *
     * <p>The call is idempotent for an already-finished tick. Signals offered
     * after it are rejected as stale, so a late callback cannot reopen an
     * already-settled boundary.
     */
    public void finishTick(UUID botId, long botGeneration, long currentTick) {
        requireOwnerThread();
        requireNonZero(botId, "botId");
        if (botGeneration < 1L || currentTick < 0L) {
            throw new IllegalArgumentException(
                    "technique finish tick generation/tick is invalid");
        }
        ActiveRun run = activeByBot.get(botId);
        if (run == null || run.state.isTerminal()) {
            return;
        }
        if (currentTick < run.updatedTick) {
            throw new IllegalArgumentException(
                    "technique finish tick must not move backwards");
        }
        if (run.botGeneration != botGeneration) {
            beginEnding(run, TechniqueState.FAILED,
                    TechniqueFailureCode.GENERATION_CHANGED,
                    "Technique generation changed before its tick boundary",
                    TechniqueCancelReason.GENERATION_CHANGED, currentTick);
            if (isActive(run) && activeChildCount(run) != 0) {
                failCleanupUnsafe(run, currentTick);
            }
            return;
        }
        if (currentTick <= run.finishedThroughTick) {
            return;
        }
        if (cleanupDeadlineElapsed(run, currentTick)) {
            failCleanupUnsafe(run, currentTick);
            return;
        }
        run.finishedThroughTick = currentTick;
    }

    public TechniqueSignalStatus offerSignal(TechniqueSignal signal,
            long currentTick) {
        requireOwnerThread();
        TechniqueSignal required = Objects.requireNonNull(signal, "signal");
        if (currentTick < 0L) {
            throw new IllegalArgumentException("currentTick must not be negative");
        }
        ActiveRun run = activeByRun.get(required.techniqueRunId());
        if (run == null) {
            return TechniqueSignalStatus.RUN_NOT_FOUND;
        }
        if (!run.botId.equals(required.botId())
                || run.botGeneration != required.botGeneration()) {
            return TechniqueSignalStatus.IDENTITY_MISMATCH;
        }
        /*
         * Reject this before mutating the child. A delayed acknowledgement
         * must not terminally settle a ticket and only then trip transition's
         * monotonicity guard, which would strand the parent in WAITING.
         */
        if (currentTick <= run.finishedThroughTick
                || currentTick < run.updatedTick) {
            return TechniqueSignalStatus.STALE_TICK;
        }
        if (cleanupDeadlineElapsed(run, currentTick)
                && !run.cleanupDeadlineSaturated) {
            failCleanupUnsafe(run, currentTick);
            return TechniqueSignalStatus.RUN_NOT_FOUND;
        }
        TechniqueChildTicket ticket = run.children.get(required.ticketId());
        if (ticket == null) {
            return TechniqueSignalStatus.TICKET_NOT_FOUND;
        }
        if (ticket.state().isTerminal()) {
            return TechniqueSignalStatus.TICKET_ALREADY_TERMINAL;
        }
        if (ticket.revision() != required.ticketRevision()) {
            return TechniqueSignalStatus.IDENTITY_MISMATCH;
        }
        run.children.put(ticket.ticketId(), ticket.terminal(required.state()));
        if (required.state() != TechniqueChildState.SUCCEEDED
                && (!isEnding(run)
                        || authoritativeChildFailure(
                                required.failureCode()))) {
            /*
             * A terminal child can discover a more authoritative endpoint
             * while its parent is already cleaning. In particular an unsafe
             * cleanup proof or a generation change must not be hidden behind
             * an earlier L0 preemption. beginEnding's priority relation makes
             * lower-priority receipts harmless while allowing those endpoints
             * to upgrade the parent and every still-live child port.
             */
            beginEnding(run, TechniqueState.FAILED,
                    required.failureCode(),
                    "Technique child completed without success",
                    childFailureCancellationReason(required.failureCode()),
                    currentTick);
            return TechniqueSignalStatus.ACCEPTED;
        }
        if (run.state == TechniqueState.WAITING_CHILDREN) {
            resumeAfterChildrenIfReady(run, currentTick);
        } else if (run.state == TechniqueState.CANCELLING
                || run.state == TechniqueState.PREEMPTING) {
            finishIfChildrenSettled(run, currentTick);
        }
        return TechniqueSignalStatus.ACCEPTED;
    }

    public TechniqueCancellationStatus cancel(UUID techniqueRunId,
            UUID botId, long botGeneration, long currentTick) {
        return requestEnding(techniqueRunId, botId, botGeneration,
                TechniqueState.CANCELLED, TechniqueFailureCode.CANCELLED,
                "Technique cancellation requested", TechniqueCancelReason.REQUESTED,
                currentTick);
    }

    public TechniqueCancellationStatus preemptForSafety(UUID techniqueRunId,
            UUID botId, long botGeneration, long currentTick) {
        return requestEnding(techniqueRunId, botId, botGeneration,
                TechniqueState.PREEMPTED,
                TechniqueFailureCode.SAFETY_PREEMPTED,
                "Technique preempted by L0 safety", TechniqueCancelReason.SAFETY_PREEMPTION,
                currentTick);
    }

    public void closeGeneration(UUID botId, long botGeneration,
            long currentTick) {
        requireOwnerThread();
        requireNonZero(botId, "botId");
        if (botGeneration < 1L || currentTick < 0L) {
            throw new IllegalArgumentException(
                    "technique generation close is invalid");
        }
        ActiveRun run = activeByBot.get(botId);
        if (run != null && run.botGeneration == botGeneration) {
            if (currentTick < run.updatedTick) {
                throw new IllegalArgumentException(
                        "technique generation close must not move backwards");
            }
            if (cleanupDeadlineElapsed(run, currentTick)
                    && !run.cleanupDeadlineSaturated) {
                failCleanupUnsafe(run, currentTick);
                return;
            }
            if (currentTick <= run.finishedThroughTick) {
                beginEnding(run, TechniqueState.FAILED,
                        TechniqueFailureCode.GENERATION_CHANGED,
                        "Technique body generation closed after its tick boundary",
                        TechniqueCancelReason.GENERATION_CHANGED, currentTick);
                if (isActive(run) && activeChildCount(run) != 0) {
                    failCleanupUnsafe(run, currentTick);
                }
                return;
            }
            beginEnding(run, TechniqueState.FAILED,
                    TechniqueFailureCode.GENERATION_CHANGED,
                    "Technique body generation closed",
                    TechniqueCancelReason.GENERATION_CHANGED, currentTick);
        }
    }

    /**
     * Returns whether this exact body generation has no live technique and no
     * unresolved child-cleanup quarantine.  Lifecycle code must require this
     * alongside the Action runtime's generation fence before it can retain a
     * body or checkpoint as safe.
     */
    public boolean isGenerationSafe(UUID botId, long botGeneration) {
        requireOwnerThread();
        requireNonZero(botId, "botId");
        if (botGeneration < 1L) {
            throw new IllegalArgumentException(
                    "technique generation must be positive");
        }
        if (unsafeGenerationCapacityExhausted
                || unresolvedChildrenByGeneration.containsKey(
                        new GenerationKey(botId, botGeneration))) {
            return false;
        }
        ActiveRun run = activeByBot.get(botId);
        return run == null || run.botGeneration != botGeneration;
    }

    /**
     * Closes new-technique ingress and asks every live child port to stop with
     * the strongest lifecycle reason.  A normal child acknowledgement may
     * still be offered afterwards so shutdown can settle safely; if the last
     * owner tick has already been sealed, the generation is quarantined rather
     * than accepting an ambiguous same-tick callback.
     */
    public void shutdown(long currentTick) {
        requireOwnerThread();
        if (currentTick < 0L) {
            throw new IllegalArgumentException(
                    "technique shutdown tick must not be negative");
        }
        if (closed) {
            return;
        }
        closed = true;
        for (ActiveRun run : List.copyOf(activeByRun.values())) {
            if (currentTick < run.updatedTick) {
                throw new IllegalArgumentException(
                        "technique shutdown tick must not move backwards");
            }
            beginEnding(run, TechniqueState.FAILED,
                    TechniqueFailureCode.RUNTIME_CLOSED,
                    "Technique runtime closed", TechniqueCancelReason.SERVER_STOP,
                    currentTick);
            if (isActive(run)
                    && currentTick <= run.finishedThroughTick
                    && activeChildCount(run) != 0) {
                failCleanupUnsafe(run, currentTick);
            }
        }
    }

    public boolean isClosed() {
        requireOwnerThread();
        return closed;
    }

    public Optional<TechniqueRunView> inspect(UUID botId) {
        requireOwnerThread();
        requireNonZero(botId, "botId");
        ActiveRun run = activeByBot.get(botId);
        return run == null ? Optional.empty() : Optional.of(run.view());
    }

    public Optional<TechniqueRunView> inspectRun(UUID techniqueRunId) {
        requireOwnerThread();
        requireNonZero(techniqueRunId, "techniqueRunId");
        ActiveRun run = activeByRun.get(techniqueRunId);
        return run == null ? Optional.empty() : Optional.of(run.view());
    }

    public Optional<TechniqueOutcome> latestOutcome(UUID botId) {
        requireOwnerThread();
        requireNonZero(botId, "botId");
        return Optional.ofNullable(latestByBot.get(botId));
    }

    private TechniqueCancellationStatus requestEnding(UUID techniqueRunId,
            UUID botId, long botGeneration, TechniqueState terminal,
            TechniqueFailureCode failureCode, String summary,
            TechniqueCancelReason reason, long currentTick) {
        requireOwnerThread();
        requireNonZero(techniqueRunId, "techniqueRunId");
        requireNonZero(botId, "botId");
        if (botGeneration < 1L || currentTick < 0L) {
            throw new IllegalArgumentException(
                    "technique cancellation generation/tick is invalid");
        }
        ActiveRun run = activeByRun.get(techniqueRunId);
        if (run == null) {
            return TechniqueCancellationStatus.RUN_NOT_FOUND;
        }
        if (!run.botId.equals(botId) || run.botGeneration != botGeneration) {
            return TechniqueCancellationStatus.IDENTITY_MISMATCH;
        }
        if (currentTick <= run.finishedThroughTick
                || currentTick < run.updatedTick) {
            return TechniqueCancellationStatus.STALE_TICK;
        }
        if (cleanupDeadlineElapsed(run, currentTick)
                && !run.cleanupDeadlineSaturated) {
            failCleanupUnsafe(run, currentTick);
            return TechniqueCancellationStatus.ALREADY_TERMINAL;
        }
        if (run.state.isTerminal()) {
            return TechniqueCancellationStatus.ALREADY_TERMINAL;
        }
        beginEnding(run, terminal, failureCode, summary, reason, currentTick);
        return run.pendingTerminal == TechniqueState.PREEMPTED
                ? TechniqueCancellationStatus.PREEMPTING
                : TechniqueCancellationStatus.CANCELLING;
    }

    private void invokeStart(ActiveRun run, long currentTick) {
        try {
            applyDirective(run, run.technique.start(context(run, currentTick),
                    run.parameters), currentTick);
        } catch (RuntimeException exception) {
            beginEnding(run, TechniqueState.FAILED,
                    TechniqueFailureCode.INTERNAL_ERROR,
                    "Technique start callback failed", TechniqueCancelReason.REQUESTED,
                    currentTick);
        }
    }

    private void invokeTick(ActiveRun run, long currentTick) {
        try {
            applyDirective(run, run.technique.tick(context(run, currentTick),
                    run.view()), currentTick);
        } catch (RuntimeException exception) {
            beginEnding(run, TechniqueState.FAILED,
                    TechniqueFailureCode.INTERNAL_ERROR,
                    "Technique tick callback failed", TechniqueCancelReason.REQUESTED,
                    currentTick);
        }
    }

    private void invokeVerify(ActiveRun run, long currentTick) {
        try {
            applyDirective(run, run.technique.verify(context(run, currentTick),
                    run.view()), currentTick);
        } catch (RuntimeException exception) {
            beginEnding(run, TechniqueState.FAILED,
                    TechniqueFailureCode.INTERNAL_ERROR,
                    "Technique verify callback failed", TechniqueCancelReason.REQUESTED,
                    currentTick);
        }
    }

    private void applyDirective(ActiveRun run, TechniqueDirective directive,
            long currentTick) {
        if (run.state.isTerminal() || isEnding(run)) {
            return;
        }
        TechniqueDirective required = Objects.requireNonNull(directive,
                "technique directive");
        if (required instanceof TechniqueDirective.Continue next) {
            if (setPhase(run, next.phase(), currentTick)) {
                enterOrAdvance(run, TechniqueState.RUNNING, run.phase,
                        currentTick);
            }
        } else if (required instanceof TechniqueDirective.AwaitChildren next) {
            dispatchChildren(run, next, currentTick);
        } else if (required instanceof TechniqueDirective.Verify next) {
            if (setPhase(run, next.phase(), currentTick)) {
                enterOrAdvance(run, TechniqueState.VERIFYING, run.phase,
                        currentTick);
            }
        } else if (required instanceof TechniqueDirective.Recover next) {
            if (activeChildCount(run) != 0) {
                beginEnding(run, TechniqueState.FAILED,
                        TechniqueFailureCode.ACTION_CLEANUP_UNSAFE,
                        "Technique requested recovery with active children",
                        TechniqueCancelReason.REQUESTED, currentTick);
            } else if (run.recoveryAttempts >= run.descriptor
                    .maximumRecoveryAttempts()) {
                beginEnding(run, TechniqueState.FAILED,
                        TechniqueFailureCode.BUDGET_EXCEEDED,
                        "Technique recovery budget is exhausted",
                        TechniqueCancelReason.REQUESTED, currentTick);
            } else if (setPhase(run, next.phase(), currentTick)) {
                run.recoveryAttempts++;
                enterOrAdvance(run, TechniqueState.RECOVERING, run.phase,
                        currentTick);
            }
        } else if (required instanceof TechniqueDirective.Complete next) {
            beginEnding(run, TechniqueState.SUCCEEDED,
                    TechniqueFailureCode.NONE, next.summary(),
                    TechniqueCancelReason.REQUESTED, currentTick);
        } else if (required instanceof TechniqueDirective.Fail next) {
            beginEnding(run, TechniqueState.FAILED, next.failureCode(),
                    next.summary(), TechniqueCancelReason.REQUESTED,
                    currentTick);
        } else {
            throw new IllegalStateException("unknown technique directive");
        }
    }

    private void dispatchChildren(ActiveRun run,
            TechniqueDirective.AwaitChildren directive, long currentTick) {
        if (!setPhase(run, directive.phase(), currentTick)) {
            return;
        }
        List<TechniqueChildRequest> requests = directive.children();
        if (run.submittedChildren + requests.size() > run.descriptor
                .maximumSubmittedChildren()) {
            beginEnding(run, TechniqueState.FAILED,
                    TechniqueFailureCode.BUDGET_EXCEEDED,
                    "Technique child submission budget is exhausted",
                    TechniqueCancelReason.REQUESTED, currentTick);
            return;
        }
        if (activeChildCount(run) + requests.size() > run.descriptor
                .maximumActiveChildren()) {
            beginEnding(run, TechniqueState.FAILED,
                    TechniqueFailureCode.BUDGET_EXCEEDED,
                    "Technique active child budget is exhausted",
                    TechniqueCancelReason.REQUESTED, currentTick);
            return;
        }
        Set<ActionChannel> occupied = occupiedChannels(run);
        Set<String> operationKeys = new java.util.HashSet<>();
        for (TechniqueChildRequest request : requests) {
            if (!operationKeys.add(request.operationKey())
                    || !java.util.Collections.disjoint(occupied,
                            request.channels())) {
                beginEnding(run, TechniqueState.FAILED,
                        TechniqueFailureCode.CHANNEL_CONFLICT,
                        "Technique child channels conflict",
                        TechniqueCancelReason.REQUESTED, currentTick);
                return;
            }
            occupied.addAll(request.channels());
        }
        transition(run, TechniqueState.WAITING_CHILDREN, run.phase,
                currentTick);
        /*
         * submit() is an external port and may synchronously acknowledge a
         * ticket. Keep the full batch pending until every submission has been
         * registered, so one fast child cannot resume RUNNING between sibling
         * submissions.
         */
        run.pendingChildSubmissions = requests.size();
        for (TechniqueChildRequest request : requests) {
            TechniqueChildTicket ticket = new TechniqueChildTicket(
                    UUID.randomUUID(), run.techniqueRunId, run.botId,
                    run.botGeneration, run.revision, request.operationKey(),
                    request.channels(), currentTick, TechniqueChildState.ACTIVE);
            /* Register before the external call so a re-entrant ACK is exact. */
            run.children.put(ticket.ticketId(), ticket);
            TechniqueChildDispatcher.Submission submission;
            try {
                submission = Objects.requireNonNull(childDispatcher.submit(ticket),
                        "child submission");
            } catch (RuntimeException exception) {
                /* A throwing port has not returned an accepted child contract. */
                run.children.remove(ticket.ticketId());
                run.pendingChildSubmissions = 0;
                beginEnding(run, TechniqueState.FAILED,
                        TechniqueFailureCode.INTERNAL_ERROR,
                        "Technique child dispatcher threw while submitting",
                        TechniqueCancelReason.REQUESTED, currentTick);
                return;
            }
            if (submission.status() != TechniqueChildDispatcher.Status.ACCEPTED) {
                run.children.remove(ticket.ticketId());
                run.pendingChildSubmissions = 0;
                TechniqueFailureCode code = submission.status()
                        == TechniqueChildDispatcher.Status.CHANNEL_BUSY
                        ? TechniqueFailureCode.CHANNEL_CONFLICT
                        : TechniqueFailureCode.BUDGET_EXCEEDED;
                beginEnding(run, TechniqueState.FAILED, code,
                        "Technique child dispatcher rejected submission",
                        TechniqueCancelReason.REQUESTED, currentTick);
                return;
            }
            run.submittedChildren++;
            run.pendingChildSubmissions--;
            /* A synchronous failure/preemption must stop the remaining batch. */
            if (!isActive(run) || isEnding(run)) {
                return;
            }
        }
        resumeAfterChildrenIfReady(run, currentTick);
    }

    /** Only resume after the whole external submission batch is registered. */
    private void resumeAfterChildrenIfReady(ActiveRun run, long currentTick) {
        if (run.state == TechniqueState.WAITING_CHILDREN
                && run.pendingChildSubmissions == 0
                && activeChildCount(run) == 0) {
            transition(run, TechniqueState.RUNNING, run.phase, currentTick);
        }
    }

    private boolean isActive(ActiveRun run) {
        return activeByRun.get(run.techniqueRunId) == run;
    }

    private boolean setPhase(ActiveRun run, String next, long currentTick) {
        String required = TechniqueText.phase(next);
        if (required.equals(run.phase)) {
            return true;
        }
        if (run.phaseCount >= run.descriptor.maximumPhases()) {
            beginEnding(run, TechniqueState.FAILED,
                    TechniqueFailureCode.BUDGET_EXCEEDED,
                    "Technique phase budget is exhausted",
                    TechniqueCancelReason.REQUESTED, currentTick);
            return false;
        }
        run.phase = required;
        run.phaseCount++;
        return true;
    }

    /**
     * A technique may legitimately keep returning Continue, Verify or Recover
     * on consecutive ticks. Preserve the FSM's no-self-transition rule while
     * still advancing the monotonic observation tick, so an old cancellation or
     * child acknowledgement cannot be accepted after a later tick was handled.
     */
    private void enterOrAdvance(ActiveRun run, TechniqueState next,
            String phase, long currentTick) {
        if (run.state == next) {
            if (currentTick < run.updatedTick) {
                throw new IllegalArgumentException(
                        "technique advance tick must not move backwards");
            }
            run.phase = TechniqueText.phase(phase);
            run.updatedTick = currentTick;
            return;
        }
        transition(run, next, phase, currentTick);
    }

    private void beginEnding(ActiveRun run, TechniqueState terminal,
            TechniqueFailureCode failureCode, String summary,
            TechniqueCancelReason reason, long currentTick) {
        if (run.state.isTerminal()) {
            return;
        }
        requireTerminal(terminal, failureCode);
        boolean pendingEndpointChanged = shouldReplacePendingTerminal(
                run.pendingTerminal, run.pendingFailureCode, terminal,
                failureCode);
        if (pendingEndpointChanged) {
            run.pendingTerminal = terminal;
            run.pendingFailureCode = failureCode;
            run.pendingSummary = TechniqueText.summary(summary);
        }
        /*
         * An endpoint upgrade can leave the visible cleanup FSM unchanged
         * (for example PREEMPTING stays PREEMPTING while GENERATION_CHANGED
         * becomes the pending terminal). It is still a newer authoritative
         * observation, so stale child receipts from before that boundary must
         * not be accepted merely because no transition incremented revision.
         * Equal-tick reentrant acknowledgements remain legal.
         */
        if (pendingEndpointChanged && currentTick > run.updatedTick) {
            run.updatedTick = currentTick;
        }
        if (activeChildCount(run) == 0) {
            moveToImmediateTerminalState(run, currentTick);
            finishTerminal(run, currentTick);
            return;
        }
        if (run.cleanupDeadlineTick < 0L) {
            CleanupDeadline cleanupDeadline = cleanupDeadline(currentTick);
            run.cleanupDeadlineTick = cleanupDeadline.tick();
            run.cleanupDeadlineSaturated = cleanupDeadline.saturated();
        }
        moveToCleanupState(run, currentTick);
        if (!run.cancellationNotified) {
            run.cancellationNotified = true;
            if (reason == TechniqueCancelReason.SAFETY_PREEMPTION) {
                run.safetyPreemptionNotified = true;
            }
            try {
                run.technique.cancelled(context(run, currentTick), run.view(),
                        reason);
            } catch (RuntimeException exception) {
                run.pendingTerminal = TechniqueState.FAILED;
                run.pendingFailureCode = TechniqueFailureCode.ACTION_CLEANUP_UNSAFE;
                run.pendingSummary = "Technique cancellation callback failed";
            }
        } else if (reason == TechniqueCancelReason.SAFETY_PREEMPTION
                && run.pendingTerminal == TechniqueState.PREEMPTED
                && !run.safetyPreemptionNotified) {
            run.safetyPreemptionNotified = true;
            try {
                run.technique.safetyPreempted(context(run, currentTick),
                        run.view());
            } catch (RuntimeException exception) {
                run.pendingTerminal = TechniqueState.FAILED;
                run.pendingFailureCode = TechniqueFailureCode.ACTION_CLEANUP_UNSAFE;
                run.pendingSummary = "Technique safety-preemption callback failed";
            }
        }
        /*
         * A voluntary cancellation can be superseded by an L0 safety preemption while a child is
         * still active.  The technique callback is deliberately one-shot (it may release local
         * state), but the external child port must receive the stronger cancellation reason so an
         * Action/Navigation implementation can apply its own safety priority.  Repeating an equal
         * or weaker reason would only create duplicate cleanup work.
        */
        requestChildCancellation(run, reason);
    }

    /**
     * Dispatch child cleanup in priority rounds. A port is allowed to re-enter
     * the owner-thread runtime (for example, a first ordinary cancellation can
     * immediately reveal an L0 incident). In that case the old round stops
     * before it can send a weaker reason to a sibling; the outer loop then
     * restarts over the current active children with the stronger reason.
     */
    private void requestChildCancellation(ActiveRun run,
            TechniqueCancelReason reason) {
        if (shouldDispatchChildCancellation(
                run.requestedChildCancellationReason, reason)) {
            run.requestedChildCancellationReason = reason;
        }
        if (run.childCancellationDispatching || !isActive(run)
                || run.state.isTerminal()) {
            return;
        }
        run.childCancellationDispatching = true;
        try {
            while (isActive(run) && !run.state.isTerminal()
                    && shouldDispatchChildCancellation(
                            run.dispatchedChildCancellationReason,
                            run.requestedChildCancellationReason)) {
                TechniqueCancelReason roundReason =
                        run.requestedChildCancellationReason;
                run.dispatchedChildCancellationReason = roundReason;
                for (TechniqueChildTicket snapshot : activeTickets(run)) {
                    if (!isActive(run) || run.state.isTerminal()
                            || hasStrongerRequestedChildCancellation(run,
                                    roundReason)) {
                        break;
                    }
                    /*
                     * A previous child port can synchronously acknowledge a
                     * sibling. Re-read the live ticket map instead of sending
                     * cancellation to the stale snapshot; ports may correctly
                     * reject a duplicate cancellation of a terminal child.
                     */
                    TechniqueChildTicket ticket = run.children.get(
                            snapshot.ticketId());
                    if (ticket == null || ticket.state().isTerminal()) {
                        continue;
                    }
                    try {
                        childDispatcher.cancel(ticket, roundReason);
                    } catch (RuntimeException exception) {
                        run.pendingTerminal = TechniqueState.FAILED;
                        run.pendingFailureCode =
                                TechniqueFailureCode.ACTION_CLEANUP_UNSAFE;
                        run.pendingSummary =
                                "Technique child cleanup dispatch failed";
                    }
                    if (hasStrongerRequestedChildCancellation(run,
                            roundReason)) {
                        break;
                    }
                }
            }
        } finally {
            run.childCancellationDispatching = false;
        }
    }

    private static boolean hasStrongerRequestedChildCancellation(
            ActiveRun run, TechniqueCancelReason dispatched) {
        return cancellationReasonPriority(
                run.requestedChildCancellationReason)
                > cancellationReasonPriority(dispatched);
    }

    private static TechniqueCancelReason childFailureCancellationReason(
            TechniqueFailureCode failureCode) {
        return failureCode == TechniqueFailureCode.GENERATION_CHANGED
                ? TechniqueCancelReason.GENERATION_CHANGED
                : TechniqueCancelReason.REQUESTED;
    }

    /**
     * Ordinary cancellation/preemption acknowledgements settle a parent that
     * is already ending; they are not a reason to rewrite that endpoint. Only
     * an unsafe cleanup proof or a body-generation change is more
     * authoritative than an existing voluntary/L0 ending decision.
     */
    private static boolean authoritativeChildFailure(
            TechniqueFailureCode failureCode) {
        return failureCode == TechniqueFailureCode.ACTION_CLEANUP_UNSAFE
                || failureCode == TechniqueFailureCode.GENERATION_CHANGED;
    }

    /** CANCELLED and PREEMPTED need an intermediate FSM state even with no child. */
    private void moveToImmediateTerminalState(ActiveRun run, long currentTick) {
        if (run.pendingTerminal == TechniqueState.CANCELLED
                && run.state != TechniqueState.CANCELLING) {
            transition(run, TechniqueState.CANCELLING, run.phase, currentTick);
        } else if (run.pendingTerminal == TechniqueState.PREEMPTED
                && run.state != TechniqueState.PREEMPTING) {
            transition(run, TechniqueState.PREEMPTING, run.phase, currentTick);
        }
    }

    /**
     * Children need a terminal cleanup state before they can acknowledge.
     * PREEMPTING is a strict upgrade from CANCELLING; a subsequent FAILED
     * endpoint deliberately remains on either existing branch because both
     * state-machine transitions to FAILED are legal.
     */
    private void moveToCleanupState(ActiveRun run, long currentTick) {
        TechniqueState ending = run.pendingTerminal == TechniqueState.PREEMPTED
                ? TechniqueState.PREEMPTING : TechniqueState.CANCELLING;
        if (run.state == TechniqueState.CANCELLING
                && ending == TechniqueState.PREEMPTING) {
            transition(run, TechniqueState.PREEMPTING, run.phase, currentTick);
        } else if (run.state != TechniqueState.CANCELLING
                && run.state != TechniqueState.PREEMPTING) {
            transition(run, ending, run.phase, currentTick);
        }
    }

    private void finishIfChildrenSettled(ActiveRun run, long currentTick) {
        if (activeChildCount(run) == 0 && run.pendingTerminal != null) {
            finishTerminal(run, currentTick);
        }
    }

    /**
     * Records an unsafe parent terminal without manufacturing terminal child
     * receipts.  The exact generation remains quarantined, so no same-body
     * technique can overlap the unresolved delegated operation.
     */
    private void failCleanupUnsafe(ActiveRun run, long currentTick) {
        if (!isActive(run) || run.state.isTerminal()) {
            return;
        }
        if (activeChildCount(run) == 0) {
            finishIfChildrenSettled(run, currentTick);
            return;
        }
        quarantineUnresolvedChildren(run);
        run.pendingTerminal = TechniqueState.FAILED;
        run.pendingFailureCode = TechniqueFailureCode.ACTION_CLEANUP_UNSAFE;
        run.pendingSummary = "Technique child cleanup deadline elapsed without proof";
        transition(run, TechniqueState.FAILED, run.phase, currentTick);
        recordTerminalOutcome(run, TechniqueState.FAILED,
                TechniqueFailureCode.ACTION_CLEANUP_UNSAFE,
                run.pendingSummary, currentTick);
    }

    private void finishTerminal(ActiveRun run, long currentTick) {
        if (activeChildCount(run) != 0) {
            throw new IllegalStateException(
                    "technique cannot finish while child cleanup is outstanding");
        }
        TechniqueState terminal = Objects.requireNonNull(run.pendingTerminal,
                "pending terminal");
        TechniqueFailureCode failure = Objects.requireNonNull(
                run.pendingFailureCode, "pending failure");
        transition(run, terminal, run.phase, currentTick);
        recordTerminalOutcome(run, terminal, failure, run.pendingSummary,
                currentTick);
    }

    private void recordTerminalOutcome(ActiveRun run, TechniqueState terminal,
            TechniqueFailureCode failure, String summary, long currentTick) {
        TechniqueOutcome outcome = new TechniqueOutcome(run.techniqueRunId,
                run.skillRunId, run.botId, run.botGeneration,
                run.descriptor.id(), run.descriptor.version(), terminal, failure,
                run.startedTick, currentTick, run.revision, summary);
        activeByBot.remove(run.botId, run);
        activeByRun.remove(run.techniqueRunId, run);
        latestByBot.remove(run.botId);
        latestByBot.put(run.botId, outcome);
        while (latestByBot.size() > limits.maximumRememberedOutcomes()) {
            UUID oldest = latestByBot.keySet().iterator().next();
            latestByBot.remove(oldest);
        }
    }

    private void quarantineUnresolvedChildren(ActiveRun run) {
        GenerationKey key = new GenerationKey(run.botId, run.botGeneration);
        if (unsafeGenerationCapacityExhausted
                || unresolvedChildrenByGeneration.containsKey(key)) {
            return;
        }
        if (unresolvedChildrenByGeneration.size()
                >= MAX_QUARANTINED_GENERATIONS) {
            unsafeGenerationCapacityExhausted = true;
            return;
        }
        List<TechniqueChildTicket> outstanding = List.copyOf(activeTickets(run));
        if (outstanding.isEmpty()) {
            throw new IllegalStateException(
                    "unsafe cleanup quarantine requires outstanding children");
        }
        unresolvedChildrenByGeneration.put(key, outstanding);
    }

    private static boolean cleanupDeadlineElapsed(ActiveRun run,
            long currentTick) {
        return run.cleanupDeadlineTick >= 0L
                && (run.cleanupDeadlineSaturated
                        ? currentTick >= run.cleanupDeadlineTick
                        : currentTick > run.cleanupDeadlineTick)
                && activeChildCount(run) != 0;
    }

    private static CleanupDeadline cleanupDeadline(long currentTick) {
        if (currentTick >= Long.MAX_VALUE - MAX_CLEANUP_TICKS) {
            return new CleanupDeadline(Long.MAX_VALUE, true);
        }
        return new CleanupDeadline(currentTick + MAX_CLEANUP_TICKS, false);
    }

    private static boolean shouldReplacePendingTerminal(
            TechniqueState existing, TechniqueFailureCode existingFailure,
            TechniqueState candidate, TechniqueFailureCode candidateFailure) {
        return existing == null || terminalPriority(candidate,
                candidateFailure) > terminalPriority(existing,
                        Objects.requireNonNull(existingFailure,
                                "existing pending failure"));
    }

    private static boolean shouldDispatchChildCancellation(
            TechniqueCancelReason dispatched, TechniqueCancelReason candidate) {
        return dispatched == null || cancellationReasonPriority(candidate)
                > cancellationReasonPriority(dispatched);
    }

    /** Higher-priority body-safety transitions must reach every live child port. */
    private static int cancellationReasonPriority(TechniqueCancelReason reason) {
        return switch (Objects.requireNonNull(reason, "reason")) {
            case REQUESTED -> 1;
            case SAFETY_PREEMPTION -> 2;
            case GENERATION_CHANGED -> 3;
            case SERVER_STOP -> 4;
        };
    }

    /**
     * An unsafe cleanup proof failure is irrevocable. A lifecycle generation
     * close is next: an old body must never be reported as merely safety
     * preempted. Otherwise L0 preemption outranks an ordinary
     * failure/cancellation so a safety handoff remains the observable endpoint
     * when it races a business-level timeout or child failure during cleanup.
     */
    private static int terminalPriority(TechniqueState terminal,
            TechniqueFailureCode failureCode) {
        Objects.requireNonNull(failureCode, "failureCode");
        return switch (terminal) {
            case FAILED -> failureCode == TechniqueFailureCode.ACTION_CLEANUP_UNSAFE
                    ? 6
                    : failureCode == TechniqueFailureCode.GENERATION_CHANGED
                            ? 5 : 3;
            case PREEMPTED -> 4;
            case CANCELLED -> 2;
            case SUCCEEDED -> 1;
            case CREATED, PREPARING, RUNNING, WAITING_CHILDREN, VERIFYING,
                    RECOVERING, CANCELLING, PREEMPTING -> throw new IllegalArgumentException(
                            "terminal priority requires a terminal state");
        };
    }

    private void transition(ActiveRun run, TechniqueState next, String phase,
            long currentTick) {
        if (currentTick < run.updatedTick) {
            throw new IllegalArgumentException(
                    "technique transition tick must not move backwards");
        }
        run.state.requireTransitionTo(next);
        run.state = next;
        run.phase = TechniqueText.phase(phase);
        run.revision = Math.incrementExact(run.revision);
        run.updatedTick = currentTick;
    }

    private static boolean isEnding(ActiveRun run) {
        return run.state == TechniqueState.CANCELLING
                || run.state == TechniqueState.PREEMPTING;
    }

    private static int activeChildCount(ActiveRun run) {
        int active = 0;
        for (TechniqueChildTicket ticket : run.children.values()) {
            if (!ticket.state().isTerminal()) {
                active++;
            }
        }
        return active;
    }

    private static List<TechniqueChildTicket> activeTickets(ActiveRun run) {
        return run.children.values().stream()
                .filter(ticket -> !ticket.state().isTerminal())
                .sorted(Comparator.comparing(TechniqueChildTicket::ticketId))
                .toList();
    }

    private static Set<ActionChannel> occupiedChannels(ActiveRun run) {
        EnumSet<ActionChannel> occupied = EnumSet.noneOf(ActionChannel.class);
        for (TechniqueChildTicket ticket : run.children.values()) {
            if (!ticket.state().isTerminal()) {
                occupied.addAll(ticket.channels());
            }
        }
        return occupied;
    }

    private static void requireTerminal(TechniqueState state,
            TechniqueFailureCode failureCode) {
        Objects.requireNonNull(state, "terminal state");
        Objects.requireNonNull(failureCode, "failureCode");
        if (state == TechniqueState.SUCCEEDED
                && failureCode == TechniqueFailureCode.NONE) {
            return;
        }
        if (state == TechniqueState.FAILED
                && failureCode != TechniqueFailureCode.NONE) {
            return;
        }
        if (state == TechniqueState.CANCELLED
                && failureCode == TechniqueFailureCode.CANCELLED) {
            return;
        }
        if (state == TechniqueState.PREEMPTED
                && failureCode == TechniqueFailureCode.SAFETY_PREEMPTED) {
            return;
        }
        throw new IllegalArgumentException(
                "incoherent technique terminal state/failure code");
    }

    private TechniqueContext context(ActiveRun run, long currentTick) {
        return new TechniqueContext(run.techniqueRunId, run.skillRunId,
                run.botId, run.botGeneration, run.descriptor.id(),
                run.descriptor.version(), currentTick, run.revision);
    }

    private void requireOwnerThread() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException(
                    "technique runtime requires its owner thread");
        }
    }

    private static void requireNonZero(UUID value, String name) {
        if (ZERO_UUID.equals(Objects.requireNonNull(value, name))) {
            throw new IllegalArgumentException(name + " must not be zero UUID");
        }
    }

    private record CleanupDeadline(long tick, boolean saturated) {
        private CleanupDeadline {
            if (tick < 0L) {
                throw new IllegalArgumentException(
                        "cleanup deadline must not be negative");
            }
        }
    }

    private record TechniqueKey(TechniqueId id, TechniqueVersion version) {
        private TechniqueKey {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(version, "version");
        }
    }

    private record GenerationKey(UUID botId, long botGeneration) {
        private GenerationKey {
            requireNonZero(botId, "botId");
            if (botGeneration < 1L) {
                throw new IllegalArgumentException(
                        "botGeneration must be positive");
            }
        }
    }

    private static final class ActiveRun {
        private final UUID techniqueRunId;
        private final UUID skillRunId;
        private final UUID botId;
        private final long botGeneration;
        private final PlayerTechnique technique;
        private final TechniqueDescriptor descriptor;
        private final TechniqueParameters parameters;
        private final long startedTick;
        private final long deadlineTick;
        private final Map<UUID, TechniqueChildTicket> children =
                new LinkedHashMap<>();
        private TechniqueState state = TechniqueState.CREATED;
        private String phase = "created";
        private long updatedTick;
        /* Negative means no terminal cleanup window is currently pending. */
        private long cleanupDeadlineTick = -1L;
        private boolean cleanupDeadlineSaturated;
        /* Last explicit owner finishTick boundary; negative means none. */
        private long finishedThroughTick = -1L;
        private long revision = 1L;
        private int submittedChildren;
        /* Suppresses re-entrant child completions from resuming mid-batch. */
        private int pendingChildSubmissions;
        private int recoveryAttempts;
        /* "created" is runtime scaffolding, not a handler-declared phase. */
        private int phaseCount;
        private TechniqueState pendingTerminal;
        private TechniqueFailureCode pendingFailureCode;
        private String pendingSummary;
        private boolean cancellationNotified;
        private boolean safetyPreemptionNotified;
        private TechniqueCancelReason requestedChildCancellationReason;
        private TechniqueCancelReason dispatchedChildCancellationReason;
        private boolean childCancellationDispatching;

        private ActiveRun(UUID techniqueRunId, TechniqueStartRequest request,
                PlayerTechnique technique, long deadlineTick) {
            this.techniqueRunId = Objects.requireNonNull(techniqueRunId,
                    "techniqueRunId");
            skillRunId = request.skillRunId();
            botId = request.botId();
            botGeneration = request.botGeneration();
            this.technique = Objects.requireNonNull(technique, "technique");
            descriptor = Objects.requireNonNull(technique.descriptor(),
                    "descriptor");
            parameters = request.parameters();
            startedTick = request.currentTick();
            updatedTick = startedTick;
            this.deadlineTick = deadlineTick;
        }

        private TechniqueRunView view() {
            return new TechniqueRunView(techniqueRunId, skillRunId, botId,
                    botGeneration, descriptor.id(), descriptor.version(), state,
                    phase, startedTick, deadlineTick, revision,
                    submittedChildren, recoveryAttempts,
                    List.copyOf(children.values()));
        }
    }
}
