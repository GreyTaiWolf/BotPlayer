package io.github.greytaiwolf.botplayer.ai;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 包装任意 {@link AiProvider} 的确定性故障注入器。
 *
 * <p>它只用于单元/chaos 测试：给定调用序号总会得到同一安全失败或格式故障，不做网络、
 * HTTP 或 Minecraft 接线。延迟必须由调用方显式注入 scheduler；委托 Provider 的原始异常
 * 也会被收敛为 {@link AiProviderException}。
 */
public final class ChaosAiProvider implements AiProvider {
    public static final int MAX_FAULTS = 256;

    private final String providerId;
    private final AiProvider delegate;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;
    private final Map<Integer, ChaosAiFault> faults;
    private final AtomicInteger invocation = new AtomicInteger();
    private final AtomicReference<ProviderHealth> injectedHealth =
            new AtomicReference<>();

    public ChaosAiProvider(
            String providerId,
            AiProvider delegate,
            Clock clock,
            List<ChaosAiFault> faults) {
        this(providerId, delegate, clock, faults, null);
    }

    /**
     * 构造支持延迟故障的 Chaos Provider；不会自行创建线程。
     */
    public ChaosAiProvider(
            String providerId,
            AiProvider delegate,
            Clock clock,
            List<ChaosAiFault> faults,
            ScheduledExecutorService scheduler) {
        this.providerId = AiChecks.providerId(providerId, "providerId");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.scheduler = scheduler;
        AiChecks.instant(clock.instant(), "clock instant");
        this.faults = copyFaults(faults);
    }

