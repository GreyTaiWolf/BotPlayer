package io.github.greytaiwolf.botplayer.ai;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 仅依赖 DTO 的异步、有限 Provider 重试包装器。
 *
 * <p>没有默认线程池：调用者必须显式提供受生命周期管理的 scheduler。所有重试都复用同一
 * requestId，受 request timeout、{@link AiRetryPolicy}、熔断 permit 和最大并发数共同限制。
 * deadline、调用者撤销或外部 token 取消都会尽力中断当前 delegate stage；撤销不记为
 * Provider 健康退化。本类不接触 Minecraft、网络实现、凭据或 P5 runtime。
 */
public final class RetryingAiProvider implements AiProvider {
    public static final int MAX_HEALTH_HISTORY = 512;

    private final String providerId;
    private final AiProvider delegate;
    private final AiCircuitBreaker circuitBreaker;
    private final AiRetryPolicy retryPolicy;
    private final ScheduledExecutorService scheduler;
    private final Clock clock;
    private final ConcurrentHashMap<UUID, AiRequestHealth> requestHealth =
            new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<UUID> terminalHistory =
            new ConcurrentLinkedDeque<>();
    private final AtomicInteger inFlightRequests = new AtomicInteger();
    private final AtomicReference<ProviderHealth> latestFailureHealth =
            new AtomicReference<>();

    public RetryingAiProvider(
            String providerId,
            AiProvider delegate,
            AiCircuitBreaker circuitBreaker,
            AiRetryPolicy retryPolicy,
            ScheduledExecutorService scheduler,
            Clock clock) {
        this.providerId = AiChecks.providerId(providerId, "providerId");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.circuitBreaker = Objects.requireNonNull(
                circuitBreaker, "circuitBreaker");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
        AiChecks.instant(clock.instant(), "clock instant");
    }

