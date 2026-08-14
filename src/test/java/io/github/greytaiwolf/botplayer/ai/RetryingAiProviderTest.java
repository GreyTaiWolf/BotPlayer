package io.github.greytaiwolf.botplayer.ai;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RetryingAiProviderTest {
    private static final String PROVIDER_ID = "retrying";

    private ScheduledExecutorService scheduler;

    @BeforeEach
    void createScheduler() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    @AfterEach
    void stopScheduler() throws InterruptedException {
        scheduler.shutdownNow();
        Assertions.assertTrue(scheduler.awaitTermination(2L, TimeUnit.SECONDS));
    }

    @Test
    void retriesOnlyWithinBudgetAndPublishesSafeHealth() throws Exception {
        UUID requestId = UUID.fromString(
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        AiRequest request = AiTestFixtures.request(requestId);
        ScriptedAiProvider scripted = new ScriptedAiProvider(
                AiTestFixtures.capabilities(PROVIDER_ID),
                Clock.systemUTC(),
                Map.of(requestId, List.of(
                        new ScriptedAiOutcome.Failure(
                                AiProviderException.of(
                                        AiFailureKind.UNAVAILABLE)),
                        new ScriptedAiOutcome.Response(
                                AiTestFixtures.response(request, PROVIDER_ID)))));
        RetryingAiProvider provider = provider(scripted, 2, 2);

        AiResponse response = provider.complete(request, CancellationToken.none())
                .toCompletableFuture()
                .get(2L, TimeUnit.SECONDS);
        AiRequestHealth health = provider.requestHealth(requestId).orElseThrow();

        Assertions.assertEquals(requestId, response.requestId());
        Assertions.assertEquals(AiRequestHealthState.SUCCEEDED, health.state());
        Assertions.assertEquals(2, health.attemptsStarted());
        Assertions.assertEquals(2, health.attemptsCompleted());
    }

    @Test
    void stopsAtMaximumAttemptsWithoutLeakingFailureText() {
        UUID requestId = UUID.fromString(
                "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        AiRequest request = AiTestFixtures.request(requestId);
        AiProvider alwaysFailing = new AiProvider() {
            @Override
            public CompletionStage<AiResponse> complete(
                    AiRequest ignored, CancellationToken token) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("sk-live-hidden-value"));
            }

            @Override
            public CompletionStage<AiCapabilities> probeCapabilities() {
                return CompletableFuture.completedFuture(
                        AiTestFixtures.capabilities(PROVIDER_ID));
            }

            @Override
            public ProviderHealth health() {
                return ProviderHealth.healthy(PROVIDER_ID, java.time.Instant.EPOCH);
            }
        };
        RetryingAiProvider provider = provider(alwaysFailing, 2, 2);

        AiProviderException failure = failure(provider.complete(
                request, CancellationToken.none()));
        AiRequestHealth health = provider.requestHealth(requestId).orElseThrow();

        Assertions.assertEquals(AiFailureKind.UNKNOWN, failure.failureKind());
        Assertions.assertFalse(failure.getMessage().contains("hidden-value"));
        Assertions.assertEquals(AiRequestHealthState.FAILED, health.state());
        Assertions.assertEquals(1, health.attemptsStarted());
        Assertions.assertEquals(1, health.attemptsCompleted());
    }

    @Test
    void stopsAfterTheRetryableAttemptBudget() {
        UUID requestId = UUID.fromString(
                "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
        AiRequest request = AiTestFixtures.request(requestId);
        AtomicInteger calls = new AtomicInteger();
        AiProvider unavailable = new AiProvider() {
            @Override
            public CompletionStage<AiResponse> complete(
                    AiRequest ignored, CancellationToken token) {
                calls.incrementAndGet();
                return CompletableFuture.failedFuture(
                        AiProviderException.of(AiFailureKind.UNAVAILABLE));
            }

            @Override
            public CompletionStage<AiCapabilities> probeCapabilities() {
                return CompletableFuture.completedFuture(
                        AiTestFixtures.capabilities(PROVIDER_ID));
            }

            @Override
            public ProviderHealth health() {
                return ProviderHealth.healthy(
                        PROVIDER_ID, java.time.Instant.EPOCH);
            }
        };
        RetryingAiProvider provider = provider(unavailable, 2, 2);

        AiProviderException failure = failure(provider.complete(
                request, CancellationToken.none()));
        AiRequestHealth health = provider.requestHealth(requestId).orElseThrow();

        Assertions.assertEquals(AiFailureKind.UNAVAILABLE, failure.failureKind());
        Assertions.assertEquals(2, calls.get());
        Assertions.assertEquals(AiRequestHealthState.FAILED, health.state());
        Assertions.assertEquals(2, health.attemptsStarted());
        Assertions.assertEquals(2, health.attemptsCompleted());
    }

    @Test
    void rejectsConcurrentRequestsBeyondBoundedCapacity() {
        UUID firstId = UUID.fromString(
                "cccccccc-cccc-cccc-cccc-cccccccccccc");
        UUID secondId = UUID.fromString(
                "dddddddd-dddd-dddd-dddd-dddddddddddd");
        CompletableFuture<AiResponse> never = new CompletableFuture<>();
        AiProvider pending = new AiProvider() {
            @Override
            public CompletionStage<AiResponse> complete(
                    AiRequest request, CancellationToken token) {
                return never;
            }

            @Override
            public CompletionStage<AiCapabilities> probeCapabilities() {
                return CompletableFuture.completedFuture(
                        AiTestFixtures.capabilities(PROVIDER_ID));
            }

            @Override
            public ProviderHealth health() {
                return ProviderHealth.healthy(PROVIDER_ID, java.time.Instant.EPOCH);
            }
        };
        RetryingAiProvider provider = provider(pending, 1, 1);

        CompletableFuture<AiResponse> first = provider.complete(
                AiTestFixtures.request(firstId), CancellationToken.none())
                .toCompletableFuture();
        AiProviderException overloaded = failure(provider.complete(
                AiTestFixtures.request(secondId), CancellationToken.none()));
        Assertions.assertEquals(AiFailureKind.OVERLOADED, overloaded.failureKind());

        first.cancel(false);
        Assertions.assertEquals(AiRequestHealthState.CANCELLED,
                provider.requestHealth(firstId).orElseThrow().state());
    }

    @Test
    void deadlineCancelsTheActiveDelegateFuture() throws Exception {
        UUID requestId = UUID.fromString(
                "12121212-1212-1212-1212-121212121212");
        TrackingFuture<AiResponse> pending = new TrackingFuture<>();
        RetryingAiProvider provider = provider(providerReturning(pending), 1, 1);

        AiProviderException failure = failure(provider.complete(
                requestWithTimeout(requestId, 100L), CancellationToken.none()));

        Assertions.assertEquals(AiFailureKind.TIMEOUT, failure.failureKind());
        Assertions.assertTrue(pending.awaitCancellation());
        Assertions.assertTrue(pending.isCancelled());
        Assertions.assertTrue(pending.wasInterrupted());
        Assertions.assertEquals(AiRequestHealthState.FAILED,
                provider.requestHealth(requestId).orElseThrow().state());
    }

    @Test
    void callerCancellationCancelsDelegateAndDoesNotDegradeProviderHealth()
            throws Exception {
        UUID firstId = UUID.fromString(
                "19191919-1919-1919-1919-191919191919");
        UUID secondId = UUID.fromString(
                "20202020-2020-2020-2020-202020202020");
        TrackingFuture<AiResponse> pending = new TrackingFuture<>();
        AtomicInteger calls = new AtomicInteger();
        AiProvider delegate = new AiProvider() {
            @Override
            public CompletionStage<AiResponse> complete(
                    AiRequest request, CancellationToken token) {
                if (calls.getAndIncrement() == 0) {
                    return pending;
                }
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
        RetryingAiProvider provider = provider(delegate, 1, 1);

        CompletableFuture<AiResponse> cancelled = provider.complete(
                AiTestFixtures.request(firstId), CancellationToken.none())
                .toCompletableFuture();
        Assertions.assertTrue(cancelled.cancel(false));

        Assertions.assertTrue(pending.awaitCancellation());
        Assertions.assertTrue(pending.isCancelled());
        Assertions.assertTrue(pending.wasInterrupted());
        Assertions.assertEquals(AiRequestHealthState.CANCELLED,
                provider.requestHealth(firstId).orElseThrow().state());
        ProviderHealth health = provider.health();
        Assertions.assertEquals(ProviderHealthState.HEALTHY, health.state());
        Assertions.assertTrue(health.reasonCode().isEmpty());

        AiResponse response = provider.complete(
                AiTestFixtures.request(secondId), CancellationToken.none())
                .toCompletableFuture()
                .get(2L, TimeUnit.SECONDS);
        Assertions.assertEquals(secondId, response.requestId());
        Assertions.assertEquals(2, calls.get());
    }

    @Test
    void externalCancellationSourceImmediatelyCancelsDelegateAndReleasesSlot()
            throws Exception {
        UUID firstId = UUID.fromString(
                "21212121-2121-2121-2121-212121212121");
        UUID secondId = UUID.fromString(
                "22222222-2222-2222-2222-222222222222");
        TrackingFuture<AiResponse> pending = new TrackingFuture<>();
        AtomicInteger delegateCancellationNotifications = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        AiProvider delegate = new AiProvider() {
            @Override
            public CompletionStage<AiResponse> complete(
                    AiRequest request, CancellationToken token) {
                if (calls.getAndIncrement() == 0) {
                    token.onCancellation(
                            delegateCancellationNotifications::incrementAndGet);
                    return pending;
                }
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
        RetryingAiProvider provider = provider(delegate, 1, 1);
        CancellationTokenSource source = new CancellationTokenSource();

        CompletionStage<AiResponse> cancelled = provider.complete(
                AiTestFixtures.request(firstId), source.token());

        Assertions.assertTrue(source.cancel());
        AiProviderException failure = failure(cancelled);
        Assertions.assertEquals(AiFailureKind.CANCELLED, failure.failureKind());
        Assertions.assertTrue(pending.awaitCancellation());
        Assertions.assertTrue(pending.isCancelled());
        Assertions.assertTrue(pending.wasInterrupted());
        Assertions.assertEquals(1, delegateCancellationNotifications.get());
        Assertions.assertEquals(AiRequestHealthState.CANCELLED,
                provider.requestHealth(firstId).orElseThrow().state());
        Assertions.assertEquals(ProviderHealthState.HEALTHY,
                provider.health().state());

        AiResponse response = provider.complete(
                AiTestFixtures.request(secondId), CancellationToken.none())
                .toCompletableFuture()
                .get(2L, TimeUnit.SECONDS);
        Assertions.assertEquals(secondId, response.requestId());
        Assertions.assertEquals(2, calls.get());
    }

    @Test
    void cancellationRaceWithDelegateCompletionLeavesTerminalHealthAndSlot()
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int iteration = 0; iteration < 32; iteration++) {
                UUID requestId = new UUID(0L, iteration + 100L);
                CompletableFuture<AiResponse> pending = new CompletableFuture<>();
                AtomicInteger calls = new AtomicInteger();
                AiProvider delegate = new AiProvider() {
                    @Override
                    public CompletionStage<AiResponse> complete(
                            AiRequest request, CancellationToken token) {
                        if (calls.getAndIncrement() == 0) {
                            return pending;
                        }
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
                        return ProviderHealth.healthy(
                                PROVIDER_ID, Instant.EPOCH);
                    }
                };
                RetryingAiProvider provider = provider(delegate, 1, 1);
                CancellationTokenSource source = new CancellationTokenSource();
                AiRequest request = AiTestFixtures.request(requestId);
                CompletableFuture<AiResponse> result = provider.complete(
                        request, source.token()).toCompletableFuture();
                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch start = new CountDownLatch(1);
                Future<?> cancellation = executor.submit(() -> {
                    ready.countDown();
                    await(start);
                    source.cancel();
                });
                Future<?> response = executor.submit(() -> {
                    ready.countDown();
                    await(start);
                    pending.complete(AiTestFixtures.response(
                            request, PROVIDER_ID));
                });

                Assertions.assertTrue(ready.await(2L, TimeUnit.SECONDS));
                start.countDown();
                cancellation.get(2L, TimeUnit.SECONDS);
                response.get(2L, TimeUnit.SECONDS);

                AiRequestHealth health = provider.requestHealth(requestId)
                        .orElseThrow();
                if (result.isCompletedExceptionally()) {
                    Assertions.assertEquals(AiFailureKind.CANCELLED,
                            failure(result).failureKind());
                    Assertions.assertEquals(AiRequestHealthState.CANCELLED,
                            health.state());
                } else {
                    Assertions.assertEquals(requestId, result.get(
                            2L, TimeUnit.SECONDS).requestId());
                    Assertions.assertEquals(AiRequestHealthState.SUCCEEDED,
                            health.state());
                }

                UUID followUpId = new UUID(0L, iteration + 1000L);
                AiResponse followUp = provider.complete(
                        AiTestFixtures.request(followUpId),
                        CancellationToken.none()).toCompletableFuture().get(
                                2L, TimeUnit.SECONDS);
                Assertions.assertEquals(followUpId, followUp.requestId());
                Assertions.assertEquals(2, calls.get());
            }
        } finally {
            executor.shutdownNow();
            Assertions.assertTrue(executor.awaitTermination(
                    2L, TimeUnit.SECONDS));
        }
    }

    @Test
    void terminalRequestReleasesItsExternalCancellationRegistration()
            throws Exception {
        UUID requestId = UUID.fromString(
                "23232323-2323-2323-2323-232323232323");
        TrackingCancellationToken token = new TrackingCancellationToken();
        AiProvider immediate = new AiProvider() {
            @Override
            public CompletionStage<AiResponse> complete(
                    AiRequest request, CancellationToken ignored) {
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
        RetryingAiProvider provider = provider(immediate, 1, 1);

        provider.complete(AiTestFixtures.request(requestId), token)
                .toCompletableFuture()
                .get(2L, TimeUnit.SECONDS);

        Assertions.assertEquals(1, token.registrations.get());
        Assertions.assertEquals(1, token.releases.get());
    }

    @Test
    void failsClosedWhenALateLeaseSettlementWouldOtherwiseReturnAResponse() {
        Instant start = Instant.parse("2026-08-11T00:00:00Z");
        MutableClock clock = new MutableClock(start);
        UUID requestId = UUID.fromString(
                "13131313-1313-1313-1313-131313131313");
        AiRequest request = AiTestFixtures.request(requestId);
        CompletableFuture<AiResponse> lateResponse = new CompletableFuture<>();
        AiProvider delayed = providerReturning(lateResponse);
        RetryingAiProvider provider = provider(
                delayed,
                3,
                1,
                clock,
                Duration.ofMillis(1L));

        CompletionStage<AiResponse> result = provider.complete(
                request, CancellationToken.none());
        clock.set(start.plusSeconds(1L));
        lateResponse.complete(AiTestFixtures.response(request, PROVIDER_ID));

        AiProviderException failure = failure(result);
        AiRequestHealth health = provider.requestHealth(requestId).orElseThrow();
        Assertions.assertEquals(AiFailureKind.TIMEOUT, failure.failureKind());
        Assertions.assertEquals(AiReasonCode.STALE_LEASE, failure.reasonCode());
        Assertions.assertEquals(AiRequestHealthState.FAILED, health.state());
    }

    @Test
    void preservesRateLimitAndUnavailableHealthIncludingRetryAfter() {
        Instant start = Instant.parse("2026-08-11T00:00:00Z");
        Clock clock = Clock.fixed(start, ZoneOffset.UTC);
        AiRequest rateRequest = AiTestFixtures.request(UUID.fromString(
                "14141414-1414-1414-1414-141414141414"));
        AiProvider rateLimited = failingProvider(
                AiProviderException.rateLimited(start.plusSeconds(30L)));
        RetryingAiProvider rateProvider = provider(
                rateLimited, 1, 1, clock, Duration.ofSeconds(2L));

        failure(rateProvider.complete(rateRequest, CancellationToken.none()));
        ProviderHealth rateHealth = rateProvider.health();

        Assertions.assertEquals(ProviderHealthState.RATE_LIMITED,
                rateHealth.state());
        Assertions.assertEquals(start.plusSeconds(30L),
                rateHealth.retryAfter().orElseThrow());
        Assertions.assertEquals(AiReasonCode.RATE_LIMITED.wireCode(),
                rateHealth.reasonCode().orElseThrow());

        AiRequest unavailableRequest = AiTestFixtures.request(UUID.fromString(
                "15151515-1515-1515-1515-151515151515"));
        RetryingAiProvider unavailableProvider = provider(
                failingProvider(AiProviderException.of(AiFailureKind.UNAVAILABLE)),
                1,
                1,
                clock,
                Duration.ofSeconds(2L));

        failure(unavailableProvider.complete(
                unavailableRequest, CancellationToken.none()));
        Assertions.assertEquals(ProviderHealthState.UNAVAILABLE,
                unavailableProvider.health().state());
    }

    @Test
    void rejectsUnrepresentableDeadlineBeforeReservingTheOnlySlot() {
        MutableClock clock = new MutableClock(Instant.MAX);
        AiProvider immediate = new AiProvider() {
            @Override
            public CompletionStage<AiResponse> complete(
                    AiRequest request, CancellationToken token) {
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
        RetryingAiProvider provider = provider(
                immediate, 1, 1, clock, Duration.ofSeconds(2L));
        UUID overflowId = UUID.fromString(
                "16161616-1616-1616-1616-161616161616");

        AiProviderException overflow = failure(provider.complete(
                AiTestFixtures.request(overflowId), CancellationToken.none()));

        Assertions.assertEquals(AiReasonCode.DEADLINE_OUT_OF_RANGE,
                overflow.reasonCode());
        Assertions.assertTrue(provider.requestHealth(overflowId).isEmpty());

        clock.set(Instant.parse("2026-08-11T00:00:00Z"));
        AiResponse response = provider.complete(AiTestFixtures.request(
                UUID.fromString("17171717-1717-1717-1717-171717171717")),
                CancellationToken.none()).toCompletableFuture().join();
        Assertions.assertEquals(PROVIDER_ID, response.providerId());
    }

    @Test
    void convertsWhenCompleteAttachmentFailuresToSafeProviderFailures() {
        UUID requestId = UUID.fromString(
                "18181818-1818-1818-1818-181818181818");
        ThrowingWhenCompleteFuture<AiResponse> pending =
                new ThrowingWhenCompleteFuture<>();
        AiProvider attachmentFailure = new AiProvider() {
            @Override
            public CompletionStage<AiResponse> complete(
                    AiRequest request, CancellationToken token) {
                return pending;
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
        RetryingAiProvider provider = provider(attachmentFailure, 1, 1);

        AiProviderException failure = failure(provider.complete(
                AiTestFixtures.request(requestId), CancellationToken.none()));

        Assertions.assertEquals(AiReasonCode.CALLBACK_ATTACHMENT_FAILED,
                failure.reasonCode());
        Assertions.assertEquals(AiRequestHealthState.FAILED,
                provider.requestHealth(requestId).orElseThrow().state());
        Assertions.assertTrue(pending.isCancelled());
    }

    private RetryingAiProvider provider(
            AiProvider delegate, int maximumAttempts, int maximumInFlight) {
        return provider(
                delegate,
                maximumAttempts,
                maximumInFlight,
                Clock.systemUTC(),
                Duration.ofSeconds(2L));
    }

    private RetryingAiProvider provider(
            AiProvider delegate,
            int maximumAttempts,
            int maximumInFlight,
            Clock clock,
            Duration permitLeaseDuration) {
        return new RetryingAiProvider(
                PROVIDER_ID,
                delegate,
                new AiCircuitBreaker(new AiCircuitBreakerPolicy(
                        3,
                        1,
                        maximumInFlight,
                        Duration.ofSeconds(1L),
                        permitLeaseDuration)),
                new AiRetryPolicy(
                        maximumAttempts,
                        maximumInFlight,
                        Duration.ZERO,
                        Duration.ZERO,
                        Duration.ZERO,
                        AiRetryJitter.none()),
                scheduler,
                clock);
    }

    private static AiProvider providerReturning(
            CompletionStage<AiResponse> stage) {
        return new AiProvider() {
            @Override
            public CompletionStage<AiResponse> complete(
                    AiRequest request, CancellationToken token) {
                return stage;
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

    private static AiProvider failingProvider(AiProviderException exception) {
        return new AiProvider() {
            @Override
            public CompletionStage<AiResponse> complete(
                    AiRequest request, CancellationToken token) {
                return CompletableFuture.failedFuture(exception);
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

    private static AiRequest requestWithTimeout(
            UUID requestId, long timeoutMillis) {
        AiRequest base = AiTestFixtures.request(requestId);
        return new AiRequest(
                requestId,
                base.model(),
                base.messages(),
                new AiRequestOptions(
                        base.options().maximumOutputTokens(),
                        timeoutMillis,
                        base.options().responseFormat(),
                        base.options().reasoningAllowed(),
                        base.options().toolCallsAllowed(),
                        Optional.empty()),
                base.responseSchemaJson());
    }

    private static final class MutableClock extends Clock {
        private volatile Instant value;

        private MutableClock(Instant value) {
            this.value = value;
        }

        private void set(Instant value) {
            this.value = value;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(value, zone);
        }

        @Override
        public Instant instant() {
            return value;
        }
    }

    private static final class ThrowingWhenCompleteFuture<T>
            extends CompletableFuture<T> {
        @Override
        public CompletableFuture<T> whenComplete(
                BiConsumer<? super T, ? super Throwable> action) {
            throw new IllegalStateException("callback attachment unavailable");
        }
    }

    private static final class TrackingFuture<T> extends CompletableFuture<T> {
        private final CountDownLatch cancellation = new CountDownLatch(1);
        private volatile boolean interrupted;

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            interrupted |= mayInterruptIfRunning;
            boolean cancelled = super.cancel(mayInterruptIfRunning);
            cancellation.countDown();
            return cancelled;
        }

        private boolean awaitCancellation() throws InterruptedException {
            return cancellation.await(2L, TimeUnit.SECONDS);
        }

        private boolean wasInterrupted() {
            return interrupted;
        }
    }

    private static final class TrackingCancellationToken
            implements CancellationToken {
        private final AtomicBoolean cancellationRequested = new AtomicBoolean();
        private final AtomicInteger registrations = new AtomicInteger();
        private final AtomicInteger releases = new AtomicInteger();

        @Override
        public boolean isCancellationRequested() {
            return cancellationRequested.get();
        }

        @Override
        public CancellationToken.ListenerRegistration onCancellation(
                Runnable listener) {
            registrations.incrementAndGet();
            AtomicBoolean released = new AtomicBoolean();
            return () -> {
                if (released.compareAndSet(false, true)) {
                    releases.incrementAndGet();
                }
            };
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("test thread interrupted", exception);
        }
    }

    private static AiProviderException failure(CompletionStage<?> stage) {
        CompletionException exception = Assertions.assertThrows(
                CompletionException.class,
                () -> stage.toCompletableFuture().join());
        return Assertions.assertInstanceOf(
                AiProviderException.class, exception.getCause());
    }
}