    @Override
    public CompletionStage<AiResponse> complete(
            AiRequest request, CancellationToken token) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(token, "token");
        if (token.isCancellationRequested()) {
            return failure(AiProviderException.of(AiFailureKind.CANCELLED));
        }
        ChaosAiFault fault = faults.get(nextInvocation());
        if (fault != null) {
            try {
                return injectedFault(fault, token);
            } catch (RuntimeException exception) {
                return failure(AiProviderException.of(
                        AiFailureKind.UNAVAILABLE,
                        AiReasonCode.DEADLINE_OUT_OF_RANGE));
            }
        }
        CompletionStage<AiResponse> delegateStage;
        try {
            delegateStage = delegate.complete(request, token);
        } catch (RuntimeException exception) {
            return failure(AiProviderException.fromThrowable(exception));
        }
        if (delegateStage == null) {
            return failure(AiProviderException.of(
                    AiFailureKind.MALFORMED_RESPONSE));
        }
        CompletableFuture<AiResponse> result = new CompletableFuture<>();
        try {
            delegateStage.whenComplete((response, throwable) -> {
                if (throwable != null) {
                    AiProviderException exception =
                            AiProviderException.fromThrowable(throwable);
                    healthForFailure(exception, now());
                    result.completeExceptionally(exception);
                    return;
                }
                AiProviderException invalid = validateResponse(request, response);
                if (invalid != null) {
                    healthForFailure(invalid, now());
                    result.completeExceptionally(invalid);
                    return;
                }
                injectedHealth.set(null);
                result.complete(response);
            });
        } catch (RuntimeException exception) {
            AiProviderException safe = AiProviderException.of(
                    AiFailureKind.UNKNOWN,
                    AiReasonCode.CALLBACK_ATTACHMENT_FAILED);
            healthForFailure(safe, now());
            result.completeExceptionally(safe);
        }
        return result;
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
        ProviderHealth injected = injectedHealth.get();
        if (injected != null) {
            return injected;
        }
        try {
            ProviderHealth delegateHealth = delegate.health();
            if (delegateHealth == null
                    || !delegateHealth.providerId().equals(providerId)) {
                return new ProviderHealth(
                        providerId,
                        ProviderHealthState.DEGRADED,
                        now(),
                        Optional.empty(),
                        Optional.of(AiReasonCode.DELEGATE_HEALTH_MISMATCH
                                .wireCode()));
            }
            return delegateHealth;
        } catch (RuntimeException exception) {
            return new ProviderHealth(
                    providerId,
                    ProviderHealthState.UNAVAILABLE,
                    now(),
                    Optional.empty(),
                    Optional.of(AiReasonCode.DELEGATE_HEALTH_FAILURE
                            .wireCode()));
        }
    }

    private CompletionStage<AiResponse> failure(
            AiProviderException exception) {
        healthForFailure(exception, now());
        return CompletableFuture.failedFuture(exception);
    }

    private CompletionStage<AiResponse> injectedFault(
            ChaosAiFault fault, CancellationToken token) {
        AiProviderException exception = fault.mode().malformedResponse()
                ? AiProviderException.of(AiFailureKind.MALFORMED_RESPONSE)
                : toException(fault, now());
        if (!fault.mode().delayed()) {
            return failure(exception);
        }
        if (scheduler == null) {
            return failure(AiProviderException.of(
                    AiFailureKind.UNAVAILABLE,
                    AiReasonCode.SCHEDULER_REQUIRED));
        }
        CompletableFuture<AiResponse> result = new CompletableFuture<>();
        try {
            ScheduledFuture<?> scheduled = scheduler.schedule(() -> {
                if (result.isCancelled()) {
                    return;
                }
                AiProviderException delayedException = token
                        .isCancellationRequested()
                        ? AiProviderException.of(AiFailureKind.CANCELLED)
                        : exception;
                healthForFailure(delayedException, now());
                result.completeExceptionally(delayedException);
            }, fault.delay().orElseThrow().toNanos(), TimeUnit.NANOSECONDS);
            result.whenComplete((response, throwable) -> {
                if (result.isCancelled()) {
                    scheduled.cancel(false);
                }
            });
        } catch (RuntimeException exceptionDuringSchedule) {
            return failure(AiProviderException.of(
                    AiFailureKind.UNAVAILABLE,
                    AiReasonCode.SCHEDULER_REJECTED));
        }
        return result;
    }

    private void healthForFailure(
            AiProviderException exception, Instant observedAt) {
        Optional<Instant> retryAfter = exception.retryAfter()
                .filter(value -> !value.isBefore(observedAt));
        injectedHealth.set(new ProviderHealth(
                providerId,
                exception.failureKind().healthState(),
                observedAt,
                retryAfter,
                Optional.of(exception.reasonCode().wireCode())));
    }

    private AiProviderException toException(
            ChaosAiFault fault, Instant observedAt) {
        if (fault.failureKind() == AiFailureKind.RATE_LIMITED) {
            Instant retryAfter = fault.retryAfterDelay()
                    .map(delay -> plus(observedAt, delay))
                    .orElse(observedAt);
            return AiProviderException.rateLimited(retryAfter);
        }
        return AiProviderException.of(fault.failureKind());
    }

    private AiProviderException validateResponse(
            AiRequest request, AiResponse response) {
        if (response == null
                || !response.requestId().equals(request.requestId())
                || !response.providerId().equals(providerId)
                || !response.model().equals(request.model())) {
            return AiProviderException.of(AiFailureKind.MALFORMED_RESPONSE);
        }
        return null;
    }

    private int nextInvocation() {
        return invocation.getAndUpdate(current -> current
                >= ChaosAiFault.MAX_INVOCATION
                ? ChaosAiFault.MAX_INVOCATION
                : current + 1) + 1;
    }

    private Instant now() {
        return AiChecks.instant(clock.instant(), "clock instant");
    }

    private static Instant plus(Instant instant, java.time.Duration duration) {
        try {
            return instant.plus(duration);
        } catch (java.time.DateTimeException exception) {
            throw new IllegalArgumentException(
                    "retryAfter exceeds Instant range");
        }
    }

    private static Map<Integer, ChaosAiFault> copyFaults(
            List<ChaosAiFault> source) {
        Objects.requireNonNull(source, "faults");
        if (source.size() > MAX_FAULTS) {
            throw new IllegalArgumentException(
                    "faults exceeds maximum size " + MAX_FAULTS);
        }
        Map<Integer, ChaosAiFault> copied = new HashMap<>();
        for (ChaosAiFault fault : source) {
            ChaosAiFault checked = Objects.requireNonNull(fault, "fault");
            if (copied.put(checked.invocation(), checked) != null) {
                throw new IllegalArgumentException(
                        "duplicate chaos fault invocation "
                                + checked.invocation());
            }
        }
        return Map.copyOf(copied);
    }
}
