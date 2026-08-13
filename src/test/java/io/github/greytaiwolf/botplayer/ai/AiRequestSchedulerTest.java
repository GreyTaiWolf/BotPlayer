package io.github.greytaiwolf.botplayer.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;

/**
 * These tests deliberately use hostile Executor implementations through the package-private
 * supervisor seam. Production construction has no raw Executor input; the hostile handoff runs
 * only on the supervisor's isolated broker, never on submit/pump/deadline paths.
 */
class AiRequestSchedulerTest {
    private static final String PROVIDER_ID = "scheduler-test";
    private static final UUID OWNER = new UUID(1L, 1L);
    private static final UUID BOT_A = new UUID(2L, 2L);
    private static final UUID BOT_B = new UUID(3L, 3L);
    private static final UUID AGENT_A = new UUID(4L, 4L);
    private static final UUID AGENT_B = new UUID(5L, 5L);
    private static final Duration START_TIMEOUT = Duration.ofMillis(200L);
    private static final Duration STALL_TIMEOUT = Duration.ofMillis(250L);

    @Test
    void enforcesGlobalAndPerBotCapacityAndValidatesResponses() throws Exception {
        LaneFixture lanes = new LaneFixture();
        try {
            ControllableProvider provider = new ControllableProvider();
            AiRequestScheduler scheduler = scheduler(provider,
                    new AiRequestSchedulerPolicy(2, 1, 8, 8), lanes);
            AiRequest first = request("00000000-0000-0000-0000-000000000101");
            AiRequest second = request("00000000-0000-0000-0000-000000000102");
            AiRequest other = request("00000000-0000-0000-0000-000000000103");

            CompletableFuture<AiResponse> firstResult = scheduler.submit(
                    scheduled(BOT_A, AGENT_A, first), CancellationToken.none())
                    .response().toCompletableFuture();
            scheduler.submit(scheduled(BOT_A, AGENT_A, second), CancellationToken.none());
            CompletableFuture<AiResponse> otherResult = scheduler.submit(
                    scheduled(BOT_B, AGENT_B, other), CancellationToken.none())
                    .response().toCompletableFuture();

            assertTrue(await(() -> provider.calls().size() == 2, 2_000L));
            assertTrue(provider.calls().stream().anyMatch(
                    call -> call.request().requestId().equals(first.requestId())));
            assertTrue(provider.calls().stream().anyMatch(
                    call -> call.request().requestId().equals(other.requestId())));
            assertEquals(1, scheduler.queuedRequestCount());
            callFor(provider, first).stage().complete(response(first));
            assertTrue(await(() -> provider.calls().size() == 3, 2_000L));
            callFor(provider, other).stage().complete(response(other));
            callFor(provider, second).stage().complete(response(second));

            assertEquals(first.requestId(), firstResult.get(2L, TimeUnit.SECONDS).requestId());
            assertEquals(other.requestId(), otherResult.get(2L, TimeUnit.SECONDS).requestId());
            assertEquals(0, scheduler.inFlightRequestCount());

            AiRequest malformed = request("00000000-0000-0000-0000-000000000104");
            CompletableFuture<AiResponse> malformedResult = scheduler.submit(
                    scheduled(BOT_A, AGENT_A, malformed), CancellationToken.none())
                    .response().toCompletableFuture();
            assertTrue(await(() -> provider.calls().size() == 4, 2_000L));
            callFor(provider, malformed).stage().complete(response(other));
            assertEquals(AiFailureKind.MALFORMED_RESPONSE,
                    failure(malformedResult).failureKind());
            scheduler.close();
        } finally {
            lanes.close();
        }
    }

    @Test
    void absoluteDeadlineFailsAQueuedRequestBeforeItsProviderCanStart() throws Exception {
        LaneFixture lanes = new LaneFixture();
        try {
            /* Make the prerequisite first-start ordering deterministic for this deadline test. */
            lanes.set(AiRequestSchedulerSupervisor.Lane.TOKEN_SETUP, new InlineExecutor());
            MutableClock clock = new MutableClock(Instant.EPOCH);
            ControllableProvider provider = new ControllableProvider();
            AiRequestScheduler scheduler = scheduler(provider,
                    new AiRequestSchedulerPolicy(1, 1, 8, 8), lanes, clock);
            AiRequest first = request("00000000-0000-0000-0000-000000000151", 20_000L);
            AiRequest expired = request("00000000-0000-0000-0000-000000000152", 5_000L);

            CompletableFuture<AiResponse> firstResult = scheduler.submit(
                    scheduled(BOT_A, AGENT_A, first), CancellationToken.none())
                    .response().toCompletableFuture();
            CompletableFuture<AiResponse> expiredResult = scheduler.submit(
                    scheduled(BOT_B, AGENT_B, expired), CancellationToken.none())
                    .response().toCompletableFuture();
            assertTrue(await(() -> provider.calls().size() == 1, 2_000L));

            clock.set(Instant.EPOCH.plusSeconds(10L));
            callFor(provider, first).stage().complete(response(first));
            assertEquals(first.requestId(), firstResult.get(2L, TimeUnit.SECONDS).requestId());
            assertEquals(AiFailureKind.TIMEOUT, failure(expiredResult).failureKind());
            assertEquals(1, provider.calls().size());
            assertEquals(AiSchedulerRequestState.FAILED,
                    scheduler.requestHealth(expired.requestId()).orElseThrow().state());
            scheduler.close();
        } finally {
            lanes.close();
        }
    }

    @Test
    void boundedQueueRejectsWithoutLettingAResponseCopyMutateTheScheduler() throws Exception {
        LaneFixture lanes = new LaneFixture();
        try {
            ControllableProvider provider = new ControllableProvider();
            AiRequestScheduler scheduler = scheduler(provider,
                    new AiRequestSchedulerPolicy(1, 1, 1, 1), lanes);
            AiRequest first = request("00000000-0000-0000-0000-000000000161");
            AiRequest queued = request("00000000-0000-0000-0000-000000000162");
            AiRequest rejected = request("00000000-0000-0000-0000-000000000163");

            AiScheduledRequestHandle firstHandle = scheduler.submit(
                    scheduled(BOT_A, AGENT_A, first), CancellationToken.none());
            assertTrue(await(() -> provider.calls().size() == 1, 2_000L));
            scheduler.submit(scheduled(BOT_B, AGENT_B, queued), CancellationToken.none());
            assertTrue(await(() -> scheduler.queuedRequestCount() == 1, 2_000L));
            AiScheduledRequestHandle rejectedHandle = scheduler.submit(
                    scheduled(new UUID(8L, 8L), new UUID(9L, 9L), rejected),
                    CancellationToken.none());

            assertEquals(AiFailureKind.OVERLOADED,
                    failure(rejectedHandle.response().toCompletableFuture()).failureKind());
            assertEquals(AiSchedulerRequestState.REJECTED,
                    scheduler.requestHealth(rejected.requestId()).orElseThrow().state());
            assertEquals(AiRequestCancellationDisposition.ALREADY_TERMINAL,
                    rejectedHandle.requestCancellation().toCompletableFuture()
                            .get(2L, TimeUnit.SECONDS).disposition());

            CompletableFuture<AiResponse> callerCopy =
                    firstHandle.response().toCompletableFuture();
            assertTrue(callerCopy.cancel(true));
            assertFalse(firstHandle.response().toCompletableFuture().isDone());
            callFor(provider, first).stage().complete(response(first));
            assertTrue(await(() -> provider.calls().size() == 2, 2_000L));
            callFor(provider, queued).stage().complete(response(queued));
            assertEquals(first.requestId(), firstHandle.response().toCompletableFuture()
                    .get(2L, TimeUnit.SECONDS).requestId());
            scheduler.close();
        } finally {
            lanes.close();
        }
    }

    @Test
    void acceptedButDroppedProviderWrapperFailsClosedWithoutCallingProvider() throws Exception {
        LaneFixture lanes = new LaneFixture();
        try {
            lanes.set(AiRequestSchedulerSupervisor.Lane.PROVIDER_START,
                    new DroppingExecutor());
            ControllableProvider provider = new ControllableProvider();
            AiRequestScheduler scheduler = scheduler(provider,
                    new AiRequestSchedulerPolicy(1, 1, 8, 8), lanes);
            AiRequest request = request("00000000-0000-0000-0000-000000000201");

            CompletableFuture<AiResponse> result = scheduler.submit(
                    scheduled(BOT_A, AGENT_A, request), CancellationToken.none())
                    .response().toCompletableFuture();

            assertEquals(AiFailureKind.UNAVAILABLE, failure(result).failureKind());
            assertEquals(0, provider.calls().size());
            assertTrue(scheduler.diagnostics().rejectedDispatchCount() > 0L);
            assertTrue(scheduler.diagnostics().recentQuarantines().stream().anyMatch(
                    event -> event.requestId().equals(request.requestId())
                            && event.kind() == AiRequestSchedulerQuarantineKind.DISPATCH_START));
            scheduler.close();
        } finally {
            lanes.close();
        }
    }

    @Test
    void wrapperRetainedPastTheStartWatchdogIsAnInertLateNoOp() throws Exception {
        LaneFixture lanes = new LaneFixture();
        CapturingExecutor capture = new CapturingExecutor();
        try {
            lanes.set(AiRequestSchedulerSupervisor.Lane.PROVIDER_START, capture);
            ControllableProvider provider = new ControllableProvider();
            AiRequestScheduler scheduler = scheduler(provider,
                    new AiRequestSchedulerPolicy(1, 1, 8, 8), lanes);
            AiRequest request = request("00000000-0000-0000-0000-000000000205");

            CompletableFuture<AiResponse> result = scheduler.submit(
                    scheduled(BOT_A, AGENT_A, request), CancellationToken.none())
                    .response().toCompletableFuture();
            assertTrue(capture.captured.await(2L, TimeUnit.SECONDS));
            assertEquals(AiFailureKind.UNAVAILABLE, failure(result).failureKind());
            capture.runCaptured();
            assertEquals(0, provider.calls().size());
            scheduler.close();
        } finally {
            lanes.close();
        }
    }

    @Test
    void duplicateWrapperInvocationCanStartProviderOnlyOnce() throws Exception {
        LaneFixture lanes = new LaneFixture();
        try {
            lanes.set(AiRequestSchedulerSupervisor.Lane.PROVIDER_START,
                    new TwiceExecutor());
            ControllableProvider provider = new ControllableProvider();
            AiRequestScheduler scheduler = scheduler(provider,
                    new AiRequestSchedulerPolicy(1, 1, 8, 8), lanes);
            AiRequest request = request("00000000-0000-0000-0000-000000000211");

            CompletableFuture<AiResponse> result = scheduler.submit(
                    scheduled(BOT_A, AGENT_A, request), CancellationToken.none())
                    .response().toCompletableFuture();
            assertTrue(await(() -> provider.calls().size() == 1, 2_000L));
            provider.calls().get(0).stage().complete(response(request));
            assertEquals(request.requestId(), result.get(2L, TimeUnit.SECONDS).requestId());
            assertEquals(1, provider.calls().size());
            scheduler.close();
        } finally {
            lanes.close();
        }
    }

