package io.github.greytaiwolf.botplayer.ai;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AiRetryPolicyAndHealthTest {
    private static final Instant START = Instant.parse("2026-08-11T00:00:00Z");

    @Test
    void clampsProviderRetryAfterAndStopsAtAttemptBudgetOrDeadline() {
        AiRetryPolicy policy = new AiRetryPolicy(
                3,
                2,
                Duration.ofMillis(100L),
                Duration.ofMillis(500L),
                Duration.ofMillis(300L),
                AiRetryJitter.none());
        AiProviderException rateLimited = AiProviderException.rateLimited(
                START.plusSeconds(30L));

        AiRetryDecision first = policy.decide(
                rateLimited,
                1,
                START,
                START.plusSeconds(2L));
        Assertions.assertTrue(first.retry());
        Assertions.assertEquals(START.plusMillis(300L),
                first.retryAt().orElseThrow());
        Assertions.assertEquals(2, first.nextAttempt());

        AiRetryDecision exhausted = policy.decide(
                rateLimited,
                3,
                START,
                START.plusSeconds(2L));
        Assertions.assertFalse(exhausted.retry());

        AiRetryDecision deadline = policy.decide(
                AiProviderException.of(AiFailureKind.UNAVAILABLE),
                1,
                START,
                START.plusMillis(100L));
        Assertions.assertFalse(deadline.retry());
    }

    @Test
    void exposesOnlySafeBoundedRequestHealthTransitions() {
        AiRequest request = AiTestFixtures.request(UUID.fromString(
                "99999999-9999-9999-9999-999999999999"));
        AiRequestHealth queued = AiRequestHealth.queued(
                request, "test", START);
        AiRequestHealth inFlight = queued.inFlight(START.plusMillis(1L));
        AiProviderException unavailable = new AiProviderException(
                AiFailureKind.UNAVAILABLE,
                Optional.empty(),
                AiReasonCode.UNAVAILABLE);
        AiRequestHealth backingOff = inFlight.backingOff(
                unavailable,
                START.plusMillis(2L),
                START.plusMillis(100L));
        AiRequestHealth completed = backingOff.inFlight(
                START.plusMillis(100L)).succeeded(START.plusMillis(101L));

        Assertions.assertEquals(AiRequestHealthState.SUCCEEDED,
                completed.state());
        Assertions.assertEquals(2, completed.attemptsStarted());
        Assertions.assertEquals(2, completed.attemptsCompleted());
        Assertions.assertTrue(completed.reasonCode().isEmpty());
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new AiRequestHealth(
                        request.requestId(),
                        "test",
                        request.model(),
                        AiRequestHealthState.SUCCEEDED,
                        1,
                        0,
                        START,
                        Optional.empty(),
                        Optional.empty(),
                Optional.empty()));
    }

    @Test
    void appliesBoundedInjectableJitterTo429AndAvailabilityRetries() {
        AiRetryPolicy policy = new AiRetryPolicy(
                3,
                2,
                Duration.ofMillis(100L),
                Duration.ofSeconds(1L),
                Duration.ofSeconds(1L),
                AiRetryJitter.fixed(25L));

        AiRetryDecision rateLimited = policy.decide(
                AiProviderException.rateLimited(START.plusMillis(200L)),
                1,
                START,
                START.plusSeconds(2L));
        AiRetryDecision unavailable = policy.decide(
                AiProviderException.of(AiFailureKind.UNAVAILABLE),
                1,
                START,
                START.plusSeconds(2L));

        Assertions.assertEquals(START.plusMillis(225L),
                rateLimited.retryAt().orElseThrow());
        Assertions.assertEquals(START.plusMillis(125L),
                unavailable.retryAt().orElseThrow());
    }
}
