package io.github.greytaiwolf.botplayer.ai;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * 不依赖线程池或 Minecraft 状态的有界 Provider 熔断器。
 *
 * <p>已经开始 physical Provider 调用的 accepted permit 必须用 success/failure 结算；已证明
 * 从未开始的 permit 可仅由本包 {@link #abandonUnstartedPermit(AiCircuitPermit)} 无 outcome
 * 归还。其余遗失 permit 到期后会按 TIMEOUT 失败处理，防止一个永不回调的 Provider 无限占用
 * 准入容量。
 */
public final class AiCircuitBreaker {
    private final AiCircuitBreakerPolicy policy;
    private final Map<Long, AiCircuitPermit> activePermits = new HashMap<>();

    private AiCircuitState state = AiCircuitState.CLOSED;
    private long epoch = 1L;
    private long nextPermitId = 1L;
    private int consecutiveTransientFailures;
    private Instant openUntil = Instant.EPOCH;
    private AiFailureKind openFailureKind = AiFailureKind.CIRCUIT_OPEN;

    public AiCircuitBreaker(AiCircuitBreakerPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /**
     * 获取一个有限时长 permit，或返回安全拒绝原因。
     */
    public synchronized AiCircuitAdmission admit(Instant now) {
        Instant observedAt = AiChecks.instant(now, "now");
        expireLeases(observedAt);
        if (state == AiCircuitState.OPEN) {
            if (observedAt.isBefore(openUntil)) {
                return rejected(
                        AiCircuitState.OPEN,
                        AiFailureKind.CIRCUIT_OPEN,
                        Optional.of(openUntil));
            }
            transitionToHalfOpen();
        }
        if (activePermits.size() >= policy.maximumInFlightPermits()) {
            return rejected(
                    state,
                    AiFailureKind.OVERLOADED,
                    Optional.empty());
        }
        if (state == AiCircuitState.HALF_OPEN
                && countHalfOpenPermits() >= policy.halfOpenProbeLimit()) {
            return rejected(
                    AiCircuitState.HALF_OPEN,
                    AiFailureKind.OVERLOADED,
                    Optional.empty());
        }
        AiCircuitPermit permit = new AiCircuitPermit(
                nextPermitId(),
                epoch,
                state == AiCircuitState.HALF_OPEN,
                plus(observedAt, policy.permitLeaseDuration()));
        activePermits.put(permit.permitId(), permit);
        return new AiCircuitAdmission(
                state,
                Optional.of(permit),
                Optional.empty(),
                Optional.empty());
    }

    /**
     * 结算成功；旧、重复或已超时 permit 返回 {@code false}。
     */
    public synchronized boolean recordSuccess(
            AiCircuitPermit permit, Instant now) {
        Instant observedAt = AiChecks.instant(now, "now");
        expireLeases(observedAt);
        if (!removeCurrentPermit(permit)) {
            return false;
        }
        if (state == AiCircuitState.HALF_OPEN
                && countHalfOpenPermits() == 0) {
            closeCircuit();
        } else if (state == AiCircuitState.CLOSED) {
            consecutiveTransientFailures = 0;
        }
        return true;
    }

    /**
     * 结算失败；只有短暂 Provider 失败会累积 closed 状态的熔断计数。
     */
    public synchronized boolean recordFailure(
            AiCircuitPermit permit,
            AiFailureKind failureKind,
            Instant now) {
        Objects.requireNonNull(failureKind, "failureKind");
        Instant observedAt = AiChecks.instant(now, "now");
        expireLeases(observedAt);
        if (!removeCurrentPermit(permit)) {
            return false;
        }
        applyFailure(failureKind, observedAt);
        return true;
    }

    /**
     * Atomically fences bounded local accounting immediately before one physical Provider call.
     *
     * <p>The caller must supply only bounded, local accounting that neither re-enters this breaker
     * nor invokes a Provider, scheduler, callback, Minecraft object or other unbounded boundary.
     * This method first applies lease expiry and verifies the exact current permit. It invokes the
     * gate only when that permit is still current; a stale permit therefore cannot settle another
     * accounting reservation. A {@code false} result or a gate exception neutrally removes the
     * exact permit before releasing this breaker monitor.
     *
     * <p>A {@code true} result is the circuit-side linearization point for the physical attempt.
     * The caller must then invoke its delegate exactly once without another terminal re-check.
     * Later lease expiry is consequently handled as an already-started attempt, rather than as an
     * unstarted local gate failure.
     */
    synchronized boolean settleUnstartedPermit(
            AiCircuitPermit permit,
            Instant now,
            BooleanSupplier boundedLocalGate) {
        AiCircuitPermit checkedPermit = Objects.requireNonNull(permit, "permit");
        BooleanSupplier checkedGate = Objects.requireNonNull(
                boundedLocalGate, "boundedLocalGate");
        Instant observedAt = AiChecks.instant(now, "now");
        expireLeases(observedAt);
        if (!isCurrentPermit(checkedPermit)) {
            return false;
        }
        try {
            if (checkedGate.getAsBoolean()) {
                return true;
            }
        } catch (RuntimeException exception) {
            removeCurrentPermit(checkedPermit);
            throw exception;
        }
        removeCurrentPermit(checkedPermit);
        return false;
    }

    /**
     * Discards one exact admission proven not to have reached a physical Provider invocation.
     *
     * <p>This deliberately does not run lease expiry or apply a health outcome: callers may use
     * it only before a delegate call starts. It prevents a local pre-delegate gate (such as token
     * accounting) from turning an otherwise unused permit into a synthetic Provider timeout.
     */
    synchronized boolean abandonUnstartedPermit(AiCircuitPermit permit) {
        return removeCurrentPermit(Objects.requireNonNull(permit, "permit"));
    }

    /**
     * 返回状态快照，并顺便清理已超时租约。
     */
    public synchronized AiCircuitSnapshot snapshot(Instant now) {
        Instant observedAt = AiChecks.instant(now, "now");
        expireLeases(observedAt);
        if (state == AiCircuitState.OPEN
                && !observedAt.isBefore(openUntil)) {
            transitionToHalfOpen();
        }
        Optional<Instant> retryAfter = state == AiCircuitState.OPEN
                ? Optional.of(openUntil)
                : Optional.empty();
        return new AiCircuitSnapshot(
                state,
                consecutiveTransientFailures,
                activePermits.size(),
                observedAt,
                retryAfter);
    }

    /**
     * 把状态转换为现有 {@link ProviderHealth} DTO，供纯 Java 调度层读取。
     */
    public synchronized ProviderHealth health(
            String providerId, Instant now) {
        String checkedProviderId = AiChecks.providerId(
                providerId, "providerId");
        AiCircuitSnapshot snapshot = snapshot(now);
        if (snapshot.state() == AiCircuitState.OPEN) {
            ProviderHealthState healthState = isAvailabilityBlockingFailure(
                    openFailureKind)
                    ? openFailureKind.healthState()
                    : ProviderHealthState.CIRCUIT_OPEN;
            AiReasonCode reasonCode = healthState
                    == ProviderHealthState.CIRCUIT_OPEN
                    ? AiReasonCode.CIRCUIT_OPEN
                    : openFailureKind.defaultReasonCode();
            return new ProviderHealth(
                    checkedProviderId,
                    healthState,
                    snapshot.observedAt(),
                    snapshot.retryAfter(),
                    Optional.of(reasonCode.wireCode()));
        }
        if (snapshot.state() == AiCircuitState.HALF_OPEN) {
            return new ProviderHealth(
                    checkedProviderId,
                    ProviderHealthState.DEGRADED,
                    snapshot.observedAt(),
                    Optional.empty(),
                    Optional.of(AiReasonCode.CIRCUIT_HALF_OPEN.wireCode()));
        }
        if (snapshot.consecutiveTransientFailures() > 0) {
            return new ProviderHealth(
                    checkedProviderId,
                    ProviderHealthState.DEGRADED,
                    snapshot.observedAt(),
                    Optional.empty(),
                    Optional.of(AiReasonCode.TRANSIENT_FAILURE.wireCode()));
        }
        return ProviderHealth.healthy(checkedProviderId, snapshot.observedAt());
    }

    private void expireLeases(Instant now) {
        List<AiCircuitPermit> expired = new ArrayList<>();
        Iterator<Map.Entry<Long, AiCircuitPermit>> iterator =
                activePermits.entrySet().iterator();
        while (iterator.hasNext()) {
            AiCircuitPermit permit = iterator.next().getValue();
            if (!now.isBefore(permit.expiresAt())) {
                iterator.remove();
                expired.add(permit);
            }
        }
        for (AiCircuitPermit permit : expired) {
            if (permit.epoch() == epoch) {
                applyFailure(AiFailureKind.TIMEOUT, now);
            }
        }
    }

    private boolean removeCurrentPermit(AiCircuitPermit permit) {
        if (permit == null || permit.epoch() != epoch) {
            return false;
        }
        AiCircuitPermit current = activePermits.remove(permit.permitId());
        return current == permit;
    }

    private boolean isCurrentPermit(AiCircuitPermit permit) {
        return permit != null
                && permit.epoch() == epoch
                && activePermits.get(permit.permitId()) == permit;
    }

    private void applyFailure(AiFailureKind failureKind, Instant now) {
        if (isAvailabilityBlockingFailure(failureKind)) {
            openCircuit(now, failureKind);
            return;
        }
        if (state == AiCircuitState.HALF_OPEN) {
            if (failureKind != AiFailureKind.CANCELLED) {
                openCircuit(now);
            }
            return;
        }
        if (state != AiCircuitState.CLOSED || !countsTowardCircuit(failureKind)) {
            return;
        }
        if (consecutiveTransientFailures < policy.failureThreshold()) {
            consecutiveTransientFailures++;
        }
        if (consecutiveTransientFailures >= policy.failureThreshold()) {
            openCircuit(now);
        }
    }

    private boolean countsTowardCircuit(AiFailureKind failureKind) {
        return failureKind == AiFailureKind.RATE_LIMITED
                || failureKind == AiFailureKind.TIMEOUT
                || failureKind == AiFailureKind.UNAVAILABLE
                || failureKind == AiFailureKind.OVERLOADED;
    }

    private void transitionToHalfOpen() {
        state = AiCircuitState.HALF_OPEN;
        epoch++;
        activePermits.clear();
        consecutiveTransientFailures = 0;
        openFailureKind = AiFailureKind.CIRCUIT_OPEN;
    }

    private void closeCircuit() {
        state = AiCircuitState.CLOSED;
        epoch++;
        activePermits.clear();
        consecutiveTransientFailures = 0;
        openUntil = Instant.EPOCH;
        openFailureKind = AiFailureKind.CIRCUIT_OPEN;
    }

    private void openCircuit(Instant now) {
        openCircuit(now, AiFailureKind.CIRCUIT_OPEN);
    }

    private void openCircuit(Instant now, AiFailureKind failureKind) {
        state = AiCircuitState.OPEN;
        epoch++;
        activePermits.clear();
        consecutiveTransientFailures = (failureKind == AiFailureKind.CIRCUIT_OPEN
                || countsTowardCircuit(failureKind))
                ? policy.failureThreshold()
                : 0;
        openUntil = plus(now, policy.openDuration());
        openFailureKind = failureKind;
    }

    private static boolean isAvailabilityBlockingFailure(
            AiFailureKind failureKind) {
        return failureKind == AiFailureKind.AUTHENTICATION
                || failureKind == AiFailureKind.QUOTA_EXHAUSTED;
    }

    private int countHalfOpenPermits() {
        int count = 0;
        for (AiCircuitPermit permit : activePermits.values()) {
            if (permit.halfOpenProbe()) {
                count++;
            }
        }
        return count;
    }

    private long nextPermitId() {
        if (nextPermitId == Long.MAX_VALUE) {
            throw new IllegalStateException("circuit permit id space exhausted");
        }
        return nextPermitId++;
    }

    private static AiCircuitAdmission rejected(
            AiCircuitState state,
            AiFailureKind failureKind,
            Optional<Instant> retryAfter) {
        return new AiCircuitAdmission(
                state,
                Optional.empty(),
                retryAfter,
                Optional.of(failureKind));
    }

    private static Instant plus(Instant instant, java.time.Duration duration) {
        try {
            return instant.plus(duration);
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException(
                    "circuit deadline exceeds Instant range");
        }
    }
}