    @Test
    void wrapperThenThrowDoesNotTurnACommittedProviderStartIntoRejection() throws Exception {
        LaneFixture lanes = new LaneFixture();
        try {
            lanes.set(AiRequestSchedulerSupervisor.Lane.PROVIDER_START,
                    new InvokeThenThrowExecutor());
            ImmediateProvider provider = new ImmediateProvider();
            AiRequestScheduler scheduler = scheduler(provider,
                    new AiRequestSchedulerPolicy(1, 1, 8, 8), lanes);
            AiRequest request = request("00000000-0000-0000-0000-000000000221");

            AiResponse response = scheduler.submit(scheduled(BOT_A, AGENT_A, request),
                    CancellationToken.none()).response().toCompletableFuture()
                    .get(2L, TimeUnit.SECONDS);
            assertEquals(request.requestId(), response.requestId());
            assertEquals(1, provider.calls.get());
            assertEquals(0L, scheduler.diagnostics().rejectedDispatchCount());
            assertFalse(scheduler.diagnostics().dispatchDegraded());
            scheduler.close();
        } finally {
            lanes.close();
        }
    }

    @Test
    void blockingExecutorExecuteCannotBlockSubmitAndIsWatchdogFailed() throws Exception {
        LaneFixture lanes = new LaneFixture();
        BlockingExecuteExecutor blocking = new BlockingExecuteExecutor();
        try {
            lanes.set(AiRequestSchedulerSupervisor.Lane.PROVIDER_START, blocking);
            ControllableProvider provider = new ControllableProvider();
            AiRequestScheduler scheduler = scheduler(provider,
                    new AiRequestSchedulerPolicy(1, 1, 8, 8), lanes);
            AiRequest request = request("00000000-0000-0000-0000-000000000231");

            long startedAt = System.nanoTime();
            CompletableFuture<AiResponse> result = scheduler.submit(
                    scheduled(BOT_A, AGENT_A, request), CancellationToken.none())
                    .response().toCompletableFuture();
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            assertTrue(elapsedMillis < 250L, "submit was blocked by executor.execute");
            assertTrue(blocking.entered.await(2L, TimeUnit.SECONDS));
            assertEquals(AiFailureKind.UNAVAILABLE, failure(result).failureKind());
            assertEquals(0, provider.calls().size());
            assertTrue(scheduler.diagnostics().rejectedDispatchCount() > 0L);
            scheduler.close();
        } finally {
            blocking.release.countDown();
            lanes.close();
        }
    }

    @Test
    void cancellationBeforeProviderStartCommitNeverCallsProvider() throws Exception {
        LaneFixture lanes = new LaneFixture();
        CapturingExecutor capture = new CapturingExecutor();
        try {
            lanes.set(AiRequestSchedulerSupervisor.Lane.PROVIDER_START, capture);
            ControllableProvider provider = new ControllableProvider();
            AiRequestScheduler scheduler = scheduler(provider,
                    new AiRequestSchedulerPolicy(1, 1, 8, 8), lanes,
                    Duration.ofSeconds(1L), STALL_TIMEOUT);
            AiRequest request = request("00000000-0000-0000-0000-000000000241");
            AiScheduledRequestHandle handle = scheduler.submit(
                    scheduled(BOT_A, AGENT_A, request), CancellationToken.none());

            assertTrue(capture.captured.await(2L, TimeUnit.SECONDS));
            assertEquals(AiRequestCancellationDisposition.CANCELLED,
                    handle.requestCancellation().toCompletableFuture()
                            .get(2L, TimeUnit.SECONDS).disposition());
            capture.runCaptured();
            assertEquals(0, provider.calls().size());
            assertEquals(AiFailureKind.CANCELLED,
                    failure(handle.response().toCompletableFuture()).failureKind());
            scheduler.close();
        } finally {
            lanes.close();
        }
    }

    @Test
    void cancellationAfterProviderStartCommitReportsMayHaveStartedAndQuarantinesPhysicalWork()
            throws Exception {
        LaneFixture lanes = new LaneFixture();
        BlockingProvider provider = new BlockingProvider();
        try {
            AiRequestScheduler scheduler = scheduler(provider,
                    new AiRequestSchedulerPolicy(1, 1, 8, 8), lanes);
            AiRequest request = request("00000000-0000-0000-0000-000000000251");
            AiScheduledRequestHandle handle = scheduler.submit(
                    scheduled(BOT_A, AGENT_A, request), CancellationToken.none());
            assertTrue(provider.entered.await(2L, TimeUnit.SECONDS));

            assertEquals(AiRequestCancellationDisposition.CANCELLED,
                    handle.requestCancellation().toCompletableFuture()
                            .get(2L, TimeUnit.SECONDS).disposition());
            assertEquals(AiFailureKind.CANCELLED,
                    failure(handle.response().toCompletableFuture()).failureKind());
            assertEquals(1, provider.calls.get());
            assertTrue(scheduler.diagnostics().quarantinedProviderInvocationCount() > 0);
            assertTrue(scheduler.diagnostics().dispatchDegraded());

            provider.release.countDown();
            assertTrue(await(() -> provider.stage.isCancelled(), 2_000L));
            assertTrue(await(() -> scheduler.diagnostics()
                    .quarantinedProviderInvocationCount() == 0, 2_000L));
            scheduler.close();
        } finally {
            provider.release.countDown();
            lanes.close();
        }
    }

    @Test
    void productionSupervisorKeepsAStalledProviderWithinItsFixedPhysicalLane()
            throws Exception {
        AiRequestSchedulerPolicy policy = new AiRequestSchedulerPolicy(1, 1, 8, 8);
        AiRequestSchedulerSupervisor supervisor = AiRequestSchedulerSupervisor.create(
                "scheduler-production-bound", policy, START_TIMEOUT, STALL_TIMEOUT);
        BlockingProvider provider = new BlockingProvider();
        AiRequestScheduler scheduler = new AiRequestScheduler(
                PROVIDER_ID, provider, policy, Clock.systemUTC(), supervisor);
        try {
            AiRequest first = request("00000000-0000-0000-0000-000000000252");
            CompletableFuture<AiResponse> firstResult = scheduler.submit(
                    scheduled(BOT_A, AGENT_A, first), CancellationToken.none())
                    .response().toCompletableFuture();
            assertTrue(provider.entered.await(2L, TimeUnit.SECONDS));
            assertEquals(AiFailureKind.UNAVAILABLE, failure(firstResult).failureKind());
            assertTrue(scheduler.diagnostics().quarantinedProviderInvocationCount() > 0);

            AiRequest second = request("00000000-0000-0000-0000-000000000253");
            CompletableFuture<AiResponse> rejected = scheduler.submit(
                    scheduled(BOT_B, AGENT_B, second), CancellationToken.none())
                    .response().toCompletableFuture();
            assertEquals(AiFailureKind.UNAVAILABLE, failure(rejected).failureKind());
            assertEquals(1, provider.calls.get());
            assertTrue(scheduler.diagnostics().dispatchDegraded());

            provider.release.countDown();
            assertTrue(await(() -> scheduler.diagnostics()
                    .quarantinedProviderInvocationCount() == 0, 2_000L));
            scheduler.close();
        } finally {
            provider.release.countDown();
            scheduler.close();
            supervisor.close();
        }
    }

    @Test
    void blockedTokenSetupIsQuarantinedAndNeverBecomesProviderEligible() throws Exception {
        LaneFixture lanes = new LaneFixture();
        BlockingToken token = new BlockingToken();
        try {
            ControllableProvider provider = new ControllableProvider();
            AiRequestScheduler scheduler = scheduler(provider,
                    new AiRequestSchedulerPolicy(1, 1, 8, 8), lanes);
            AiRequest request = request("00000000-0000-0000-0000-000000000261");
            CompletableFuture<AiResponse> result = scheduler.submit(
                    scheduled(BOT_A, AGENT_A, request), token).response().toCompletableFuture();

            assertTrue(token.entered.await(2L, TimeUnit.SECONDS));
            assertEquals(AiFailureKind.UNAVAILABLE, failure(result).failureKind());
            assertEquals(0, provider.calls().size());
            assertTrue(scheduler.diagnostics().quarantinedTokenSetupCount() > 0);
            assertTrue(scheduler.diagnostics().dispatchDegraded());

            token.release.countDown();
            assertTrue(await(() -> scheduler.diagnostics()
                    .quarantinedTokenSetupCount() == 0, 2_000L));
            scheduler.close();
        } finally {
            token.release.countDown();
            lanes.close();
        }
    }

    @Test
    void tokenSetupUsesOnlyItsTwoLinearizedReadsAndExternalCancellationCancelsDelegate()
            throws Exception {
        LaneFixture lanes = new LaneFixture();
        try {
            ControllableProvider provider = new ControllableProvider();
            AiRequestScheduler scheduler = scheduler(provider,
                    new AiRequestSchedulerPolicy(1, 1, 8, 8), lanes);
            CountingToken token = new CountingToken();
            AiRequest request = request("00000000-0000-0000-0000-000000000265");
            AiScheduledRequestHandle handle = scheduler.submit(
                    scheduled(BOT_A, AGENT_A, request), token);
            assertTrue(await(() -> provider.calls().size() == 1, 2_000L));
            assertEquals(2, token.reads.get());

            token.cancel();
            assertEquals(AiFailureKind.CANCELLED,
                    failure(handle.response().toCompletableFuture()).failureKind());
            assertTrue(callFor(provider, request).stage().isCancelled());
            assertEquals(AiRequestCancellationDisposition.ALREADY_TERMINAL,
                    handle.requestCancellation().toCompletableFuture()
                            .get(2L, TimeUnit.SECONDS).disposition());
            scheduler.close();
        } finally {
            lanes.close();
        }
    }

    @Test
    void earlierSameBotTokenSetupCannotBeOvertakenByALaterReadyRequest() throws Exception {
        LaneFixture lanes = new LaneFixture();
        BlockingToken firstToken = new BlockingToken();
        try {
            ControllableProvider provider = new ControllableProvider();
            AiRequestScheduler scheduler = scheduler(provider,
                    new AiRequestSchedulerPolicy(2, 1, 8, 8), lanes,
                    Duration.ofSeconds(2L), Duration.ofSeconds(2L));
            AiRequest first = request("00000000-0000-0000-0000-000000000267");
            AiRequest second = request("00000000-0000-0000-0000-000000000268");

            scheduler.submit(scheduled(BOT_A, AGENT_A, first), firstToken);
            assertTrue(firstToken.entered.await(2L, TimeUnit.SECONDS));
            scheduler.submit(scheduled(BOT_A, AGENT_A, second), CancellationToken.none());
            assertFalse(await(() -> !provider.calls().isEmpty(), 500L));

            firstToken.release.countDown();
            assertTrue(await(() -> provider.calls().size() == 1, 2_000L));
            assertEquals(first.requestId(), provider.calls().get(0).request().requestId());
            callFor(provider, first).stage().complete(response(first));
            assertTrue(await(() -> provider.calls().size() == 2, 2_000L));
            assertEquals(second.requestId(), provider.calls().get(1).request().requestId());
            callFor(provider, second).stage().complete(response(second));
            scheduler.close();
        } finally {
            firstToken.release.countDown();
            lanes.close();
        }
    }

