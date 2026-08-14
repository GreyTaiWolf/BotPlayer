package io.github.greytaiwolf.botplayer.ai;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 只用于测试和本地 fixture 的确定性 Provider。
 *
 * <p>脚本按 requestId 精确匹配并逐项消费，不会访问网络、凭据、文件或 Minecraft 对象。
 * 未脚本化、过度消费、取消和模型不一致均返回安全失败，避免测试意外把错误请求当作成功。
 */
public final class ScriptedAiProvider implements AiProvider {
    public static final int MAX_SCRIPTED_REQUESTS = 256;
    public static final int MAX_OUTCOMES_PER_REQUEST = 16;
    public static final int MAX_TOTAL_OUTCOMES = 1_024;

    private final String providerId;
    private final AiCapabilities capabilities;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;
    private final Map<UUID, List<ScriptedAiOutcome>> scripts;
    private final Map<UUID, AtomicInteger> cursors;
    private final AtomicReference<ProviderHealth> health;

    public ScriptedAiProvider(
            AiCapabilities capabilities,
            Clock clock,
            Map<UUID, List<ScriptedAiOutcome>> scripts) {
        this(capabilities, clock, scripts, null);
    }

    /**
     * 构造支持 {@link ScriptedAiOutcome.Delayed} 的 fixture Provider。
     *
     * <p>scheduler 只由测试或本地 fixture 注入；本类绝不自行创建线程。传入 {@code null}
     * 仍允许立即结果，但延迟结果会安全失败。
     */
    public ScriptedAiProvider(
            AiCapabilities capabilities,
            Clock clock,
            Map<UUID, List<ScriptedAiOutcome>> scripts,
            ScheduledExecutorService scheduler) {
        this.capabilities = Objects.requireNonNull(
                capabilities, "capabilities");
        this.providerId = capabilities.providerId();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.scheduler = scheduler;
        Instant observedAt = AiChecks.instant(
                clock.instant(), "clock instant");
        this.scripts = copyScripts(providerId, scripts);
        this.cursors = createCursors(this.scripts);
        this.health = new AtomicReference<>(ProviderHealth.healthy(
                providerId, observedAt));
    }