    @Override
    public CompletionStage<AiResponse> complete(
            AiRequest request, CancellationToken token) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(token, "token");
        if (token.isCancellationRequested()) {
            return CompletableFuture.failedFuture(
                    AiProviderException.of(AiFailureKind.CANCELLED));
        }
        Instant observedAt;
        Instant deadline;
        try {
            observedAt = now();
            deadline = plus(
                    observedAt,
                    Duration.ofMillis(request.options().timeoutMillis()));
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(AiProviderException.of(
                    AiFailureKind.UNAVAILABLE,
                    AiReasonCode.DEADLINE_OUT_OF_RANGE));
        }
        if (!reserveRequestSlot()) {
            return CompletableFuture.failedFuture(
                    AiProviderException.of(AiFailureKind.OVERLOADED));
        }
        AiRequestHealth initial = AiRequestHealth.queued(
                request, providerId, observedAt);
        if (requestHealth.putIfAbsent(request.requestId(), initial) != null) {
            releaseRequestSlot();
            return CompletableFuture.failedFuture(
                    AiProviderException.of(AiFailureKind.INVALID_REQUEST));
        }
        RequestRun run = new RequestRun(request, token, initial, deadline);
        run.start();
        return run.completion();
    }

    @Override
    public CompletionStage<AiCapabilities> probeCapabilities() {
        CompletionStage<AiCapabilities> delegateStage;
        try {
            delegateStage = delegate.probeCapabilities();
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(
                    AiProviderException.fromThrowable(exception));
        }
        if (delegateStage == null) {
            return CompletableFuture.failedFuture(
                    AiProviderException.of(AiFailureKind.MALFORMED_RESPONSE));
        }
        CompletableFuture<AiCapabilities> result = new CompletableFuture<>();
        try {
            delegateStage.whenComplete((capabilities, throwable) -> {
                if (throwable != null) {
                    result.completeExceptionally(
                            AiProviderException.fromThrowable(throwable));
                    return;
                }
                if (capabilities == null
                        || !capabilities.providerId().equals(providerId)) {
                    result.completeExceptionally(AiProviderException.of(
                            AiFailureKind.MALFORMED_RESPONSE));
                    return;
                }
                result.complete(capabilities);
            });
        } catch (RuntimeException exception) {
            result.completeExceptionally(AiProviderException.of(
                    AiFailureKind.UNKNOWN,
                    AiReasonCode.CALLBACK_ATTACHMENT_FAILED));
        }
        return result;
    }

    @Override
    public ProviderHealth health() {
        Instant observedAt = now();
        ProviderHealth circuitHealth = circuitBreaker.health(
                providerId, observedAt);
        ProviderHealth remembered = rememberedFailure(observedAt);
        if (!circuitHealth.acceptingRequests()
                || circuitHealth.reasonCode().equals(Optional.of(
                AiReasonCode.CIRCUIT_HALF_OPEN.wireCode()))) {
            if (remembered != null) {
                return mergeRememberedFailure(
                        remembered, circuitHealth, observedAt);
            }
            return circuitHealth;
        }
        if (remembered != null) {
            return remembered;
        }
        if (circuitHealth.state() != ProviderHealthState.HEALTHY) {
            return circuitHealth;
        }
        try {
            ProviderHealth delegateHealth = delegate.health();
            if (delegateHealth == null
                    || !delegateHealth.providerId().equals(providerId)) {
                return new ProviderHealth(
                        providerId,
                        ProviderHealthState.DEGRADED,
                        observedAt,
                        Optional.empty(),
                        Optional.of(AiReasonCode.DELEGATE_HEALTH_MISMATCH
                                .wireCode()));
            }
            return delegateHealth;
        } catch (RuntimeException exception) {
            return new ProviderHealth(
                    providerId,
                    ProviderHealthState.UNAVAILABLE,
                    observedAt,
                    Optional.empty(),
                    Optional.of(AiReasonCode.DELEGATE_HEALTH_FAILURE
                            .wireCode()));
        }
    }

    /**
     * 查询有界 request 健康历史；正文、异常和凭据不在此模型中。
     */
    public Optional<AiRequestHealth> requestHealth(UUID requestId) {
        AiChecks.requireNonZero(requestId, "requestId");
        return Optional.ofNullable(requestHealth.get(requestId));
    }

    private boolean reserveRequestSlot() {
        while (true) {
            int current = inFlightRequests.get();
            if (current >= retryPolicy.maximumInFlightRequests()) {
                return false;
            }
            if (inFlightRequests.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    private void releaseRequestSlot() {
        int remaining = inFlightRequests.decrementAndGet();
        if (remaining < 0) {
            throw new IllegalStateException("request slot underflow");
        }
    }

    private void recordHealth(AiRequestHealth health) {
        requestHealth.put(health.requestId(), health);
    }

    private void rememberFailure(
            AiProviderException exception, Instant observedAt) {
        if (exception.failureKind() == AiFailureKind.CANCELLED) {
            // 撤销属于调用生命周期，不代表 Provider 健康退化。
            return;
        }
        Optional<Instant> retryAfter = exception.retryAfter()
                .filter(value -> !value.isBefore(observedAt));
        latestFailureHealth.set(new ProviderHealth(
                providerId,
                exception.failureKind().healthState(),
                observedAt,
                retryAfter,
                Optional.of(exception.reasonCode().wireCode())));
    }

    /**
     * 到达明确的 rate-limit 重试时刻后不再永久缓存旧状态；其他失败须由后续成功覆盖。
     */
    private ProviderHealth rememberedFailure(Instant observedAt) {
        ProviderHealth remembered = latestFailureHealth.get();
        if (remembered == null || remembered.retryAfter().isEmpty()) {
            return remembered;
        }
        Instant retryAfter = remembered.retryAfter().orElseThrow();
        if (!retryAfter.isBefore(observedAt)) {
            return remembered;
        }
        latestFailureHealth.compareAndSet(remembered, null);
        return null;
    }

    /**
     * 熔断拒绝不能抹去已经收到的 RATE_LIMITED/UNAVAILABLE 及其 retryAfter。
     */
    private ProviderHealth mergeRememberedFailure(
            ProviderHealth remembered,
            ProviderHealth circuitHealth,
            Instant observedAt) {
        Optional<Instant> retryAfter = remembered.retryAfter().isPresent()
                ? remembered.retryAfter()
                : circuitHealth.retryAfter();
        return new ProviderHealth(
                providerId,
                remembered.state(),
                observedAt,
                retryAfter,
                remembered.reasonCode());
    }

    private void retainTerminalHealth(UUID requestId) {
        terminalHistory.addLast(requestId);
        while (terminalHistory.size() > MAX_HEALTH_HISTORY) {
            UUID evicted = terminalHistory.pollFirst();
            if (evicted != null) {
                requestHealth.remove(evicted);
            }
        }
    }

    private Instant now() {
        return AiChecks.instant(clock.instant(), "clock instant");
    }

    private final class RequestRun {
        private final AiRequest request;
        private final CancellationToken cancellationToken;
        private final Instant deadline;
        private final CompletableFuture<AiResponse> completion =
                new CompletableFuture<>();
        private final AtomicBoolean terminal = new AtomicBoolean();
        private final CancellationTokenSource delegateCancellation =
                new CancellationTokenSource();

        private AiRequestHealth health;
        private AiCircuitPermit activePermit;
        private CompletionStage<AiResponse> activeDelegateStage;
        private ScheduledFuture<?> deadlineFuture;
        private ScheduledFuture<?> retryFuture;
        private CancellationToken.ListenerRegistration cancellationRegistration =
                CancellationToken.ListenerRegistration.none();

        private RequestRun(
                AiRequest request,
                CancellationToken cancellationToken,
                AiRequestHealth initialHealth,
                Instant deadline) {
            this.request = request;
            this.cancellationToken = cancellationToken;
            this.health = initialHealth;
            this.deadline = AiChecks.instant(deadline, "deadline");
            completion.whenComplete((response, throwable) -> {
                if (completion.isCancelled()) {
                    cancelFromCaller();
                }
            });
        }

        private CompletionStage<AiResponse> completion() {
            return completion;
        }

        private void start() {
            if (!registerCancellationListener()) {
                return;
            }
            try {
                ScheduledFuture<?> scheduled = scheduler.schedule(
                        this::timeout,
                        request.options().timeoutMillis(),
                        TimeUnit.MILLISECONDS);
                synchronized (this) {
                    if (terminal.get()) {
                        scheduled.cancel(false);
                        return;
                    }
                    deadlineFuture = scheduled;
                }
            } catch (RuntimeException exception) {
                synchronized (this) {
                    if (!terminal.get()) {
                        finishFailureLocked(new AiProviderException(
                                AiFailureKind.UNAVAILABLE,
                                Optional.empty(),
                                AiReasonCode.SCHEDULER_REJECTED),
                                health.observedAt());
                    }
                }
                return;
            }
            startAttemptSafely();
        }

        /**
         * 先登记外部取消，避免在首个 delegate attempt 已经启动后才错过取消信号。
         */
        private boolean registerCancellationListener() {
            CancellationToken.ListenerRegistration registration;
            try {
                registration = cancellationToken.onCancellation(
                        this::cancelFromToken);
            } catch (RuntimeException exception) {
                failUnexpected(AiReasonCode.CALLBACK_ATTACHMENT_FAILED);
                return false;
            }
            if (registration == null) {
                failUnexpected(AiReasonCode.CALLBACK_ATTACHMENT_FAILED);
                return false;
            }
            boolean releaseImmediately;
            synchronized (this) {
                releaseImmediately = terminal.get();
                if (!releaseImmediately) {
                    cancellationRegistration = registration;
                }
            }
            if (releaseImmediately) {
                closeCancellationRegistration(registration);
            }
            return !releaseImmediately;
        }

        /**
         * scheduler、时钟或熔断器边界异常不能遗留占用的 request slot。
         */
        private void startAttemptSafely() {
            try {
                startAttempt();
            } catch (RuntimeException exception) {
                failUnexpected(AiReasonCode.DEADLINE_OUT_OF_RANGE);
            }
        }

        private void startAttempt() {
            AiCircuitPermit permit;
            synchronized (this) {
                retryFuture = null;
                if (terminal.get()) {
                    return;
                }
                Instant observedAt = now();
                if (cancellationToken.isCancellationRequested()) {
                    finishCancelledLocked(observedAt);
                    return;
                }
                if (!observedAt.isBefore(deadline)) {
                    finishFailureLocked(AiProviderException.of(
                            AiFailureKind.TIMEOUT), observedAt);
                    return;
                }
                AiCircuitAdmission admission = circuitBreaker.admit(observedAt);
                if (!admission.accepted()) {
                    AiFailureKind rejectionKind = admission.rejectionKind()
                            .orElse(AiFailureKind.UNKNOWN);
                    AiProviderException exception = new AiProviderException(
                            rejectionKind,
                            admission.retryAfter(),
                            rejectionKind.defaultReasonCode());
                    if (health.state() == AiRequestHealthState.QUEUED) {
                        health = health.rejected(rejectionKind, observedAt);
                        recordHealth(health);
                        rememberFailure(exception, observedAt);
                        finishTerminalLocked(exception);
                    } else {
                        finishFailureLocked(exception, observedAt);
                    }
                    return;
                }
                permit = admission.permit().orElseThrow();
                activePermit = permit;
                health = health.inFlight(observedAt);
                recordHealth(health);
            }
            if (terminal.get()) {
                return;
            }
            CompletionStage<AiResponse> delegateStage;
            try {
                delegateStage = delegate.complete(
                        request, delegateCancellation.token());
            } catch (RuntimeException exception) {
                completeAttempt(permit, null, null, exception);
                return;
            }
            if (delegateStage == null) {
                completeAttempt(permit, null, null, AiProviderException.of(
                        AiFailureKind.MALFORMED_RESPONSE));
                return;
            }
            boolean cancelImmediately;
            synchronized (this) {
                cancelImmediately = terminal.get() || activePermit != permit;
                if (!cancelImmediately) {
                    activeDelegateStage = delegateStage;
                }
            }
            if (cancelImmediately) {
                cancelDelegateStage(delegateStage);
                return;
            }
            try {
                delegateStage.whenComplete((response, throwable) ->
                        completeAttempt(
                                permit, delegateStage, response, throwable));
            } catch (RuntimeException exception) {
                // 无法登记回调时必须主动收回请求，不能把调用留给未知 Provider 继续执行。
                completeAttempt(
                        permit,
                        delegateStage,
                        null,
                        AiProviderException.of(
                                AiFailureKind.UNKNOWN,
                                AiReasonCode.CALLBACK_ATTACHMENT_FAILED));
                cancelDelegateStage(delegateStage);
            }
        }

        private void completeAttempt(
                AiCircuitPermit permit,
                CompletionStage<AiResponse> delegateStage,
                AiResponse response,
                Throwable throwable) {
            AiProviderException failure = throwable == null
                    ? validateResponse(response)
                    : AiProviderException.fromThrowable(throwable);
            Instant observedAt;
            try {
                observedAt = now();
            } catch (RuntimeException exception) {
                failUnexpected(AiReasonCode.DEADLINE_OUT_OF_RANGE);
                return;
            }
            Instant retryAt = null;
            synchronized (this) {
                if (activeDelegateStage == delegateStage) {
                    activeDelegateStage = null;
                }
                boolean settled = failure == null
                        ? circuitBreaker.recordSuccess(permit, observedAt)
                        : circuitBreaker.recordFailure(
                                permit, failure.failureKind(), observedAt);
                if (!settled) {
                    failure = AiProviderException.of(
                            AiFailureKind.TIMEOUT,
                            AiReasonCode.STALE_LEASE);
                    if (activePermit == permit) {
                        activePermit = null;
                    }
                    if (!terminal.get()) {
                        finishFailureLocked(failure, observedAt);
                    }
                    return;
                }
                if (activePermit == permit) {
                    activePermit = null;
                }
                if (terminal.get()) {
                    return;
                }
                if (cancellationToken.isCancellationRequested()) {
                    finishCancelledLocked(observedAt);
                    return;
                }
                if (failure == null) {
                    health = health.succeeded(observedAt);
                    recordHealth(health);
                    latestFailureHealth.set(null);
                    finishTerminalLocked(response);
                    return;
                }
                rememberFailure(failure, observedAt);
                int completedAttempts = health.attemptsCompleted() + 1;
                AiRetryDecision decision;
                try {
                    decision = retryPolicy.decide(
                            failure, completedAttempts, observedAt, deadline);
                } catch (RuntimeException exception) {
                    finishFailureLocked(AiProviderException.of(
                            AiFailureKind.UNAVAILABLE,
                            AiReasonCode.DEADLINE_OUT_OF_RANGE), observedAt);
                    return;
                }
                if (decision.retry()) {
                    retryAt = decision.retryAt().orElseThrow();
                    health = health.backingOff(failure, observedAt, retryAt);
                    recordHealth(health);
                } else {
                    finishFailureLocked(failure, observedAt);
                    return;
                }
            }
            scheduleRetry(retryAt, observedAt);
        }

        private void scheduleRetry(Instant retryAt, Instant observedAt) {
            long delayMillis;
            try {
                delayMillis = Math.max(0L, Duration.between(
                        now(), retryAt).toMillis());
            } catch (RuntimeException exception) {
                synchronized (this) {
                    if (!terminal.get()) {
                        finishFailureLocked(AiProviderException.of(
                                AiFailureKind.UNAVAILABLE,
                                AiReasonCode.DEADLINE_OUT_OF_RANGE), observedAt);
                    }
                }
                return;
            }
            try {
                ScheduledFuture<?> scheduled = scheduler.schedule(
                        this::startAttemptSafely,
                        delayMillis,
                        TimeUnit.MILLISECONDS);
                synchronized (this) {
                    if (terminal.get()) {
                        scheduled.cancel(false);
                    } else {
                        retryFuture = scheduled;
                    }
                }
            } catch (RuntimeException exception) {
                synchronized (this) {
                    if (!terminal.get()) {
                        finishFailureLocked(new AiProviderException(
                                AiFailureKind.UNAVAILABLE,
                                Optional.empty(),
                                AiReasonCode.SCHEDULER_REJECTED), observedAt);
                    }
                }
            }
        }

        private void timeout() {
            CompletionStage<AiResponse> delegateStage;
            synchronized (this) {
                if (terminal.get()) {
                    return;
                }
                Instant observedAt = safeObservedAt();
                if (activePermit != null) {
                    circuitBreaker.recordFailure(
                            activePermit, AiFailureKind.TIMEOUT, observedAt);
                    activePermit = null;
                }
                delegateStage = detachActiveDelegateLocked();
                finishFailureLocked(AiProviderException.of(
                        AiFailureKind.TIMEOUT), observedAt);
            }
            requestDelegateCancellation();
            cancelDelegateStage(delegateStage);
        }

        private void cancelFromCaller() {
            cancelActiveRequest();
        }

        private void cancelFromToken() {
            cancelActiveRequest();
        }

        /**
         * 外部 token 与调用方 future 共用同一终止路径：先确定本运行的安全终态，再通知并中断
         * 当前 delegate，避免同步回调抢先把取消误记为普通 Provider 失败。
         */
        private void cancelActiveRequest() {
            CompletionStage<AiResponse> delegateStage;
            synchronized (this) {
                if (terminal.get()) {
                    return;
                }
                Instant observedAt = safeObservedAt();
                if (activePermit != null) {
                    circuitBreaker.recordFailure(
                            activePermit, AiFailureKind.CANCELLED, observedAt);
                    activePermit = null;
                }
                delegateStage = detachActiveDelegateLocked();
                finishCancelledLocked(observedAt);
            }
            requestDelegateCancellation();
            cancelDelegateStage(delegateStage);
        }

        private Instant safeObservedAt() {
            try {
                return now();
            } catch (RuntimeException exception) {
                return health.observedAt();
            }
        }

        private void failUnexpected(AiReasonCode reasonCode) {
            CompletionStage<AiResponse> delegateStage;
            synchronized (this) {
                if (terminal.get()) {
                    return;
                }
                Instant observedAt = safeObservedAt();
                if (activePermit != null) {
                    try {
                        circuitBreaker.recordFailure(
                                activePermit,
                                AiFailureKind.TIMEOUT,
                                observedAt);
                    } catch (RuntimeException ignored) {
                        // 已进入安全失败路径，不能让熔断器诊断异常泄出回调线程。
                    }
                    activePermit = null;
                }
                delegateStage = detachActiveDelegateLocked();
                finishFailureLocked(AiProviderException.of(
                        AiFailureKind.UNAVAILABLE, reasonCode), observedAt);
            }
            requestDelegateCancellation();
            cancelDelegateStage(delegateStage);
        }

        private AiProviderException validateResponse(AiResponse response) {
            if (response == null
                    || !response.requestId().equals(request.requestId())
                    || !response.providerId().equals(providerId)
                    || !response.model().equals(request.model())) {
                return AiProviderException.of(
                        AiFailureKind.MALFORMED_RESPONSE);
            }
            return null;
        }

        private void finishCancelledLocked(Instant observedAt) {
            AiProviderException exception = AiProviderException.of(
                    AiFailureKind.CANCELLED);
            health = health.cancelled(observedAt);
            recordHealth(health);
            finishTerminalLocked(exception);
        }

        private void finishFailureLocked(
                AiProviderException exception, Instant observedAt) {
            if (health.state() == AiRequestHealthState.QUEUED) {
                health = health.rejected(exception.failureKind(), observedAt);
            } else {
                health = health.failed(exception, observedAt);
            }
            recordHealth(health);
            rememberFailure(exception, observedAt);
            finishTerminalLocked(exception);
        }

        private void finishTerminalLocked(AiResponse response) {
            if (!terminal.compareAndSet(false, true)) {
                return;
            }
            cancelScheduledLocked();
            releaseCancellationRegistrationLocked();
            retainTerminalHealth(request.requestId());
            releaseRequestSlot();
            completion.complete(response);
        }

        private void finishTerminalLocked(AiProviderException exception) {
            if (!terminal.compareAndSet(false, true)) {
                return;
            }
            cancelScheduledLocked();
            releaseCancellationRegistrationLocked();
            retainTerminalHealth(request.requestId());
            releaseRequestSlot();
            completion.completeExceptionally(exception);
        }

        private void cancelScheduledLocked() {
            if (deadlineFuture != null) {
                deadlineFuture.cancel(false);
                deadlineFuture = null;
            }
            if (retryFuture != null) {
                retryFuture.cancel(false);
                retryFuture = null;
            }
        }

        /**
         * 终态不再保留对调用方 token 的监听，避免长寿命取消源把已经完成的运行一直引用住。
         */
        private void releaseCancellationRegistrationLocked() {
            CancellationToken.ListenerRegistration registration =
                    cancellationRegistration;
            cancellationRegistration = CancellationToken.ListenerRegistration
                    .none();
            closeCancellationRegistration(registration);
        }

        private static void closeCancellationRegistration(
                CancellationToken.ListenerRegistration registration) {
            try {
                registration.close();
            } catch (RuntimeException ignored) {
                // 自定义 token 的释放异常不能阻止 slot、健康和 completion 进入终态。
            }
        }

        /**
         * 让支持监听的 delegate 收到即时取消；其后仍尝试取消 CompletionStage，以兼容旧实现。
         */
        private void requestDelegateCancellation() {
            try {
                delegateCancellation.cancel();
            } catch (RuntimeException ignored) {
                // 本地取消源已隔离监听器异常；保留兜底以免第三方边界干扰既定终态。
            }
        }

        /**
         * 只在持有 RequestRun 锁时拆离当前 delegate，避免旧回调取消后续重试的 stage。
         */
        private CompletionStage<AiResponse> detachActiveDelegateLocked() {
            CompletionStage<AiResponse> delegateStage = activeDelegateStage;
            activeDelegateStage = null;
            return delegateStage;
        }

        /**
         * CompletionStage 的可取消能力并非强制；可转换时请求中断，否则仍由组合 token 让
         * 协作式 Provider 尽快结束。取消失败绝不能覆盖原始 timeout/caller-cancel 终态。
         */
        private static void cancelDelegateStage(
                CompletionStage<AiResponse> delegateStage) {
            if (delegateStage == null) {
                return;
            }
            try {
                delegateStage.toCompletableFuture().cancel(true);
            } catch (RuntimeException ignored) {
                // 第三方 CompletionStage 可能拒绝暴露可取消 CompletableFuture；安全终态已确定。
            }
        }
    }

    private static Instant plus(Instant instant, Duration duration) {
        try {
            return instant.plus(duration);
        } catch (java.time.DateTimeException exception) {
            throw new IllegalArgumentException(
                    "request deadline exceeds Instant range");
        }
    }
}