    @Test
    void terminalCleanupCancelsTheDelegateBeforeAQueuedSuccessorCanInvokeProvider()
            throws Exception {
        LaneFixture lanes = new LaneFixture();
        CapturingExecutor cleanup = new CapturingExecutor();
        OrderingProvider provider = new OrderingProvider();
        try {
            configureInlineLanesExcept(lanes,
                    AiRequestSchedulerSupervisor.Lane.TERMINAL_CLEANUP);
            lanes.set(AiRequestSchedulerSupervisor.Lane.TERMINAL_CLEANUP, cleanup);
            AiRequestScheduler scheduler = scheduler(provider,
                    new AiRequestSchedulerPolicy(1, 1, 8, 8), lanes,
                    Duration.ofSeconds(5L), STALL_TIMEOUT);
            AiRequest first = request(
                    "00000000-0000-0000-0000-000000000269", 15_000L);
            AiRequest second = request(
                    "00000000-0000-0000-0000-000000000270", 15_000L);
            AiScheduledRequestHandle firstHandle = scheduler.submit(
                    scheduled(BOT_A, AGENT_A, first), CancellationToken.none());
            CompletableFuture<AiResponse> secondResult = scheduler.submit(
                    scheduled(BOT_B, AGENT_B, second), CancellationToken.none())
                    .response().toCompletableFuture();

            assertTrue(provider.firstEntered.await(2L, TimeUnit.SECONDS));
            assertTrue(await(() -> scheduler.diagnostics()
                    .activeProviderInvocationCount() == 0, 2_000L));
            CompletableFuture<AiRequestCancellationReceipt> cancellation =
                    firstHandle.requestCancellation().toCompletableFuture();
            assertTrue(cleanup.captured.await(2L, TimeUnit.SECONDS));
            cleanup.runCaptured();
            assertEquals(AiRequestCancellationDisposition.CANCELLED,
                    cancellation.get(2L, TimeUnit.SECONDS).disposition());
            assertTrue(provider.secondEntered.await(2L, TimeUnit.SECONDS));
            assertFalse(provider.secondStartedBeforeFirstCancellation.get());
            provider.secondStage.complete(response(second));
            assertEquals(second.requestId(), secondResult.get(2L, TimeUnit.SECONDS).requestId());
            scheduler.close();
        } finally {
            lanes.close();
        }
    }

    @Test
    void blockedTerminalCleanupQuarantinesItsPhysicalLaneAndHoldsSuccessorAdmission()
            throws Exception {
        LaneFixture lanes = new LaneFixture();
        BlockingCleanupProvider provider = new BlockingCleanupProvider();
        try {
            configureAllInlineLanes(lanes);
            AiRequestScheduler scheduler = scheduler(provider,
                    new AiRequestSchedulerPolicy(1, 1, 8, 8), lanes,
                    Duration.ofSeconds(5L), STALL_TIMEOUT);
            AiRequest first = request("00000000-0000-0000-0000-000000000264", 15_000L);
            AiRequest second = request("00000000-0000-0000-0000-000000000273", 15_000L);
            AiScheduledRequestHandle firstHandle = scheduler.submit(
                    scheduled(BOT_A, AGENT_A, first), CancellationToken.none());
            CompletableFuture<AiResponse> secondResult = scheduler.submit(
                    scheduled(BOT_B, AGENT_B, second), CancellationToken.none())
                    .response().toCompletableFuture();

            assertTrue(provider.firstEntered.await(2L, TimeUnit.SECONDS));
            assertTrue(await(() -> scheduler.diagnostics()
                    .activeProviderInvocationCount() == 0, 2_000L));
            firstHandle.requestCancellation();
            assertTrue(provider.cancellationEntered.await(2L, TimeUnit.SECONDS));
            assertEquals(AiFailureKind.CANCELLED,
                    failure(firstHandle.response().toCompletableFuture()).failureKind());
            assertTrue(await(() -> scheduler.diagnostics().quarantinedCleanupCount() > 0
                    && scheduler.diagnostics().quarantinedPhysicalCleanupCount() > 0
                    && scheduler.diagnostics().dispatchDegraded(), 2_000L));
            assertFalse(await(() -> provider.secondEntered.getCount() == 0L, 500L));

            provider.releaseCancellation.countDown();
            assertTrue(provider.secondEntered.await(2L, TimeUnit.SECONDS));
            provider.secondStage.complete(response(second));
            assertEquals(second.requestId(), secondResult.get(2L, TimeUnit.SECONDS).requestId());
            scheduler.close();
        } finally {
            provider.releaseCancellation.countDown();
            lanes.close();
        }
    }

    @Test
    void blockingCompletionStageAttachmentIsQuarantinedOnTheProviderLane() throws Exception {
        LaneFixture lanes = new LaneFixture();
        BlockingAttachmentProvider provider = new BlockingAttachmentProvider();
        try {
            AiRequestScheduler scheduler = scheduler(provider,
                    new AiRequestSchedulerPolicy(1, 1, 8, 8), lanes);
            AiRequest request = request("00000000-0000-0000-0000-000000000266");
            CompletableFuture<AiResponse> result = scheduler.submit(
                    scheduled(BOT_A, AGENT_A, request), CancellationToken.none())
                    .response().toCompletableFuture();

            assertTrue(provider.attachmentEntered.await(2L, TimeUnit.SECONDS));
            assertEquals(AiFailureKind.UNAVAILABLE, failure(result).failureKind());
            assertTrue(scheduler.diagnostics().quarantinedProviderInvocationCount() > 0);
            assertTrue(provider.stage.isCancelled());

            provider.releaseAttachment.countDown();
            assertTrue(await(() -> scheduler.diagnostics()
                    .quarantinedProviderInvocationCount() == 0, 2_000L));
            scheduler.close();
        } finally {
            provider.releaseAttachment.countDown();
            lanes.close();
        }
    }

    @Test
    void blockedCompletionDeliveryDoesNotBlockAlreadyQueuedSuccessorProviderStart() throws Exception {
        LaneFixture lanes = new LaneFixture();
        CountDownLatch releaseDelivery = new CountDownLatch(1);
        try {
            /* Runs only on the delivery broker, never on submit/pump/timer. */
            lanes.set(AiRequestSchedulerSupervisor.Lane.COMPLETION_DELIVERY,
                    new InlineExecutor());
            ControllableProvider provider = new ControllableProvider();
            AiRequestScheduler scheduler = scheduler(provider,
                    new AiRequestSchedulerPolicy(1, 1, 8, 8), lanes,
                    Duration.ofSeconds(2L), STALL_TIMEOUT);
            AiRequest first = request("00000000-0000-0000-0000-000000000271", 15_000L);
            AiRequest second = request("00000000-0000-0000-0000-000000000272", 15_000L);
            CountingToken firstToken = new CountingToken();
            CountingToken secondToken = new CountingToken();
            AiScheduledRequestHandle firstHandle = scheduler.submit(
                    scheduled(BOT_A, AGENT_A, first), firstToken);
            assertTrue(await(() -> provider.calls().size() == 1, 2_000L));
            assertEquals(first.requestId(), callFor(provider, first)
                    .request().requestId());
            assertTrue(await(() -> scheduler.diagnostics()
                    .activeProviderInvocationCount() == 0, 2_000L));
            assertEquals(2, firstToken.reads.get());
            CompletableFuture<AiResponse> secondResult = scheduler.submit(
                    scheduled(BOT_B, AGENT_B, second), secondToken)
                    .response().toCompletableFuture();
            assertTrue(await(() -> secondToken.reads.get() == 2, 2_000L));
            assertEquals(1, scheduler.queuedRequestCount());

            CountDownLatch deliveryEntered = new CountDownLatch(1);
            firstHandle.response().whenComplete((ignored, failure) -> {
                deliveryEntered.countDown();
                awaitLatch(releaseDelivery);
            });
            callFor(provider, first).stage().complete(response(first));

            assertTrue(deliveryEntered.await(5L, TimeUnit.SECONDS));
            assertTrue(await(() -> provider.calls().size() == 2, 5_000L));
            assertEquals(second.requestId(), callFor(provider, second)
                    .request().requestId());
            assertTrue(await(() -> scheduler.diagnostics()
                    .quarantinedCompletionDeliveryCount() > 0, 2_000L));
            assertTrue(scheduler.diagnostics().dispatchDegraded());

            releaseDelivery.countDown();
            callFor(provider, second).stage().complete(response(second));
            assertEquals(second.requestId(), secondResult.get(2L, TimeUnit.SECONDS).requestId());
            scheduler.close();
        } finally {
            releaseDelivery.countDown();
            lanes.close();
        }
    }

    @Test
    void droppedCleanupIsPublishedAsQuarantineAndRetainedForBoundedRecovery() throws Exception {
        LaneFixture lanes = new LaneFixture();
        DropOnceExecutor cleanup = new DropOnceExecutor(lanes.executor(
                AiRequestSchedulerSupervisor.Lane.TERMINAL_CLEANUP));
        try {
            lanes.set(AiRequestSchedulerSupervisor.Lane.TERMINAL_CLEANUP, cleanup);
            ImmediateProvider provider = new ImmediateProvider();
            AiRequestScheduler scheduler = scheduler(provider,
                    new AiRequestSchedulerPolicy(1, 1, 8, 8), lanes);
            AiRequest request = request("00000000-0000-0000-0000-000000000281");

            AiResponse response = scheduler.submit(scheduled(BOT_A, AGENT_A, request),
                    CancellationToken.none()).response().toCompletableFuture()
                    .get(2L, TimeUnit.SECONDS);
            assertEquals(request.requestId(), response.requestId());
            assertTrue(await(() -> scheduler.diagnostics().pendingRecoveryDispatchCount() == 1,
                    2_000L));
            assertTrue(scheduler.diagnostics().dispatchDegraded());
            scheduler.resumeDispatch();
            assertTrue(await(() -> scheduler.diagnostics().pendingRecoveryDispatchCount() == 0,
                    2_000L));
            assertFalse(scheduler.diagnostics().dispatchDegraded());
            scheduler.close();
        } finally {
            lanes.close();
        }
    }

    @Test
    void delayedCleanupWrapperCannotRunAfterWatchdogAndItsRecoveryRemainsRetained()
            throws Exception {
        LaneFixture lanes = new LaneFixture();
        CapturingExecutor cleanup = new CapturingExecutor();
        try {
            configureInlineLanesExcept(lanes, AiRequestSchedulerSupervisor.Lane.TERMINAL_CLEANUP);
            lanes.set(AiRequestSchedulerSupervisor.Lane.TERMINAL_CLEANUP, cleanup);
            ImmediateProvider provider = new ImmediateProvider();
            AiRequestScheduler scheduler = scheduler(provider,
                    new AiRequestSchedulerPolicy(1, 1, 8, 8), lanes);
            AiRequest request = request("00000000-0000-0000-0000-000000000286");
            AiScheduledRequestHandle handle = scheduler.submit(
                    scheduled(BOT_A, AGENT_A, request), CancellationToken.none());

            assertTrue(cleanup.captured.await(2L, TimeUnit.SECONDS));
            assertTrue(await(() -> scheduler.diagnostics().pendingRecoveryDispatchCount() == 1,
                    2_000L));
            assertEquals(request.requestId(), handle.response().toCompletableFuture()
                    .get(2L, TimeUnit.SECONDS).requestId());
            cleanup.runCaptured();
            assertEquals(1, provider.calls.get());

            scheduler.resumeDispatch();
            assertTrue(await(() -> cleanup.command.get() != null, 2_000L));
            cleanup.runCaptured();
            assertTrue(await(() -> scheduler.diagnostics().pendingRecoveryDispatchCount() == 0,
                    2_000L));
            assertFalse(scheduler.diagnostics().dispatchDegraded());
            scheduler.close();
        } finally {
            lanes.close();
        }
    }

