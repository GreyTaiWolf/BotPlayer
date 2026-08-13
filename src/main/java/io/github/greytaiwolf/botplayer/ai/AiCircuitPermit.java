package io.github.greytaiwolf.botplayer.ai;

import java.time.Instant;

/**
 * 一次熔断器准入的不可变租约。
 *
 * <p>构造器仅包内可见；调用方只能把 {@link AiCircuitBreaker#admit(Instant)} 返回的 permit
 * 交回同一 breaker 结算。过期或旧 epoch permit 会被忽略，不能回写新状态。
 */
public final class AiCircuitPermit {
    private final long permitId;
    private final long epoch;
    private final boolean halfOpenProbe;
    private final Instant expiresAt;

    AiCircuitPermit(
            long permitId,
            long epoch,
            boolean halfOpenProbe,
            Instant expiresAt) {
        this.permitId = permitId;
        this.epoch = epoch;
        this.halfOpenProbe = halfOpenProbe;
        this.expiresAt = AiChecks.instant(expiresAt, "expiresAt");
    }

    long permitId() {
        return permitId;
    }

    long epoch() {
        return epoch;
    }

    public boolean halfOpenProbe() {
        return halfOpenProbe;
    }

    public Instant expiresAt() {
        return expiresAt;
    }
}
