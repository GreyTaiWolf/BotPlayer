package io.github.greytaiwolf.botplayer.ai;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 单个 Provider request 的安全健康快照。
 *
 * <p>它不保存消息、prompt、响应正文、Authorization 或异常文本，因此可供未来状态 UI 或
 * 调度诊断使用。尝试次数严格受 {@link AiRetryPolicy#MAX_ATTEMPTS} 限制。
 */
public record AiRequestHealth(
        UUID requestId,
        String providerId,
        String model,
        AiRequestHealthState state,
        int attemptsStarted,
        int attemptsCompleted,
        Instant observedAt,
        Optional<Instant> retryAt,
        Optional<AiFailureKind> failureKind,
        Optional<AiReasonCode> reasonCode) {
    public AiRequestHealth {
        AiChecks.requireNonZero(requestId, "requestId");
        providerId = AiChecks.providerId(providerId, "providerId");
        model = AiChecks.modelId(model, "model");
        Objects.requireNonNull(state, "state");
        if (attemptsStarted < 0 || attemptsStarted > AiRetryPolicy.MAX_ATTEMPTS
                || attemptsCompleted < 0
                || attemptsCompleted > attemptsStarted) {
            throw new IllegalArgumentException(
                    "request attempt counters are outside supported bounds");
        }
        Instant checkedObservedAt = AiChecks.instant(observedAt, "observedAt");
        observedAt = checkedObservedAt;
        retryAt = Objects.requireNonNull(retryAt, "retryAt").map(value -> {
            Instant checked = AiChecks.instant(value, "retryAt value");
            if (checked.isBefore(checkedObservedAt)) {
                throw new IllegalArgumentException(
                        "retryAt must not be before observedAt");
            }
            return checked;
        });
        failureKind = Objects.requireNonNull(
                failureKind, "failureKind");
        reasonCode = Objects.requireNonNull(reasonCode, "reasonCode");
        validateState(
                state,
                attemptsStarted,
                attemptsCompleted,
                retryAt,
                failureKind,
                reasonCode);
    }

    public static AiRequestHealth queued(
            AiRequest request, String providerId, Instant observedAt) {
        Objects.requireNonNull(request, "request");
        return new AiRequestHealth(
                request.requestId(),
                providerId,
                request.model(),
                AiRequestHealthState.QUEUED,
                0,
                0,
                observedAt,
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    public AiRequestHealth inFlight(Instant observedAt) {
        if (state != AiRequestHealthState.QUEUED
                && state != AiRequestHealthState.BACKING_OFF) {
            throw new IllegalStateException(
                    "only queued or backing-off requests may start an attempt");
        }
        return new AiRequestHealth(
                requestId,
                providerId,
                model,
                AiRequestHealthState.IN_FLIGHT,
                attemptsStarted + 1,
                attemptsCompleted,
                observedAt,
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    public AiRequestHealth backingOff(
            AiProviderException exception, Instant observedAt, Instant nextRetryAt) {
        Objects.requireNonNull(exception, "exception");
        if (state != AiRequestHealthState.IN_FLIGHT) {
            throw new IllegalStateException(
                    "only in-flight requests may enter backing off");
        }
        return new AiRequestHealth(
                requestId,
                providerId,
                model,
                AiRequestHealthState.BACKING_OFF,
                attemptsStarted,
                attemptsCompleted + 1,
                observedAt,
                Optional.of(nextRetryAt),
                Optional.of(exception.failureKind()),
                Optional.of(exception.reasonCode()));
    }

    public AiRequestHealth succeeded(Instant observedAt) {
        if (state != AiRequestHealthState.IN_FLIGHT) {
            throw new IllegalStateException(
                    "only in-flight requests may succeed");
        }
        return new AiRequestHealth(
                requestId,
                providerId,
                model,
                AiRequestHealthState.SUCCEEDED,
                attemptsStarted,
                attemptsCompleted + 1,
                observedAt,
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    public AiRequestHealth failed(
            AiProviderException exception, Instant observedAt) {
        Objects.requireNonNull(exception, "exception");
        if (state != AiRequestHealthState.IN_FLIGHT
                && state != AiRequestHealthState.BACKING_OFF) {
            throw new IllegalStateException(
                    "only in-flight or backing-off requests may fail");
        }
        int completed = state == AiRequestHealthState.IN_FLIGHT
                ? attemptsCompleted + 1
                : attemptsCompleted;
        return terminal(
                AiRequestHealthState.FAILED,
                completed,
                exception.failureKind(),
                Optional.of(exception.reasonCode()),
                observedAt);
    }

    public AiRequestHealth cancelled(Instant observedAt) {
        return terminal(
                AiRequestHealthState.CANCELLED,
                attemptsCompleted,
                AiFailureKind.CANCELLED,
                Optional.of(AiReasonCode.CANCELLED),
                observedAt);
    }

    public AiRequestHealth rejected(
            AiFailureKind rejectionKind, Instant observedAt) {
        Objects.requireNonNull(rejectionKind, "rejectionKind");
        if (state != AiRequestHealthState.QUEUED) {
            throw new IllegalStateException(
                    "only queued requests may be rejected");
        }
        return terminal(
                AiRequestHealthState.REJECTED,
                0,
                rejectionKind,
                Optional.of(rejectionKind.defaultReasonCode()),
                observedAt);
    }

    private AiRequestHealth terminal(
            AiRequestHealthState terminalState,
            int completedAttempts,
            AiFailureKind terminalFailure,
            Optional<AiReasonCode> terminalReason,
            Instant observedAt) {
        return new AiRequestHealth(
                requestId,
                providerId,
                model,
                terminalState,
                attemptsStarted,
                completedAttempts,
                observedAt,
                Optional.empty(),
                Optional.of(terminalFailure),
                terminalReason.or(() -> Optional.of(
                        terminalFailure.defaultReasonCode())));
    }

    private static void validateState(
            AiRequestHealthState state,
            int attemptsStarted,
            int attemptsCompleted,
            Optional<Instant> retryAt,
            Optional<AiFailureKind> failureKind,
            Optional<AiReasonCode> reasonCode) {
        switch (state) {
            case QUEUED -> require(
                    attemptsStarted == 0 && attemptsCompleted == 0
                            && retryAt.isEmpty() && failureKind.isEmpty()
                            && reasonCode.isEmpty(),
                    "queued state must not contain attempts, retryAt or failure");
            case IN_FLIGHT -> require(
                    attemptsStarted == attemptsCompleted + 1
                            && retryAt.isEmpty() && failureKind.isEmpty()
                            && reasonCode.isEmpty(),
                    "in-flight state must contain exactly one active attempt");
            case BACKING_OFF -> require(
                    attemptsStarted == attemptsCompleted
                            && attemptsCompleted > 0
                            && retryAt.isPresent() && failureKind.isPresent()
                            && reasonCode.isPresent(),
                    "backing-off state requires completed attempt, retryAt and failure");
            case SUCCEEDED -> require(
                    attemptsStarted == attemptsCompleted
                            && attemptsCompleted > 0
                            && retryAt.isEmpty() && failureKind.isEmpty()
                            && reasonCode.isEmpty(),
                    "succeeded state requires completed attempts without failure");
            case FAILED -> require(
                    attemptsStarted == attemptsCompleted
                            && attemptsCompleted > 0
                            && retryAt.isEmpty() && failureKind.isPresent()
                            && reasonCode.isPresent(),
                    "failed state requires completed attempts and failure");
            case CANCELLED -> require(
                    attemptsCompleted <= attemptsStarted
                            && retryAt.isEmpty()
                            && failureKind.equals(Optional.of(
                            AiFailureKind.CANCELLED))
                            && reasonCode.isPresent(),
                    "cancelled state requires CANCELLED failure without retryAt");
            case REJECTED -> require(
                    attemptsStarted == 0 && attemptsCompleted == 0
                            && retryAt.isEmpty() && failureKind.isPresent()
                            && reasonCode.isPresent(),
                    "rejected state requires no attempts and a rejection reason");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