    @Test
    void droppedPublicationIsRetainedUntilExplicitRecoveryThenCompletesThePublicHandle()
            throws Exception {
        LaneFixture lanes = new LaneFixture();
        DropOnceExecutor delivery = new DropOnceExecutor(lanes.executor(
                AiRequestSchedulerSupervisor.Lane.COMPLETION_DELIVERY));
        try {
            lanes.set(AiRequestSchedulerSupervisor.Lane.COMPLETION_DELIVERY, delivery);
            ImmediateProvider provider = new ImmediateProvider();
            AiRequestScheduler scheduler = scheduler(provider,
                    new AiRequestSchedulerPolicy(1, 1, 8, 8), lanes);
            AiRequest request = request("00000000-0000-0000-0000-000000000291");
            AiScheduledRequestHandle handle = scheduler.submit(
                    scheduled(BOT_A, AGENT_A, request), CancellationToken.none());

            assertTrue(await(() -> scheduler.diagnostics().pendingRecoveryDispatchCount() == 1,
                    2_000L));
            assertFalse(handle.response().toCompletableFuture().isDone());
            scheduler.resumeDispatch();
            assertEquals(request.requestId(), handle.response().toCompletableFuture()
                    .get(2L, TimeUnit.SECONDS).requestId());
            assertTrue(await(() -> scheduler.diagnostics()
                    .pendingRecoveryDispatchCount() == 0, 2_000L));
            assertFalse(scheduler.diagnostics().dispatchDegraded());
            scheduler.close();
        } finally {
            lanes.close();
        }
    }

    @Test
    void delayedPublicationWrapperCannotPublishAfterWatchdogAndRecoveryCompletesOnce()
            throws Exception {
        LaneFixture lanes = new LaneFixture();
        CapturingExecutor delivery = new CapturingExecutor();
        try {
            configureInlineLanesExcept(lanes, AiRequestSchedulerSupervisor.Lane.COMPLETION_DELIVERY);
            lanes.set(AiRequestSchedulerSupervisor.Lane.COMPLETION_DELIVERY, delivery);
            ImmediateProvider provider = new ImmediateProvider();
            AiRequestScheduler scheduler = scheduler(provider,
                    new AiRequestSchedulerPolicy(1, 1, 8, 8), lanes);
            AiRequest request = request("00000000-0000-0000-0000-000000000296");
            AiScheduledRequestHandle handle = scheduler.submit(
                    scheduled(BOT_A, AGENT_A, request), CancellationToken.none());

            assertTrue(delivery.captured.await(2L, TimeUnit.SECONDS));
            assertTrue(await(() -> scheduler.diagnostics().pendingRecoveryDispatchCount() == 1,
                    2_000L));
            assertFalse(handle.response().toCompletableFuture().isDone());
            delivery.runCaptured();
            assertFalse(handle.response().toCompletableFuture().isDone());

            scheduler.resumeDispatch();
            assertTrue(await(() -> delivery.command.get() != null, 2_000L));
            delivery.runCaptured();
            assertEquals(request.requestId(), handle.response().toCompletableFuture()
                    .get(2L, TimeUnit.SECONDS).requestId());
            assertEquals(1, provider.calls.get());
            scheduler.close();
        } finally {
            lanes.close();
        }
    }

    @Test
    void supervisorCloseWinsTheArmedToStartedRaceBeforeAnyExternalBodyRuns()
            throws Exception {
        CountDownLatch physicalHandoffEntered = new CountDownLatch(1);
        CountDownLatch releasePhysicalWrapper = new CountDownLatch(1);
        CountDownLatch closeOpened = new CountDownLatch(1);
        CountDownLatch releaseCloseInvalidation = new CountDownLatch(1);
        AtomicInteger started = new AtomicInteger();
        AtomicInteger startLost = new AtomicInteger();
        AtomicInteger bodyRuns = new AtomicInteger();
        AiRequestSchedulerPolicy policy = new AiRequestSchedulerPolicy(1, 1, 8, 8);
        AiRequestSchedulerSupervisor supervisor =
                AiRequestSchedulerSupervisor.forAdversarialTesting(
                        "scheduler-close-race", policy, Duration.ofSeconds(5L),
                        Duration.ofSeconds(5L), (lane, wrapper) -> {
                            physicalHandoffEntered.countDown();
                            awaitLatch(releasePhysicalWrapper);
                            wrapper.run();
                        }, () -> {
                            closeOpened.countDown();
                            awaitLatch(releaseCloseInvalidation);
                        });
        Thread closer = null;
        try {
            assertTrue(supervisor.claim());
            AiRequestSchedulerSupervisor.DispatchAttempt attempt =
                    new AiRequestSchedulerSupervisor.DispatchAttempt(1L,
                            AiRequestSchedulerSupervisor.Lane.COMPLETION_DELIVERY,
                            new AiRequestSchedulerSupervisor.DispatchCallbacks() {
                                @Override
                                public boolean started(
                                        AiRequestSchedulerSupervisor.DispatchAttempt ignored) {
                                    started.incrementAndGet();
                                    return true;
                                }

                                @Override
                                public void startLost(
                                        AiRequestSchedulerSupervisor.DispatchAttempt ignored) {
                                    startLost.incrementAndGet();
                                }

                                @Override
                                public void stalled(
                                        AiRequestSchedulerSupervisor.DispatchAttempt ignored) {
                                    throw new AssertionError("close race wrapper unexpectedly stalled");
                                }

                                @Override
                                public void bodySucceeded(
                                        AiRequestSchedulerSupervisor.DispatchAttempt ignored) {
                                    throw new AssertionError("close race wrapper unexpectedly succeeded");
                                }

                                @Override
                                public void bodyFailed(
                                        AiRequestSchedulerSupervisor.DispatchAttempt ignored,
                                        Throwable failure) {
                                    throw new AssertionError("close race wrapper unexpectedly failed",
                                            failure);
                                }
                            });
            supervisor.handoff(attempt, bodyRuns::incrementAndGet);
            assertTrue(physicalHandoffEntered.await(2L, TimeUnit.SECONDS));

            closer = new Thread(supervisor::close, "scheduler-close-race-closer");
            closer.setDaemon(true);
            closer.start();
            assertTrue(closeOpened.await(2L, TimeUnit.SECONDS));

            /* The old close-before-invalidate window could commit here. */
            releasePhysicalWrapper.countDown();
            assertTrue(await(() -> startLost.get() == 1, 2_000L));
            assertEquals(0, started.get());
            assertEquals(0, bodyRuns.get());

            releaseCloseInvalidation.countDown();
            closer.join(2_000L);
            assertFalse(closer.isAlive());
        } finally {
            releasePhysicalWrapper.countDown();
            releaseCloseInvalidation.countDown();
            supervisor.close();
            if (closer != null) {
                closer.join(2_000L);
            }
        }
    }

    @Test
    void supervisorCloseInvalidatesAnArmedDeliveryAttemptInsteadOfLosingItsRecovery()
            throws Exception {
        LaneFixture lanes = new LaneFixture();
        CapturingExecutor delivery = new CapturingExecutor();
        AiRequestSchedulerSupervisor supervisor = null;
        try {
            configureInlineLanesExcept(lanes, AiRequestSchedulerSupervisor.Lane.COMPLETION_DELIVERY);
            lanes.set(AiRequestSchedulerSupervisor.Lane.COMPLETION_DELIVERY, delivery);
            AiRequestSchedulerPolicy policy = new AiRequestSchedulerPolicy(1, 1, 8, 8);
            supervisor = lanes.supervisor(policy, Duration.ofSeconds(5L), STALL_TIMEOUT);
            AiRequestScheduler scheduler = new AiRequestScheduler(
                    PROVIDER_ID, new ImmediateProvider(), policy, Clock.systemUTC(), supervisor);
            AiRequest request = request("00000000-0000-0000-0000-000000000297");
            AiScheduledRequestHandle handle = scheduler.submit(
                    scheduled(BOT_A, AGENT_A, request), CancellationToken.none());

            assertTrue(delivery.captured.await(2L, TimeUnit.SECONDS));
            supervisor.close();
            assertTrue(await(() -> scheduler.diagnostics().pendingRecoveryDispatchCount() == 1,
                    2_000L));
            delivery.runCaptured();
            assertFalse(handle.response().toCompletableFuture().isDone());
            scheduler.close();
        } finally {
            if (supervisor != null) {
                supervisor.close();
            }
            lanes.close();
        }
    }

    @Test
    void supervisorCloseInvalidatesAnArmedCleanupAttemptAndRetainsItsPublicationChain()
            throws Exception {
        LaneFixture lanes = new LaneFixture();
        CapturingExecutor cleanup = new CapturingExecutor();
        AiRequestSchedulerSupervisor supervisor = null;
        try {
            configureInlineLanesExcept(lanes, AiRequestSchedulerSupervisor.Lane.TERMINAL_CLEANUP);
            lanes.set(AiRequestSchedulerSupervisor.Lane.TERMINAL_CLEANUP, cleanup);
            AiRequestSchedulerPolicy policy = new AiRequestSchedulerPolicy(1, 1, 8, 8);
            supervisor = lanes.supervisor(policy, Duration.ofSeconds(5L), STALL_TIMEOUT);
            AiRequestScheduler scheduler = new AiRequestScheduler(
                    PROVIDER_ID, new ImmediateProvider(), policy, Clock.systemUTC(), supervisor);
            AiRequest request = request("00000000-0000-0000-0000-000000000298");
            AiScheduledRequestHandle handle = scheduler.submit(
                    scheduled(BOT_A, AGENT_A, request), CancellationToken.none());

            assertTrue(cleanup.captured.await(2L, TimeUnit.SECONDS));
            supervisor.close();
            assertTrue(await(() -> scheduler.diagnostics().pendingRecoveryDispatchCount() == 2,
                    2_000L));
            cleanup.runCaptured();
            assertFalse(handle.response().toCompletableFuture().isDone());
            scheduler.close();
        } finally {
            if (supervisor != null) {
                supervisor.close();
            }
            lanes.close();
        }
    }

    @Test
    void providerCompleteAssertionErrorFailsClosedWithoutRetryingThatProviderStart()
            throws Exception {
        LaneFixture lanes = new LaneFixture();
        try {
            ThrowingThenImmediateProvider provider = new ThrowingThenImmediateProvider();
            AiRequestScheduler scheduler = scheduler(provider,
                    new AiRequestSchedulerPolicy(1, 1, 8, 8), lanes);
            AiRequest failed = request("00000000-0000-0000-0000-000000000301");
            AiRequest successor = request("00000000-0000-0000-0000-000000000302");

            assertEquals(AiFailureKind.UNKNOWN, failure(scheduler.submit(
                    scheduled(BOT_A, AGENT_A, failed), CancellationToken.none())
                    .response().toCompletableFuture()).failureKind());
            assertEquals(1, provider.calls.get());
            assertTrue(scheduler.diagnostics().recentQuarantines().stream().anyMatch(
                    event -> event.requestId().equals(failed.requestId())
                            && event.kind()
                            == AiRequestSchedulerQuarantineKind.PROVIDER_INVOCATION));

            assertEquals(successor.requestId(), scheduler.submit(
                    scheduled(BOT_B, AGENT_B, successor), CancellationToken.none())
                    .response().toCompletableFuture().get(2L, TimeUnit.SECONDS).requestId());
            assertEquals(2, provider.calls.get());
            assertFalse(scheduler.diagnostics().dispatchDegraded());
            scheduler.close();
        } finally {
            lanes.close();
        }
    }

