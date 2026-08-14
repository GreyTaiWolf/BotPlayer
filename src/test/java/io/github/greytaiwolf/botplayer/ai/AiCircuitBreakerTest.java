package io.github.greytaiwolf.botplayer.ai;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AiCircuitBreakerTest {
    private static final Instant START = Instant.parse("2026-08-11T00:00:00Z");

    @Test
    void opensAfterBoundedTransientFailuresThenClosesOnHalfOpenSuccess() {
        AiCircuitBreaker breaker = new AiCircuitBreaker(
                new AiCircuitBreakerPolicy(
                        2,
                        1,
                        2,
                        Duration.ofSeconds(10L),
                        Duration.ofSeconds(5L)));

        AiCircuitPermit first = breaker.admit(START).permit().orElseThrow();
        Assertions.assertTrue(breaker.recordFailure(
                first, AiFailureKind.UNAVAILABLE, START));
        Assertions.assertEquals(
                AiCircuitState.CLOSED, breaker.snapshot(START).state());

        AiCircuitPermit second = breaker.admit(START).permit().orElseThrow();
        Assertions.assertTrue(breaker.recordFailure(
                second, AiFailureKind.TIMEOUT, START));
        AiCircuitSnapshot open = breaker.snapshot(START);
        Assertions.assertEquals(AiCircuitState.OPEN, open.state());
        Assertions.assertEquals(START.plusSeconds(10L),
                open.retryAfter().orElseThrow());

        AiCircuitAdmission blocked = breaker.admit(START.plusSeconds(1L));
        Assertions.assertFalse(blocked.accepted());
        Assertions.assertEquals(AiFailureKind.CIRCUIT_OPEN,
                blocked.rejectionKind().orElseThrow());

        AiCircuitPermit halfOpen = breaker.admit(START.plusSeconds(10L))
                .permit()
                .orElseThrow();
        Assertions.assertTrue(halfOpen.halfOpenProbe());
        Assertions.assertTrue(breaker.recordSuccess(
                halfOpen, START.plusSeconds(10L)));
        Assertions.assertEquals(AiCircuitState.CLOSED,
                breaker.snapshot(START.plusSeconds(10L)).state());
    }

    @Test
    void expiresLeakedPermitsAndRejectsLateCompletion() {
        AiCircuitBreaker breaker = new AiCircuitBreaker(
                new AiCircuitBreakerPolicy(
                        1,
                        1,
                        1,
                        Duration.ofSeconds(10L),
                        Duration.ofSeconds(2L)));

        AiCircuitPermit permit = breaker.admit(START).permit().orElseThrow();
        AiCircuitSnapshot expired = breaker.snapshot(START.plusSeconds(2L));
        Assertions.assertEquals(AiCircuitState.OPEN, expired.state());
        Assertions.assertFalse(breaker.recordSuccess(
                permit, START.plusSeconds(2L)));
        Assertions.assertEquals(ProviderHealthState.CIRCUIT_OPEN,
                breaker.health("test", START.plusSeconds(2L)).state());
    }

    @Test
    void opensImmediatelyForAuthenticationFailureWithoutRetryingRequests() {
        AiCircuitBreaker breaker = new AiCircuitBreaker(
                new AiCircuitBreakerPolicy(
                        3,
                        1,
                        3,
                        Duration.ofSeconds(10L),
                        Duration.ofSeconds(2L)));

        AiCircuitPermit permit = breaker.admit(START).permit().orElseThrow();
        Assertions.assertTrue(breaker.recordFailure(
                permit, AiFailureKind.AUTHENTICATION, START));

        Assertions.assertEquals(AiCircuitState.OPEN,
                breaker.snapshot(START).state());
        Assertions.assertEquals(ProviderHealthState.AUTHENTICATION_FAILED,
                breaker.health("test", START).state());
        Assertions.assertEquals(AiFailureKind.CIRCUIT_OPEN,
                breaker.admit(START).rejectionKind().orElseThrow());
    }

    @Test
    void keepsConcurrentHalfOpenProbesUntilEveryOutstandingProbeSucceeds() {
        AiCircuitBreaker breaker = new AiCircuitBreaker(
                new AiCircuitBreakerPolicy(
                        1,
                        2,
                        2,
                        Duration.ofSeconds(10L),
                        Duration.ofSeconds(2L)));
        AiCircuitPermit initial = breaker.admit(START).permit().orElseThrow();
        breaker.recordFailure(initial, AiFailureKind.UNAVAILABLE, START);

        Instant probeAt = START.plusSeconds(10L);
        AiCircuitPermit firstProbe = breaker.admit(probeAt).permit().orElseThrow();
        AiCircuitPermit secondProbe = breaker.admit(probeAt).permit().orElseThrow();
        Assertions.assertTrue(breaker.recordSuccess(firstProbe, probeAt));
        Assertions.assertEquals(AiCircuitState.HALF_OPEN,
                breaker.snapshot(probeAt).state());
        Assertions.assertTrue(breaker.recordFailure(
                secondProbe, AiFailureKind.UNAVAILABLE, probeAt));
        Assertions.assertEquals(AiCircuitState.OPEN,
                breaker.snapshot(probeAt).state());
    }

    @Test
    void rejectsInvalidPolicyBounds() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new AiCircuitBreakerPolicy(
                        0,
                        1,
                        1,
                        Duration.ofSeconds(1L),
                        Duration.ofSeconds(1L)));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new AiCircuitBreakerPolicy(
                        1,
                        2,
                        1,
                        Duration.ofSeconds(1L),
                        Duration.ofSeconds(1L)));
    }
}
