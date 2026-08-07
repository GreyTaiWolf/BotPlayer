package io.github.greytaiwolf.botplayer.ai;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 可安全用于状态页和调度判断的 Provider 健康快照。
 *
 * <p>`reasonCode` 只能是稳定代码，不能放入异常正文、Authorization、Key 或原始响应体。
 */
public record ProviderHealth(
        String providerId,
        ProviderHealthState state,
        Instant observedAt,
        Optional<Instant> retryAfter,
        Optional<String> reasonCode) {
    public ProviderHealth {
        providerId = AiChecks.providerId(
                providerId, "providerId");
        Objects.requireNonNull(state, "state");
        observedAt = AiChecks.instant(observedAt, "observedAt");
        retryAfter = Objects.requireNonNull(
                retryAfter, "retryAfter");
        retryAfter.ifPresent(value -> {
            AiChecks.instant(value, "retryAfter value");
            if (value.isBefore(observedAt)) {
                throw new IllegalArgumentException(
                        "retryAfter must not be before observedAt");
            }
        });
        reasonCode = AiChecks.optionalReasonCode(
                reasonCode, "reasonCode");
    }

    public boolean acceptingRequests() {
        return state == ProviderHealthState.HEALTHY
                || state == ProviderHealthState.DEGRADED;
    }

    public static ProviderHealth unknown(
            String providerId, Instant observedAt) {
        return new ProviderHealth(
                providerId,
                ProviderHealthState.UNKNOWN,
                observedAt,
                Optional.empty(),
                Optional.empty());
    }

    public static ProviderHealth healthy(
            String providerId, Instant observedAt) {
        return new ProviderHealth(
                providerId,
                ProviderHealthState.HEALTHY,
                observedAt,
                Optional.empty(),
                Optional.empty());
    }
}