    @Test
    void whenCompleteAttachmentAssertionErrorFailsClosedAndAllowsSuccessor()
            throws Exception {
        LaneFixture lanes = new LaneFixture();
        try {
            AttachmentErrorThenImmediateProvider provider = new AttachmentErrorThenImmediateProvider();
            AiRequestScheduler scheduler = scheduler(provider,
                    new AiRequestSchedulerPolicy(1, 1, 8, 8), lanes);
            AiRequest failed = request("00000000-0000-0000-0000-000000000303");
            AiRequest successor = request("00000000-0000-0000-0000-000000000304");

            assertEquals(AiFailureKind.UNKNOWN, failure(scheduler.submit(
                    scheduled(BOT_A, AGENT_A, failed), CancellationToken.none())
                    .response().toCompletableFuture()).failureKind());
            assertTrue(scheduler.diagnostics().recentQuarantines().stream().anyMatch(
                    event -> event.requestId().equals(failed.requestId())
                            && event.kind()
                            == AiRequestSchedulerQuarantineKind.PROVIDER_INVOCATION));

            assertEquals(successor.requestId(), scheduler.submit(
                    scheduled(BOT_B, AGENT_B, successor), CancellationToken.none())
                    .response().toCompletableFuture().get(2L, TimeUnit.SECONDS).requestId());
            assertEquals(2, provider.calls.get());
            scheduler.close();
        } finally {
            lanes.close();
        }
    }

    @Test
    void tokenRegistrationAssertionErrorFailsClosedBeforeProviderAdmission()
            throws Exception {
        LaneFixture lanes = new LaneFixture();
        try {
            ImmediateProvider provider = new ImmediateProvider();
            AiRequestScheduler scheduler = scheduler(provider,
                    new AiRequestSchedulerPolicy(1, 1, 8, 8), lanes);
            AiRequest failed = request("00000000-0000-0000-0000-000000000305");
            AiRequest successor = request("00000000-0000-0000-0000-000000000306");

            assertEquals(AiFailureKind.UNAVAILABLE, failure(scheduler.submit(
                    scheduled(BOT_A, AGENT_A, failed), new ThrowingRegistrationToken())
                    .response().toCompletableFuture()).failureKind());
            assertEquals(0, provider.calls.get());
            assertTrue(scheduler.diagnostics().recentQuarantines().stream().anyMatch(
                    event -> event.requestId().equals(failed.requestId())
                            && event.kind() == AiRequestSchedulerQuarantineKind.TOKEN_SETUP));

            assertEquals(successor.requestId(), scheduler.submit(
                    scheduled(BOT_B, AGENT_B, successor), CancellationToken.none())
                    .response().toCompletableFuture().get(2L, TimeUnit.SECONDS).requestId());
            assertEquals(1, provider.calls.get());
            scheduler.close();
        } finally {
            lanes.close();
        }
    }

    @Test
    void synchronousCancellationCallbackThenAttachmentErrorFailsClosedWithoutCancellationWin()
            throws Exception {
        LaneFixture lanes = new LaneFixture();
        CallbackThenThrowToken token = new CallbackThenThrowToken();
        try {
            configureAllInlineLanes(lanes);
            ImmediateProvider provider = new ImmediateProvider();
            AiRequestScheduler scheduler = scheduler(provider,
                    new AiRequestSchedulerPolicy(1, 1, 8, 8), lanes,
                    Duration.ofSeconds(2L), Duration.ofSeconds(2L));
            AiRequest request = request("00000000-0000-0000-0000-000000000310");
            AiScheduledRequestHandle handle = scheduler.submit(
                    scheduled(BOT_A, AGENT_A, request), token);

            assertTrue(token.callbackInvoked.await(2L, TimeUnit.SECONDS));
            /* The callback has run, but its throwing attachment has not returned yet. */
            assertFalse(handle.response().toCompletableFuture().isDone());
            token.releaseThrow.countDown();

            AiProviderException failure = failure(handle.response().toCompletableFuture());
            assertEquals(AiFailureKind.UNAVAILABLE, failure.failureKind());
            assertEquals(AiReasonCode.CALLBACK_ATTACHMENT_FAILED, failure.reasonCode());
            assertEquals(AiRequestCancellationDisposition.ALREADY_TERMINAL,
                    handle.requestCancellation().toCompletableFuture()
                            .get(2L, TimeUnit.SECONDS).disposition());
            assertEquals(0, provider.calls.get());
            assertEquals(AiSchedulerRequestState.FAILED,
                    scheduler.requestHealth(request.requestId()).orElseThrow().state());
            assertTrue(scheduler.diagnostics().recentQuarantines().stream().anyMatch(
                    event -> event.requestId().equals(request.requestId())
                            && event.kind() == AiRequestSchedulerQuarantineKind.TOKEN_SETUP));
            assertEquals(0, scheduler.diagnostics().pendingRecoveryDispatchCount());
            assertFalse(scheduler.diagnostics().dispatchDegraded());

            AiRequest successor = request("00000000-0000-0000-0000-000000000312");
            assertEquals(successor.requestId(), scheduler.submit(
                    scheduled(BOT_B, AGENT_B, successor), CancellationToken.none())
                    .response().toCompletableFuture().get(2L, TimeUnit.SECONDS).requestId());
            assertEquals(1, provider.calls.get());
            scheduler.close();
        } finally {
            token.releaseThrow.countDown();
            lanes.close();
        }
    }

    @Test
    void synchronousCompletionCallbackThenAttachmentErrorFailsClosedWithoutSuccessPublication()
            throws Exception {
        LaneFixture lanes = new LaneFixture();
        CallbackThenThrowAttachmentProvider provider =
                new CallbackThenThrowAttachmentProvider();
        try {
            configureAllInlineLanes(lanes);
            AiRequestScheduler scheduler = scheduler(provider,
                    new AiRequestSchedulerPolicy(1, 1, 8, 8), lanes,
                    Duration.ofSeconds(2L), Duration.ofSeconds(2L));
            AiRequest request = request("00000000-0000-0000-0000-000000000311");
            AiScheduledRequestHandle handle = scheduler.submit(
                    scheduled(BOT_A, AGENT_A, request), CancellationToken.none());

            assertTrue(provider.callbackInvoked.await(2L, TimeUnit.SECONDS));
            /* A valid response was supplied, but attachment has not yet returned normally. */
            assertFalse(handle.response().toCompletableFuture().isDone());
            provider.releaseThrow.countDown();

            AiProviderException failure = failure(handle.response().toCompletableFuture());
            assertEquals(AiFailureKind.UNKNOWN, failure.failureKind());
            assertEquals(AiReasonCode.CALLBACK_ATTACHMENT_FAILED, failure.reasonCode());
            assertEquals(AiRequestCancellationDisposition.ALREADY_TERMINAL,
                    handle.requestCancellation().toCompletableFuture()
                            .get(2L, TimeUnit.SECONDS).disposition());
            assertEquals(1, provider.calls.get());
            assertEquals(AiSchedulerRequestState.FAILED,
                    scheduler.requestHealth(request.requestId()).orElseThrow().state());
            assertTrue(scheduler.diagnostics().recentQuarantines().stream().anyMatch(
                    event -> event.requestId().equals(request.requestId())
                            && event.kind()
                            == AiRequestSchedulerQuarantineKind.PROVIDER_INVOCATION));
            assertEquals(0, scheduler.diagnostics().pendingRecoveryDispatchCount());
            assertFalse(scheduler.diagnostics().dispatchDegraded());

            AiRequest successor = request("00000000-0000-0000-0000-000000000313");
            assertEquals(successor.requestId(), scheduler.submit(
                    scheduled(BOT_B, AGENT_B, successor), CancellationToken.none())
                    .response().toCompletableFuture().get(2L, TimeUnit.SECONDS).requestId());
            assertEquals(2, provider.calls.get());
            scheduler.close();
        } finally {
            provider.releaseThrow.countDown();
            lanes.close();
        }
    }

    @Test
    void synchronousCancellationCallbackWaitsForTokenBodyAckBeforeItCanPublish()
            throws Exception {
        LaneFixture lanes = new LaneFixture();
        CallbackThenReturnToken token = new CallbackThenReturnToken();
        try {
            configureAllInlineLanes(lanes);
            ImmediateProvider provider = new ImmediateProvider();
            AiRequestScheduler scheduler = scheduler(provider,
                    new AiRequestSchedulerPolicy(1, 1, 8, 8), lanes,
                    Duration.ofSeconds(2L), Duration.ofSeconds(2L));
            AiRequest request = request("00000000-0000-0000-0000-000000000314");
            AiScheduledRequestHandle handle = scheduler.submit(
                    scheduled(BOT_A, AGENT_A, request), token);

            assertTrue(token.callbackInvoked.await(2L, TimeUnit.SECONDS));
            assertFalse(handle.response().toCompletableFuture().isDone());
            token.releaseRegistration.countDown();
            assertTrue(token.registrationCloseEntered.await(2L, TimeUnit.SECONDS));
            /* Cleanup found the returned registration, so cancellation has not bypassed ACK. */
            assertFalse(handle.response().toCompletableFuture().isDone());
            token.releaseRegistrationClose.countDown();

            assertEquals(AiFailureKind.CANCELLED,
                    failure(handle.response().toCompletableFuture()).failureKind());
            assertEquals(0, provider.calls.get());
            assertEquals(1, token.closeCalls.get());
            scheduler.close();
        } finally {
            token.releaseRegistration.countDown();
            token.releaseRegistrationClose.countDown();
            lanes.close();
        }
    }

    @Test
    void synchronousCompletionCallbackWaitsForProviderBodyAckBeforeItCanPublish()
            throws Exception {
        LaneFixture lanes = new LaneFixture();
        CallbackThenReturnAttachmentProvider provider =
                new CallbackThenReturnAttachmentProvider();
        try {
            configureAllInlineLanes(lanes);
            AiRequestScheduler scheduler = scheduler(provider,
                    new AiRequestSchedulerPolicy(1, 1, 8, 8), lanes,
                    Duration.ofSeconds(2L), Duration.ofSeconds(2L));
            AiRequest request = request("00000000-0000-0000-0000-000000000315");
            AiScheduledRequestHandle handle = scheduler.submit(
                    scheduled(BOT_A, AGENT_A, request), CancellationToken.none());

            assertTrue(provider.callbackInvoked.await(2L, TimeUnit.SECONDS));
            assertFalse(handle.response().toCompletableFuture().isDone());
            provider.releaseAttachment.countDown();

            assertEquals(request.requestId(), handle.response().toCompletableFuture()
                    .get(2L, TimeUnit.SECONDS).requestId());
            assertEquals(1, provider.calls.get());
            assertFalse(scheduler.diagnostics().recentQuarantines().stream().anyMatch(
                    event -> event.requestId().equals(request.requestId())));
            scheduler.close();
        } finally {
            provider.releaseAttachment.countDown();
            lanes.close();
        }
    }

