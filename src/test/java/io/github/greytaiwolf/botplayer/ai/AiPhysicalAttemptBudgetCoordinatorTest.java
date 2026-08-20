package io.github.greytaiwolf.botplayer.ai;

import io.github.greytaiwolf.botplayer.ai.transport.AiClientRequestDispatch;
import io.github.greytaiwolf.botplayer.ai.transport.AiRequestDispatchReceipt;
import io.github.greytaiwolf.botplayer.ai.transport.AiRequestPurpose;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AiPhysicalAttemptBudgetCoordinatorTest {
    private static final Instant START = Instant.parse("2026-08-20T00:00:00Z");
    private static final UUID SERVER = UUID.fromString(
            "11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER = UUID.fromString(
            "22222222-2222-2222-2222-222222222222");
    private static final UUID BOT = UUID.fromString(
            "33333333-3333-3333-3333-333333333333");
    private static final UUID AGENT = UUID.fromString(
            "44444444-4444-4444-4444-444444444444");
    private static final UUID REQUEST = UUID.fromString(
            "55555555-5555-5555-5555-555555555555");
    private static final UUID NONCE = UUID.fromString(
            "66666666-6666-6666-6666-666666666666");
    private static final UUID ATTEMPT = UUID.fromString(
            "77777777-7777-7777-7777-777777777777");
    private static final UUID SECOND_ATTEMPT = UUID.fromString(
            "88888888-8888-8888-8888-888888888888");

    @Test
    void offerAckSettlementAndGrantCarryOneRedactedExactIdentity() {
        Harness harness = harness(new AiPhysicalAttemptBudgetCoordinator.Limits(2, 4));

        AiPhysicalAttemptOfferResult offered = harness.offer(START.plusSeconds(5L));
        AiPhysicalAttemptOffer offer = offered.offer().orElseThrow();
        AiPhysicalAttemptIdentity identity = offer.identity();
        AiTokenBudgetSnapshot reserved = harness.ledger.snapshot();

        AiPhysicalAttemptPrepareResult granted = harness.coordinator.acknowledge(
                offer.prepareAck());
        AiPhysicalAttemptStartGrant grant = granted.grant().orElseThrow();
        AiPhysicalAttemptPrepareResult duplicate = harness.coordinator.acknowledge(
                offer.prepareAck());
        AiTokenBudgetSnapshot committed = harness.ledger.snapshot();

        Assertions.assertAll(
                () -> Assertions.assertEquals(AiPhysicalAttemptOfferStatus.OFFERED,
                        offered.status()),
                () -> Assertions.assertEquals(SERVER, identity.serverInstanceId()),
                () -> Assertions.assertEquals(OWNER, identity.ownerId()),
                () -> Assertions.assertEquals(AiRequestDispatchReceipt.fromDispatch(
                        harness.dispatch), identity.dispatchReceipt()),
                () -> Assertions.assertFalse(identity.attemptId().equals(new UUID(0L, 0L))),
                () -> Assertions.assertEquals(NONCE, identity.nonce()),
                () -> Assertions.assertEquals(harness.dispatch.expiresAtEpochMillis(),
                        identity.clientNotAfterEpochMillis()),
                () -> Assertions.assertEquals(START.plusSeconds(5L).toEpochMilli(),
                        identity.physicalStartNotAfterEpochMillis()),
                () -> Assertions.assertEquals(30L, reserved.reservedTokens()),
                () -> Assertions.assertEquals(0L, reserved.committedTokens()),
                () -> Assertions.assertEquals(AiPhysicalAttemptPrepareStatus.START_GRANTED,
                        granted.status()),
                () -> Assertions.assertSame(grant, duplicate.grant().orElseThrow()),
                () -> Assertions.assertEquals(AiTokenBudgetOperationStatus.SETTLED,
                        duplicate.settlementStatus().orElseThrow()),
                () -> Assertions.assertEquals(0L, committed.reservedTokens()),
                () -> Assertions.assertEquals(30L, committed.committedTokens()),
                () -> Assertions.assertEquals(new AiPhysicalAttemptBudgetSnapshot(0, 1, 0),
                        harness.coordinator.snapshot()));

        assertRedacted(identity, offer, offer.prepareAck(), grant);
        assertNoForbiddenProductionReference(AiPhysicalAttemptBudgetCoordinator.class);
        assertNoForbiddenProductionReference(AiPhysicalAttemptClientGrantGate.class);
    }

    @Test
    void driftAndReplayRejectWithoutMutatingReservationOrAttemptIndexes() {
        Harness harness = harness(new AiPhysicalAttemptBudgetCoordinator.Limits(2, 4));
        AiPhysicalAttemptOffer offer = harness.offer(START.plusSeconds(5L))
                .offer().orElseThrow();
        AiPhysicalAttemptBudgetSnapshot before = harness.coordinator.snapshot();
        AiTokenBudgetSnapshot budgetBefore = harness.ledger.snapshot();
        AiPhysicalAttemptIdentity identity = offer.identity();
        AiPhysicalAttemptIdentity driftedNonce = new AiPhysicalAttemptIdentity(
                identity.serverInstanceId(), identity.ownerId(), identity.dispatchReceipt(),
                identity.attemptId(), SECOND_ATTEMPT, identity.clientNotAfterEpochMillis(),
                identity.physicalStartNotAfterEpochMillis());
        AiPhysicalAttemptIdentity driftedAttempt = new AiPhysicalAttemptIdentity(
                identity.serverInstanceId(), identity.ownerId(), identity.dispatchReceipt(),
                SECOND_ATTEMPT, identity.nonce(), identity.clientNotAfterEpochMillis(),
                identity.physicalStartNotAfterEpochMillis());

        AiPhysicalAttemptOfferResult duplicateOffer = harness.offer(START.plusSeconds(5L));
        AiPhysicalAttemptOfferResult sameRequestDifferentAttempt = harness.offer(
                START.plusSeconds(5L));
        AiPhysicalAttemptPrepareResult nonceRejected = harness.coordinator.acknowledge(
                new AiPhysicalAttemptPrepareAck(driftedNonce));
        AiPhysicalAttemptPrepareResult attemptRejected = harness.coordinator.acknowledge(
                new AiPhysicalAttemptPrepareAck(driftedAttempt));

        Assertions.assertAll(
                () -> Assertions.assertEquals(AiPhysicalAttemptOfferStatus.REQUEST_ATTEMPT_IN_FLIGHT,
                        duplicateOffer.status()),
                () -> Assertions.assertEquals(AiPhysicalAttemptOfferStatus.REQUEST_ATTEMPT_IN_FLIGHT,
                        sameRequestDifferentAttempt.status()),
                () -> Assertions.assertEquals(AiPhysicalAttemptPrepareStatus.IDENTITY_MISMATCH,
                        nonceRejected.status()),
                () -> Assertions.assertEquals(AiPhysicalAttemptPrepareStatus.IDENTITY_MISMATCH,
                        attemptRejected.status()),
                () -> Assertions.assertEquals(before, harness.coordinator.snapshot()),
                () -> Assertions.assertEquals(budgetBefore, harness.ledger.snapshot()));
    }

    @Test
    void preCommitCloseAndOfferExpiryReleaseOnlyUnstartedReservationAndTombstoneReplay() {
        Harness closedHarness = harness(new AiPhysicalAttemptBudgetCoordinator.Limits(2, 4));
        AiPhysicalAttemptOffer closedOffer = closedHarness.offer(START.plusSeconds(5L))
                .offer().orElseThrow();

        AiPhysicalAttemptCloseResult close = closedHarness.coordinator.closeExact(
                closedOffer.identity());
        AiPhysicalAttemptPrepareResult replay = closedHarness.coordinator.acknowledge(
                closedOffer.prepareAck());
        AiTokenBudgetSnapshot released = closedHarness.ledger.snapshot();
        AiPhysicalAttemptOfferResult retryOffer = closedHarness.offer(START.plusSeconds(5L));

        Assertions.assertAll(
                () -> Assertions.assertEquals(AiPhysicalAttemptCloseStatus.CLOSED_UNSTARTED,
                        close.status()),
                () -> Assertions.assertEquals(AiTokenBudgetOperationStatus.RELEASED,
                        close.releaseStatus().orElseThrow()),
                () -> Assertions.assertEquals(0L, released.reservedTokens()),
                () -> Assertions.assertEquals(0L, released.committedTokens()),
                () -> Assertions.assertEquals(AiPhysicalAttemptPrepareStatus.ATTEMPT_TOMBSTONED,
                        replay.status()),
                () -> Assertions.assertEquals(AiPhysicalAttemptOfferStatus.OFFERED,
                        retryOffer.status()),
                () -> Assertions.assertFalse(closedOffer.identity().attemptId().equals(
                        retryOffer.offer().orElseThrow().identity().attemptId())));

        Harness expiryHarness = harness(new AiPhysicalAttemptBudgetCoordinator.Limits(2, 4));
        AiPhysicalAttemptOffer expiredOffer = expiryHarness.offer(START.plusSeconds(5L))
                .offer().orElseThrow();
        expiryHarness.clock.set(START.plusSeconds(5L));
        AiPhysicalAttemptCloseSummary expired = expiryHarness.coordinator.expireDueAttempts();

        Assertions.assertAll(
                () -> Assertions.assertEquals(new AiPhysicalAttemptCloseSummary(1, 0), expired),
                () -> Assertions.assertEquals(0L, expiryHarness.ledger.snapshot().reservedTokens()),
                () -> Assertions.assertEquals(AiPhysicalAttemptPrepareStatus.ATTEMPT_TOMBSTONED,
                        expiryHarness.coordinator.acknowledge(expiredOffer.prepareAck()).status()),
                () -> Assertions.assertEquals(new AiPhysicalAttemptBudgetSnapshot(0, 0, 1),
                        expiryHarness.coordinator.snapshot()));
    }

    @Test
    void expiredGrantReplayAndDisconnectNeverRefundCommittedAttemptEvenWhenGrantIsLost() {
        Harness harness = harness(new AiPhysicalAttemptBudgetCoordinator.Limits(2, 4));
        AiPhysicalAttemptOffer first = harness.offer(START.plusSeconds(5L))
                .offer().orElseThrow();
        AiPhysicalAttemptStartGrant grant = harness.coordinator.acknowledge(first.prepareAck())
                .grant().orElseThrow();

        harness.clock.set(START.plusSeconds(10L));
        AiPhysicalAttemptPrepareResult expiredGrantReplay = harness.coordinator.acknowledge(
                first.prepareAck());
        AiPhysicalAttemptCloseSummary disconnect = harness.coordinator.closeAll();
        AiPhysicalAttemptPrepareResult lostGrantReplay = harness.coordinator.acknowledge(
                first.prepareAck());

        Assertions.assertAll(
                () -> Assertions.assertEquals(new AiPhysicalAttemptCloseSummary(0, 0),
                        disconnect),
                () -> Assertions.assertEquals(30L, harness.ledger.snapshot().committedTokens()),
                () -> Assertions.assertEquals(0L, harness.ledger.snapshot().reservedTokens()),
                () -> Assertions.assertEquals(
                        AiPhysicalAttemptPrepareStatus.CLIENT_NOT_AFTER_EXPIRED,
                        expiredGrantReplay.status()),
                () -> Assertions.assertEquals(AiPhysicalAttemptPrepareStatus.ATTEMPT_TOMBSTONED,
                        lostGrantReplay.status()),
                () -> Assertions.assertEquals(new AiPhysicalAttemptBudgetSnapshot(0, 0, 1),
                        harness.coordinator.snapshot()),
                () -> Assertions.assertEquals(first.identity(), grant.identity()));
    }

    @Test
    void periodicReaperTombstonesExpiredGrantWithoutRefundAndReclaimsActiveCapacity() {
        Harness harness = harness(new AiPhysicalAttemptBudgetCoordinator.Limits(1, 3));
        AiPhysicalAttemptOffer first = harness.offer(START.plusSeconds(5L))
                .offer().orElseThrow();
        harness.coordinator.acknowledge(first.prepareAck()).grant().orElseThrow();
        harness.clock.set(START.plusSeconds(5L));

        AiPhysicalAttemptCloseSummary expired = harness.coordinator.expireDueAttempts();
        AiTokenBudgetSnapshot budgetAfterExpiry = harness.ledger.snapshot();
        AiClientRequestDispatch nextDispatch = dispatch(
                OWNER,
                UUID.fromString("99999999-9999-9999-9999-999999999999"),
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                START.plusSeconds(20L));
        AiPhysicalAttemptOfferResult nextOffer = harness.coordinator.offer(
                new AiPhysicalAttemptOfferRequest(
                        nextDispatch,
                        context(harness.ledger, binding(nextDispatch), START.plusSeconds(19L)),
                        START.plusSeconds(15L)));

        Assertions.assertAll(
                () -> Assertions.assertEquals(new AiPhysicalAttemptCloseSummary(0, 1), expired),
                () -> Assertions.assertEquals(30L, budgetAfterExpiry.committedTokens()),
                () -> Assertions.assertEquals(0L, budgetAfterExpiry.reservedTokens()),
                () -> Assertions.assertEquals(AiPhysicalAttemptPrepareStatus.ATTEMPT_TOMBSTONED,
                        harness.coordinator.acknowledge(first.prepareAck()).status()),
                () -> Assertions.assertEquals(AiPhysicalAttemptOfferStatus.OFFERED,
                        nextOffer.status()));
    }

    @Test
    void settlementFailureIsTombstonedAndCannotCreateAGrantOrLeakAnUnstartedReservation() {
        Harness harness = harness(new AiPhysicalAttemptBudgetCoordinator.Limits(2, 4));
        AiPhysicalAttemptOffer offer = harness.offer(START.plusSeconds(5L))
                .offer().orElseThrow();
        harness.ledger.close();

        AiPhysicalAttemptPrepareResult result = harness.coordinator.acknowledge(
                offer.prepareAck());

        Assertions.assertAll(
                () -> Assertions.assertEquals(AiPhysicalAttemptPrepareStatus.SETTLEMENT_REJECTED,
                        result.status()),
                () -> Assertions.assertEquals(AiTokenBudgetOperationStatus.NOT_FOUND,
                        result.settlementStatus().orElseThrow()),
                () -> Assertions.assertTrue(result.grant().isEmpty()),
                () -> Assertions.assertEquals(0L, harness.ledger.snapshot().reservedTokens()),
                () -> Assertions.assertEquals(0L, harness.ledger.snapshot().committedTokens()),
                () -> Assertions.assertEquals(new AiPhysicalAttemptBudgetSnapshot(0, 0, 1),
                        harness.coordinator.snapshot()));
    }

    @Test
    void trustedBudgetBindingAndClientDeadlineAreValidatedBeforeReservation() {
        Harness harness = harness(new AiPhysicalAttemptBudgetCoordinator.Limits(2, 4));
        AiTokenBudgetScope foreignScope = new AiTokenBudgetScope(
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"), BOT, AGENT);
        AiRetryAttemptBudgetContext foreignContext = context(
                new AiTokenBudgetLedger(foreignScope, policy(100L, 2), harness.clock,
                        UUID::randomUUID),
                new AiTokenBudgetRequestBinding(foreignScope, REQUEST, 7L), START.plusSeconds(9L));
        AiPhysicalAttemptOfferRequest mismatched = new AiPhysicalAttemptOfferRequest(
                harness.dispatch, foreignContext, START.plusSeconds(5L));
        AiPhysicalAttemptOfferRequest tooLate = new AiPhysicalAttemptOfferRequest(
                harness.dispatch, harness.context, START.plusSeconds(11L));

        AiPhysicalAttemptOfferResult bindingRejected = harness.coordinator.offer(mismatched);
        AiPhysicalAttemptOfferResult deadlineRejected = harness.coordinator.offer(tooLate);

        Assertions.assertAll(
                () -> Assertions.assertEquals(AiPhysicalAttemptOfferStatus.REQUEST_BINDING_MISMATCH,
                        bindingRejected.status()),
                () -> Assertions.assertEquals(
                        AiPhysicalAttemptOfferStatus.ATTEMPT_DEADLINE_EXCEEDS_CLIENT_NOT_AFTER,
                        deadlineRejected.status()),
                () -> Assertions.assertEquals(0L, harness.ledger.snapshot().consumedTokens()),
                () -> Assertions.assertEquals(new AiPhysicalAttemptBudgetSnapshot(0, 0, 0),
                        harness.coordinator.snapshot()));
    }

    @Test
    void boundedTombstoneCapacityRefusesNewWorkUntilItsNotAfterExpires() {
        Harness harness = harness(new AiPhysicalAttemptBudgetCoordinator.Limits(1, 1));
        AiPhysicalAttemptOffer first = harness.offer(START.plusSeconds(5L))
                .offer().orElseThrow();
        harness.coordinator.closeExact(first.identity());
        AiClientRequestDispatch nextDispatch = dispatch(
                UUID.fromString("99999999-9999-9999-9999-999999999999"),
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                START.plusSeconds(20L));
        AiRetryAttemptBudgetContext nextContext = context(harness.ledger,
                binding(nextDispatch), START.plusSeconds(20L));
        AiPhysicalAttemptOfferRequest blocked = new AiPhysicalAttemptOfferRequest(nextDispatch,
                nextContext, START.plusSeconds(15L));

        Assertions.assertEquals(AiPhysicalAttemptOfferStatus.TOMBSTONE_CAPACITY,
                harness.coordinator.offer(blocked).status());

        harness.clock.set(START.plusSeconds(10L));
        Assertions.assertEquals(1, harness.coordinator.expireTombstones());
        Assertions.assertEquals(AiPhysicalAttemptOfferStatus.OFFERED,
                harness.coordinator.offer(blocked).status());
    }

    @Test
    void generatedAttemptIdCapacityFailsClosedWithoutReservingOrEvictingReplayDefense() {
        Harness harness = harness(new AiPhysicalAttemptBudgetCoordinator.Limits(1, 1),
                () -> new UUID(0L, 0L));

        AiPhysicalAttemptOfferResult result = harness.offer(START.plusSeconds(5L));

        Assertions.assertAll(
                () -> Assertions.assertEquals(AiPhysicalAttemptOfferStatus.ATTEMPT_ID_EXHAUSTED,
                        result.status()),
                () -> Assertions.assertTrue(result.offer().isEmpty()),
                () -> Assertions.assertEquals(0L, harness.ledger.snapshot().consumedTokens()),
                () -> Assertions.assertEquals(new AiPhysicalAttemptBudgetSnapshot(0, 0, 0),
                        harness.coordinator.snapshot()));
    }

    @Test
    void clockRollbackDoesNotSettleOrReleaseAnExactOutstandingOffer() {
        Harness harness = harness(new AiPhysicalAttemptBudgetCoordinator.Limits(2, 4));
        AiPhysicalAttemptOffer offer = harness.offer(START.plusSeconds(5L))
                .offer().orElseThrow();
        AiTokenBudgetSnapshot before = harness.ledger.snapshot();
        harness.clock.set(START.minusSeconds(1L));

        AiPhysicalAttemptPrepareResult result = harness.coordinator.acknowledge(
                offer.prepareAck());
        /* The coordinator rejected before touching its ledger; restore the test clock to inspect it. */
        harness.clock.set(START);

        Assertions.assertAll(
                () -> Assertions.assertEquals(AiPhysicalAttemptPrepareStatus.CLOCK_ROLLBACK,
                        result.status()),
                () -> Assertions.assertEquals(before, harness.ledger.snapshot()),
                () -> Assertions.assertEquals(new AiPhysicalAttemptBudgetSnapshot(1, 0, 0),
                        harness.coordinator.snapshot()));
    }

    @Test
    void clientGrantGateAllowsOneHandoffAndOneAtomicPhysicalStartClaim() {
        Harness harness = harness(new AiPhysicalAttemptBudgetCoordinator.Limits(2, 4));
        AiPhysicalAttemptOffer offer = harness.offer(START.plusSeconds(5L))
                .offer().orElseThrow();
        AiPhysicalAttemptStartGrant grant = harness.coordinator.acknowledge(offer.prepareAck())
                .grant().orElseThrow();
        AtomicLong currentBindingEpoch = new AtomicLong(7L);
        AtomicLong currentEpoch = new AtomicLong(START.toEpochMilli());
        AtomicBoolean sessionActive = new AtomicBoolean(true);
        AtomicInteger handoffs = new AtomicInteger();
        AtomicReference<AiPhysicalAttemptClientGrantGate.LocalStartLease> lease =
                new AtomicReference<>();
        AiPhysicalAttemptClientGrantGate gate = new AiPhysicalAttemptClientGrantGate(
                offer, harness.dispatch, 7L, currentBindingEpoch::get, currentEpoch::get,
                sessionActive::get, (received, receivedLease) -> {
                    Assertions.assertSame(grant, received);
                    handoffs.incrementAndGet();
                    lease.set(receivedLease);
                });

        Assertions.assertAll(
                () -> Assertions.assertEquals(AiPhysicalAttemptClientGrantStatus.HANDED_OFF,
                        gate.accept(grant)),
                () -> Assertions.assertEquals(AiPhysicalAttemptClientGrantStatus.ALREADY_HANDED_OFF,
                        gate.accept(grant)),
                () -> Assertions.assertEquals(1, handoffs.get()),
                () -> Assertions.assertTrue(lease.get().tryClaimPhysicalStart()),
                () -> Assertions.assertFalse(lease.get().tryClaimPhysicalStart()));

        gate.close();
        Assertions.assertFalse(lease.get().tryClaimPhysicalStart());
        Assertions.assertEquals(AiPhysicalAttemptClientGrantStatus.CLOSED, gate.accept(grant));
    }

    @Test
    void concurrentLocalPhysicalStartClaimsHaveExactlyOneWinner() throws Exception {
        Harness harness = harness(new AiPhysicalAttemptBudgetCoordinator.Limits(2, 4));
        AiPhysicalAttemptOffer offer = harness.offer(START.plusSeconds(5L))
                .offer().orElseThrow();
        AiPhysicalAttemptStartGrant grant = harness.coordinator.acknowledge(offer.prepareAck())
                .grant().orElseThrow();
        AtomicReference<AiPhysicalAttemptClientGrantGate.LocalStartLease> lease =
                new AtomicReference<>();
        AiPhysicalAttemptClientGrantGate gate = new AiPhysicalAttemptClientGrantGate(
                offer, harness.dispatch, 1L, () -> 1L, () -> START.toEpochMilli(), () -> true,
                (ignoredGrant, receivedLease) -> lease.set(receivedLease));
        Assertions.assertEquals(AiPhysicalAttemptClientGrantStatus.HANDED_OFF,
                gate.accept(grant));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> {
                ready.countDown();
                start.await();
                return lease.get().tryClaimPhysicalStart();
            });
            Future<Boolean> second = executor.submit(() -> {
                ready.countDown();
                start.await();
                return lease.get().tryClaimPhysicalStart();
            });
            Assertions.assertTrue(ready.await(2L, TimeUnit.SECONDS));
            start.countDown();
            Assertions.assertTrue(first.get(2L, TimeUnit.SECONDS)
                    ^ second.get(2L, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
            Assertions.assertTrue(executor.awaitTermination(2L, TimeUnit.SECONDS));
        }
    }

    @Test
    void clientGrantGateFencesUnclaimedStartsAtRebindAndPermanentClockBoundaries() {
        Harness harness = harness(new AiPhysicalAttemptBudgetCoordinator.Limits(2, 4));
        AiPhysicalAttemptOffer offer = harness.offer(START.plusSeconds(5L))
                .offer().orElseThrow();
        AiPhysicalAttemptStartGrant grant = harness.coordinator.acknowledge(offer.prepareAck())
                .grant().orElseThrow();
        AtomicLong currentBindingEpoch = new AtomicLong(7L);
        AtomicLong currentEpoch = new AtomicLong(START.toEpochMilli());
        AtomicBoolean sessionActive = new AtomicBoolean(true);
        AtomicReference<AiPhysicalAttemptClientGrantGate.LocalStartLease> rebindLease =
                new AtomicReference<>();
        AiPhysicalAttemptClientGrantGate rebindGate = new AiPhysicalAttemptClientGrantGate(
                offer, harness.dispatch, 7L, currentBindingEpoch::get, currentEpoch::get,
                sessionActive::get, (ignoredGrant, receivedLease) -> rebindLease.set(receivedLease));

        Assertions.assertEquals(AiPhysicalAttemptClientGrantStatus.HANDED_OFF,
                rebindGate.accept(grant));
        currentBindingEpoch.incrementAndGet();
        Assertions.assertFalse(rebindLease.get().tryClaimPhysicalStart());
        rebindGate.close();
        Assertions.assertFalse(rebindLease.get().tryClaimPhysicalStart());

        AtomicReference<AiPhysicalAttemptClientGrantGate.LocalStartLease> closeLease =
                new AtomicReference<>();
        AiPhysicalAttemptClientGrantGate closeGate = new AiPhysicalAttemptClientGrantGate(
                offer, harness.dispatch, 1L, () -> 1L, () -> START.toEpochMilli(), () -> true,
                (ignoredGrant, receivedLease) -> closeLease.set(receivedLease));
        Assertions.assertEquals(AiPhysicalAttemptClientGrantStatus.HANDED_OFF,
                closeGate.accept(grant));
        closeGate.close();
        Assertions.assertFalse(closeLease.get().tryClaimPhysicalStart());

        AtomicInteger deadlineHandoffs = new AtomicInteger();
        AtomicBoolean inactive = new AtomicBoolean(false);
        AtomicLong deadlineClock = new AtomicLong(
                grant.identity().physicalStartNotAfterEpochMillis());
        AiPhysicalAttemptClientGrantGate deadlineGate = new AiPhysicalAttemptClientGrantGate(
                offer, harness.dispatch, 1L, () -> 1L, deadlineClock::get, inactive::get,
                (ignoredGrant, ignoredLease) -> deadlineHandoffs.incrementAndGet());

        Assertions.assertAll(
                () -> Assertions.assertEquals(
                        AiPhysicalAttemptClientGrantStatus.PHYSICAL_START_NOT_AFTER_EXPIRED,
                        deadlineGate.accept(grant)),
                () -> Assertions.assertEquals(0, deadlineHandoffs.get()));
        deadlineClock.set(START.toEpochMilli());
        inactive.set(true);
        Assertions.assertAll(
                () -> Assertions.assertEquals(
                        AiPhysicalAttemptClientGrantStatus.PHYSICAL_START_NOT_AFTER_EXPIRED,
                        deadlineGate.accept(grant)),
                () -> Assertions.assertEquals(0, deadlineHandoffs.get()));

        AtomicLong duplicateGrantClock = new AtomicLong(START.toEpochMilli());
        AtomicReference<AiPhysicalAttemptClientGrantGate.LocalStartLease> duplicateGrantLease =
                new AtomicReference<>();
        AiPhysicalAttemptClientGrantGate duplicateGrantGate =
                new AiPhysicalAttemptClientGrantGate(
                        offer, harness.dispatch, 1L, () -> 1L, duplicateGrantClock::get,
                        () -> true,
                        (ignoredGrant, receivedLease) -> duplicateGrantLease.set(receivedLease));
        Assertions.assertEquals(AiPhysicalAttemptClientGrantStatus.HANDED_OFF,
                duplicateGrantGate.accept(grant));
        duplicateGrantClock.set(grant.identity().physicalStartNotAfterEpochMillis());
        Assertions.assertEquals(AiPhysicalAttemptClientGrantStatus.ALREADY_HANDED_OFF,
                duplicateGrantGate.accept(grant));
        duplicateGrantClock.set(START.toEpochMilli());
        Assertions.assertFalse(duplicateGrantLease.get().tryClaimPhysicalStart());

        AtomicReference<AiPhysicalAttemptClientGrantGate.LocalStartLease> clockLease =
                new AtomicReference<>();
        currentEpoch.set(START.plusSeconds(1L).toEpochMilli());
        AiPhysicalAttemptClientGrantGate clockGate = new AiPhysicalAttemptClientGrantGate(
                offer, harness.dispatch, 1L, () -> 1L, currentEpoch::get, () -> true,
                (ignoredGrant, receivedLease) -> clockLease.set(receivedLease));
        Assertions.assertEquals(AiPhysicalAttemptClientGrantStatus.HANDED_OFF,
                clockGate.accept(grant));
        currentEpoch.set(START.toEpochMilli());
        Assertions.assertFalse(clockLease.get().tryClaimPhysicalStart());
    }

    @Test
    void identityBindsOwnerAndClientClaimHonorsEarlierPhysicalStartDeadline() {
        Harness harness = harness(new AiPhysicalAttemptBudgetCoordinator.Limits(2, 4));
        AiPhysicalAttemptOffer offer = harness.offer(START.plusSeconds(5L))
                .offer().orElseThrow();
        AiClientRequestDispatch foreignOwnerDispatch = dispatch(
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                REQUEST, NONCE, START.plusSeconds(10L));
        Assertions.assertFalse(offer.identity().matches(foreignOwnerDispatch));
        boolean foreignOwnerRejected = false;
        try {
            new AiPhysicalAttemptClientGrantGate(
                    offer, foreignOwnerDispatch, 1L, () -> 1L, () -> START.toEpochMilli(),
                    () -> true, (ignoredGrant, ignoredLease) -> { });
        } catch (IllegalArgumentException exception) {
            foreignOwnerRejected = true;
        }
        Assertions.assertTrue(foreignOwnerRejected);

        Harness shortHarness = harness(new AiPhysicalAttemptBudgetCoordinator.Limits(2, 4));
        AiRetryAttemptBudgetContext shortContext = context(
                shortHarness.ledger, binding(shortHarness.dispatch), START.plusMillis(100L));
        AiPhysicalAttemptOffer shortOffer = shortHarness.coordinator.offer(
                new AiPhysicalAttemptOfferRequest(
                        shortHarness.dispatch, shortContext, START.plusSeconds(5L)))
                .offer().orElseThrow();
        AiPhysicalAttemptStartGrant shortGrant = shortHarness.coordinator.acknowledge(
                shortOffer.prepareAck()).grant().orElseThrow();
        AtomicLong clientEpoch = new AtomicLong(START.toEpochMilli());
        AtomicReference<AiPhysicalAttemptClientGrantGate.LocalStartLease> shortLease =
                new AtomicReference<>();
        AiPhysicalAttemptClientGrantGate shortGate = new AiPhysicalAttemptClientGrantGate(
                shortOffer, shortHarness.dispatch, 1L, () -> 1L, clientEpoch::get, () -> true,
                (ignoredGrant, receivedLease) -> shortLease.set(receivedLease));

        Assertions.assertEquals(AiPhysicalAttemptClientGrantStatus.HANDED_OFF,
                shortGate.accept(shortGrant));
        clientEpoch.set(START.plusMillis(100L).toEpochMilli());
        Assertions.assertFalse(shortLease.get().tryClaimPhysicalStart());
    }

    @Test
    void throwingOrReentrantLocalHandoffCannotConsumeTheSameGrantTwice() {
        Harness throwingHarness = harness(new AiPhysicalAttemptBudgetCoordinator.Limits(2, 4));
        AiPhysicalAttemptOffer throwingOffer = throwingHarness.offer(START.plusSeconds(5L))
                .offer().orElseThrow();
        AiPhysicalAttemptStartGrant throwingGrant = throwingHarness.coordinator.acknowledge(
                throwingOffer.prepareAck()).grant().orElseThrow();
        AtomicInteger throwingCalls = new AtomicInteger();
        IllegalStateException sentinel = new IllegalStateException("handoff sentinel");
        AiPhysicalAttemptClientGrantGate throwingGate = new AiPhysicalAttemptClientGrantGate(
                throwingOffer, throwingHarness.dispatch, 1L, () -> 1L,
                () -> START.toEpochMilli(), () -> true, (ignoredGrant, ignoredLease) -> {
                    throwingCalls.incrementAndGet();
                    throw sentinel;
                });
        AtomicReference<RuntimeException> observed = new AtomicReference<>();
        try {
            throwingGate.accept(throwingGrant);
        } catch (RuntimeException exception) {
            observed.set(exception);
        }

        Assertions.assertAll(
                () -> Assertions.assertSame(sentinel, observed.get()),
                () -> Assertions.assertEquals(AiPhysicalAttemptClientGrantStatus.ALREADY_HANDED_OFF,
                        throwingGate.accept(throwingGrant)),
                () -> Assertions.assertEquals(1, throwingCalls.get()));

        Harness reentrantHarness = harness(new AiPhysicalAttemptBudgetCoordinator.Limits(2, 4));
        AiPhysicalAttemptOffer reentrantOffer = reentrantHarness.offer(START.plusSeconds(5L))
                .offer().orElseThrow();
        AiPhysicalAttemptStartGrant reentrantGrant = reentrantHarness.coordinator.acknowledge(
                reentrantOffer.prepareAck()).grant().orElseThrow();
        AtomicInteger reentrantCalls = new AtomicInteger();
        AtomicReference<AiPhysicalAttemptClientGrantGate> reentrantGate = new AtomicReference<>();
        AtomicReference<AiPhysicalAttemptClientGrantStatus> nested = new AtomicReference<>();
        AiPhysicalAttemptClientGrantGate gate = new AiPhysicalAttemptClientGrantGate(
                reentrantOffer, reentrantHarness.dispatch, 1L, () -> 1L,
                () -> START.toEpochMilli(), () -> true, (ignoredGrant, ignoredLease) -> {
                    reentrantCalls.incrementAndGet();
                    nested.set(reentrantGate.get().accept(reentrantGrant));
                });
        reentrantGate.set(gate);

        Assertions.assertAll(
                () -> Assertions.assertEquals(AiPhysicalAttemptClientGrantStatus.HANDED_OFF,
                        gate.accept(reentrantGrant)),
                () -> Assertions.assertEquals(AiPhysicalAttemptClientGrantStatus.ALREADY_HANDED_OFF,
                        nested.get()),
                () -> Assertions.assertEquals(1, reentrantCalls.get()));
    }

    private static Harness harness(AiPhysicalAttemptBudgetCoordinator.Limits limits) {
        AtomicLong nextAttemptId = new AtomicLong(1L);
        return harness(limits, () -> new UUID(0L, nextAttemptId.getAndIncrement()));
    }

    private static Harness harness(
            AiPhysicalAttemptBudgetCoordinator.Limits limits,
            Supplier<UUID> attemptIdSupplier) {
        MutableClock clock = new MutableClock(START);
        AiClientRequestDispatch dispatch = dispatch(REQUEST, NONCE, START.plusSeconds(10L));
        AiTokenBudgetScope scope = new AiTokenBudgetScope(OWNER, BOT, AGENT);
        AiTokenBudgetLedger ledger = new AiTokenBudgetLedger(scope, policy(100L, 4), clock,
                UUID::randomUUID);
        AiRetryAttemptBudgetContext context = context(ledger, binding(dispatch),
                START.plusSeconds(9L));
        return new Harness(clock, ledger, context, dispatch,
                new AiPhysicalAttemptBudgetCoordinator(clock, limits, attemptIdSupplier));
    }

    private static AiClientRequestDispatch dispatch(
            UUID requestId, UUID nonce, Instant expiresAt) {
        return dispatch(OWNER, requestId, nonce, expiresAt);
    }

    private static AiClientRequestDispatch dispatch(
            UUID ownerId, UUID requestId, UUID nonce, Instant expiresAt) {
        long issued = START.toEpochMilli();
        long expires = expiresAt.toEpochMilli();
        AiRequest template = new AiRequest(
                UUID.fromString("aaaaaaaa-1111-1111-1111-111111111111"),
                "test-model",
                List.of(new AiMessage(AiMessageRole.USER, "物理尝试预算测试")),
                new AiRequestOptions(128, 500L, AiResponseFormat.TEXT, false, false,
                        Optional.empty()),
                Optional.empty());
        return new AiClientRequestDispatch(
                SERVER, BOT, ownerId, AGENT, 3L, requestId, nonce, 7L,
                AiRequestPurpose.REVIEW_ONLY_V1,
                0L, Math.toIntExact((expires - issued) / 50L), issued, expires,
                "test-provider", template.model(), template.messages(), template.options(),
                template.responseSchemaJson());
    }

    private static AiTokenBudgetRequestBinding binding(AiClientRequestDispatch dispatch) {
        return new AiTokenBudgetRequestBinding(
                new AiTokenBudgetScope(dispatch.ownerId(), dispatch.botId(), dispatch.agentId()),
                dispatch.requestId(), dispatch.revision());
    }

    private static AiRetryAttemptBudgetContext context(
            AiTokenBudgetLedger ledger,
            AiTokenBudgetRequestBinding binding,
            Instant upstreamDeadline) {
        return new AiRetryAttemptBudgetContext(ledger, binding,
                new AiModelAdmission(AiModelAdmissionStatus.ACCEPTED,
                        10L, 20L, 30L), upstreamDeadline);
    }

    private static AiTokenBudgetPolicy policy(long maximumTokens, int maximumActive) {
        return new AiTokenBudgetPolicy(maximumTokens, maximumActive,
                Duration.ofSeconds(30L));
    }

    private static void assertRedacted(
            AiPhysicalAttemptIdentity identity, Object... values) {
        for (Object value : values) {
            String rendered = value.toString();
            Assertions.assertAll(
                    () -> Assertions.assertFalse(rendered.contains(SERVER.toString())),
                    () -> Assertions.assertFalse(rendered.contains(OWNER.toString())),
                    () -> Assertions.assertFalse(rendered.contains(BOT.toString())),
                    () -> Assertions.assertFalse(rendered.contains(AGENT.toString())),
                    () -> Assertions.assertFalse(rendered.contains(REQUEST.toString())),
                    () -> Assertions.assertFalse(rendered.contains(NONCE.toString())),
                    () -> Assertions.assertFalse(rendered.contains(
                            identity.attemptId().toString())));
        }
    }

    private static void assertNoForbiddenProductionReference(Class<?> type) {
        List<Class<?>> forbidden = List.of(
                AiProvider.class,
                RetryingAiProvider.class,
                AiRequestScheduler.class);
        for (Field field : type.getDeclaredFields()) {
            Assertions.assertFalse(forbidden.contains(field.getType()),
                    () -> "forbidden field type " + field.getType().getName());
        }
        for (Method method : type.getDeclaredMethods()) {
            Assertions.assertFalse(forbidden.contains(method.getReturnType()),
                    () -> "forbidden return type " + method.getReturnType().getName());
            for (Class<?> parameter : method.getParameterTypes()) {
                Assertions.assertFalse(forbidden.contains(parameter),
                        () -> "forbidden parameter type " + parameter.getName());
            }
        }
    }

    private record Harness(
            MutableClock clock,
            AiTokenBudgetLedger ledger,
            AiRetryAttemptBudgetContext context,
            AiClientRequestDispatch dispatch,
            AiPhysicalAttemptBudgetCoordinator coordinator) {
        private AiPhysicalAttemptOfferResult offer(Instant attemptDeadline) {
            return coordinator.offer(new AiPhysicalAttemptOfferRequest(
                    dispatch, context, attemptDeadline));
        }
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        private void set(Instant current) {
            this.current = current;
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
            return current;
        }
    }
}
