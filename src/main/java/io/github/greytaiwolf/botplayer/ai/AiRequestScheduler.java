package io.github.greytaiwolf.botplayer.ai;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * P6 的纯 Java、有界异步请求调度器。
 *
 * <p>生产构造器不接受任意 {@code Executor}。所有 Provider、token setup、终态清理和 public
 * completion 都必须经过 {@link AiRequestSchedulerSupervisor} 的受信任、有界且物理隔离的 lanes。
 * 每次 handoff 都带有单次 generation/attempt ID，并由独立 start watchdog 在 wrapper 真正开始前
 * 保留。{@code Executor.execute()} 返回从不代表 Provider 已开始或 completion 已发布。</p>
 *
 * <p>Provider start 在线性化锁内提交：取消在该提交前胜出时绝不会调用
 * {@link AiProvider#complete(AiRequest, CancellationToken)}；提交先胜出时，Provider <em>可能</em>
 * 已开始，随后取消只会得到异步回执，并在受限物理 lane 中显式追踪/quarantine。调度器从不在
 * submit、pump 或 deadline timer 线程直接调用 Provider、CancellationToken、stage cancellation
 * 或调用方 continuation。</p>
 */
public final class AiRequestScheduler implements AutoCloseable {
    public static final int MAX_HEALTH_HISTORY = 512;
    private static final AtomicLong OWNED_SUPERVISOR_SEQUENCE = new AtomicLong();

    private final String providerId;
    private final AiProvider provider;
    private final AiRequestSchedulerPolicy policy;
    private final Clock clock;
    private final AiRequestSchedulerSupervisor supervisor;
    private final boolean ownsSupervisor;
    private final Object lock = new Object();
    private final Deque<Run> queued = new ArrayDeque<>();
    private final Deque<Run> pendingStarts = new ArrayDeque<>();
    private final Deque<Finish> pendingCleanup = new ArrayDeque<>();
    private final Deque<Finish> pendingPublication = new ArrayDeque<>();
    private final Deque<DetachedCleanup> pendingDetachedCleanup = new ArrayDeque<>();
    private final Deque<DetachedCleanup> retainedDetachedCleanup = new ArrayDeque<>();
    private final Map<UUID, Run> liveByRequest = new LinkedHashMap<>();
    private final Map<UUID, Finish> finishingByRequest = new LinkedHashMap<>();
    private final Map<UUID, Integer> queuedByBot = new LinkedHashMap<>();
    private final Map<UUID, Integer> inFlightByBot = new LinkedHashMap<>();
    private final Map<UUID, AiScheduledRequestHealth> healthByRequest =
            new LinkedHashMap<>();
    private final Deque<UUID> terminalHistory = new ArrayDeque<>();
    private final Deque<AiRequestSchedulerQuarantine> quarantineHistory =
            new ArrayDeque<>();

    private long nextAttemptId = 1L;
    private int inFlightCount;
    private int cleanupGates;
    private int publicationGates;
    private int pendingCleanupAcknowledgements;
    private int quarantinedCleanupCount;
    private int quarantinedPhysicalCleanupCount;
    private int activeProviderInvocationCount;
    private int quarantinedProviderInvocationCount;
    private int activeTokenSetupCount;
    private int quarantinedTokenSetupCount;
    private int activeCompletionDeliveryCount;
    private int quarantinedCompletionDeliveryCount;
    private int outstandingDetachedCleanupCount;
    private long rejectedDispatchCount;
    private boolean dispatchDegraded;
    private boolean pumpRunning;
    private boolean closed;

    /** Creates a scheduler which owns a fresh trusted bounded supervisor. */
    public AiRequestScheduler(
            String providerId, AiProvider provider, AiRequestSchedulerPolicy policy) {
        this(providerId, provider, policy, Clock.systemUTC(),
                AiRequestSchedulerSupervisor.create(
                        "ai-scheduler-" + OWNED_SUPERVISOR_SEQUENCE.incrementAndGet(), policy),
                true);
    }

    /**
     * Creates a scheduler backed by exactly one caller-lifecycle-owned trusted supervisor.
     *
     * <p>The supervisor is claimed once and is never closed by this scheduler. The owner must
     * close it only after the scheduler has finished its terminal publication work. No raw
     * caller-supplied executor can enter this production API.</p>
     */
    public AiRequestScheduler(
            String providerId,
            AiProvider provider,
            AiRequestSchedulerPolicy policy,
            AiRequestSchedulerSupervisor supervisor) {
        this(providerId, provider, policy, Clock.systemUTC(), supervisor, false);
    }

    /** Package-private deterministic/adversarial test constructor. */
    AiRequestScheduler(
            String providerId,
            AiProvider provider,
            AiRequestSchedulerPolicy policy,
            Clock clock,
            AiRequestSchedulerSupervisor supervisor) {
        this(providerId, provider, policy, clock, supervisor, false);
    }

    private AiRequestScheduler(
            String providerId,
            AiProvider provider,
            AiRequestSchedulerPolicy policy,
            Clock clock,
            AiRequestSchedulerSupervisor supervisor,
            boolean ownsSupervisor) {
        this.providerId = AiChecks.providerId(providerId, "providerId");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.supervisor = Objects.requireNonNull(supervisor, "supervisor");
        this.ownsSupervisor = ownsSupervisor;
        if (!supervisor.claim()) {
            throw new IllegalArgumentException(
                    "scheduler supervisor is closed or already bound to another scheduler");
        }
        AiChecks.instant(clock.instant(), "clock instant");
    }

    /**
     * Submits a request whose owner/bot/agent/revision were already bound by the server session.
     *
     * <p>Submission only mutates scheduler state and requests trusted broker handoffs. It never
     * reads the external token, invokes a Provider, or runs caller continuation code.</p>
     */
    public AiScheduledRequestHandle submit(
            AiScheduledRequest request, CancellationToken externalToken) {
        AiScheduledRequest checkedRequest = Objects.requireNonNull(request, "request");
        CancellationToken checkedToken = Objects.requireNonNull(externalToken, "externalToken");
        Instant submittedAt;
        Instant deadline;
        try {
            submittedAt = now();
            deadline = deadlineAt(submittedAt,
                    checkedRequest.request().options().timeoutMillis());
        } catch (RuntimeException exception) {
            return rejectedHandle(checkedRequest.request().requestId(),
                    AiProviderException.of(AiFailureKind.UNAVAILABLE,
                            AiReasonCode.DEADLINE_OUT_OF_RANGE));
        }

        boolean runPump;
        synchronized (lock) {
            runPump = enqueueFinishesLocked(expireDueRunsLocked(submittedAt));
        }
        if (runPump) {
            drainPump();
        }

        Run run = new Run(checkedRequest, checkedToken, providerId, submittedAt, deadline);
        run.handle = new AiScheduledRequestHandle(
                checkedRequest.request().requestId(), run.responseView,
                () -> requestCancellation(run));

        AiProviderException immediate = null;
        boolean startTokenSetup = false;
        synchronized (lock) {
            UUID requestId = checkedRequest.request().requestId();
            if (liveByRequest.containsKey(requestId)
                    || finishingByRequest.containsKey(requestId)
                    || healthByRequest.containsKey(requestId)) {
                immediate = AiProviderException.of(AiFailureKind.INVALID_REQUEST);
            } else if (closed || dispatchDegraded) {
                immediate = rejectImmediatelyLocked(checkedRequest,
                        AiProviderException.of(AiFailureKind.UNAVAILABLE,
                                AiReasonCode.SCHEDULER_REJECTED), submittedAt);
            } else if (queued.size() >= policy.maximumQueuedRequests()
                    || count(queuedByBot, checkedRequest.botId())
                    >= policy.maximumQueuedRequestsPerBot()) {
                immediate = rejectImmediatelyLocked(checkedRequest,
                        AiProviderException.of(AiFailureKind.OVERLOADED), submittedAt);
            } else {
                liveByRequest.put(requestId, run);
                queued.addLast(run);
                increment(queuedByBot, checkedRequest.botId());
                recordHealthLocked(run.health);
                startTokenSetup = true;
            }
        }
        if (immediate != null) {
            return rejectedHandle(checkedRequest.request().requestId(), immediate);
        }

        installDeadline(run);
        if (startTokenSetup) {
            beginTokenSetup(run);
        }
        return run.handle;
    }

    /** Returns bounded, secret-free lane and quarantine diagnostics. */
    public AiRequestSchedulerDiagnostics diagnostics() {
        synchronized (lock) {
            return new AiRequestSchedulerDiagnostics(
                    dispatchDegraded,
                    pendingCleanupAcknowledgements,
                    quarantinedCleanupCount,
                    quarantinedPhysicalCleanupCount,
                    activeProviderInvocationCount,
                    quarantinedProviderInvocationCount,
                    activeTokenSetupCount,
                    quarantinedTokenSetupCount,
                    activeCompletionDeliveryCount,
                    quarantinedCompletionDeliveryCount,
                    rejectedDispatchCount,
                    pendingRecoveryDispatchCountLocked(),
                    List.copyOf(quarantineHistory));
        }
    }

    /**
     * Retries only retained, watchdog-invalidated cleanup/publication work after a lifecycle
     * owner has restored the affected trusted capacity. It never retries a Provider start, which
     * could otherwise issue a duplicate remote request.
     */
    public void resumeDispatch() {
        boolean runPump = false;
        synchronized (lock) {
            if (physicalQuarantineCountLocked() > 0) {
                return;
            }
            for (Finish finish : finishingByRequest.values()) {
                if (finish.cleanupRecoveryNeeded && !finish.cleanupCompleted
                        && !finish.cleanupQueued) {
                    finish.cleanupQueued = true;
                    pendingCleanup.addLast(finish);
                }
                if (finish.publicationRecoveryNeeded && !finish.publicationDelivered
                        && finish.deliveryAttempt == null && !finish.publicationQueued) {
                    finish.publicationQueued = true;
                    pendingPublication.addLast(finish);
                }
            }
            for (DetachedCleanup cleanup : List.copyOf(retainedDetachedCleanup)) {
                if (!cleanup.done && !cleanup.queued) {
                    cleanup.queued = true;
                    pendingDetachedCleanup.addLast(cleanup);
                }
            }
            runPump = claimPumpLocked();
        }
        if (runPump) {
            drainPump();
        }
    }

    /** Returns a bounded, safe history entry when the request is still retained. */
    public Optional<AiScheduledRequestHealth> requestHealth(UUID requestId) {
        AiChecks.requireNonZero(requestId, "requestId");
        synchronized (lock) {
            return Optional.ofNullable(healthByRequest.get(requestId));
        }
    }

    public int queuedRequestCount() {
        synchronized (lock) {
            return queued.size();
        }
    }

    public int inFlightRequestCount() {
        synchronized (lock) {
            return inFlightCount;
        }
    }

    /**
     * Linearizes cancellation of all live requests. Terminal cleanup/publication remain on their
     * isolated lanes; an owned supervisor closes only after those bounded handoffs settle.
     */
    @Override
    public void close() {
        List<Run> runs;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            runs = List.copyOf(liveByRequest.values());
        }
        for (Run run : runs) {
            cancelRun(run);
        }
        maybeCloseOwnedSupervisor();
    }

    private void installDeadline(Run run) {
        long delayMillis;
        try {
            Instant observedAt = now();
            if (!observedAt.isBefore(run.deadline)) {
                failRun(run, AiProviderException.of(AiFailureKind.TIMEOUT));
                return;
            }
            delayMillis = roundedUpMillis(Duration.between(observedAt, run.deadline));
        } catch (Throwable failure) {
            failRun(run, AiProviderException.of(AiFailureKind.UNAVAILABLE,
                    AiReasonCode.DEADLINE_OUT_OF_RANGE));
            AiRequestSchedulerSupervisor.DispatchAttempt.rethrowIfFatal(failure);
            return;
        }
        ScheduledFuture<?> task;
        try {
            task = supervisor.scheduleDeadline(() -> timeoutRun(run), delayMillis,
                    TimeUnit.MILLISECONDS);
        } catch (Throwable failure) {
            markDispatchFailure(run, AiRequestSchedulerQuarantineKind.DISPATCH_START);
            failRun(run, AiProviderException.of(AiFailureKind.UNAVAILABLE,
                    AiReasonCode.SCHEDULER_REJECTED));
            AiRequestSchedulerSupervisor.DispatchAttempt.rethrowIfFatal(failure);
            return;
        }
        boolean cancel;
        synchronized (lock) {
            run.deadlineTask = task;
            cancel = run.terminal;
        }
        if (cancel) {
            scheduleDetachedCleanup(run, DetachedCleanupKind.CANCEL_DEADLINE,
                    () -> cancelScheduledFuture(task));
        }
    }

    private void beginTokenSetup(Run run) {
        AiRequestSchedulerSupervisor.DispatchAttempt attempt;
        synchronized (lock) {
            if (run.terminal || run.tokenAttempt != null) {
                return;
            }
            attempt = newAttemptLocked(AiRequestSchedulerSupervisor.Lane.TOKEN_SETUP,
                    new AiRequestSchedulerSupervisor.DispatchCallbacks() {
                        @Override
                        public boolean started(
                                AiRequestSchedulerSupervisor.DispatchAttempt started) {
                            return tokenSetupStarted(run, started);
                        }

                        @Override
                        public void startLost(
                                AiRequestSchedulerSupervisor.DispatchAttempt lost) {
                            tokenSetupStartLost(run, lost);
                        }

                        @Override
                        public void stalled(
                                AiRequestSchedulerSupervisor.DispatchAttempt stalled) {
                            tokenSetupStalled(run, stalled);
                        }

                        @Override
                        public void bodySucceeded(
                                AiRequestSchedulerSupervisor.DispatchAttempt succeeded) {
                            tokenSetupFinished(run, succeeded);
                        }

                        @Override
                        public void bodyFailed(
                                AiRequestSchedulerSupervisor.DispatchAttempt failed,
                                Throwable failure) {
                            tokenSetupBodyFailed(run, failed, failure);
                        }
                    });
            run.tokenAttempt = attempt;
        }
        supervisor.handoff(attempt, () -> installExternalCancellation(run));
    }

    private boolean tokenSetupStarted(
            Run run, AiRequestSchedulerSupervisor.DispatchAttempt attempt) {
        synchronized (lock) {
            if (run.tokenAttempt != attempt || run.terminal) {
                return false;
            }
            run.tokenSetupActive = true;
            activeTokenSetupCount++;
            return true;
        }
    }

    private void tokenSetupStartLost(
            Run run, AiRequestSchedulerSupervisor.DispatchAttempt attempt) {
        boolean runPump = false;
        synchronized (lock) {
            if (run.tokenAttempt != attempt || run.terminal) {
                return;
            }
            run.tokenAttempt = null;
            markDispatchFailureLocked(run, AiRequestSchedulerQuarantineKind.DISPATCH_START);
            Finish finish = terminateLocked(run, Terminal.failure(
                    AiProviderException.of(AiFailureKind.UNAVAILABLE,
                            AiReasonCode.SCHEDULER_REJECTED)));
            runPump = enqueueFinishLocked(finish);
        }
        if (runPump) {
            drainPump();
        }
    }

    private void tokenSetupStalled(
            Run run, AiRequestSchedulerSupervisor.DispatchAttempt attempt) {
        Finish finish = null;
        boolean runPump = false;
        synchronized (lock) {
            if (run.tokenAttempt != attempt || !run.tokenSetupActive) {
                return;
            }
            if (!run.tokenSetupQuarantined) {
                run.tokenSetupQuarantined = true;
                quarantinedTokenSetupCount++;
                recordQuarantineLocked(run, AiRequestSchedulerQuarantineKind.TOKEN_SETUP);
            }
            dispatchDegraded = true;
            if (!run.terminal) {
                finish = terminateLocked(run, Terminal.failure(AiProviderException.of(
                        AiFailureKind.UNAVAILABLE, AiReasonCode.SCHEDULER_REJECTED)));
                runPump = enqueueFinishLocked(finish);
            }
        }
        if (runPump) {
            drainPump();
        }
    }

    private void tokenSetupFinished(
            Run run, AiRequestSchedulerSupervisor.DispatchAttempt attempt) {
        Finish cancellation = null;
        boolean runPump = false;
        synchronized (lock) {
            if (run.tokenAttempt != attempt || !run.tokenSetupActive) {
                return;
            }
            run.tokenSetupActive = false;
            activeTokenSetupCount--;
            if (activeTokenSetupCount < 0) {
                throw new IllegalStateException("token setup count underflow");
            }
            if (run.tokenSetupQuarantined) {
                run.tokenSetupQuarantined = false;
                quarantinedTokenSetupCount--;
                if (quarantinedTokenSetupCount < 0) {
                    throw new IllegalStateException("token setup quarantine count underflow");
                }
            }
            DeferredCancellation attachment = takeCancellationAttachmentLocked(run);
            if (run.terminal) {
                discard(attachment);
            } else if (attachment == null) {
                /* A normal token body without its registration bridge is never Provider-safe. */
                markDispatchFailureLocked(run, AiRequestSchedulerQuarantineKind.TOKEN_SETUP);
                cancellation = terminateLocked(run, Terminal.failure(AiProviderException.of(
                        AiFailureKind.UNAVAILABLE, AiReasonCode.CALLBACK_ATTACHMENT_FAILED)));
                runPump = enqueueFinishLocked(cancellation);
            } else {
                CancellationAttachmentAck acknowledgement = attachment.activateAfterBodyAck();
                if (acknowledgement == CancellationAttachmentAck.INVALID) {
                    discard(attachment);
                    markDispatchFailureLocked(run, AiRequestSchedulerQuarantineKind.TOKEN_SETUP);
                    cancellation = terminateLocked(run, Terminal.failure(AiProviderException.of(
                            AiFailureKind.UNAVAILABLE,
                            AiReasonCode.CALLBACK_ATTACHMENT_FAILED)));
                    runPump = enqueueFinishLocked(cancellation);
                } else if (acknowledgement == CancellationAttachmentAck.CANCELLED) {
                    cancellation = terminateLocked(run, Terminal.cancelled());
                    runPump = enqueueFinishLocked(cancellation);
                } else {
                    run.cancellationReady = true;
                    reserveEligibleStartsLocked();
                }
            }
            maybeRecoverDispatchLocked();
            if (!runPump) {
                runPump = claimPumpIfWorkLocked();
            }
        }
        if (runPump) {
            drainPump();
        }
    }

    /**
     * An external-token body did not return normally. This is deliberately distinct from a
     * successful token setup: the physical lane has returned, but the request must never become
     * Provider-eligible on the strength of a partial registration/read.
     */
    private void tokenSetupBodyFailed(
            Run run,
            AiRequestSchedulerSupervisor.DispatchAttempt attempt,
            Throwable failure) {
        Finish finish = null;
        boolean runPump = false;
        synchronized (lock) {
            if (run.tokenAttempt != attempt || !run.tokenSetupActive) {
                return;
            }
            run.tokenSetupActive = false;
            activeTokenSetupCount--;
            if (activeTokenSetupCount < 0) {
                throw new IllegalStateException("token setup count underflow");
            }
            if (run.tokenSetupQuarantined) {
                run.tokenSetupQuarantined = false;
                quarantinedTokenSetupCount--;
                if (quarantinedTokenSetupCount < 0) {
                    throw new IllegalStateException("token setup quarantine count underflow");
                }
            }
            discard(takeCancellationAttachmentLocked(run));
            markDispatchFailureLocked(run, AiRequestSchedulerQuarantineKind.TOKEN_SETUP);
            if (!run.terminal) {
                finish = terminateLocked(run, Terminal.failure(AiProviderException.of(
                        AiFailureKind.UNAVAILABLE, AiReasonCode.CALLBACK_ATTACHMENT_FAILED)));
                runPump = enqueueFinishLocked(finish);
            }
            maybeRecoverDispatchLocked();
            if (!runPump) {
                runPump = claimPumpIfWorkLocked();
            }
        }
        if (runPump) {
            drainPump();
        }
    }

    private void installExternalCancellation(Run run) {
        CancellationObservation initial = observeCancellation(run.externalToken);
        if (initial == CancellationObservation.CANCELLED) {
            cancelRun(run);
            return;
        }
        DeferredCancellation attachment = new DeferredCancellation();
        CancellationToken.ListenerRegistration registration;
        try {
            registration = Objects.requireNonNull(run.externalToken.onCancellation(() -> {
                if (attachment.recordCallback()) {
                    cancelRun(run);
                }
            }), "cancellation registration");
        } catch (Throwable failure) {
            /* A synchronous callback followed by a throw has not earned cancellation commit. */
            attachment.discard();
            throw failure;
        }
        if (!attachment.attachmentReturnedNormally()) {
            attachment.discard();
            scheduleDetachedCleanup(run, DetachedCleanupKind.CLOSE_REGISTRATION,
                    () -> closeRegistration(registration));
            throw new IllegalStateException("cancellation attachment lost its provisional state");
        }
        CancellationObservation second;
        try {
            second = observeCancellation(run.externalToken);
        } catch (Throwable failure) {
            attachment.discard();
            scheduleDetachedCleanup(run, DetachedCleanupKind.CLOSE_REGISTRATION,
                    () -> closeRegistration(registration));
            throw failure;
        }

        boolean closeRegistration = false;
        synchronized (lock) {
            if (run.terminal) {
                attachment.discard();
                closeRegistration = true;
            } else {
                run.externalRegistration = registration;
                run.cancellationAttachment = attachment;
                if (second == CancellationObservation.CANCELLED) {
                    attachment.recordObservedCancellation();
                }
            }
        }
        if (closeRegistration) {
            scheduleDetachedCleanup(run, DetachedCleanupKind.CLOSE_REGISTRATION,
                    () -> closeRegistration(registration));
        }
    }

    private void timeoutRun(Run run) {
        failRun(run, AiProviderException.of(AiFailureKind.TIMEOUT));
    }

    private void cancelRun(Run run) {
        completeTerminal(run, Terminal.cancelled());
    }

    private CompletionStage<AiRequestCancellationReceipt> requestCancellation(Run run) {
        boolean runPump = false;
        synchronized (lock) {
            if (!run.terminal) {
                run.handleCancellationWon = true;
                runPump = enqueueFinishLocked(terminateLocked(run, Terminal.cancelled()));
            }
        }
        if (runPump) {
            drainPump();
        }
        return run.cancellationReceiptView;
    }

    private void failRun(Run run, AiProviderException failure) {
        completeTerminal(run, Terminal.failure(Objects.requireNonNull(failure, "failure")));
    }

    private void succeedRun(Run run, AiResponse response) {
        completeTerminal(run, Terminal.success(Objects.requireNonNull(response, "response")));
    }

    private void completeTerminal(Run run, Terminal terminal) {
        boolean runPump;
        synchronized (lock) {
            if (terminal.failure == null
                    || terminal.failure.failureKind() != AiFailureKind.CANCELLED) {
                Terminal deadline = deadlineFailure(run);
                if (deadline != null) {
                    terminal = deadline;
                }
            }
            runPump = enqueueFinishLocked(terminateLocked(run, terminal));
        }
        if (runPump) {
            drainPump();
        }
    }

    /** Must run under {@link #lock}; no external callback is reached here. */
    private Finish terminateLocked(Run run, Terminal terminal) {
        if (run.terminal) {
            return null;
        }
        run.terminal = true;
        /* A pre-body-ACK callback is only provisional; terminalization must make it inert. */
        discard(takeCancellationAttachmentLocked(run));
        discard(takeCompletionAttachmentLocked(run));
        liveByRequest.remove(run.request.request().requestId(), run);
        if (run.queued) {
            queued.remove(run);
            decrement(queuedByBot, run.request.botId());
            run.queued = false;
        }
        pendingStarts.remove(run);
        if (run.inFlight) {
            run.inFlight = false;
            inFlightCount--;
            if (inFlightCount < 0) {
                throw new IllegalStateException("in-flight request count underflow");
            }
            decrement(inFlightByBot, run.request.botId());
        }
        if (!run.providerStartCommitted) {
            /* A wrapper which was accepted but has not committed cannot call the Provider. */
            run.providerAttempt = null;
        }
        if (!run.tokenSetupActive) {
            /* A delayed token wrapper sees terminal and becomes a single-use no-op. */
            run.tokenAttempt = null;
        }
        if (run.providerInvocationActive && !run.providerInvocationQuarantined) {
            run.providerInvocationQuarantined = true;
            quarantinedProviderInvocationCount++;
            recordQuarantineLocked(run, AiRequestSchedulerQuarantineKind.PROVIDER_INVOCATION);
            dispatchDegraded = true;
        }
        if (run.tokenSetupActive && !run.tokenSetupQuarantined) {
            run.tokenSetupQuarantined = true;
            quarantinedTokenSetupCount++;
            recordQuarantineLocked(run, AiRequestSchedulerQuarantineKind.TOKEN_SETUP);
            dispatchDegraded = true;
        }
        Instant observedAt = safeNow(run.health.observedAt());
        if (terminal.response != null) {
            run.health = run.health.succeeded(observedAt);
        } else if (terminal.failure.failureKind() == AiFailureKind.CANCELLED) {
            run.health = run.health.cancelled(observedAt);
        } else {
            run.health = run.health.failed(terminal.failure, observedAt);
        }
        recordHealthLocked(run.health);
        terminalHistory.addLast(run.request.request().requestId());
        trimTerminalHistoryLocked();

        Finish finish = new Finish(run, terminal);
        finishingByRequest.put(run.request.request().requestId(), finish);
        finish.cleanupQueued = true;
        cleanupGates++;
        pendingCleanupAcknowledgements = cleanupGates;
        pendingCleanup.addLast(finish);
        return finish;
    }

    private boolean enqueueFinishLocked(Finish finish) {
        return finish != null && claimPumpLocked();
    }

    private boolean enqueueFinishesLocked(List<Finish> finishes) {
        return !finishes.isEmpty() && claimPumpLocked();
    }

    /** Scheduler-owned pump; all externally supplied work is delegated through the supervisor. */
    private void drainPump() {
        while (true) {
            DetachedCleanup detached = null;
            Finish cleanup = null;
            Finish publication = null;
            Run providerStart = null;
            synchronized (lock) {
                if (!pendingDetachedCleanup.isEmpty()) {
                    detached = pendingDetachedCleanup.removeFirst();
                    detached.queued = false;
                } else if (!pendingCleanup.isEmpty()) {
                    cleanup = pendingCleanup.removeFirst();
                    cleanup.cleanupQueued = false;
                } else if (cleanupGates > 0) {
                    pumpRunning = false;
                    return;
                } else if (!pendingPublication.isEmpty()) {
                    publication = pendingPublication.removeFirst();
                    publication.publicationQueued = false;
                } else if (publicationGates > 0) {
                    pumpRunning = false;
                    return;
                } else if (dispatchDegraded) {
                    pumpRunning = false;
                    return;
                } else {
                    reserveEligibleStartsLocked();
                    if (pendingStarts.isEmpty()) {
                        pumpRunning = false;
                        return;
                    }
                    providerStart = pendingStarts.removeFirst();
                }
            }
            if (detached != null) {
                beginDetachedCleanup(detached);
            } else if (cleanup != null) {
                beginFinishCleanup(cleanup);
            } else if (publication != null) {
                beginPublication(publication);
            } else {
                beginProviderStart(providerStart);
            }
        }
    }

    private void beginProviderStart(Run run) {
        AiRequestSchedulerSupervisor.DispatchAttempt attempt;
        synchronized (lock) {
            if (run.terminal) {
                return;
            }
            if (dispatchDegraded) {
                pendingStarts.addFirst(run);
                return;
            }
            if (run.providerAttempt != null) {
                return;
            }
            attempt = newAttemptLocked(AiRequestSchedulerSupervisor.Lane.PROVIDER_START,
                    new AiRequestSchedulerSupervisor.DispatchCallbacks() {
                        @Override
                        public boolean started(
                                AiRequestSchedulerSupervisor.DispatchAttempt started) {
                            return providerStartCommitted(run, started);
                        }

                        @Override
                        public void startLost(
                                AiRequestSchedulerSupervisor.DispatchAttempt lost) {
                            providerStartLost(run, lost);
                        }

                        @Override
                        public void stalled(
                                AiRequestSchedulerSupervisor.DispatchAttempt stalled) {
                            providerStalled(run, stalled);
                        }

                        @Override
                        public void bodySucceeded(
                                AiRequestSchedulerSupervisor.DispatchAttempt succeeded) {
                            providerFinished(run, succeeded);
                        }

                        @Override
                        public void bodyFailed(
                                AiRequestSchedulerSupervisor.DispatchAttempt failed,
                                Throwable failure) {
                            providerBodyFailed(run, failed, failure);
                        }
                    });
            run.providerAttempt = attempt;
            if (!run.inFlight) {
                throw new IllegalStateException("provider start lost its in-flight reservation");
            }
        }
        supervisor.handoff(attempt, () -> invokeProvider(run));
    }

    /** The cancellation-vs-provider start linearization point. */
    private boolean providerStartCommitted(
            Run run, AiRequestSchedulerSupervisor.DispatchAttempt attempt) {
        Finish finish = null;
        boolean runPump = false;
        synchronized (lock) {
            if (run.providerAttempt != attempt || run.terminal) {
                return false;
            }
            if (dispatchDegraded) {
                run.providerAttempt = null;
                pendingStarts.addFirst(run);
                return false;
            }
            Terminal deadline = deadlineFailure(run);
            if (deadline != null) {
                finish = terminateLocked(run, deadline);
                runPump = enqueueFinishLocked(finish);
            } else {
                /* Once this write occurs under lock, a later cancellation means Provider may run. */
                run.providerStartCommitted = true;
                run.providerInvocationActive = true;
                activeProviderInvocationCount++;
                return true;
            }
        }
        if (runPump) {
            drainPump();
        }
        return false;
    }

    private void providerStartLost(
            Run run, AiRequestSchedulerSupervisor.DispatchAttempt attempt) {
        boolean runPump = false;
        synchronized (lock) {
            if (run.providerAttempt != attempt || run.terminal) {
                return;
            }
            run.providerAttempt = null;
            markDispatchFailureLocked(run, AiRequestSchedulerQuarantineKind.DISPATCH_START);
            runPump = enqueueFinishLocked(terminateLocked(run, Terminal.failure(
                    AiProviderException.of(AiFailureKind.UNAVAILABLE,
                            AiReasonCode.SCHEDULER_REJECTED))));
        }
        if (runPump) {
            drainPump();
        }
    }

    private void providerStalled(
            Run run, AiRequestSchedulerSupervisor.DispatchAttempt attempt) {
        boolean runPump = false;
        synchronized (lock) {
            if (run.providerAttempt != attempt || !run.providerInvocationActive) {
                return;
            }
            if (!run.providerInvocationQuarantined) {
                run.providerInvocationQuarantined = true;
                quarantinedProviderInvocationCount++;
                recordQuarantineLocked(run, AiRequestSchedulerQuarantineKind.PROVIDER_INVOCATION);
            }
            dispatchDegraded = true;
            if (!run.terminal) {
                runPump = enqueueFinishLocked(terminateLocked(run, Terminal.failure(
                        AiProviderException.of(AiFailureKind.UNAVAILABLE,
                                AiReasonCode.SCHEDULER_REJECTED))));
            }
        }
        if (runPump) {
            drainPump();
        }
    }

    private void providerFinished(
            Run run, AiRequestSchedulerSupervisor.DispatchAttempt attempt) {
        CompletionSignal completion = null;
        Finish attachmentFailure = null;
        boolean runPump = false;
        synchronized (lock) {
            if (run.providerAttempt != attempt || !run.providerInvocationActive) {
                return;
            }
            run.providerInvocationActive = false;
            activeProviderInvocationCount--;
            if (activeProviderInvocationCount < 0) {
                throw new IllegalStateException("provider invocation count underflow");
            }
            if (run.providerInvocationQuarantined) {
                run.providerInvocationQuarantined = false;
                quarantinedProviderInvocationCount--;
                if (quarantinedProviderInvocationCount < 0) {
                    throw new IllegalStateException("provider quarantine count underflow");
                }
            }
            DeferredCompletion attachment = takeCompletionAttachmentLocked(run);
            if (run.terminal) {
                discard(attachment);
            } else if (attachment == null) {
                /* A normal Provider body without its completion bridge is not a valid ACK. */
                markDispatchFailureLocked(run,
                        AiRequestSchedulerQuarantineKind.PROVIDER_INVOCATION);
                attachmentFailure = terminateLocked(run, Terminal.failure(AiProviderException.of(
                        AiFailureKind.UNKNOWN, AiReasonCode.CALLBACK_ATTACHMENT_FAILED)));
                runPump = enqueueFinishLocked(attachmentFailure);
            } else {
                CompletionAttachmentAck acknowledgement = attachment.activateAfterBodyAck();
                if (!acknowledgement.acknowledged()) {
                    discard(attachment);
                    markDispatchFailureLocked(run,
                            AiRequestSchedulerQuarantineKind.PROVIDER_INVOCATION);
                    attachmentFailure = terminateLocked(run, Terminal.failure(
                            AiProviderException.of(AiFailureKind.UNKNOWN,
                                    AiReasonCode.CALLBACK_ATTACHMENT_FAILED)));
                    runPump = enqueueFinishLocked(attachmentFailure);
                } else {
                    completion = acknowledgement.signal();
                }
            }
            maybeRecoverDispatchLocked();
            /* A synchronously buffered completion must terminalize before this body opens work. */
            if (completion == null && !runPump) {
                runPump = claimPumpIfWorkLocked();
            }
        }
        if (completion != null) {
            completeProviderStage(run, completion.response(), completion.throwable());
        }
        if (runPump) {
            drainPump();
        }
    }

    /**
     * A Provider invocation or completion-attachment body threw. The Provider-start commit is
     * never rolled back or retried: once it won the scheduler lock, the remote side may have
     * observed the call even if attachment subsequently fails.
     */
    private void providerBodyFailed(
            Run run,
            AiRequestSchedulerSupervisor.DispatchAttempt attempt,
            Throwable failure) {
        Finish finish = null;
        boolean runPump = false;
        synchronized (lock) {
            if (run.providerAttempt != attempt || !run.providerInvocationActive) {
                return;
            }
            run.providerInvocationActive = false;
            activeProviderInvocationCount--;
            if (activeProviderInvocationCount < 0) {
                throw new IllegalStateException("provider invocation count underflow");
            }
            if (run.providerInvocationQuarantined) {
                run.providerInvocationQuarantined = false;
                quarantinedProviderInvocationCount--;
                if (quarantinedProviderInvocationCount < 0) {
                    throw new IllegalStateException("provider quarantine count underflow");
                }
            }
            discard(takeCompletionAttachmentLocked(run));
            markDispatchFailureLocked(run,
                    AiRequestSchedulerQuarantineKind.PROVIDER_INVOCATION);
            if (!run.terminal) {
                finish = terminateLocked(run,
                        Terminal.failure(AiProviderException.fromThrowable(failure)));
                runPump = enqueueFinishLocked(finish);
            }
            maybeRecoverDispatchLocked();
            if (!runPump) {
                runPump = claimPumpIfWorkLocked();
            }
        }
        if (runPump) {
            drainPump();
        }
    }

    private void invokeProvider(Run run) {
        CompletionStage<AiResponse> stage = provider.complete(
                run.request.request(), run.internalCancellation.token());
        if (stage == null) {
            failRun(run, AiProviderException.of(AiFailureKind.MALFORMED_RESPONSE));
            return;
        }

        CompletionStage<AiResponse> cancellation = null;
        boolean attach = false;
        synchronized (lock) {
            run.delegateStage = stage;
            if (run.terminal) {
                cancellation = claimDelegateStageCancellationLocked(run);
            } else {
                attach = true;
            }
        }
        if (cancellation != null) {
            CompletionStage<AiResponse> stageToCancel = cancellation;
            scheduleDetachedCleanup(run, DetachedCleanupKind.CANCEL_STAGE,
                    () -> cancelStage(stageToCancel));
        }
        if (!attach) {
            return;
        }
        DeferredCompletion attachment = new DeferredCompletion();
        try {
            stage.whenComplete((response, throwable) -> {
                CompletionSignal signal = attachment.recordCallback(response, throwable);
                if (signal != null) {
                    completeProviderStage(run, signal.response(), signal.throwable());
                }
            });
        } catch (Throwable failure) {
            /* A callback synchronously invoked before a throwing attachment is still provisional. */
            attachment.discard();
            if (AiRequestSchedulerSupervisor.DispatchAttempt.isFatal(failure)) {
                rethrowBodyFailure(failure);
            }
            throw AiProviderException.of(AiFailureKind.UNKNOWN,
                    AiReasonCode.CALLBACK_ATTACHMENT_FAILED);
        }
        if (!attachment.attachmentReturnedNormally()) {
            attachment.discard();
            throw new IllegalStateException("completion attachment lost its provisional state");
        }
        synchronized (lock) {
            if (run.providerInvocationActive && !run.terminal) {
                run.completionAttachment = attachment;
            } else {
                attachment.discard();
            }
        }
    }

    /**
     * Completion may run on an untrusted Provider thread after the Provider-start body returned.
     * A callback-execution Throwable must therefore fail the run locally instead of escaping that
     * thread with no scheduler state transition. A stage's {@code throwable} argument is data,
     * including when it happens to be an Error, and is safely normalized by fromThrowable.
     */
    private void completeProviderStage(Run run, AiResponse response, Throwable throwable) {
        try {
            if (throwable != null) {
                failRun(run, AiProviderException.fromThrowable(throwable));
            } else if (response == null) {
                failRun(run, AiProviderException.of(AiFailureKind.MALFORMED_RESPONSE));
            } else {
                AiProviderException invalid = validateResponse(run, response);
                if (invalid != null) {
                    failRun(run, invalid);
                } else {
                    succeedRun(run, response);
                }
            }
        } catch (Throwable failure) {
            failRun(run, AiProviderException.fromThrowable(failure));
            AiRequestSchedulerSupervisor.DispatchAttempt.rethrowIfFatal(failure);
        }
    }

    private void beginFinishCleanup(Finish finish) {
        AiRequestSchedulerSupervisor.DispatchAttempt attempt;
        synchronized (lock) {
            if (finish.cleanupCompleted || finish.cleanupAttempt != null
                    || (finish.cleanupGateOpen && !finish.cleanupRecoveryNeeded)) {
                return;
            }
            attempt = newAttemptLocked(AiRequestSchedulerSupervisor.Lane.TERMINAL_CLEANUP,
                    new AiRequestSchedulerSupervisor.DispatchCallbacks() {
                        @Override
                        public boolean started(
                                AiRequestSchedulerSupervisor.DispatchAttempt started) {
                            return cleanupStarted(finish, started);
                        }

                        @Override
                        public void startLost(
                                AiRequestSchedulerSupervisor.DispatchAttempt lost) {
                            cleanupStartLost(finish, lost);
                        }

                        @Override
                        public void stalled(
                                AiRequestSchedulerSupervisor.DispatchAttempt stalled) {
                            cleanupStalled(finish, stalled);
                        }

                        @Override
                        public void bodySucceeded(
                                AiRequestSchedulerSupervisor.DispatchAttempt succeeded) {
                            cleanupFinished(finish, succeeded);
                        }

                        @Override
                        public void bodyFailed(
                                AiRequestSchedulerSupervisor.DispatchAttempt failed,
                                Throwable failure) {
                            cleanupBodyFailed(finish, failed, failure);
                        }
                    });
            finish.cleanupAttempt = attempt;
        }
        supervisor.handoff(attempt, () -> cleanUpFinishedRun(finish));
    }

    private boolean cleanupStarted(
            Finish finish, AiRequestSchedulerSupervisor.DispatchAttempt attempt) {
        synchronized (lock) {
            return finish.cleanupAttempt == attempt && !finish.cleanupCompleted;
        }
    }

    private void cleanupStartLost(
            Finish finish, AiRequestSchedulerSupervisor.DispatchAttempt attempt) {
        boolean runPump = false;
        synchronized (lock) {
            if (finish.cleanupAttempt != attempt || finish.cleanupCompleted) {
                return;
            }
            finish.cleanupAttempt = null;
            finish.cleanupRecoveryNeeded = true;
            markCleanupQuarantinedLocked(finish, AiRequestSchedulerQuarantineKind.DISPATCH_START,
                    false);
            dispatchDegraded = true;
            runPump = releaseCleanupGateLocked(finish);
        }
        if (runPump) {
            drainPump();
        }
    }

    private void cleanupStalled(
            Finish finish, AiRequestSchedulerSupervisor.DispatchAttempt attempt) {
        boolean runPump = false;
        synchronized (lock) {
            if (finish.cleanupAttempt != attempt || finish.cleanupCompleted) {
                return;
            }
            markCleanupQuarantinedLocked(finish,
                    AiRequestSchedulerQuarantineKind.TERMINAL_CLEANUP, true);
            dispatchDegraded = true;
            runPump = releaseCleanupGateLocked(finish);
        }
        if (runPump) {
            drainPump();
        }
    }

    private void cleanupFinished(
            Finish finish, AiRequestSchedulerSupervisor.DispatchAttempt attempt) {
        boolean runPump = false;
        synchronized (lock) {
            if (finish.cleanupAttempt != attempt || finish.cleanupCompleted) {
                return;
            }
            finish.cleanupCompleted = true;
            finish.cleanupRecoveryNeeded = false;
            if (finish.cleanupPhysicalQuarantined) {
                finish.cleanupPhysicalQuarantined = false;
                quarantinedPhysicalCleanupCount--;
                if (quarantinedPhysicalCleanupCount < 0) {
                    throw new IllegalStateException("physical cleanup quarantine count underflow");
                }
            }
            if (finish.cleanupQuarantined) {
                finish.cleanupQuarantined = false;
                quarantinedCleanupCount--;
                if (quarantinedCleanupCount < 0) {
                    throw new IllegalStateException("cleanup quarantine count underflow");
                }
            }
            runPump = releaseCleanupGateLocked(finish);
            maybeRecoverDispatchLocked();
            maybeDiscardFinishLocked(finish);
            if (!runPump) {
                runPump = claimPumpIfWorkLocked();
            }
        }
        if (runPump) {
            drainPump();
        }
        maybeCloseOwnedSupervisor();
    }

    /**
     * A cleanup body did not acknowledge every terminal resource. Keep the finish retained and
     * make publication eligible through the explicit quarantine path, but never mark cleanup as
     * complete. A later resumeDispatch() retries the same bounded resource set.
     */
    private void cleanupBodyFailed(
            Finish finish,
            AiRequestSchedulerSupervisor.DispatchAttempt attempt,
            Throwable failure) {
        boolean runPump = false;
        synchronized (lock) {
            if (finish.cleanupAttempt != attempt || finish.cleanupCompleted) {
                return;
            }
            finish.cleanupAttempt = null;
            finish.cleanupRecoveryNeeded = true;
            clearCleanupPhysicalQuarantineLocked(finish);
            markCleanupQuarantinedLocked(finish,
                    AiRequestSchedulerQuarantineKind.TERMINAL_CLEANUP, false);
            dispatchDegraded = true;
            runPump = releaseCleanupGateLocked(finish);
        }
        if (runPump) {
            drainPump();
        }
    }

    private void cleanUpFinishedRun(Finish finish) {
        Run run = finish.run;
        ScheduledFuture<?> deadline;
        CancellationToken.ListenerRegistration registration;
        CompletionStage<AiResponse> delegate = null;
        synchronized (lock) {
            deadline = run.deadlineTask;
            registration = run.externalRegistration;
            if (finish.terminal.response == null) {
                delegate = claimDelegateStageCancellationLocked(run);
            }
        }
        Throwable failure = null;
        failure = runCleanupStep(failure, () -> cancelScheduledFuture(deadline));
        failure = runCleanupStep(failure, () -> closeRegistration(registration));
        if (finish.terminal.response == null) {
            failure = runCleanupStep(failure, () -> cancelInternalToken(run.internalCancellation));
            CompletionStage<AiResponse> delegateToCancel = delegate;
            failure = runCleanupStep(failure,
                    () -> cancelDelegateStageForCleanup(run, delegateToCancel));
        }
        rethrowBodyFailure(failure);
    }

    /** Restores the finite cleanup claim if this exact external stage refused/errored on cancel. */
    private void cancelDelegateStageForCleanup(
            Run run, CompletionStage<AiResponse> delegate) {
        if (delegate == null) {
            return;
        }
        try {
            cancelStage(delegate);
        } catch (Throwable failure) {
            synchronized (lock) {
                if (run.delegateStage == delegate) {
                    run.delegateStageCancellationClaimed = false;
                }
            }
            rethrowBodyFailure(failure);
        }
    }

    private boolean releaseCleanupGateLocked(Finish finish) {
        if (finish.cleanupGateOpen) {
            return false;
        }
        finish.cleanupGateOpen = true;
        cleanupGates--;
        if (cleanupGates < 0) {
            throw new IllegalStateException("cleanup gate count underflow");
        }
        pendingCleanupAcknowledgements = cleanupGates;
        if (!finish.publicationCommitted && !finish.publicationQueued
                && !finish.publicationRecoveryNeeded) {
            finish.publicationQueued = true;
            publicationGates++;
            pendingPublication.addLast(finish);
        }
        return claimPumpLocked();
    }

    private void beginPublication(Finish finish) {
        AiRequestSchedulerSupervisor.DispatchAttempt attempt;
        synchronized (lock) {
            if (finish.publicationDelivered || finish.deliveryAttempt != null) {
                return;
            }
            attempt = newAttemptLocked(AiRequestSchedulerSupervisor.Lane.COMPLETION_DELIVERY,
                    new AiRequestSchedulerSupervisor.DispatchCallbacks() {
                        @Override
                        public boolean started(
                                AiRequestSchedulerSupervisor.DispatchAttempt started) {
                            return publicationCommitted(finish, started);
                        }

                        @Override
                        public void startLost(
                                AiRequestSchedulerSupervisor.DispatchAttempt lost) {
                            publicationStartLost(finish, lost);
                        }

                        @Override
                        public void stalled(
                                AiRequestSchedulerSupervisor.DispatchAttempt stalled) {
                            publicationStalled(finish, stalled);
                        }

                        @Override
                        public void bodySucceeded(
                                AiRequestSchedulerSupervisor.DispatchAttempt succeeded) {
                            publicationFinished(finish, succeeded);
                        }

                        @Override
                        public void bodyFailed(
                                AiRequestSchedulerSupervisor.DispatchAttempt failed,
                                Throwable failure) {
                            publicationBodyFailed(finish, failed, failure);
                        }
                    });
            finish.deliveryAttempt = attempt;
        }
        supervisor.handoff(attempt, () -> publishFinishedRun(finish));
    }

    /** The delivery wrapper's commit point; no caller continuation has been invoked yet. */
    private boolean publicationCommitted(
            Finish finish, AiRequestSchedulerSupervisor.DispatchAttempt attempt) {
        boolean runPump = false;
        synchronized (lock) {
            if (finish.deliveryAttempt != attempt || finish.publicationDelivered) {
                return false;
            }
            if (!finish.publicationCommitted) {
                finish.publicationCommitted = true;
                publicationGates--;
                if (publicationGates < 0) {
                    throw new IllegalStateException("publication gate count underflow");
                }
            }
            activeCompletionDeliveryCount++;
            runPump = claimPumpIfWorkLocked();
        }
        if (runPump) {
            drainPump();
        }
        return true;
    }

    private void publicationStartLost(
            Finish finish, AiRequestSchedulerSupervisor.DispatchAttempt attempt) {
        synchronized (lock) {
            if (finish.deliveryAttempt != attempt || finish.publicationDelivered) {
                return;
            }
            finish.deliveryAttempt = null;
            finish.publicationRecoveryNeeded = true;
            rejectedDispatchCount++;
            dispatchDegraded = true;
            recordQuarantineLocked(finish.run, AiRequestSchedulerQuarantineKind.DISPATCH_START);
        }
    }

    private void publicationStalled(
            Finish finish, AiRequestSchedulerSupervisor.DispatchAttempt attempt) {
        synchronized (lock) {
            if (finish.deliveryAttempt != attempt || !finish.publicationCommitted) {
                return;
            }
            if (!finish.deliveryQuarantined) {
                finish.deliveryQuarantined = true;
                quarantinedCompletionDeliveryCount++;
                recordQuarantineLocked(finish.run,
                        AiRequestSchedulerQuarantineKind.COMPLETION_DELIVERY);
            }
            dispatchDegraded = true;
        }
    }

    private void publicationFinished(
            Finish finish, AiRequestSchedulerSupervisor.DispatchAttempt attempt) {
        boolean runPump = false;
        synchronized (lock) {
            if (finish.deliveryAttempt != attempt || !finish.publicationCommitted) {
                return;
            }
            activeCompletionDeliveryCount--;
            if (activeCompletionDeliveryCount < 0) {
                throw new IllegalStateException("completion delivery count underflow");
            }
            if (finish.deliveryQuarantined) {
                finish.deliveryQuarantined = false;
                quarantinedCompletionDeliveryCount--;
                if (quarantinedCompletionDeliveryCount < 0) {
                    throw new IllegalStateException("completion delivery quarantine underflow");
                }
            }
            finish.publicationDelivered = true;
            finish.publicationRecoveryNeeded = false;
            maybeRecoverDispatchLocked();
            maybeDiscardFinishLocked(finish);
            runPump = claimPumpIfWorkLocked();
        }
        if (runPump) {
            drainPump();
        }
        maybeCloseOwnedSupervisor();
    }

    /**
     * A completion body threw after its delivery commit. The public futures may already have been
     * completed (CompletableFuture completion is idempotent), but that does not prove the wrapper
     * acknowledged publication. Retain and retry it; the consumed publication gate remains
     * consumed so a recovery attempt cannot underflow it.
     */
    private void publicationBodyFailed(
            Finish finish,
            AiRequestSchedulerSupervisor.DispatchAttempt attempt,
            Throwable failure) {
        synchronized (lock) {
            if (finish.deliveryAttempt != attempt || !finish.publicationCommitted
                    || finish.publicationDelivered) {
                return;
            }
            finish.deliveryAttempt = null;
            activeCompletionDeliveryCount--;
            if (activeCompletionDeliveryCount < 0) {
                throw new IllegalStateException("completion delivery count underflow");
            }
            clearDeliveryPhysicalQuarantineLocked(finish);
            finish.publicationRecoveryNeeded = true;
            markDispatchFailureLocked(finish.run,
                    AiRequestSchedulerQuarantineKind.COMPLETION_DELIVERY);
        }
    }

    private void publishFinishedRun(Finish finish) {
        Run run = finish.run;
        AiRequestCancellationReceipt receipt = new AiRequestCancellationReceipt(
                run.request.request().requestId(),
                run.handleCancellationWon && finish.terminal.failure != null
                        && finish.terminal.failure.failureKind() == AiFailureKind.CANCELLED
                                ? AiRequestCancellationDisposition.CANCELLED
                                : AiRequestCancellationDisposition.ALREADY_TERMINAL);
        run.cancellationReceiptCompletion.complete(receipt);
        if (finish.terminal.response == null) {
            run.responseCompletion.completeExceptionally(finish.terminal.failure);
        } else {
            run.responseCompletion.complete(finish.terminal.response);
        }
    }

    /**
     * Queues one of the only three post-terminal external cleanup operations.
     *
     * <p>The kind is retained on the run before the broker handoff. Repeated race paths for the
     * same resource therefore coalesce behind the first attempt instead of losing the operation
     * or growing an unbounded detached-cleanup list.</p>
     */
    private void scheduleDetachedCleanup(
            Run run, DetachedCleanupKind kind, Runnable action) {
        DetachedCleanup cleanup;
        boolean runPump = false;
        synchronized (lock) {
            if (!run.detachedCleanupKinds.add(Objects.requireNonNull(kind, "kind"))) {
                return;
            }
            cleanup = new DetachedCleanup(run, Objects.requireNonNull(action, "action"));
            outstandingDetachedCleanupCount++;
            cleanup.queued = true;
            pendingDetachedCleanup.addLast(cleanup);
            runPump = claimPumpLocked();
        }
        if (runPump) {
            drainPump();
        }
    }

    private void beginDetachedCleanup(DetachedCleanup cleanup) {
        AiRequestSchedulerSupervisor.DispatchAttempt attempt;
        synchronized (lock) {
            if (cleanup.done || cleanup.attempt != null) {
                return;
            }
            attempt = newAttemptLocked(AiRequestSchedulerSupervisor.Lane.TERMINAL_CLEANUP,
                    new AiRequestSchedulerSupervisor.DispatchCallbacks() {
                        @Override
                        public boolean started(
                                AiRequestSchedulerSupervisor.DispatchAttempt started) {
                            synchronized (lock) {
                                return cleanup.attempt == started && !cleanup.done;
                            }
                        }

                        @Override
                        public void startLost(
                                AiRequestSchedulerSupervisor.DispatchAttempt lost) {
                            detachedStartLost(cleanup, lost);
                        }

                        @Override
                        public void stalled(
                                AiRequestSchedulerSupervisor.DispatchAttempt stalled) {
                            detachedStalled(cleanup, stalled);
                        }

                        @Override
                        public void bodySucceeded(
                                AiRequestSchedulerSupervisor.DispatchAttempt succeeded) {
                            detachedFinished(cleanup, succeeded);
                        }

                        @Override
                        public void bodyFailed(
                                AiRequestSchedulerSupervisor.DispatchAttempt failed,
                                Throwable failure) {
                            detachedBodyFailed(cleanup, failed, failure);
                        }
                    });
            cleanup.attempt = attempt;
        }
        supervisor.handoff(attempt, cleanup.action);
    }

    private void detachedStartLost(
            DetachedCleanup cleanup, AiRequestSchedulerSupervisor.DispatchAttempt attempt) {
        synchronized (lock) {
            if (cleanup.attempt != attempt || cleanup.done) {
                return;
            }
            cleanup.attempt = null;
            cleanup.recoveryNeeded = true;
            if (!retainedDetachedCleanup.contains(cleanup)) {
                retainedDetachedCleanup.addLast(cleanup);
            }
            markDetachedQuarantinedLocked(cleanup, AiRequestSchedulerQuarantineKind.DISPATCH_START,
                    false);
            dispatchDegraded = true;
        }
    }

    private void detachedStalled(
            DetachedCleanup cleanup, AiRequestSchedulerSupervisor.DispatchAttempt attempt) {
        synchronized (lock) {
            if (cleanup.attempt != attempt || cleanup.done) {
                return;
            }
            markDetachedQuarantinedLocked(cleanup,
                    AiRequestSchedulerQuarantineKind.TERMINAL_CLEANUP, true);
            dispatchDegraded = true;
        }
    }

    private void detachedFinished(
            DetachedCleanup cleanup, AiRequestSchedulerSupervisor.DispatchAttempt attempt) {
        boolean runPump = false;
        synchronized (lock) {
            if (cleanup.attempt != attempt || cleanup.done) {
                return;
            }
            cleanup.done = true;
            cleanup.recoveryNeeded = false;
            retainedDetachedCleanup.remove(cleanup);
            outstandingDetachedCleanupCount--;
            if (outstandingDetachedCleanupCount < 0) {
                throw new IllegalStateException("detached cleanup count underflow");
            }
            if (cleanup.physicalQuarantined) {
                cleanup.physicalQuarantined = false;
                quarantinedPhysicalCleanupCount--;
                if (quarantinedPhysicalCleanupCount < 0) {
                    throw new IllegalStateException("physical detached cleanup quarantine underflow");
                }
            }
            if (cleanup.quarantined) {
                cleanup.quarantined = false;
                quarantinedCleanupCount--;
                if (quarantinedCleanupCount < 0) {
                    throw new IllegalStateException("detached cleanup quarantine underflow");
                }
            }
            maybeRecoverDispatchLocked();
            runPump = claimPumpIfWorkLocked();
        }
        if (runPump) {
            drainPump();
        }
        maybeCloseOwnedSupervisor();
    }

    /** A detached cleanup Error is retained exactly like a lost wrapper, never acknowledged. */
    private void detachedBodyFailed(
            DetachedCleanup cleanup,
            AiRequestSchedulerSupervisor.DispatchAttempt attempt,
            Throwable failure) {
        synchronized (lock) {
            if (cleanup.attempt != attempt || cleanup.done) {
                return;
            }
            cleanup.attempt = null;
            cleanup.recoveryNeeded = true;
            if (!retainedDetachedCleanup.contains(cleanup)) {
                retainedDetachedCleanup.addLast(cleanup);
            }
            clearDetachedPhysicalQuarantineLocked(cleanup);
            markDetachedQuarantinedLocked(cleanup,
                    AiRequestSchedulerQuarantineKind.TERMINAL_CLEANUP, false);
            dispatchDegraded = true;
        }
    }

    private AiRequestSchedulerSupervisor.DispatchAttempt newAttemptLocked(
            AiRequestSchedulerSupervisor.Lane lane,
            AiRequestSchedulerSupervisor.DispatchCallbacks callbacks) {
        long attemptId = nextAttemptId++;
        if (attemptId <= 0L) {
            throw new IllegalStateException("scheduler attempt ID overflow");
        }
        return new AiRequestSchedulerSupervisor.DispatchAttempt(attemptId, lane, callbacks);
    }

    private void reserveEligibleStartsLocked() {
        if (closed || dispatchDegraded || cleanupGates > 0 || publicationGates > 0) {
            return;
        }
        while (inFlightCount < policy.maximumGlobalInFlight()) {
            Run next = removeNextEligibleLocked();
            if (next == null) {
                return;
            }
            next.inFlight = true;
            inFlightCount++;
            increment(inFlightByBot, next.request.botId());
            next.health = next.health.inFlight(safeNow(next.health.observedAt()));
            recordHealthLocked(next.health);
            pendingStarts.addLast(next);
        }
    }

    private Run removeNextEligibleLocked() {
        Iterator<Run> iterator = queued.iterator();
        /* A later request for one bot must not overtake its earlier token-setup handoff. */
        java.util.Set<UUID> blockedBots = new HashSet<>();
        while (iterator.hasNext()) {
            Run candidate = iterator.next();
            if (candidate.terminal) {
                iterator.remove();
                decrement(queuedByBot, candidate.request.botId());
                candidate.queued = false;
                continue;
            }
            if (!candidate.cancellationReady) {
                blockedBots.add(candidate.request.botId());
                continue;
            }
            if (blockedBots.contains(candidate.request.botId())) {
                continue;
            }
            if (count(inFlightByBot, candidate.request.botId())
                    >= policy.maximumInFlightPerBot()) {
                continue;
            }
            iterator.remove();
            decrement(queuedByBot, candidate.request.botId());
            candidate.queued = false;
            return candidate;
        }
        return null;
    }

    private void markCleanupQuarantinedLocked(
            Finish finish, AiRequestSchedulerQuarantineKind kind, boolean physical) {
        boolean wasQuarantined = finish.cleanupQuarantined;
        if (!wasQuarantined) {
            finish.cleanupQuarantined = true;
            quarantinedCleanupCount++;
            recordQuarantineLocked(finish.run, kind);
        }
        if (physical && !finish.cleanupPhysicalQuarantined) {
            finish.cleanupPhysicalQuarantined = true;
            quarantinedPhysicalCleanupCount++;
            if (wasQuarantined) {
                recordQuarantineLocked(finish.run, kind);
            }
        }
    }

    private void markDetachedQuarantinedLocked(
            DetachedCleanup cleanup, AiRequestSchedulerQuarantineKind kind, boolean physical) {
        boolean wasQuarantined = cleanup.quarantined;
        if (!wasQuarantined) {
            cleanup.quarantined = true;
            quarantinedCleanupCount++;
            recordQuarantineLocked(cleanup.run, kind);
        }
        if (physical && !cleanup.physicalQuarantined) {
            cleanup.physicalQuarantined = true;
            quarantinedPhysicalCleanupCount++;
            if (wasQuarantined) {
                recordQuarantineLocked(cleanup.run, kind);
            }
        }
    }

    private void clearCleanupPhysicalQuarantineLocked(Finish finish) {
        if (finish.cleanupPhysicalQuarantined) {
            finish.cleanupPhysicalQuarantined = false;
            quarantinedPhysicalCleanupCount--;
            if (quarantinedPhysicalCleanupCount < 0) {
                throw new IllegalStateException("physical cleanup quarantine count underflow");
            }
        }
    }

    private void clearDeliveryPhysicalQuarantineLocked(Finish finish) {
        if (finish.deliveryQuarantined) {
            finish.deliveryQuarantined = false;
            quarantinedCompletionDeliveryCount--;
            if (quarantinedCompletionDeliveryCount < 0) {
                throw new IllegalStateException("completion delivery quarantine underflow");
            }
        }
    }

    private void clearDetachedPhysicalQuarantineLocked(DetachedCleanup cleanup) {
        if (cleanup.physicalQuarantined) {
            cleanup.physicalQuarantined = false;
            quarantinedPhysicalCleanupCount--;
            if (quarantinedPhysicalCleanupCount < 0) {
                throw new IllegalStateException("physical detached cleanup quarantine underflow");
            }
        }
    }

    private void markDispatchFailure(
            Run run, AiRequestSchedulerQuarantineKind kind) {
        synchronized (lock) {
            markDispatchFailureLocked(run, kind);
        }
    }

    private void markDispatchFailureLocked(
            Run run, AiRequestSchedulerQuarantineKind kind) {
        rejectedDispatchCount++;
        dispatchDegraded = true;
        recordQuarantineLocked(run, kind);
    }

    private void maybeRecoverDispatchLocked() {
        if (physicalQuarantineCountLocked() == 0 && pendingRecoveryDispatchCountLocked() == 0) {
            dispatchDegraded = false;
            reserveEligibleStartsLocked();
        }
    }

    private int physicalQuarantineCountLocked() {
        return quarantinedPhysicalCleanupCount + quarantinedProviderInvocationCount
                + quarantinedTokenSetupCount
                + quarantinedCompletionDeliveryCount;
    }

    private int pendingRecoveryDispatchCountLocked() {
        int pending = 0;
        for (Finish finish : finishingByRequest.values()) {
            if (finish.cleanupRecoveryNeeded) {
                pending++;
            }
            if (finish.publicationRecoveryNeeded) {
                pending++;
            }
        }
        for (DetachedCleanup cleanup : retainedDetachedCleanup) {
            if (cleanup.recoveryNeeded) {
                pending++;
            }
        }
        return pending;
    }

    private boolean claimPumpIfWorkLocked() {
        if (!hasPumpWorkLocked()) {
            return false;
        }
        return claimPumpLocked();
    }

    private boolean hasPumpWorkLocked() {
        return !pendingDetachedCleanup.isEmpty()
                || !pendingCleanup.isEmpty()
                || (cleanupGates == 0 && !pendingPublication.isEmpty())
                || (!dispatchDegraded && cleanupGates == 0 && publicationGates == 0
                        && (!pendingStarts.isEmpty() || !queued.isEmpty()));
    }

    private boolean claimPumpLocked() {
        if (pumpRunning) {
            return false;
        }
        pumpRunning = true;
        return true;
    }

    private void maybeDiscardFinishLocked(Finish finish) {
        if (!finish.publicationDelivered || !finish.cleanupCompleted
                || finish.cleanupQuarantined || finish.cleanupRecoveryNeeded
                || finish.publicationRecoveryNeeded || finish.deliveryQuarantined) {
            return;
        }
        finishingByRequest.remove(finish.run.request.request().requestId(), finish);
    }

    private void maybeCloseOwnedSupervisor() {
        if (!ownsSupervisor) {
            return;
        }
        boolean closeSupervisor;
        synchronized (lock) {
            closeSupervisor = closed && liveByRequest.isEmpty()
                    && finishingByRequest.isEmpty()
                    && retainedDetachedCleanup.isEmpty()
                    && pendingDetachedCleanup.isEmpty()
                    && outstandingDetachedCleanupCount == 0;
        }
        if (closeSupervisor) {
            supervisor.close();
        }
    }

    private List<Finish> expireDueRunsLocked(Instant observedAt) {
        List<Finish> finishes = new ArrayList<>();
        for (Run run : List.copyOf(liveByRequest.values())) {
            if (!run.terminal && !observedAt.isBefore(run.deadline)) {
                Finish finish = terminateLocked(run, Terminal.failure(
                        AiProviderException.of(AiFailureKind.TIMEOUT)));
                if (finish != null) {
                    finishes.add(finish);
                }
            }
        }
        return finishes;
    }

    private AiProviderException rejectImmediatelyLocked(
            AiScheduledRequest request, AiProviderException failure, Instant observedAt) {
        AiScheduledRequestHealth health = AiScheduledRequestHealth.queued(
                request, providerId, observedAt).rejected(failure, observedAt);
        recordHealthLocked(health);
        terminalHistory.addLast(request.request().requestId());
        trimTerminalHistoryLocked();
        return failure;
    }

    private void recordHealthLocked(AiScheduledRequestHealth health) {
        healthByRequest.put(health.requestId(), health);
    }

    private void recordQuarantineLocked(
            Run run, AiRequestSchedulerQuarantineKind kind) {
        AiRequestScheduledHealthData data = healthData(run);
        quarantineHistory.addLast(new AiRequestSchedulerQuarantine(
                data.requestId, data.failureKind, data.reasonCode, kind, data.observedAt));
        while (quarantineHistory.size() > MAX_HEALTH_HISTORY) {
            quarantineHistory.removeFirst();
        }
    }

    private static AiRequestScheduledHealthData healthData(Run run) {
        AiScheduledRequestHealth health = run.health;
        return new AiRequestScheduledHealthData(
                health.requestId(), health.failureKind().orElse(AiFailureKind.UNKNOWN),
                health.reasonCode().orElse(AiReasonCode.UNKNOWN), health.observedAt());
    }

    private void trimTerminalHistoryLocked() {
        while (terminalHistory.size() > MAX_HEALTH_HISTORY) {
            UUID expired = terminalHistory.removeFirst();
            if (!liveByRequest.containsKey(expired) && !finishingByRequest.containsKey(expired)) {
                healthByRequest.remove(expired);
            }
        }
    }

    private Instant now() {
        return AiChecks.instant(clock.instant(), "clock instant");
    }

    private Instant safeNow(Instant fallback) {
        try {
            return now();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static Instant deadlineAt(Instant submittedAt, long timeoutMillis) {
        try {
            return submittedAt.plusMillis(timeoutMillis);
        } catch (DateTimeException | ArithmeticException exception) {
            throw new IllegalArgumentException("request deadline is out of range", exception);
        }
    }

    private static long roundedUpMillis(Duration duration) {
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("deadline must be in the future");
        }
        long wholeMillis = duration.toMillis();
        return duration.minusMillis(wholeMillis).isZero()
                ? Math.max(1L, wholeMillis)
                : Math.addExact(wholeMillis, 1L);
    }

    private Terminal deadlineFailure(Run run) {
        try {
            return now().isBefore(run.deadline) ? null
                    : Terminal.failure(AiProviderException.of(AiFailureKind.TIMEOUT));
        } catch (RuntimeException ignored) {
            return Terminal.failure(AiProviderException.of(AiFailureKind.UNAVAILABLE,
                    AiReasonCode.DEADLINE_OUT_OF_RANGE));
        }
    }

    private AiProviderException validateResponse(Run run, AiResponse response) {
        if (!response.requestId().equals(run.request.request().requestId())
                || !response.providerId().equals(providerId)
                || !response.model().equals(run.request.request().model())) {
            return AiProviderException.of(AiFailureKind.MALFORMED_RESPONSE);
        }
        return null;
    }

    /**
     * Runs every finite cleanup step even when a preceding extension throws a nonfatal Error.
     * The first failure is rethrown from the lane body afterwards, so DispatchAttempt records
     * non-ACK recovery instead of treating this wrapper's finally as successful cleanup.
     */
    private static Throwable runCleanupStep(Throwable priorFailure, Runnable action) {
        try {
            Objects.requireNonNull(action, "action").run();
            return priorFailure;
        } catch (Throwable failure) {
            if (AiRequestSchedulerSupervisor.DispatchAttempt.isFatal(failure)) {
                rethrowBodyFailure(failure);
            }
            if (priorFailure != null && priorFailure != failure) {
                try {
                    priorFailure.addSuppressed(failure);
                } catch (RuntimeException ignored) {
                    // A hostile Throwable must not turn a later cleanup step into an ACK.
                }
                return priorFailure;
            }
            return failure;
        }
    }

    private static void rethrowBodyFailure(Throwable failure) {
        if (failure == null) {
            return;
        }
        AiRequestScheduler.<RuntimeException>throwUnchecked(failure);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void throwUnchecked(Throwable failure) throws T {
        throw (T) failure;
    }

    private static void cancelInternalToken(CancellationTokenSource source) {
        Objects.requireNonNull(source, "source").cancel();
    }

    private static void cancelScheduledFuture(ScheduledFuture<?> future) {
        if (future != null) {
            future.cancel(false);
        }
    }

    private static void closeRegistration(CancellationToken.ListenerRegistration registration) {
        if (registration != null) {
            registration.close();
        }
    }

    private static void cancelStage(CompletionStage<?> stage) {
        if (stage != null) {
            stage.toCompletableFuture().cancel(true);
        }
    }

    private static CancellationObservation observeCancellation(CancellationToken token) {
        return Objects.requireNonNull(token, "token").isCancellationRequested()
                ? CancellationObservation.CANCELLED : CancellationObservation.ACTIVE;
    }

    private static int count(Map<UUID, Integer> values, UUID key) {
        return values.getOrDefault(key, 0);
    }

    private static void increment(Map<UUID, Integer> values, UUID key) {
        values.merge(key, 1, Math::addExact);
    }

    private static void decrement(Map<UUID, Integer> values, UUID key) {
        Integer current = values.get(key);
        if (current == null || current < 1) {
            throw new IllegalStateException("scheduler count underflow");
        }
        if (current == 1) {
            values.remove(key);
        } else {
            values.put(key, current - 1);
        }
    }

    private static CompletionStage<AiResponse> minimalResponse(
            CompletableFuture<AiResponse> completion) {
        return completion.minimalCompletionStage();
    }

    private static AiScheduledRequestHandle rejectedHandle(
            UUID requestId, AiProviderException failure) {
        CompletableFuture<AiResponse> response = CompletableFuture.failedFuture(
                Objects.requireNonNull(failure, "failure"));
        CompletableFuture<AiRequestCancellationReceipt> cancellation =
                CompletableFuture.completedFuture(new AiRequestCancellationReceipt(
                        requestId, AiRequestCancellationDisposition.ALREADY_TERMINAL));
        return new AiScheduledRequestHandle(requestId, response.minimalCompletionStage(),
                cancellation::minimalCompletionStage);
    }

    /** Must run under {@link #lock}; exactly one cleanup lane claims the returned stage. */
    private static CompletionStage<AiResponse> claimDelegateStageCancellationLocked(Run run) {
        if (run.delegateStageCancellationClaimed || run.delegateStage == null) {
            return null;
        }
        run.delegateStageCancellationClaimed = true;
        return run.delegateStage;
    }

    /** Must run under {@link #lock}; only the body ACK is allowed to activate this bridge. */
    private static DeferredCancellation takeCancellationAttachmentLocked(Run run) {
        DeferredCancellation attachment = run.cancellationAttachment;
        run.cancellationAttachment = null;
        return attachment;
    }

    /** Must run under {@link #lock}; only the Provider body ACK is allowed to activate this. */
    private static DeferredCompletion takeCompletionAttachmentLocked(Run run) {
        DeferredCompletion attachment = run.completionAttachment;
        run.completionAttachment = null;
        return attachment;
    }

    private static void discard(DeferredCancellation attachment) {
        if (attachment != null) {
            attachment.discard();
        }
    }

    private static void discard(DeferredCompletion attachment) {
        if (attachment != null) {
            attachment.discard();
        }
    }

    private static final class Run {
        private final AiScheduledRequest request;
        private final CancellationToken externalToken;
        private final CancellationTokenSource internalCancellation =
                new CancellationTokenSource();
        private final CompletableFuture<AiResponse> responseCompletion = new CompletableFuture<>();
        private final CompletableFuture<AiRequestCancellationReceipt> cancellationReceiptCompletion =
                new CompletableFuture<>();
        private final CompletionStage<AiResponse> responseView = minimalResponse(responseCompletion);
        private final CompletionStage<AiRequestCancellationReceipt> cancellationReceiptView =
                cancellationReceiptCompletion.minimalCompletionStage();
        private final Instant deadline;
        private AiScheduledRequestHealth health;
        private AiScheduledRequestHandle handle;
        private ScheduledFuture<?> deadlineTask;
        private CancellationToken.ListenerRegistration externalRegistration;
        private CompletionStage<AiResponse> delegateStage;
        private AiRequestSchedulerSupervisor.DispatchAttempt tokenAttempt;
        private AiRequestSchedulerSupervisor.DispatchAttempt providerAttempt;
        /* Provisional external callbacks which have not yet crossed their lane-body ACK. */
        private DeferredCancellation cancellationAttachment;
        private DeferredCompletion completionAttachment;
        private boolean queued = true;
        private boolean cancellationReady;
        private boolean inFlight;
        private boolean tokenSetupActive;
        private boolean tokenSetupQuarantined;
        private boolean providerStartCommitted;
        private boolean providerInvocationActive;
        private boolean providerInvocationQuarantined;
        private boolean delegateStageCancellationClaimed;
        private boolean handleCancellationWon;
        private boolean terminal;
        /* Each resource has exactly one post-terminal cleanup slot. */
        private final EnumSet<DetachedCleanupKind> detachedCleanupKinds =
                EnumSet.noneOf(DetachedCleanupKind.class);

        private Run(
                AiScheduledRequest request,
                CancellationToken externalToken,
                String providerId,
                Instant observedAt,
                Instant deadline) {
            this.request = request;
            this.externalToken = externalToken;
            this.deadline = Objects.requireNonNull(deadline, "deadline");
            health = AiScheduledRequestHealth.queued(request, providerId, observedAt);
        }
    }

    /** The finite post-terminal resources which can outlive normal finish cleanup. */
    private enum DetachedCleanupKind {
        CANCEL_DEADLINE,
        CLOSE_REGISTRATION,
        CANCEL_STAGE
    }

    private static final class Finish {
        private final Run run;
        private final Terminal terminal;
        private AiRequestSchedulerSupervisor.DispatchAttempt cleanupAttempt;
        private AiRequestSchedulerSupervisor.DispatchAttempt deliveryAttempt;
        private boolean cleanupQueued;
        private boolean cleanupGateOpen;
        private boolean cleanupCompleted;
        private boolean cleanupQuarantined;
        private boolean cleanupPhysicalQuarantined;
        private boolean cleanupRecoveryNeeded;
        private boolean publicationQueued;
        private boolean publicationCommitted;
        private boolean publicationDelivered;
        private boolean publicationRecoveryNeeded;
        private boolean deliveryQuarantined;

        private Finish(Run run, Terminal terminal) {
            this.run = Objects.requireNonNull(run, "run");
            this.terminal = Objects.requireNonNull(terminal, "terminal");
        }
    }

    private static final class DetachedCleanup {
        private final Run run;
        private final Runnable action;
        private AiRequestSchedulerSupervisor.DispatchAttempt attempt;
        private boolean queued;
        private boolean quarantined;
        private boolean physicalQuarantined;
        private boolean recoveryNeeded;
        private boolean done;

        private DetachedCleanup(Run run, Runnable action) {
            this.run = Objects.requireNonNull(run, "run");
            this.action = Objects.requireNonNull(action, "action");
        }
    }

    /**
     * Buffers an external cancellation callback until the registration returned normally and the
     * exact token lane body has acknowledged its successful return. The first callback wins; a
     * failed attachment or body discards any provisional callback before it can terminalize the
     * run.
     */
    private static final class DeferredCancellation {
        private AttachmentState state = AttachmentState.ATTACHING;
        private boolean callbackObserved;

        private synchronized boolean recordCallback() {
            if (state == AttachmentState.DISCARDED || callbackObserved) {
                return false;
            }
            callbackObserved = true;
            return state == AttachmentState.ACTIVE;
        }

        private synchronized void recordObservedCancellation() {
            if (state != AttachmentState.DISCARDED && !callbackObserved) {
                callbackObserved = true;
            }
        }

        private synchronized boolean attachmentReturnedNormally() {
            if (state != AttachmentState.ATTACHING) {
                return false;
            }
            state = AttachmentState.PENDING_BODY_ACK;
            return true;
        }

        private synchronized CancellationAttachmentAck activateAfterBodyAck() {
            if (state != AttachmentState.PENDING_BODY_ACK) {
                return CancellationAttachmentAck.INVALID;
            }
            state = AttachmentState.ACTIVE;
            return callbackObserved ? CancellationAttachmentAck.CANCELLED
                    : CancellationAttachmentAck.ACTIVE;
        }

        private synchronized void discard() {
            state = AttachmentState.DISCARDED;
        }
    }

    /**
     * Buffers an external CompletionStage callback until whenComplete itself returned and the
     * exact Provider lane body acknowledged success. A stage which invokes the callback and then
     * throws therefore cannot publish a provisional success/failure before the Provider-start
     * attempt fails closed.
     */
    private static final class DeferredCompletion {
        private AttachmentState state = AttachmentState.ATTACHING;
        private CompletionSignal signal;
        private boolean callbackObserved;

        private synchronized CompletionSignal recordCallback(
                AiResponse response, Throwable throwable) {
            if (state == AttachmentState.DISCARDED || callbackObserved) {
                return null;
            }
            callbackObserved = true;
            signal = new CompletionSignal(response, throwable);
            if (state != AttachmentState.ACTIVE) {
                return null;
            }
            CompletionSignal committed = signal;
            signal = null;
            return committed;
        }

        private synchronized boolean attachmentReturnedNormally() {
            if (state != AttachmentState.ATTACHING) {
                return false;
            }
            state = AttachmentState.PENDING_BODY_ACK;
            return true;
        }

        private synchronized CompletionAttachmentAck activateAfterBodyAck() {
            if (state != AttachmentState.PENDING_BODY_ACK) {
                return CompletionAttachmentAck.INVALID;
            }
            state = AttachmentState.ACTIVE;
            CompletionSignal committed = signal;
            signal = null;
            return new CompletionAttachmentAck(true, committed);
        }

        private synchronized void discard() {
            state = AttachmentState.DISCARDED;
            signal = null;
        }
    }

    /** Attachment return and lane-body acknowledgement are separate linearization points. */
    private enum AttachmentState {
        ATTACHING,
        PENDING_BODY_ACK,
        ACTIVE,
        DISCARDED
    }

    /** A cancellation bridge can only become active after its exact token body acknowledged. */
    private enum CancellationAttachmentAck {
        ACTIVE,
        CANCELLED,
        INVALID
    }

    /** Keeps a missing/invalid Provider bridge distinct from a valid bridge with no signal. */
    private record CompletionAttachmentAck(boolean acknowledged, CompletionSignal signal) {
        private static final CompletionAttachmentAck INVALID =
                new CompletionAttachmentAck(false, null);
    }

    private record CompletionSignal(AiResponse response, Throwable throwable) {
    }

    private record Terminal(AiResponse response, AiProviderException failure) {
        private static Terminal success(AiResponse response) {
            return new Terminal(response, null);
        }

        private static Terminal failure(AiProviderException failure) {
            return new Terminal(null, failure);
        }

        private static Terminal cancelled() {
            return failure(AiProviderException.of(AiFailureKind.CANCELLED));
        }
    }

    private enum CancellationObservation {
        ACTIVE,
        CANCELLED
    }

    private record AiRequestScheduledHealthData(
            UUID requestId,
            AiFailureKind failureKind,
            AiReasonCode reasonCode,
            Instant observedAt) {
    }
}