    @Test
    void synchronousCancellationCallbackThenSecondReadErrorFailsClosedAndClosesRegistration()
            throws Exception {
        LaneFixture lanes = new LaneFixture();
        CallbackThenSecondReadErrorToken token = new CallbackThenSecondReadErrorToken();
        try {
            configureAllInlineLanes(lanes);
            ImmediateProvider provider = new ImmediateProvider();
            AiRequestScheduler scheduler = scheduler(provider,
                    new AiRequestSchedulerPolicy(1, 1, 8, 8), lanes);
            AiRequest request = request("00000000-0000-0000-0000-000000000316");
            AiScheduledRequestHandle handle = scheduler.submit(
                    scheduled(BOT_A, AGENT_A, request), token);

            AiProviderException failure = failure(handle.response().toCompletableFuture());
            assertEquals(AiFailureKind.UNAVAILABLE, failure.failureKind());
            assertEquals(AiReasonCode.CALLBACK_ATTACHMENT_FAILED, failure.reasonCode());
            assertEquals(0, provider.calls.get());
            assertTrue(await(() -> token.closeCalls.get() == 1, 2_000L));
            assertEquals(AiRequestCancellationDisposition.ALREADY_TERMINAL,
                    handle.requestCancellation().toCompletableFuture()
                            .get(2L, TimeUnit.SECONDS).disposition());
            assertTrue(scheduler.diagnostics().recentQuarantines().stream().anyMatch(
                    event -> event.requestId().equals(request.requestId())
                            && event.kind() == AiRequestSchedulerQuarantineKind.TOKEN_SETUP));
            scheduler.close();
        } finally {
            lanes.close();
        }
    }

    @Test
    void cleanupListenerCloseAndStageCancelErrorsRemainRetainedUntilResume()
            throws Exception {
        LaneFixture lanes = new LaneFixture();
        try {
            ThrowOnceCleanupToken token = new ThrowOnceCleanupToken();
            ThrowOnceCancelProvider provider = new ThrowOnceCancelProvider();
            AiRequestScheduler scheduler = scheduler(provider,
                    new AiRequestSchedulerPolicy(1, 1, 8, 8), lanes);
            AiRequest request = request("00000000-0000-0000-0000-000000000307");
            AiScheduledRequestHandle handle = scheduler.submit(
                    scheduled(BOT_A, AGENT_A, request), token);
            assertTrue(await(() -> provider.calls.get() == 1, 2_000L));

            assertEquals(AiRequestCancellationDisposition.CANCELLED,
                    handle.requestCancellation().toCompletableFuture()
                            .get(2L, TimeUnit.SECONDS).disposition());
            assertEquals(AiFailureKind.CANCELLED,
                    failure(handle.response().toCompletableFuture()).failureKind());
            assertTrue(await(() -> scheduler.diagnostics().pendingRecoveryDispatchCount() == 1,
                    2_000L));
            assertTrue(scheduler.diagnostics().quarantinedCleanupCount() > 0);
            assertEquals(1, token.closeCalls.get());
            assertEquals(1, provider.stage.cancelCalls.get());
            assertFalse(provider.stage.isCancelled());

            scheduler.resumeDispatch();
            assertTrue(await(() -> scheduler.diagnostics().pendingRecoveryDispatchCount() == 0,
                    2_000L));
            assertTrue(await(provider.stage::isCancelled, 2_000L));
            assertEquals(2, token.closeCalls.get());
            assertEquals(2, provider.stage.cancelCalls.get());
            assertEquals(0, scheduler.diagnostics().quarantinedCleanupCount());
            assertFalse(scheduler.diagnostics().dispatchDegraded());
            scheduler.close();
        } finally {
            lanes.close();
        }
    }

    @Test
    void invokeThenAssertionErrorAfterCommittedWrapperDoesNotRejectProviderStart()
            throws Exception {
        LaneFixture lanes = new LaneFixture();
        try {
            lanes.set(AiRequestSchedulerSupervisor.Lane.PROVIDER_START,
                    new InvokeThenErrorExecutor());
            ImmediateProvider provider = new ImmediateProvider();
            AiRequestScheduler scheduler = scheduler(provider,
                    new AiRequestSchedulerPolicy(1, 1, 8, 8), lanes);
            AiRequest first = request("00000000-0000-0000-0000-000000000308");
            AiRequest second = request("00000000-0000-0000-0000-000000000309");

            assertEquals(first.requestId(), scheduler.submit(scheduled(BOT_A, AGENT_A, first),
                    CancellationToken.none()).response().toCompletableFuture()
                    .get(2L, TimeUnit.SECONDS).requestId());
            assertEquals(second.requestId(), scheduler.submit(scheduled(BOT_B, AGENT_B, second),
                    CancellationToken.none()).response().toCompletableFuture()
                    .get(2L, TimeUnit.SECONDS).requestId());
            assertEquals(2, provider.calls.get());
            assertEquals(0L, scheduler.diagnostics().rejectedDispatchCount());
            assertFalse(scheduler.diagnostics().dispatchDegraded());
            scheduler.close();
        } finally {
            lanes.close();
        }
    }

    @Test
    void deliveryAttemptAssertionErrorReportsFailureInsteadOfSuccessAck() throws Exception {
        CountDownLatch failed = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicReference<Throwable> observed = new AtomicReference<>();
        AiRequestSchedulerPolicy policy = new AiRequestSchedulerPolicy(1, 1, 8, 8);
        AiRequestSchedulerSupervisor supervisor =
                AiRequestSchedulerSupervisor.forAdversarialTesting(
                        "scheduler-delivery-error", policy, Duration.ofSeconds(1L),
                        Duration.ofSeconds(1L), (lane, wrapper) -> wrapper.run());
        try {
            assertTrue(supervisor.claim());
            AiRequestSchedulerSupervisor.DispatchAttempt attempt =
                    new AiRequestSchedulerSupervisor.DispatchAttempt(1L,
                            AiRequestSchedulerSupervisor.Lane.COMPLETION_DELIVERY,
                            new AiRequestSchedulerSupervisor.DispatchCallbacks() {
                                @Override
                                public boolean started(
                                        AiRequestSchedulerSupervisor.DispatchAttempt ignored) {
                                    return true;
                                }

                                @Override
                                public void startLost(
                                        AiRequestSchedulerSupervisor.DispatchAttempt ignored) {
                                    throw new AssertionError("delivery attempt unexpectedly lost start");
                                }

                                @Override
                                public void stalled(
                                        AiRequestSchedulerSupervisor.DispatchAttempt ignored) {
                                    throw new AssertionError("delivery attempt unexpectedly stalled");
                                }

                                @Override
                                public void bodySucceeded(
                                        AiRequestSchedulerSupervisor.DispatchAttempt ignored) {
                                    succeeded.incrementAndGet();
                                }

                                @Override
                                public void bodyFailed(
                                        AiRequestSchedulerSupervisor.DispatchAttempt ignored,
                                        Throwable failure) {
                                    observed.set(failure);
                                    failed.countDown();
                                }
                            });
            supervisor.handoff(attempt, () -> {
                throw new AssertionError("delivery body error");
            });
            assertTrue(failed.await(2L, TimeUnit.SECONDS));
            assertEquals(0, succeeded.get());
            assertTrue(observed.get() instanceof AssertionError);
        } finally {
            supervisor.close();
        }
    }

    @Test
    void fatalDeliveryAttemptErrorRecordsFailureBeforeTheWorkerMayRethrow()
            throws Exception {
        CountDownLatch failed = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicReference<Throwable> rethrown = new AtomicReference<>();
        AiRequestSchedulerPolicy policy = new AiRequestSchedulerPolicy(1, 1, 8, 8);
        AiRequestSchedulerSupervisor supervisor =
                AiRequestSchedulerSupervisor.forAdversarialTesting(
                        "scheduler-delivery-fatal", policy, Duration.ofSeconds(1L),
                        Duration.ofSeconds(1L), (lane, wrapper) -> {
                            try {
                                wrapper.run();
                            } catch (Throwable failure) {
                                rethrown.set(failure);
                            }
                        });
        try {
            assertTrue(supervisor.claim());
            AiRequestSchedulerSupervisor.DispatchAttempt attempt =
                    new AiRequestSchedulerSupervisor.DispatchAttempt(1L,
                            AiRequestSchedulerSupervisor.Lane.COMPLETION_DELIVERY,
                            new AiRequestSchedulerSupervisor.DispatchCallbacks() {
                                @Override
                                public boolean started(
                                        AiRequestSchedulerSupervisor.DispatchAttempt ignored) {
                                    return true;
                                }

                                @Override
                                public void startLost(
                                        AiRequestSchedulerSupervisor.DispatchAttempt ignored) {
                                    throw new AssertionError("fatal delivery attempt lost start");
                                }

                                @Override
                                public void stalled(
                                        AiRequestSchedulerSupervisor.DispatchAttempt ignored) {
                                    throw new AssertionError("fatal delivery attempt stalled");
                                }

                                @Override
                                public void bodySucceeded(
                                        AiRequestSchedulerSupervisor.DispatchAttempt ignored) {
                                    succeeded.incrementAndGet();
                                }

                                @Override
                                public void bodyFailed(
                                        AiRequestSchedulerSupervisor.DispatchAttempt ignored,
                                        Throwable failure) {
                                    failed.countDown();
                                }
                            });
            supervisor.handoff(attempt, () -> {
                throw new LinkageError("fatal delivery body error");
            });
            assertTrue(failed.await(2L, TimeUnit.SECONDS));
            assertEquals(0, succeeded.get());
            assertTrue(await(() -> rethrown.get() instanceof LinkageError, 2_000L));
        } finally {
            supervisor.close();
        }
    }

    @Test
    void publicApiUsesSupervisorRatherThanRawExecutorHandoff() {
        assertEquals(2, AiRequestScheduler.class.getConstructors().length);
        assertThrows(IllegalArgumentException.class, () -> {
            AiRequestSchedulerSupervisor supervisor = AiRequestSchedulerSupervisor.create(
                    "single-claim", new AiRequestSchedulerPolicy(1, 1, 8, 8));
            try {
                new AiRequestScheduler(PROVIDER_ID, new ImmediateProvider(),
                        new AiRequestSchedulerPolicy(1, 1, 8, 8), supervisor);
                new AiRequestScheduler(PROVIDER_ID, new ImmediateProvider(),
                        new AiRequestSchedulerPolicy(1, 1, 8, 8), supervisor);
            } finally {
                supervisor.close();
            }
        });
    }

    private static AiRequestScheduler scheduler(
            AiProvider provider, AiRequestSchedulerPolicy policy, LaneFixture lanes) {
        return scheduler(provider, policy, lanes, START_TIMEOUT, STALL_TIMEOUT);
    }

    private static AiRequestScheduler scheduler(
            AiProvider provider,
            AiRequestSchedulerPolicy policy,
            LaneFixture lanes,
            Duration startTimeout,
            Duration stallTimeout) {
        return new AiRequestScheduler(PROVIDER_ID, provider, policy, Clock.systemUTC(),
                lanes.supervisor(policy, startTimeout, stallTimeout));
    }

    private static AiRequestScheduler scheduler(
            AiProvider provider,
            AiRequestSchedulerPolicy policy,
            LaneFixture lanes,
            Clock clock) {
        return new AiRequestScheduler(PROVIDER_ID, provider, policy, clock,
                lanes.supervisor(policy, START_TIMEOUT, STALL_TIMEOUT));
    }

    private static void configureInlineLanesExcept(
            LaneFixture lanes, AiRequestSchedulerSupervisor.Lane exception) {
        for (AiRequestSchedulerSupervisor.Lane lane
                : AiRequestSchedulerSupervisor.Lane.values()) {
            if (lane != exception) {
                lanes.set(lane, new InlineExecutor());
            }
        }
    }

