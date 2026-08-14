package io.github.greytaiwolf.botplayer.ai;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 有界、安全的调度状态快照，可用于后续状态 UI 和诊断。
 */
public record AiScheduledRequestHealth(
        UUID requestId,
        UUID botId,
        UUID agentId,
        long revision,
        String providerId,
        String model,
        AiSchedulerRequestState state,
        Instant observedAt,
        Optional<AiFailureKind> failureKind,
        Optional<AiReasonCode> reasonCode) {
    public AiScheduledRequestHealth {
        AiChecks.requireNonZero(requestId, "requestId");
        AiChecks.requireNonZero(botId, "botId");
        AiChecks.requireNonZero(agentId, "agentId");
        if (revision <= 0L) {
            throw new IllegalArgumentException("revision must be positive");
        }
        providerId = AiChecks.providerId(providerId, "providerId");
        model = AiChecks.modelId(model, "model");
        Objects.requireNonNull(state, "state");
        observedAt = AiChecks.instant(observedAt, "observedAt");
        failureKind = Objects.requireNonNull(failureKind, "failureKind");
        reasonCode = Objects.requireNonNull(reasonCode, "reasonCode");
        boolean terminalFailure = state == AiSchedulerRequestState.FAILED
                || state == AiSchedulerRequestState.CANCELLED
                || state == AiSchedulerRequestState.REJECTED;
        if (terminalFailure != failureKind.isPresent()
                || terminalFailure != reasonCode.isPresent()) {
            throw new IllegalArgumentException(
                    "only terminal failure states require failure metadata");
        }
    }

    static AiScheduledRequestHealth queued(
            AiScheduledRequest request, String providerId, Instant observedAt) {
        return new AiScheduledRequestHealth(
                request.request().requestId(), request.botId(), request.agentId(),
                request.revision(), providerId, request.request().model(),
                AiSchedulerRequestState.QUEUED, observedAt,
                Optional.empty(), Optional.empty());
    }

    AiScheduledRequestHealth inFlight(Instant observedAt) {
        return transition(AiSchedulerRequestState.IN_FLIGHT, observedAt,
                Optional.empty(), Optional.empty());
    }

    AiScheduledRequestHealth succeeded(Instant observedAt) {
        return transition(AiSchedulerRequestState.SUCCEEDED, observedAt,
                Optional.empty(), Optional.empty());
    }

    AiScheduledRequestHealth failed(
            AiProviderException failure, Instant observedAt) {
        Objects.requireNonNull(failure, "failure");
        return transition(AiSchedulerRequestState.FAILED, observedAt,
                Optional.of(failure.failureKind()), Optional.of(failure.reasonCode()));
    }

    AiScheduledRequestHealth cancelled(Instant observedAt) {
        return transition(AiSchedulerRequestState.CANCELLED, observedAt,
                Optional.of(AiFailureKind.CANCELLED),
                Optional.of(AiReasonCode.CANCELLED));
    }

    AiScheduledRequestHealth rejected(
            AiProviderException failure, Instant observedAt) {
        Objects.requireNonNull(failure, "failure");
        return transition(AiSchedulerRequestState.REJECTED, observedAt,
                Optional.of(failure.failureKind()),
                Optional.of(failure.reasonCode()));
    }

    private AiScheduledRequestHealth transition(
            AiSchedulerRequestState next,
            Instant observedAt,
            Optional<AiFailureKind> nextFailureKind,
            Optional<AiReasonCode> nextReasonCode) {
        return new AiScheduledRequestHealth(
                requestId, botId, agentId, revision, providerId, model, next,
                observedAt, nextFailureKind, nextReasonCode);
    }

    /** 不输出任何 prompt、schema、响应、工具参数或异常正文。 */
    @Override
    public String toString() {
        return "AiScheduledRequestHealth[requestId=" + requestId
                + ", botId=" + botId
                + ", agentId=" + agentId
                + ", revision=" + revision
                + ", providerId=" + providerId
                + ", model=" + model
                + ", state=" + state
                + ", observedAt=" + observedAt
                + ", failureKind=" + failureKind
                + ", reasonCode=" + reasonCode + "]";
    }
}
