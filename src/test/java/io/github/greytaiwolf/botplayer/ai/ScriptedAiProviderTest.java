package io.github.greytaiwolf.botplayer.ai;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ScriptedAiProviderTest {
    private static final String PROVIDER_ID = "scripted";
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-11T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void consumesExactRequestScriptAndKeepsHealthSafe() {
        UUID requestId = UUID.fromString(
                "44444444-4444-4444-4444-444444444444");
        AiRequest request = AiTestFixtures.request(requestId);
        AiProviderException unavailable = new AiProviderException(
                AiFailureKind.UNAVAILABLE,
                Optional.empty(),
                AiReasonCode.UNAVAILABLE);
        ScriptedAiProvider provider = new ScriptedAiProvider(
                AiTestFixtures.capabilities(PROVIDER_ID),
                CLOCK,
                Map.of(requestId, List.of(
                        new ScriptedAiOutcome.Failure(unavailable),
                        new ScriptedAiOutcome.Response(
                                AiTestFixtures.response(request, PROVIDER_ID)))));

        AiProviderException first = failure(provider.complete(
                request, CancellationToken.none()));
        Assertions.assertEquals(AiFailureKind.UNAVAILABLE, first.failureKind());
        Assertions.assertEquals(
                ProviderHealthState.UNAVAILABLE, provider.health().state());

        AiResponse response = provider.complete(request, CancellationToken.none())
                .toCompletableFuture()
                .join();
        Assertions.assertEquals(requestId, response.requestId());
        Assertions.assertEquals(ProviderHealthState.HEALTHY, provider.health().state());

        AiProviderException exhausted = failure(provider.complete(
                request, CancellationToken.none()));
        Assertions.assertEquals(AiFailureKind.OVERLOADED, exhausted.failureKind());
    }

    @Test
    void rejectsMismatchedScriptAndCancelledCallsWithoutProviderWork() {
        UUID scriptedId = UUID.fromString(
                "55555555-5555-5555-5555-555555555555");
        UUID mismatchedId = UUID.fromString(
                "66666666-6666-6666-6666-666666666666");
        AiRequest scriptedRequest = AiTestFixtures.request(scriptedId);
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new ScriptedAiProvider(
                        AiTestFixtures.capabilities(PROVIDER_ID),
                        CLOCK,
                        Map.of(mismatchedId, List.of(
                                new ScriptedAiOutcome.Response(
                                        AiTestFixtures.response(
                                                scriptedRequest,
                                                PROVIDER_ID))))));

        ScriptedAiProvider provider = new ScriptedAiProvider(
                AiTestFixtures.capabilities(PROVIDER_ID),
                CLOCK,
                Map.of(scriptedId, List.of(new ScriptedAiOutcome.Response(
                        AiTestFixtures.response(scriptedRequest, PROVIDER_ID)))));
        CancellationTokenSource cancelled = new CancellationTokenSource();
        cancelled.cancel();

        AiProviderException exception = failure(provider.complete(
                scriptedRequest, cancelled.token()));
        Assertions.assertEquals(AiFailureKind.CANCELLED, exception.failureKind());
        Assertions.assertEquals(ProviderHealthState.DEGRADED, provider.health().state());
    }

    @Test
    void completesDelayedFixtureOnlyWithExplicitScheduler() throws Exception {
        UUID requestId = UUID.fromString(
                "12121212-1212-1212-1212-121212121212");
        AiRequest request = AiTestFixtures.request(requestId);
        ScriptedAiOutcome delayed = new ScriptedAiOutcome.Delayed(
                Duration.ofMillis(10L),
                new ScriptedAiOutcome.Response(
                        AiTestFixtures.response(request, PROVIDER_ID)));

        ScriptedAiProvider noScheduler = new ScriptedAiProvider(
                AiTestFixtures.capabilities(PROVIDER_ID),
                CLOCK,
                Map.of(requestId, List.of(delayed)));
        AiProviderException required = failure(noScheduler.complete(
                request, CancellationToken.none()));
        Assertions.assertEquals(AiFailureKind.UNAVAILABLE,
                required.failureKind());
        Assertions.assertEquals(AiReasonCode.SCHEDULER_REQUIRED,
                required.reasonCode());

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            ScriptedAiProvider provider = new ScriptedAiProvider(
                    AiTestFixtures.capabilities(PROVIDER_ID),
                    CLOCK,
                    Map.of(requestId, List.of(delayed)),
                    scheduler);

            AiResponse response = provider.complete(request, CancellationToken.none())
                    .toCompletableFuture()
                    .get(1L, TimeUnit.SECONDS);

            Assertions.assertEquals(requestId, response.requestId());
            Assertions.assertEquals(ProviderHealthState.HEALTHY,
                    provider.health().state());
        } finally {
            scheduler.shutdownNow();
            Assertions.assertTrue(scheduler.awaitTermination(1L, TimeUnit.SECONDS));
        }
    }

    private static AiProviderException failure(
            java.util.concurrent.CompletionStage<?> stage) {
        CompletionException exception = Assertions.assertThrows(
                CompletionException.class,
                () -> stage.toCompletableFuture().join());
        return Assertions.assertInstanceOf(
                AiProviderException.class, exception.getCause());
    }
}