    private static void configureAllInlineLanes(LaneFixture lanes) {
        for (AiRequestSchedulerSupervisor.Lane lane
                : AiRequestSchedulerSupervisor.Lane.values()) {
            lanes.set(lane, new InlineExecutor());
        }
    }

    private static AiScheduledRequest scheduled(
            UUID botId, UUID agentId, AiRequest request) {
        return new AiScheduledRequest(OWNER, botId, agentId, 1L, request);
    }

    private static AiRequest request(String id) {
        return request(id, 5_000L);
    }

    private static AiRequest request(String id, long timeoutMillis) {
        AiRequest base = AiTestFixtures.request(UUID.fromString(id));
        return new AiRequest(base.requestId(), base.model(), base.messages(),
                new AiRequestOptions(base.options().maximumOutputTokens(), timeoutMillis,
                        base.options().responseFormat(), base.options().reasoningAllowed(),
                        base.options().toolCallsAllowed(), base.options().temperature()),
                base.responseSchemaJson());
    }

    private static AiResponse response(AiRequest request) {
        return AiTestFixtures.response(request, PROVIDER_ID);
    }

    private static AiProviderException failure(CompletableFuture<?> future) {
        try {
            future.get(2L, TimeUnit.SECONDS);
            throw new AssertionError("future unexpectedly completed normally");
        } catch (ExecutionException exception) {
            assertTrue(exception.getCause() instanceof AiProviderException);
            return (AiProviderException) exception.getCause();
        } catch (TimeoutException exception) {
            throw new AssertionError("future did not complete before timeout", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("future wait was interrupted", exception);
        }
    }

    private static Call callFor(ControllableProvider provider, AiRequest request) {
        return provider.calls().stream().filter(
                call -> call.request().requestId().equals(request.requestId()))
                .findFirst().orElseThrow();
    }

    @FunctionalInterface
    private interface Condition {
        boolean evaluate();
    }

    private static boolean await(Condition condition, long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        do {
            if (condition.evaluate()) {
                return true;
            }
            Thread.sleep(5L);
        } while (System.nanoTime() < deadline);
        return condition.evaluate();
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(5L, TimeUnit.SECONDS)) {
                throw new AssertionError("test callback did not receive release signal");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("test callback was interrupted", exception);
        }
    }

    private static final class LaneFixture implements AutoCloseable {
        private final EnumMap<AiRequestSchedulerSupervisor.Lane, Executor> executors =
                new EnumMap<>(AiRequestSchedulerSupervisor.Lane.class);
        private final List<ExecutorService> services = new CopyOnWriteArrayList<>();
        private final List<AiRequestSchedulerSupervisor> supervisors = new CopyOnWriteArrayList<>();

        private LaneFixture() {
            for (AiRequestSchedulerSupervisor.Lane lane
                    : AiRequestSchedulerSupervisor.Lane.values()) {
                ExecutorService service = Executors.newFixedThreadPool(2, runnable -> {
                    Thread thread = new Thread(runnable, "scheduler-adversarial-" + lane);
                    thread.setDaemon(true);
                    return thread;
                });
                services.add(service);
                executors.put(lane, service);
            }
        }

        private Executor executor(AiRequestSchedulerSupervisor.Lane lane) {
            return executors.get(lane);
        }

        private void set(AiRequestSchedulerSupervisor.Lane lane, Executor executor) {
            executors.put(lane, executor);
        }

        private AiRequestSchedulerSupervisor supervisor(
                AiRequestSchedulerPolicy policy,
                Duration startTimeout,
                Duration stallTimeout) {
            AiRequestSchedulerSupervisor supervisor =
                    AiRequestSchedulerSupervisor.forAdversarialTesting(
                            "scheduler-adversarial", policy, startTimeout, stallTimeout,
                            (lane, wrapper) -> executors.get(lane).execute(wrapper));
            supervisors.add(supervisor);
            return supervisor;
        }

        @Override
        public void close() {
            for (AiRequestSchedulerSupervisor supervisor : supervisors) {
                supervisor.close();
            }
            for (ExecutorService service : services) {
                service.shutdownNow();
            }
            for (ExecutorService service : services) {
                try {
                    assertTrue(service.awaitTermination(2L, TimeUnit.SECONDS));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("test lane did not terminate", exception);
                }
            }
        }
    }

    private static final class ControllableProvider implements AiProvider {
        private final List<Call> calls = new CopyOnWriteArrayList<>();

        @Override
        public CompletionStage<AiResponse> complete(
                AiRequest request, CancellationToken token) {
            TrackingFuture<AiResponse> stage = new TrackingFuture<>();
            calls.add(new Call(request, stage));
            return stage;
        }

        @Override
        public CompletionStage<AiCapabilities> probeCapabilities() {
            return CompletableFuture.completedFuture(AiTestFixtures.capabilities(PROVIDER_ID));
        }

        @Override
        public ProviderHealth health() {
            return ProviderHealth.healthy(PROVIDER_ID, Instant.EPOCH);
        }

        private List<Call> calls() {
            return calls;
        }
    }

    private static final class ImmediateProvider implements AiProvider {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public CompletionStage<AiResponse> complete(
                AiRequest request, CancellationToken token) {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(response(request));
        }

        @Override
        public CompletionStage<AiCapabilities> probeCapabilities() {
            return CompletableFuture.completedFuture(AiTestFixtures.capabilities(PROVIDER_ID));
        }

        @Override
        public ProviderHealth health() {
            return ProviderHealth.healthy(PROVIDER_ID, Instant.EPOCH);
        }
    }

    private static final class ThrowingThenImmediateProvider implements AiProvider {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public CompletionStage<AiResponse> complete(
                AiRequest request, CancellationToken token) {
            if (calls.getAndIncrement() == 0) {
                throw new AssertionError("provider complete error");
            }
            return CompletableFuture.completedFuture(response(request));
        }

        @Override
        public CompletionStage<AiCapabilities> probeCapabilities() {
            return CompletableFuture.completedFuture(AiTestFixtures.capabilities(PROVIDER_ID));
        }

        @Override
        public ProviderHealth health() {
            return ProviderHealth.healthy(PROVIDER_ID, Instant.EPOCH);
        }
    }

    private static final class AttachmentErrorThenImmediateProvider implements AiProvider {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public CompletionStage<AiResponse> complete(
                AiRequest request, CancellationToken token) {
            if (calls.getAndIncrement() == 0) {
                return new AttachmentErrorFuture<>();
            }
            return CompletableFuture.completedFuture(response(request));
        }

        @Override
        public CompletionStage<AiCapabilities> probeCapabilities() {
            return CompletableFuture.completedFuture(AiTestFixtures.capabilities(PROVIDER_ID));
        }

        @Override
        public ProviderHealth health() {
            return ProviderHealth.healthy(PROVIDER_ID, Instant.EPOCH);
        }
    }

    private static final class ThrowOnceCancelProvider implements AiProvider {
        private final AtomicInteger calls = new AtomicInteger();
        private final ThrowOnceCancelFuture<AiResponse> stage = new ThrowOnceCancelFuture<>();

        @Override
        public CompletionStage<AiResponse> complete(
                AiRequest request, CancellationToken token) {
            calls.incrementAndGet();
            return stage;
        }

        @Override
        public CompletionStage<AiCapabilities> probeCapabilities() {
            return CompletableFuture.completedFuture(AiTestFixtures.capabilities(PROVIDER_ID));
        }

        @Override
        public ProviderHealth health() {
            return ProviderHealth.healthy(PROVIDER_ID, Instant.EPOCH);
        }
    }

    private static final class OrderingProvider implements AiProvider {
        private final AtomicInteger calls = new AtomicInteger();
        private final CountDownLatch firstEntered = new CountDownLatch(1);
        private final CountDownLatch secondEntered = new CountDownLatch(1);
        private final AtomicBoolean secondStartedBeforeFirstCancellation = new AtomicBoolean();
        private final TrackingFuture<AiResponse> firstStage = new TrackingFuture<>();
        private final TrackingFuture<AiResponse> secondStage = new TrackingFuture<>();

        @Override
        public CompletionStage<AiResponse> complete(
                AiRequest request, CancellationToken token) {
            if (calls.getAndIncrement() == 0) {
                firstEntered.countDown();
                return firstStage;
            }
            if (!firstStage.isCancelled()) {
                secondStartedBeforeFirstCancellation.set(true);
            }
            secondEntered.countDown();
            return secondStage;
        }

        @Override
        public CompletionStage<AiCapabilities> probeCapabilities() {
            return CompletableFuture.completedFuture(AiTestFixtures.capabilities(PROVIDER_ID));
        }

        @Override
        public ProviderHealth health() {
            return ProviderHealth.healthy(PROVIDER_ID, Instant.EPOCH);
        }
    }

    private static final class BlockingCleanupProvider implements AiProvider {
        private final AtomicInteger calls = new AtomicInteger();
        private final CountDownLatch firstEntered = new CountDownLatch(1);
        private final CountDownLatch secondEntered = new CountDownLatch(1);
        private final CountDownLatch cancellationEntered = new CountDownLatch(1);
        private final CountDownLatch releaseCancellation = new CountDownLatch(1);
        private final BlockingCancellationFuture<AiResponse> firstStage =
                new BlockingCancellationFuture<>(cancellationEntered, releaseCancellation);
        private final TrackingFuture<AiResponse> secondStage = new TrackingFuture<>();

        @Override
        public CompletionStage<AiResponse> complete(
                AiRequest request, CancellationToken token) {
            if (calls.getAndIncrement() == 0) {
                firstEntered.countDown();
                return firstStage;
            }
            secondEntered.countDown();
            return secondStage;
        }

        @Override
        public CompletionStage<AiCapabilities> probeCapabilities() {
            return CompletableFuture.completedFuture(AiTestFixtures.capabilities(PROVIDER_ID));
        }

        @Override
        public ProviderHealth health() {
            return ProviderHealth.healthy(PROVIDER_ID, Instant.EPOCH);
        }
    }

    private static final class BlockingCancellationFuture<T> extends CompletableFuture<T> {
        private final CountDownLatch cancellationEntered;
        private final CountDownLatch releaseCancellation;

