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
    private static final AiTokenBudgetScope BUDGET_SCOPE = new AiTokenBudgetScope(
            new UUID(0L, 11L), new UUID(0L, 12L), new UUID(0L, 13L));
    private static final AiModelAdmission BUDGET_ADMISSION = new AiModelAdmission(
            AiModelAdmissionStatus.ACCEPTED, 20L, 30L, 50L);

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

    @Test
    void budgetedRetrySettlesFreshReservationForEveryPhysicalDelegateCall()
            throws Exception {
        UUID requestId = UUID.fromString(
                "24242424-2424-2424-2424-242424242424");
        AiRequest request = AiTestFixtures.request(requestId);
        AtomicInteger calls = new AtomicInteger();
        AiProvider delegate = new AiProvider() {
            @Override
            public CompletionStage<AiResponse> complete(
                    AiRequest delegateRequest, CancellationToken token) {
                if (calls.getAndIncrement() == 0) {
                    return CompletableFuture.failedFuture(
                            AiProviderException.of(AiFailureKind.UNAVAILABLE));
                }
                return CompletableFuture.completedFuture(
                        AiTestFixtures.response(delegateRequest, PROVIDER_ID));
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
        AiTokenBudgetLedger ledger = budgetLedger(100L);
        RetryingAiProvider provider = provider(delegate, 2, 1);

        AiResponse response = provider.completeBudgeted(
                request,
                CancellationToken.none(),
                budgetContext(ledger, requestId, Instant.now().plusSeconds(30L)))
                .toCompletableFuture().get(2L, TimeUnit.SECONDS);

        Assertions.assertEquals(requestId, response.requestId());
        Assertions.assertEquals(2, calls.get());
        AiRequestHealth health = provider.requestHealth(requestId).orElseThrow();
        Assertions.assertEquals(AiRequestHealthState.SUCCEEDED, health.state());
        Assertions.assertEquals(2, health.attemptsStarted());
        Assertions.assertEquals(2, health.attemptsCompleted());
        AiTokenBudgetSnapshot snapshot = ledger.snapshot();
        Assertions.assertEquals(100L, snapshot.committedTokens());
        Assertions.assertEquals(0L, snapshot.reservedTokens());
        Assertions.assertEquals(0, snapshot.activeReservations());

        UUID legacyId = UUID.fromString(
                "25252525-2525-2525-2525-252525252525");
        provider.complete(AiTestFixtures.request(legacyId), CancellationToken.none())
                .toCompletableFuture().get(2L, TimeUnit.SECONDS);
        Assertions.assertEquals(3, calls.get());
        Assertions.assertEquals(100L, ledger.snapshot().committedTokens());
    }

    @Test
    void budgetExhaustionStopsBeforeTheNextPhysicalDelegateCall()
            throws Exception {
        UUID requestId = UUID.fromString(
                "26262626-2626-2626-2626-262626262626");
        AiRequest request = AiTestFixtures.request(requestId);
        AtomicInteger calls = new AtomicInteger();
        AiProvider delegate = new AiProvider() {
            @Override
            public CompletionStage<AiResponse> complete(
                    AiRequest delegateRequest, CancellationToken token) {
                if (calls.getAndIncrement() == 0) {
                    return CompletableFuture.failedFuture(
                            AiProviderException.of(AiFailureKind.UNAVAILABLE));
                }
                return CompletableFuture.completedFuture(
                        AiTestFixtures.response(delegateRequest, PROVIDER_ID));
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
        AiTokenBudgetLedger ledger = budgetLedger(50L);
        RetryingAiProvider provider = provider(delegate, 2, 1);

        AiProviderException budgetFailure = failure(provider.completeBudgeted(
                request,
                CancellationToken.none(),
                budgetContext(ledger, requestId, Instant.now().plusSeconds(30L))));

        Assertions.assertEquals(AiFailureKind.OVERLOADED,
                budgetFailure.failureKind());
        Assertions.assertEquals(1, calls.get());
        AiRequestHealth health = provider.requestHealth(requestId).orElseThrow();
        Assertions.assertEquals(AiRequestHealthState.FAILED, health.state());
        Assertions.assertEquals(1, health.attemptsStarted());
        Assertions.assertEquals(1, health.attemptsCompleted());
        AiTokenBudgetSnapshot snapshot = ledger.snapshot();
        Assertions.assertEquals(50L, snapshot.committedTokens());
        Assertions.assertEquals(0L, snapshot.reservedTokens());
        Assertions.assertEquals(0, snapshot.activeReservations());

        UUID followUpId = UUID.fromString(
                "27272727-2727-2727-2727-272727272727");
        AiResponse followUp = provider.complete(
                AiTestFixtures.request(followUpId), CancellationToken.none())
                .toCompletableFuture().get(2L, TimeUnit.SECONDS);
        Assertions.assertEquals(followUpId, followUp.requestId());
        Assertions.assertEquals(2, calls.get());
    }

    @Test
    void budgetedEntryRejectsMismatchedRequestBindingBeforeAnyDelegateCall() {
        UUID requestId = UUID.fromString(
                "28282828-2828-2828-2828-282828282828");
        AtomicInteger calls = new AtomicInteger();
        AiProvider delegate = countingProvider(
                AiTestFixtures.fixedProvider(PROVIDER_ID,
                        AiTestFixtures.response(AiTestFixtures.request(requestId), PROVIDER_ID)),
                calls);
        AiTokenBudgetLedger ledger = budgetLedger(50L);
        RetryingAiProvider provider = provider(delegate, 1, 1);

        AiProviderException failure = failure(provider.completeBudgeted(
                AiTestFixtures.request(requestId),
                CancellationToken.none(),
                budgetContext(ledger,
                        UUID.fromString("29292929-2929-2929-2929-292929292929"),
                        Instant.now().plusSeconds(30L))));

        Assertions.assertEquals(AiFailureKind.INVALID_REQUEST,
                failure.failureKind());
        Assertions.assertEquals(0, calls.get());
        Assertions.assertTrue(provider.requestHealth(requestId).isEmpty());
        AiTokenBudgetSnapshot snapshot = ledger.snapshot();
        Assertions.assertEquals(0L, snapshot.committedTokens());
        Assertions.assertEquals(0L, snapshot.reservedTokens());
        Assertions.assertEquals(0, snapshot.activeReservations());
    }

    @Test
    void closedLedgerPreventsAnyPhysicalDelegateCallWithoutDegradingProviderHealth() {
        UUID requestId = UUID.fromString(
                "30303030-3030-3030-3030-303030303030");
        AtomicInteger calls = new AtomicInteger();
        AiProvider delegate = countingProvider(
                AiTestFixtures.fixedProvider(PROVIDER_ID,
                        AiTestFixtures.response(AiTestFixtures.request(requestId), PROVIDER_ID)),
                calls);
        AiTokenBudgetLedger ledger = budgetLedger(50L);
        ledger.close();
        RetryingAiProvider provider = provider(delegate, 1, 1);

        AiProviderException failure = failure(provider.completeBudgeted(
                AiTestFixtures.request(requestId),
                CancellationToken.none(),
                budgetContext(ledger, requestId, Instant.now().plusSeconds(30L))));

        Assertions.assertEquals(AiFailureKind.UNAVAILABLE,
                failure.failureKind());
        Assertions.assertEquals(0, calls.get());
        AiRequestHealth health = provider.requestHealth(requestId).orElseThrow();
        Assertions.assertEquals(AiRequestHealthState.REJECTED, health.state());
        Assertions.assertEquals(0, health.attemptsStarted());
        Assertions.assertEquals(0, health.attemptsCompleted());
        Assertions.assertEquals(ProviderHealthState.HEALTHY,
                provider.health().state());
        AiTokenBudgetSnapshot snapshot = ledger.snapshot();
        Assertions.assertTrue(snapshot.closed());
        Assertions.assertEquals(0L, snapshot.committedTokens());
        Assertions.assertEquals(0L, snapshot.reservedTokens());
        Assertions.assertEquals(0, snapshot.activeReservations());
    }

    @Test
    void settledBudgetIsNeverRefundedForSyncThrowNullStageOrCallbackFailure() {
        assertSettledBudgetStaysCommitted(
                UUID.fromString("31313131-3131-3131-3131-313131313131"),
                new AiProvider() {
                    @Override
                    public CompletionStage<AiResponse> complete(
                            AiRequest request, CancellationToken token) {
                        throw new IllegalStateException("delegate failed synchronously");
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
                });
        assertSettledBudgetStaysCommitted(
                UUID.fromString("32323232-3232-3232-3232-323232323232"),
                providerReturning(null));
        assertSettledBudgetStaysCommitted(
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                providerReturning(new ThrowingWhenCompleteFuture<>()));
    }

    @Test
    void cancellationBeforeSettlementReleasesReservationWithoutDelegateCall()
            throws Exception {
        Instant start = Instant.now();
        BlockingLedgerClock ledgerClock = new BlockingLedgerClock(start);
        AiTokenBudgetLedger ledger = new AiTokenBudgetLedger(
                BUDGET_SCOPE,
                new AiTokenBudgetPolicy(50L, 1, Duration.ofMinutes(1L)),
                ledgerClock,
                UUID::randomUUID);
        UUID requestId = UUID.fromString(
                "34343434-3434-3434-3434-343434343434");
        AtomicInteger calls = new AtomicInteger();
        AiProvider delegate = countingProvider(
                AiTestFixtures.fixedProvider(PROVIDER_ID,
                        AiTestFixtures.response(AiTestFixtures.request(requestId), PROVIDER_ID)),
                calls);
        RetryingAiProvider provider = provider(delegate, 1, 1);
        CancellationTokenSource source = new CancellationTokenSource();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<CompletionStage<AiResponse>> started = executor.submit(() ->
                    provider.completeBudgeted(
                            AiTestFixtures.request(requestId),
                            source.token(),
                            budgetContext(ledger, requestId, start.plusSeconds(30L))));

            Assertions.assertTrue(ledgerClock.awaitReservationRead());
            Assertions.assertTrue(source.cancel());
            ledgerClock.resumeReservation();

            AiProviderException failure = failure(started.get(
                    2L, TimeUnit.SECONDS));
            Assertions.assertEquals(AiFailureKind.CANCELLED,
                    failure.failureKind());
            Assertions.assertEquals(0, calls.get());
            Assertions.assertEquals(AiRequestHealthState.CANCELLED,
                    provider.requestHealth(requestId).orElseThrow().state());
            AiTokenBudgetSnapshot snapshot = ledger.snapshot();
            Assertions.assertEquals(0L, snapshot.committedTokens());
            Assertions.assertEquals(0L, snapshot.reservedTokens());
            Assertions.assertEquals(0, snapshot.activeReservations());
        } finally {
            ledgerClock.resumeReservation();
            executor.shutdownNow();
            Assertions.assertTrue(executor.awaitTermination(2L, TimeUnit.SECONDS));
        }
    }

    @Test
    void cancellationAfterSettlementKeepsTheCommittedAttempt() throws Exception {
        UUID requestId = UUID.fromString(
                "35353535-3535-3535-3535-353535353535");
        TrackingFuture<AiResponse> pending = new TrackingFuture<>();
        AtomicInteger calls = new AtomicInteger();
        AiProvider delegate = countingProvider(providerReturning(pending), calls);
        AiTokenBudgetLedger ledger = budgetLedger(50L);
        RetryingAiProvider provider = provider(delegate, 1, 1);
        CancellationTokenSource source = new CancellationTokenSource();

        CompletionStage<AiResponse> result = provider.completeBudgeted(
                AiTestFixtures.request(requestId),
                source.token(),
                budgetContext(ledger, requestId, Instant.now().plusSeconds(30L)));
        Assertions.assertEquals(1, calls.get());
        Assertions.assertTrue(source.cancel());

        AiProviderException failure = failure(result);
        Assertions.assertEquals(AiFailureKind.CANCELLED, failure.failureKind());
        Assertions.assertTrue(pending.awaitCancellation());
        AiTokenBudgetSnapshot snapshot = ledger.snapshot();
        Assertions.assertEquals(50L, snapshot.committedTokens());
        Assertions.assertEquals(0L, snapshot.reservedTokens());
        Assertions.assertEquals(0, snapshot.activeReservations());
    }

    @Test
    void settledBudgetRemainsCommittedWhenRequestTimeoutCancelsDelegate()
            throws Exception {
        UUID requestId = UUID.fromString(
                "36353535-3535-3535-3535-353535353535");
        TrackingFuture<AiResponse> pending = new TrackingFuture<>();
        AtomicInteger calls = new AtomicInteger();
        AiProvider delegate = countingProvider(providerReturning(pending), calls);
        AiTokenBudgetLedger ledger = budgetLedger(50L);
        RetryingAiProvider provider = provider(delegate, 1, 1);

        CompletionStage<AiResponse> result = provider.completeBudgeted(
                requestWithTimeout(requestId, 100L),
                CancellationToken.none(),
                budgetContext(ledger, requestId, Instant.now().plusSeconds(30L)));
        Assertions.assertEquals(1, calls.get());

        AiProviderException failure = failure(result);
        Assertions.assertEquals(AiFailureKind.TIMEOUT, failure.failureKind());
        Assertions.assertTrue(pending.awaitCancellation());
        Assertions.assertTrue(pending.isCancelled());
        AiRequestHealth health = provider.requestHealth(requestId).orElseThrow();
        Assertions.assertEquals(AiRequestHealthState.FAILED, health.state());
        Assertions.assertEquals(1, health.attemptsStarted());
        Assertions.assertEquals(1, health.attemptsCompleted());
        AiTokenBudgetSnapshot snapshot = ledger.snapshot();
        Assertions.assertEquals(50L, snapshot.committedTokens());
        Assertions.assertEquals(0L, snapshot.reservedTokens());
        Assertions.assertEquals(0, snapshot.activeReservations());
    }

    @Test
    void upstreamDeadlineExpiringBetweenReservationAndSettlementPreventsDelegateCall()
            throws Exception {
        Instant start = Instant.parse("2026-08-20T00:00:00Z");
        AiTokenBudgetLedger ledger = new AiTokenBudgetLedger(
                BUDGET_SCOPE,
                new AiTokenBudgetPolicy(50L, 1, Duration.ofMinutes(1L)),
                new SequenceClock(start, start, start.plusSeconds(1L)),
                UUID::randomUUID);
        UUID requestId = UUID.fromString(
                "36363636-3636-3636-3636-363636363636");
        AtomicInteger calls = new AtomicInteger();
        AiProvider delegate = new AiProvider() {
            @Override
            public CompletionStage<AiResponse> complete(
                    AiRequest request, CancellationToken token) {
                calls.incrementAndGet();
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
                return ProviderHealth.healthy(PROVIDER_ID, start);
            }
        };
        RetryingAiProvider provider = provider(
                delegate,
                1,
                1,
                Clock.fixed(start, ZoneOffset.UTC),
                Duration.ofSeconds(2L));

        AiProviderException failure = failure(provider.completeBudgeted(
                AiTestFixtures.request(requestId),
                CancellationToken.none(),
                budgetContext(ledger, requestId, start.plusSeconds(1L))));

        Assertions.assertEquals(AiFailureKind.TIMEOUT, failure.failureKind());
        Assertions.assertEquals(0, calls.get());
        AiRequestHealth health = provider.requestHealth(requestId).orElseThrow();
        Assertions.assertEquals(AiRequestHealthState.REJECTED, health.state());
        Assertions.assertEquals(0, health.attemptsStarted());
        Assertions.assertEquals(0, health.attemptsCompleted());
        AiTokenBudgetSnapshot snapshot = ledger.snapshot();
        Assertions.assertEquals(0L, snapshot.committedTokens());
        Assertions.assertEquals(0L, snapshot.reservedTokens());
        Assertions.assertEquals(0, snapshot.activeReservations());

        UUID followUpId = UUID.fromString(
                "37373737-3737-3737-3737-373737373737");
        AiResponse followUp = provider.complete(
                AiTestFixtures.request(followUpId), CancellationToken.none())
                .toCompletableFuture().get(2L, TimeUnit.SECONDS);
        Assertions.assertEquals(followUpId, followUp.requestId());
        Assertions.assertEquals(1, calls.get());
    }

    @Test
    void providerClockAtUpstreamDeadlinePreventsSettlementWhenLedgerClockLags() {
        Instant ledgerNow = Instant.parse("2026-08-20T00:00:00Z");
        Instant upstreamDeadline = ledgerNow.plusSeconds(1L);
        AtomicInteger reservationIdReads = new AtomicInteger();
        AiTokenBudgetLedger ledger = new AiTokenBudgetLedger(
                BUDGET_SCOPE,
                new AiTokenBudgetPolicy(50L, 1, Duration.ofMinutes(1L)),
                Clock.fixed(ledgerNow, ZoneOffset.UTC),
                () -> {
                    reservationIdReads.incrementAndGet();
                    return UUID.randomUUID();
                });
        UUID requestId = UUID.fromString(
                "38383838-3838-3838-3838-383838383838");
        AtomicInteger calls = new AtomicInteger();
        AiProvider delegate = new AiProvider() {
            @Override
            public CompletionStage<AiResponse> complete(
                    AiRequest request, CancellationToken token) {
                calls.incrementAndGet();
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
                return ProviderHealth.healthy(PROVIDER_ID, upstreamDeadline);
            }
        };
        RetryingAiProvider provider = provider(
                delegate,
                1,
                1,
                Clock.fixed(upstreamDeadline, ZoneOffset.UTC),
                Duration.ofSeconds(2L));

        AiProviderException failure = failure(provider.completeBudgeted(
                AiTestFixtures.request(requestId),
                CancellationToken.none(),
                budgetContext(ledger, requestId, upstreamDeadline)));

        Assertions.assertEquals(AiFailureKind.TIMEOUT, failure.failureKind());
        Assertions.assertEquals(0, calls.get());
        Assertions.assertEquals(0, reservationIdReads.get());
        AiTokenBudgetSnapshot snapshot = ledger.snapshot();
        Assertions.assertEquals(0L, snapshot.committedTokens());
        Assertions.assertEquals(0L, snapshot.reservedTokens());
        Assertions.assertEquals(0, snapshot.activeReservations());
    }

    @Test
    void expiredUnstartedCircuitPermitIsAbandonedWithoutProviderHealthFailure() {
        Instant start = Instant.parse("2026-08-20T00:00:00Z");
        SequenceClock providerClock = new SequenceClock(
                start,
                start,
                start,
                start,
                start.plusSeconds(2L));
        AiTokenBudgetLedger ledger = new AiTokenBudgetLedger(
                BUDGET_SCOPE,
                new AiTokenBudgetPolicy(50L, 1, Duration.ofMinutes(1L)),
                Clock.fixed(start, ZoneOffset.UTC),
                UUID::randomUUID);
        UUID requestId = UUID.fromString(
                "39393939-3939-3939-3939-393939393939");
        AtomicInteger calls = new AtomicInteger();
        AiProvider delegate = new AiProvider() {
            @Override
            public CompletionStage<AiResponse> complete(
                    AiRequest request, CancellationToken token) {
                calls.incrementAndGet();
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
                return ProviderHealth.healthy(PROVIDER_ID, start);
            }
        };
        AiCircuitBreaker circuitBreaker = new AiCircuitBreaker(
                new AiCircuitBreakerPolicy(
                        1,
                        1,
                        1,
                        Duration.ofSeconds(10L),
                        Duration.ofSeconds(1L)));
        RetryingAiProvider provider = new RetryingAiProvider(
                PROVIDER_ID,
                delegate,
                circuitBreaker,
                new AiRetryPolicy(
                        1,
                        1,
                        Duration.ZERO,
                        Duration.ZERO,
                        Duration.ZERO,
                        AiRetryJitter.none()),
                scheduler,
                providerClock);

        AiProviderException failure = failure(provider.completeBudgeted(
                AiTestFixtures.request(requestId),
                CancellationToken.none(),
                budgetContext(ledger, requestId, start.plusSeconds(30L))));

        Assertions.assertEquals(AiFailureKind.TIMEOUT, failure.failureKind());
        Assertions.assertEquals(0, calls.get());
        AiTokenBudgetSnapshot snapshot = ledger.snapshot();
        Assertions.assertEquals(0L, snapshot.committedTokens());
        Assertions.assertEquals(0L, snapshot.reservedTokens());
        Assertions.assertEquals(0, snapshot.activeReservations());
        AiCircuitSnapshot circuit = circuitBreaker.snapshot(start.plusSeconds(2L));
        Assertions.assertEquals(AiCircuitState.CLOSED, circuit.state());
        Assertions.assertEquals(0, circuit.consecutiveTransientFailures());
        Assertions.assertEquals(0, circuit.inFlightPermits());
    }

    @Test
    void circuitObserverWinningBeforeBudgetSettlementPreventsDelegateCall()
            throws Exception {
        Instant start = Instant.parse("2026-08-20T00:00:00Z");
        BlockingNthReadClock providerClock = new BlockingNthReadClock(
                start, 5);
        AiTokenBudgetLedger ledger = new AiTokenBudgetLedger(
                BUDGET_SCOPE,
                new AiTokenBudgetPolicy(50L, 1, Duration.ofMinutes(1L)),
                Clock.fixed(start, ZoneOffset.UTC),
                UUID::randomUUID);
        UUID requestId = UUID.fromString(
                "40404040-4040-4040-4040-404040404040");
        AtomicInteger calls = new AtomicInteger();
        AiProvider delegate = countingProvider(
                AiTestFixtures.fixedProvider(PROVIDER_ID,
                        AiTestFixtures.response(
                                AiTestFixtures.request(requestId), PROVIDER_ID)),
                calls);
        AiCircuitBreaker circuitBreaker = new AiCircuitBreaker(
                new AiCircuitBreakerPolicy(
                        1,
                        1,
                        1,
                        Duration.ofSeconds(10L),
                        Duration.ofSeconds(1L)));
        RetryingAiProvider provider = new RetryingAiProvider(
                PROVIDER_ID,
                delegate,
                circuitBreaker,
                new AiRetryPolicy(
                        1,
                        1,
                        Duration.ZERO,
                        Duration.ZERO,
                        Duration.ZERO,
                        AiRetryJitter.none()),
                scheduler,
                providerClock);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<CompletionStage<AiResponse>> started = executor.submit(() ->
                    provider.completeBudgeted(
                            AiTestFixtures.request(requestId),
                            CancellationToken.none(),
                            budgetContext(
                                    ledger,
                                    requestId,
                                    start.plusSeconds(30L))));

            Assertions.assertTrue(providerClock.awaitBlockedRead());
            Assertions.assertEquals(AiCircuitState.OPEN,
                    circuitBreaker.snapshot(start.plusSeconds(2L)).state());
            providerClock.resumeBlockedRead();

            AiProviderException failure = failure(started.get(
                    2L, TimeUnit.SECONDS));
            Assertions.assertEquals(AiFailureKind.TIMEOUT,
                    failure.failureKind());
            Assertions.assertEquals(0, calls.get());
            AiRequestHealth health = provider.requestHealth(requestId)
                    .orElseThrow();
            Assertions.assertEquals(AiRequestHealthState.REJECTED,
                    health.state());
            Assertions.assertEquals(0, health.attemptsStarted());
            Assertions.assertEquals(0, health.attemptsCompleted());
            AiTokenBudgetSnapshot snapshot = ledger.snapshot();
            Assertions.assertEquals(0L, snapshot.committedTokens());
            Assertions.assertEquals(0L, snapshot.reservedTokens());
            Assertions.assertEquals(0, snapshot.activeReservations());
        } finally {
            providerClock.resumeBlockedRead();
            executor.shutdownNow();
            Assertions.assertTrue(executor.awaitTermination(2L, TimeUnit.SECONDS));
        }
    }

    private void assertSettledBudgetStaysCommitted(
            UUID requestId, AiProvider delegate) {
        AtomicInteger calls = new AtomicInteger();
        AiTokenBudgetLedger ledger = budgetLedger(50L);
        RetryingAiProvider provider = provider(countingProvider(delegate, calls), 1, 1);

        failure(provider.completeBudgeted(
                AiTestFixtures.request(requestId),
                CancellationToken.none(),
                budgetContext(ledger, requestId, Instant.now().plusSeconds(30L))));

        Assertions.assertEquals(1, calls.get());
        AiTokenBudgetSnapshot snapshot = ledger.snapshot();
        Assertions.assertEquals(50L, snapshot.committedTokens());
        Assertions.assertEquals(0L, snapshot.reservedTokens());
        Assertions.assertEquals(0, snapshot.activeReservations());
    }

    private static AiTokenBudgetLedger budgetLedger(long maximumTokens) {
        return new AiTokenBudgetLedger(BUDGET_SCOPE,
                new AiTokenBudgetPolicy(maximumTokens, 1, Duration.ofMinutes(1L)));
    }

    private static AiRetryAttemptBudgetContext budgetContext(
            AiTokenBudgetLedger ledger, UUID requestId, Instant upstreamDeadline) {
        return new AiRetryAttemptBudgetContext(
                ledger,
                new AiTokenBudgetRequestBinding(BUDGET_SCOPE, requestId, 1L),
                BUDGET_ADMISSION,
                upstreamDeadline);
    }

    private static AiProvider countingProvider(
            AiProvider delegate, AtomicInteger calls) {
        return new AiProvider() {
            @Override
            public CompletionStage<AiResponse> complete(
                    AiRequest request, CancellationToken token) {
                calls.incrementAndGet();
                return delegate.complete(request, token);
            }

            @Override
            public CompletionStage<AiCapabilities> probeCapabilities() {
                return delegate.probeCapabilities();
            }

            @Override
            public ProviderHealth health() {
                return delegate.health();
            }
        };
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

    /** Blocks ledger reservation after the constructor's first clock read, before settlement. */
    private static final class BlockingLedgerClock extends Clock {
        private final Instant value;
        private final AtomicInteger reads = new AtomicInteger();
        private final CountDownLatch reservationRead = new CountDownLatch(1);
        private final CountDownLatch resumeReservation = new CountDownLatch(1);

        private BlockingLedgerClock(Instant value) {
            this.value = value;
        }

        private boolean awaitReservationRead() throws InterruptedException {
            return reservationRead.await(2L, TimeUnit.SECONDS);
        }

        private void resumeReservation() {
            resumeReservation.countDown();
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
            if (reads.incrementAndGet() == 2) {
                reservationRead.countDown();
                try {
                    if (!resumeReservation.await(2L, TimeUnit.SECONDS)) {
                        throw new IllegalStateException(
                                "reservation clock was not resumed");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "reservation clock was interrupted", exception);
                }
            }
            return value;
        }
    }

    /** Blocks one selected provider-clock read so another thread can observe an admitted permit. */
    private static final class BlockingNthReadClock extends Clock {
        private final Instant value;
        private final int blockedRead;
        private final AtomicInteger reads = new AtomicInteger();
        private final CountDownLatch blocked = new CountDownLatch(1);
        private final CountDownLatch resume = new CountDownLatch(1);

        private BlockingNthReadClock(Instant value, int blockedRead) {
            this.value = value;
            this.blockedRead = blockedRead;
        }

        private boolean awaitBlockedRead() throws InterruptedException {
            return blocked.await(2L, TimeUnit.SECONDS);
        }

        private void resumeBlockedRead() {
            resume.countDown();
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
            if (reads.incrementAndGet() == blockedRead) {
                blocked.countDown();
                try {
                    if (!resume.await(2L, TimeUnit.SECONDS)) {
                        throw new IllegalStateException(
                                "provider clock was not resumed");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "provider clock was interrupted", exception);
                }
            }
            return value;
        }
    }

    /** Returns a fixed adversarial sequence, then keeps returning its final instant. */
    private static final class SequenceClock extends Clock {
        private final Instant[] values;
        private int index;

        private SequenceClock(Instant... values) {
            if (values.length == 0) {
                throw new IllegalArgumentException("values must not be empty");
            }
            this.values = values.clone();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant(), zone);
        }

        @Override
        public synchronized Instant instant() {
            int current = Math.min(index, values.length - 1);
            index++;
            return values[current];
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