    @Override
    public CompletionStage<AiResponse> complete(
            AiRequest request, CancellationToken token) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(token, "token");
        if (token.isCancellationRequested()) {
            return fail(AiProviderException.of(AiFailureKind.CANCELLED));
        }
        List<ScriptedAiOutcome> outcomes = scripts.get(request.requestId());
        AtomicInteger cursor = cursors.get(request.requestId());
        if (outcomes == null || cursor == null) {
            return fail(AiProviderException.of(AiFailureKind.INVALID_REQUEST));
        }
        int outcomeIndex = cursor.getAndIncrement();
        if (outcomeIndex < 0 || outcomeIndex >= outcomes.size()) {
            return fail(AiProviderException.of(AiFailureKind.OVERLOADED));
        }
        return resolveOutcome(request, token, outcomes.get(outcomeIndex));
    }

    private CompletionStage<AiResponse> resolveOutcome(
            AiRequest request,
            CancellationToken token,
            ScriptedAiOutcome outcome) {
        if (token.isCancellationRequested()) {
            return fail(AiProviderException.of(AiFailureKind.CANCELLED));
        }
        if (outcome instanceof ScriptedAiOutcome.Delayed delayed) {
            return delayedOutcome(request, token, delayed);
        }
        if (outcome instanceof ScriptedAiOutcome.Response scriptedResponse) {
            AiResponse response = scriptedResponse.response();
            if (!response.model().equals(request.model())) {
                return fail(AiProviderException.of(
                        AiFailureKind.MALFORMED_RESPONSE));
            }
            health.set(ProviderHealth.healthy(providerId, now()));
            return CompletableFuture.completedFuture(response);
        }
        ScriptedAiOutcome.Failure scriptedFailure =
                (ScriptedAiOutcome.Failure) outcome;
        return fail(scriptedFailure.exception());
    }

    private CompletionStage<AiResponse> delayedOutcome(
            AiRequest request,
            CancellationToken token,
            ScriptedAiOutcome.Delayed delayed) {
        if (scheduler == null) {
            return fail(AiProviderException.of(
                    AiFailureKind.UNAVAILABLE,
                    AiReasonCode.SCHEDULER_REQUIRED));
        }
        CompletableFuture<AiResponse> result = new CompletableFuture<>();
        try {
            ScheduledFuture<?> scheduled = scheduler.schedule(() -> {
                if (result.isCancelled()) {
                    return;
                }
                CompletionStage<AiResponse> resolved = resolveOutcome(
                        request,
                        () -> token.isCancellationRequested()
                                || result.isCancelled(),
                        delayed.outcome());
                try {
                    resolved.whenComplete((response, throwable) -> {
                        if (throwable != null) {
                            result.completeExceptionally(
                                    AiProviderException.fromThrowable(throwable));
                            return;
                        }
                        result.complete(response);
                    });
                } catch (RuntimeException exception) {
                    AiProviderException safe = AiProviderException.of(
                            AiFailureKind.UNKNOWN,
                            AiReasonCode.CALLBACK_ATTACHMENT_FAILED);
                    health.set(healthFor(safe, now()));
                    result.completeExceptionally(safe);
                }
            }, delayed.delay().toNanos(), TimeUnit.NANOSECONDS);
            result.whenComplete((response, throwable) -> {
                if (result.isCancelled()) {
                    scheduled.cancel(false);
                }
            });
        } catch (RuntimeException exception) {
            return fail(AiProviderException.of(
                    AiFailureKind.UNAVAILABLE,
                    AiReasonCode.SCHEDULER_REJECTED));
        }
        return result;
    }

    @Override
    public CompletionStage<AiCapabilities> probeCapabilities() {
        return CompletableFuture.completedFuture(capabilities);
    }

    @Override
    public ProviderHealth health() {
        return health.get();
    }

    private CompletionStage<AiResponse> fail(
            AiProviderException exception) {
        health.set(healthFor(exception, now()));
        return CompletableFuture.failedFuture(exception);
    }

    private ProviderHealth healthFor(
            AiProviderException exception, Instant observedAt) {
        Optional<Instant> retryAfter = exception.retryAfter()
                .filter(value -> !value.isBefore(observedAt));
        return new ProviderHealth(
                providerId,
                exception.failureKind().healthState(),
                observedAt,
                retryAfter,
                Optional.of(exception.reasonCode().wireCode()));
    }

    private Instant now() {
        return AiChecks.instant(clock.instant(), "clock instant");
    }

    private static Map<UUID, List<ScriptedAiOutcome>> copyScripts(
            String providerId,
            Map<UUID, List<ScriptedAiOutcome>> source) {
        Objects.requireNonNull(source, "scripts");
        if (source.size() > MAX_SCRIPTED_REQUESTS) {
            throw new IllegalArgumentException(
                    "scripts exceeds maximum size " + MAX_SCRIPTED_REQUESTS);
        }
        Map<UUID, List<ScriptedAiOutcome>> copied = new LinkedHashMap<>();
        int totalOutcomes = 0;
        for (Map.Entry<UUID, List<ScriptedAiOutcome>> entry
                : source.entrySet()) {
            UUID requestId = entry.getKey();
            AiChecks.requireNonZero(requestId, "script requestId");
            List<ScriptedAiOutcome> outcomes = Objects.requireNonNull(
                    entry.getValue(), "script outcomes");
            if (outcomes.isEmpty()
                    || outcomes.size() > MAX_OUTCOMES_PER_REQUEST) {
                throw new IllegalArgumentException(
                        "script outcomes must contain between 1 and "
                                + MAX_OUTCOMES_PER_REQUEST + " entries");
            }
            totalOutcomes += outcomes.size();
            if (totalOutcomes > MAX_TOTAL_OUTCOMES) {
                throw new IllegalArgumentException(
                        "script outcomes exceeds maximum total "
                                + MAX_TOTAL_OUTCOMES);
            }
            List<ScriptedAiOutcome> copiedOutcomes =
                    new ArrayList<>(outcomes.size());
            for (ScriptedAiOutcome outcome : outcomes) {
                ScriptedAiOutcome checked = Objects.requireNonNull(
                        outcome, "script outcome");
                validateOutcome(providerId, requestId, checked);
                copiedOutcomes.add(checked);
            }
            copied.put(requestId, List.copyOf(copiedOutcomes));
        }
        return Map.copyOf(copied);
    }

    private static void validateOutcome(
            String providerId,
            UUID requestId,
            ScriptedAiOutcome outcome) {
        if (outcome instanceof ScriptedAiOutcome.Delayed delayed) {
            validateOutcome(providerId, requestId, delayed.outcome());
            return;
        }
        if (outcome instanceof ScriptedAiOutcome.Response scriptedResponse) {
            AiResponse response = scriptedResponse.response();
            if (!response.requestId().equals(requestId)) {
                throw new IllegalArgumentException(
                        "scripted response requestId must match script key");
            }
            if (!response.providerId().equals(providerId)) {
                throw new IllegalArgumentException(
                        "scripted response providerId must match capabilities");
            }
        }
    }

    private static Map<UUID, AtomicInteger> createCursors(
            Map<UUID, List<ScriptedAiOutcome>> scripts) {
        Map<UUID, AtomicInteger> copied = new ConcurrentHashMap<>();
        for (UUID requestId : scripts.keySet()) {
            copied.put(requestId, new AtomicInteger());
        }
        return copied;
    }
}