        private BlockingCancellationFuture(
                CountDownLatch cancellationEntered, CountDownLatch releaseCancellation) {
            this.cancellationEntered = cancellationEntered;
            this.releaseCancellation = releaseCancellation;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancellationEntered.countDown();
            try {
                releaseCancellation.await(5L, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return super.cancel(mayInterruptIfRunning);
        }
    }

    private static final class BlockingProvider implements AiProvider {
        private final AtomicInteger calls = new AtomicInteger();
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final TrackingFuture<AiResponse> stage = new TrackingFuture<>();

        @Override
        public CompletionStage<AiResponse> complete(
                AiRequest request, CancellationToken token) {
            calls.incrementAndGet();
            entered.countDown();
            awaitLatch(release);
            return stage;
        }

        @Override
        public CompletionStage<AiCapabilities> probeCapabilities() {
            return CompletableFuture.completedFuture(AiTestFixtures.capabilities(PROVIDER_ID));
        }

        @Override
        public ProviderHealth health() {
            return ProviderHealth.healthy(PROVIDER_ID, Instant.EPOCH);
        }
    }

    private static final class BlockingToken implements CancellationToken {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public boolean isCancellationRequested() {
            return false;
        }

        @Override
        public ListenerRegistration onCancellation(Runnable listener) {
            entered.countDown();
            awaitLatch(release);
            return ListenerRegistration.none();
        }
    }

    private static final class CountingToken implements CancellationToken {
        private final AtomicInteger reads = new AtomicInteger();
        private final CancellationTokenSource source = new CancellationTokenSource();

        @Override
        public boolean isCancellationRequested() {
            reads.incrementAndGet();
            return source.isCancellationRequested();
        }

        @Override
        public ListenerRegistration onCancellation(Runnable listener) {
            return source.onCancellation(listener);
        }

        private void cancel() {
            source.cancel();
        }
    }

    private static final class ThrowingRegistrationToken implements CancellationToken {
        @Override
        public boolean isCancellationRequested() {
            return false;
        }

        @Override
        public ListenerRegistration onCancellation(Runnable listener) {
            throw new AssertionError("token registration error");
        }
    }

    /**
     * Simulates a hostile token implementation that calls its listener while registration is
     * still provisional, then reports that registration itself failed. The callback must not win
     * over the attachment failure.
     */
    private static final class CallbackThenThrowToken implements CancellationToken {
        private final CountDownLatch callbackInvoked = new CountDownLatch(1);
        private final CountDownLatch releaseThrow = new CountDownLatch(1);

        @Override
        public boolean isCancellationRequested() {
            return false;
        }

        @Override
        public ListenerRegistration onCancellation(Runnable listener) {
            listener.run();
            callbackInvoked.countDown();
            awaitLatch(releaseThrow);
            throw new AssertionError("cancellation callback then attachment error");
        }
    }

    /** A synchronous callback is provisional until this registration method returns normally. */
    private static final class CallbackThenReturnToken implements CancellationToken {
        private final CountDownLatch callbackInvoked = new CountDownLatch(1);
        private final CountDownLatch releaseRegistration = new CountDownLatch(1);
        private final CountDownLatch registrationCloseEntered = new CountDownLatch(1);
        private final CountDownLatch releaseRegistrationClose = new CountDownLatch(1);
        private final AtomicInteger closeCalls = new AtomicInteger();

        @Override
        public boolean isCancellationRequested() {
            return false;
        }

        @Override
        public ListenerRegistration onCancellation(Runnable listener) {
            listener.run();
            callbackInvoked.countDown();
            awaitLatch(releaseRegistration);
            return () -> {
                closeCalls.incrementAndGet();
                registrationCloseEntered.countDown();
                awaitLatch(releaseRegistrationClose);
            };
        }
    }

    /** The listener fires during attachment, while the second token read itself fails. */
    private static final class CallbackThenSecondReadErrorToken implements CancellationToken {
        private final AtomicInteger reads = new AtomicInteger();
        private final AtomicInteger closeCalls = new AtomicInteger();

        @Override
        public boolean isCancellationRequested() {
            if (reads.getAndIncrement() == 0) {
                return false;
            }
            throw new AssertionError("second token read error");
        }

        @Override
        public ListenerRegistration onCancellation(Runnable listener) {
            listener.run();
            return closeCalls::incrementAndGet;
        }
    }

    private static final class ThrowOnceCleanupToken implements CancellationToken {
        private final AtomicBoolean failClose = new AtomicBoolean(true);
        private final AtomicInteger closeCalls = new AtomicInteger();

        @Override
        public boolean isCancellationRequested() {
            return false;
        }

        @Override
        public ListenerRegistration onCancellation(Runnable listener) {
            return () -> {
                closeCalls.incrementAndGet();
                if (failClose.compareAndSet(true, false)) {
                    throw new AssertionError("listener close error");
                }
            };
        }
    }

    private static final class BlockingAttachmentProvider implements AiProvider {
        private final CountDownLatch attachmentEntered = new CountDownLatch(1);
        private final CountDownLatch releaseAttachment = new CountDownLatch(1);
        private final BlockingAttachmentFuture<AiResponse> stage =
                new BlockingAttachmentFuture<>(attachmentEntered, releaseAttachment);

        @Override
        public CompletionStage<AiResponse> complete(
                AiRequest request, CancellationToken token) {
            return stage;
        }

        @Override
        public CompletionStage<AiCapabilities> probeCapabilities() {
            return CompletableFuture.completedFuture(AiTestFixtures.capabilities(PROVIDER_ID));
        }

        @Override
        public ProviderHealth health() {
            return ProviderHealth.healthy(PROVIDER_ID, Instant.EPOCH);
        }
    }

    /** An attachment boundary which blocks before installing the scheduler's completion callback. */
    private static final class BlockingAttachmentFuture<T> extends CompletableFuture<T> {
        private final CountDownLatch attachmentEntered;
        private final CountDownLatch releaseAttachment;

        private BlockingAttachmentFuture(
                CountDownLatch attachmentEntered, CountDownLatch releaseAttachment) {
            this.attachmentEntered = attachmentEntered;
            this.releaseAttachment = releaseAttachment;
        }

        @Override
        public CompletableFuture<T> whenComplete(
                BiConsumer<? super T, ? super Throwable> action) {
            attachmentEntered.countDown();
            try {
                releaseAttachment.await(5L, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return super.whenComplete(action);
        }
    }

    private static final class AttachmentErrorFuture<T> extends CompletableFuture<T> {
        @Override
        public CompletableFuture<T> whenComplete(
                BiConsumer<? super T, ? super Throwable> action) {
            throw new AssertionError("whenComplete attachment error");
        }
    }

    /**
     * Simulates a CompletionStage that synchronously supplies a valid response, but then throws
     * from attachment. The response is provisional until whenComplete has returned normally.
     */
    private static final class CallbackThenThrowAttachmentProvider implements AiProvider {
        private final AtomicInteger calls = new AtomicInteger();
        private final CountDownLatch callbackInvoked = new CountDownLatch(1);
        private final CountDownLatch releaseThrow = new CountDownLatch(1);

        @Override
        public CompletionStage<AiResponse> complete(
                AiRequest request, CancellationToken token) {
            if (calls.getAndIncrement() == 0) {
                return new CallbackThenThrowFuture<>(response(request), callbackInvoked,
                        releaseThrow);
            }
            return CompletableFuture.completedFuture(response(request));
        }

        @Override
        public CompletionStage<AiCapabilities> probeCapabilities() {
            return CompletableFuture.completedFuture(AiTestFixtures.capabilities(PROVIDER_ID));
        }

        @Override
        public ProviderHealth health() {
            return ProviderHealth.healthy(PROVIDER_ID, Instant.EPOCH);
        }
    }

    private static final class CallbackThenThrowFuture<T> extends CompletableFuture<T> {
        private final T synchronousResponse;
        private final CountDownLatch callbackInvoked;
        private final CountDownLatch releaseThrow;

        private CallbackThenThrowFuture(
                T synchronousResponse,
                CountDownLatch callbackInvoked,
                CountDownLatch releaseThrow) {
            this.synchronousResponse = synchronousResponse;
            this.callbackInvoked = callbackInvoked;
            this.releaseThrow = releaseThrow;
        }

        @Override
        public CompletableFuture<T> whenComplete(
                BiConsumer<? super T, ? super Throwable> action) {
            action.accept(synchronousResponse, null);
            callbackInvoked.countDown();
            awaitLatch(releaseThrow);
            throw new AssertionError("completion callback then attachment error");
        }
    }

    /** A synchronous stage callback is provisional until whenComplete returns normally. */
    private static final class CallbackThenReturnAttachmentProvider implements AiProvider {
        private final AtomicInteger calls = new AtomicInteger();
        private final CountDownLatch callbackInvoked = new CountDownLatch(1);
        private final CountDownLatch releaseAttachment = new CountDownLatch(1);

        @Override
        public CompletionStage<AiResponse> complete(
                AiRequest request, CancellationToken token) {
            calls.incrementAndGet();
            return new CallbackThenReturnFuture<>(response(request), callbackInvoked,
                    releaseAttachment);
        }

        @Override
        public CompletionStage<AiCapabilities> probeCapabilities() {
            return CompletableFuture.completedFuture(AiTestFixtures.capabilities(PROVIDER_ID));
        }

        @Override
        public ProviderHealth health() {
            return ProviderHealth.healthy(PROVIDER_ID, Instant.EPOCH);
        }
    }

    private static final class CallbackThenReturnFuture<T> extends CompletableFuture<T> {
        private final T synchronousResponse;
        private final CountDownLatch callbackInvoked;
        private final CountDownLatch releaseAttachment;

        private CallbackThenReturnFuture(
                T synchronousResponse,
                CountDownLatch callbackInvoked,
                CountDownLatch releaseAttachment) {
            this.synchronousResponse = synchronousResponse;
            this.callbackInvoked = callbackInvoked;
            this.releaseAttachment = releaseAttachment;
        }

        @Override
        public CompletableFuture<T> whenComplete(
                BiConsumer<? super T, ? super Throwable> action) {
            action.accept(synchronousResponse, null);
            callbackInvoked.countDown();
            awaitLatch(releaseAttachment);
            return this;
        }
    }

    private static final class ThrowOnceCancelFuture<T> extends CompletableFuture<T> {
        private final AtomicBoolean failCancel = new AtomicBoolean(true);
        private final AtomicInteger cancelCalls = new AtomicInteger();

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelCalls.incrementAndGet();
            if (failCancel.compareAndSet(true, false)) {
                throw new AssertionError("stage cancel error");
            }
            return super.cancel(mayInterruptIfRunning);
        }
    }

    private static final class TrackingFuture<T> extends CompletableFuture<T> {
        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return super.cancel(mayInterruptIfRunning);
        }
    }

    private record Call(AiRequest request, TrackingFuture<AiResponse> stage) {
    }

    private static final class DroppingExecutor implements Executor {
        @Override
        public void execute(Runnable command) {
            // Intentionally reports normal acceptance while silently dropping the wrapper.
        }
    }

    private static final class InlineExecutor implements Executor {
        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }

    private static final class TwiceExecutor implements Executor {
        @Override
        public void execute(Runnable command) {
            command.run();
            command.run();
        }
    }

    private static final class InvokeThenThrowExecutor implements Executor {
        @Override
        public void execute(Runnable command) {
            command.run();
            throw new RejectedExecutionException("throws after wrapper invocation");
        }
    }

    private static final class InvokeThenErrorExecutor implements Executor {
        @Override
        public void execute(Runnable command) {
            command.run();
            throw new AssertionError("throws Error after wrapper invocation");
        }
    }

    private static final class BlockingExecuteExecutor implements Executor {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void execute(Runnable command) {
            entered.countDown();
            try {
                release.await(5L, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            // A hostile executor can return without ever invoking the retained command.
        }
    }

    private static final class CapturingExecutor implements Executor {
        private final CountDownLatch captured = new CountDownLatch(1);
        private final AtomicReference<Runnable> command = new AtomicReference<>();

        @Override
        public void execute(Runnable wrapper) {
            command.set(wrapper);
            captured.countDown();
        }

        private void runCaptured() {
            Runnable wrapper = command.getAndSet(null);
            assertTrue(wrapper != null);
            wrapper.run();
        }
    }

    private static final class DropOnceExecutor implements Executor {
        private final Executor delegate;
        private final AtomicBoolean drop = new AtomicBoolean(true);

        private DropOnceExecutor(Executor delegate) {
            this.delegate = delegate;
        }

        @Override
        public void execute(Runnable command) {
            if (drop.compareAndSet(true, false)) {
                return;
            }
            delegate.execute(command);
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;

        private MutableClock(Instant initial) {
            instant = new AtomicReference<>(initial);
        }

        private void set(Instant next) {
            instant.set(next);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }
}
