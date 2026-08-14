package io.github.greytaiwolf.botplayer.ai;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ChaosAiProviderTest {
    private static final String PROVIDER_ID = "chaos";
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-11T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void injectsScheduledRateLimitBeforeDelegating() {
        AiRequest request = AiTestFixtures.request(UUID.fromString(
                "77777777-7777-7777-7777-777777777777"));
        AtomicInteger delegateCalls = new AtomicInteger();
        AiProvider delegate = responseProvider(request, delegateCalls);
        ChaosAiProvider provider = new ChaosAiProvider(
                PROVIDER_ID,
                delegate,
                CLOCK,
                List.of(new ChaosAiFault(
                        1,
                        AiFailureKind.RATE_LIMITED,
                        Optional.of(Duration.ofSeconds(2L)))));

        AiProviderException first = failure(provider.complete(
                request, CancellationToken.none()));
        Assertions.assertEquals(AiFailureKind.RATE_LIMITED, first.failureKind());
        Assertions.assertEquals(
                Instant.parse("2026-08-11T00:00:02Z"),
                first.retryAfter().orElseThrow());
        Assertions.assertEquals(0, delegateCalls.get());
        Assertions.assertEquals(
                ProviderHealthState.RATE_LIMITED, provider.health().state());

        AiResponse response = provider.complete(request, CancellationToken.none())
                .toCompletableFuture()
                .join();
        Assertions.assertEquals(request.requestId(), response.requestId());
        Assertions.assertEquals(1, delegateCalls.get());
    }

    @Test
    void doesNotExposeDelegateExceptionText() {
        AiRequest request = AiTestFixtures.request(UUID.fromString(
                "88888888-8888-8888-8888-888888888888"));
        AiProvider unsafeDelegate = new AiProvider() {
            @Override
            public CompletionStage<AiResponse> complete(
                    AiRequest ignored, CancellationToken token) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("sk-live-very-secret-value"));
            }

            @Override
            public CompletionStage<AiCapabilities> probeCapabilities() {
                return CompletableFuture.completedFuture(
                        AiTestFixtures.capabilities(PROVIDER_ID));
            }

            @Override
            public ProviderHealth health() {
                return ProviderHealth.healthy(PROVIDER_ID, Instant.EPOCH);
            }
        };
        ChaosAiProvider provider = new ChaosAiProvider(
                PROVIDER_ID, unsafeDelegate, CLOCK, List.of());

        AiProviderException exception = failure(provider.complete(
                request, CancellationToken.none()));
        Assertions.assertEquals(AiFailureKind.UNKNOWN, exception.failureKind());
        Assertions.assertFalse(exception.getMessage().contains("very-secret"));
    }

    @Test
    void injectsDelayedAndMalformedFixtureFaultsWithoutDelegateWork()
            throws Exception {
        AiRequest request = AiTestFixtures.request(UUID.fromString(
                "89898989-8989-8989-8989-898989898989"));
        AtomicInteger delegateCalls = new AtomicInteger();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            ChaosAiProvider delayed = new ChaosAiProvider(
                    PROVIDER_ID,
                    responseProvider(request, delegateCalls),
                    CLOCK,
                    List.of(new ChaosAiFault(
                            1,
                            AiFailureKind.UNAVAILABLE,
                            Optional.empty(),
                            ChaosAiFaultMode.DELAYED_FAILURE,
                            Optional.of(Duration.ofMillis(10L)))),
                    scheduler);

            AiProviderException delayedFailure = failure(delayed.complete(
                    request, CancellationToken.none()));
            Assertions.assertEquals(AiFailureKind.UNAVAILABLE,
                    delayedFailure.failureKind());

            ChaosAiProvider malformed = new ChaosAiProvider(
                    PROVIDER_ID,
                    responseProvider(request, delegateCalls),
                    CLOCK,
                    List.of(new ChaosAiFault(
                            1,
                            AiFailureKind.MALFORMED_RESPONSE,
                            Optional.empty(),
                            ChaosAiFaultMode.MALFORMED_RESPONSE,
                            Optional.empty())));
            AiProviderException malformedFailure = failure(malformed.complete(
                    request, CancellationToken.none()));

            Assertions.assertEquals(AiFailureKind.MALFORMED_RESPONSE,
                    malformedFailure.failureKind());
            Assertions.assertEquals(0, delegateCalls.get());
        } finally {
            scheduler.shutdownNow();
            Assertions.assertTrue(scheduler.awaitTermination(1L, TimeUnit.SECONDS));
        }
    }

    private static AiProvider responseProvider(
            AiRequest request, AtomicInteger delegateCalls) {
        return new AiProvider() {
            @Override
            public CompletionStage<AiResponse> complete(
                    AiRequest ignored, CancellationToken token) {
                delegateCalls.incrementAndGet();
                return CompletableFuture.completedFuture(
                        AiTestFixtures.response(request, PROVIDER_ID));
            }

            @Override
            public CompletionStage<AiCapabilities> probeCapabilities() {
                return CompletableFuture.completedFuture(
                        AiTestFixtures.capabilities(PROVIDER_ID));
            }

            @Override
            public ProviderHealth health() {
                return ProviderHealth.healthy(PROVIDER_ID, Instant.EPOCH);
            }
        };
    }

    private static AiProviderException failure(CompletionStage<?> stage) {
        CompletionException exception = Assertions.assertThrows(
                CompletionException.class,
                () -> stage.toCompletableFuture().join());
        return Assertions.assertInstanceOf(
                AiProviderException.class, exception.getCause());
    }
}
