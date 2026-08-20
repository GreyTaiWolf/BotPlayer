package io.github.greytaiwolf.botplayer.ai;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AiTokenBudgetLedgerTest {
    private static final Instant START = Instant.parse("2026-08-20T00:00:00Z");
    private static final UUID OWNER = UUID.fromString(
            "11111111-1111-1111-1111-111111111111");
    private static final UUID BOT = UUID.fromString(
            "22222222-2222-2222-2222-222222222222");
    private static final UUID AGENT = UUID.fromString(
            "33333333-3333-3333-3333-333333333333");
    private static final UUID REQUEST = UUID.fromString(
            "44444444-4444-4444-4444-444444444444");

    @Test
    void reservesOnlyAcceptedAdmissionAndFreezesExactTokenIdentity() {
        MutableClock clock = new MutableClock(START);
        AiTokenBudgetScope scope = scope();
        AiTokenBudgetLedger ledger = ledger(scope, policy(100L, 2), clock);
        AiTokenBudgetRequestBinding binding = binding(scope, REQUEST, 7L);
        AiModelAdmission admission = accepted(20L, 30L);

        AiTokenBudgetReservationResult result = ledger.reserve(
                binding, admission, START.plusSeconds(5L));
        AiTokenReservation reservation = result.reservation().orElseThrow();
        AiTokenBudgetSnapshot snapshot = ledger.snapshot();

        Assertions.assertAll(
                () -> Assertions.assertEquals(AiTokenBudgetOperationStatus.RESERVED,
                        result.status()),
                () -> Assertions.assertTrue(result.reserved()),
                () -> Assertions.assertSame(binding, reservation.binding()),
                () -> Assertions.assertEquals(20L, reservation.estimatedInputTokens()),
                () -> Assertions.assertEquals(30L, reservation.reservedOutputTokens()),
                () -> Assertions.assertEquals(50L, reservation.reservedTotalTokens()),
                () -> Assertions.assertEquals(START, reservation.reservedAt()),
                () -> Assertions.assertEquals(START.plusSeconds(5L),
                        reservation.expiresAt()),
                () -> Assertions.assertEquals(100L, snapshot.maximumTokens()),
                () -> Assertions.assertEquals(50L, snapshot.reservedTokens()),
                () -> Assertions.assertEquals(0L, snapshot.committedTokens()),
                () -> Assertions.assertEquals(50L, snapshot.availableTokens()),
                () -> Assertions.assertEquals(1, snapshot.activeReservations()),
                () -> Assertions.assertFalse(snapshot.closed()));
    }

    @Test
    void rejectsUnsafeInputsWithoutMutatingAccounting() {
        MutableClock clock = new MutableClock(START);
        AiTokenBudgetScope scope = scope();
        AiTokenBudgetLedger ledger = ledger(scope, policy(100L, 2), clock);
        AiTokenBudgetRequestBinding binding = binding(scope, REQUEST, 1L);
        AiTokenBudgetSnapshot empty = ledger.snapshot();

        assertRejected(ledger.reserve(binding, new AiModelAdmission(
                        AiModelAdmissionStatus.MODEL_NOT_AVAILABLE, 1L, 1L, 2L),
                START.plusSeconds(5L)),
                AiTokenBudgetOperationStatus.ADMISSION_REJECTED);
        Assertions.assertEquals(empty, ledger.snapshot());

        AiTokenBudgetScope foreignScope = new AiTokenBudgetScope(
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
                UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"));
        assertRejected(ledger.reserve(binding(foreignScope, REQUEST, 1L),
                        accepted(1L, 1L), START.plusSeconds(5L)),
                AiTokenBudgetOperationStatus.SCOPE_MISMATCH);
        assertRejected(ledger.reserve(binding,
                        new AiModelAdmission(AiModelAdmissionStatus.ACCEPTED,
                                0L, 0L, 0L),
                        START.plusSeconds(5L)),
                AiTokenBudgetOperationStatus.INVALID_ADMISSION);
        assertRejected(ledger.reserve(binding, accepted(1L, 1L), START),
                AiTokenBudgetOperationStatus.INVALID_EXPIRATION);
        assertRejected(ledger.reserve(binding, accepted(1L, 1L),
                        START.plusSeconds(31L)),
                AiTokenBudgetOperationStatus.INVALID_EXPIRATION);
        Assertions.assertEquals(empty, ledger.snapshot());

        reserve(ledger, binding, accepted(60L, 20L), START.plusSeconds(5L));
        AiTokenBudgetSnapshot reserved = ledger.snapshot();
        assertRejected(ledger.reserve(binding(scope,
                        UUID.fromString("55555555-5555-5555-5555-555555555555"), 2L),
                        accepted(20L, 1L), START.plusSeconds(5L)),
                AiTokenBudgetOperationStatus.TOKEN_BUDGET_EXHAUSTED);
        Assertions.assertEquals(reserved, ledger.snapshot());

        AiTokenBudgetLedger activeLimited = ledger(scope, policy(100L, 1), clock);
        reserve(activeLimited, binding(scope,
                        UUID.fromString("66666666-6666-6666-6666-666666666666"), 3L),
                accepted(1L, 1L), START.plusSeconds(5L));
        AiTokenBudgetSnapshot activeSnapshot = activeLimited.snapshot();
        assertRejected(activeLimited.reserve(binding(scope,
                        UUID.fromString("77777777-7777-7777-7777-777777777777"), 4L),
                        accepted(1L, 1L), START.plusSeconds(5L)),
                AiTokenBudgetOperationStatus.ACTIVE_RESERVATION_LIMIT);
        Assertions.assertEquals(activeSnapshot, activeLimited.snapshot());

        AiTokenBudgetLedger exhaustedIds = new AiTokenBudgetLedger(
                scope, policy(100L, 1), clock, () -> new UUID(0L, 0L));
        assertRejected(exhaustedIds.reserve(binding, accepted(1L, 1L),
                        START.plusSeconds(5L)),
                AiTokenBudgetOperationStatus.RESERVATION_ID_EXHAUSTED);
        Assertions.assertEquals(0L, exhaustedIds.snapshot().consumedTokens());
    }

    @Test
    void releaseAndExpiryRefundOnlyUnsettledReservations() {
        MutableClock clock = new MutableClock(START);
        AiTokenBudgetScope scope = scope();
        AiTokenBudgetLedger ledger = ledger(scope, policy(100L, 2), clock);
        AiTokenBudgetRequestBinding first = binding(scope, REQUEST, 1L);
        AiTokenReservation released = reserve(ledger, first,
                accepted(20L, 10L), START.plusSeconds(5L));

        Assertions.assertAll(
                () -> Assertions.assertEquals(AiTokenBudgetOperationStatus.RELEASED,
                        ledger.release(released)),
                () -> Assertions.assertEquals(AiTokenBudgetOperationStatus.NOT_FOUND,
                        ledger.release(released)),
                () -> Assertions.assertEquals(0L, ledger.snapshot().reservedTokens()),
                () -> Assertions.assertEquals(0L, ledger.snapshot().committedTokens()));

        AiTokenReservation expiring = reserve(ledger, binding(scope,
                        UUID.fromString("88888888-8888-8888-8888-888888888888"), 2L),
                accepted(10L, 20L), START.plusSeconds(5L));
        clock.set(START.plusSeconds(4L));
        Assertions.assertTrue(ledger.expireDue().isEmpty());
        Assertions.assertEquals(30L, ledger.snapshot().reservedTokens());

        clock.set(START.plusSeconds(5L));
        Assertions.assertAll(
                () -> Assertions.assertEquals(AiTokenBudgetOperationStatus.EXPIRED,
                        ledger.settleAttempt(expiring)),
                () -> Assertions.assertEquals(0L, ledger.snapshot().reservedTokens()),
                () -> Assertions.assertEquals(0L, ledger.snapshot().committedTokens()),
                () -> Assertions.assertEquals(0, ledger.snapshot().activeReservations()));
    }

    @Test
    void settlementNeverRefundsAndEveryPhysicalRetryGetsANewReservation() {
        MutableClock clock = new MutableClock(START);
        AiTokenBudgetScope scope = scope();
        AiTokenBudgetLedger ledger = ledger(scope, policy(100L, 3), clock);
        AiTokenBudgetRequestBinding binding = binding(scope, REQUEST, 9L);
        AiTokenReservation first = reserve(ledger, binding,
                accepted(20L, 20L), START.plusSeconds(5L));

        Assertions.assertEquals(AiTokenBudgetOperationStatus.SETTLED,
                ledger.settleAttempt(first));
        Assertions.assertEquals(AiTokenBudgetOperationStatus.NOT_FOUND,
                ledger.release(first));
        Assertions.assertEquals(40L, ledger.snapshot().committedTokens());

        clock.set(START.plusSeconds(1L));
        AiTokenReservation retry = reserve(ledger, binding,
                accepted(20L, 20L), START.plusSeconds(6L));
        Assertions.assertAll(
                () -> Assertions.assertNotEquals(first.reservationId(), retry.reservationId()),
                () -> Assertions.assertEquals(AiTokenBudgetOperationStatus.SETTLED,
                        ledger.settleAttempt(retry)),
                () -> Assertions.assertEquals(80L, ledger.snapshot().committedTokens()),
                () -> Assertions.assertEquals(0L, ledger.snapshot().reservedTokens()),
                () -> Assertions.assertEquals(20L, ledger.snapshot().availableTokens()));

        assertRejected(ledger.reserve(binding(scope,
                        UUID.fromString("99999999-9999-9999-9999-999999999999"), 10L),
                        accepted(20L, 1L), START.plusSeconds(6L)),
                AiTokenBudgetOperationStatus.TOKEN_BUDGET_EXHAUSTED);
        ledger.close();
        Assertions.assertEquals(80L, ledger.snapshot().committedTokens());
    }

    @Test
    void exactInstanceIdentityRejectsForgedOrDriftedReservations() {
        MutableClock clock = new MutableClock(START);
        AiTokenBudgetScope scope = scope();
        AiTokenBudgetLedger ledger = ledger(scope, policy(100L, 1), clock);
        AiTokenReservation actual = reserve(ledger, binding(scope, REQUEST, 1L),
                accepted(10L, 20L), START.plusSeconds(5L));
        AiTokenReservation copied = new AiTokenReservation(
                actual.reservationId(), actual.binding(), actual.estimatedInputTokens(),
                actual.reservedOutputTokens(), actual.reservedTotalTokens(),
                actual.reservedAt(), actual.expiresAt());
        AiTokenReservation drifted = new AiTokenReservation(
                actual.reservationId(), actual.binding(), 9L, 21L, 30L,
                actual.reservedAt(), actual.expiresAt());
        AiTokenBudgetSnapshot before = ledger.snapshot();

        Assertions.assertAll(
                () -> Assertions.assertEquals(actual, copied),
                () -> Assertions.assertNotSame(actual, copied),
                () -> Assertions.assertEquals(AiTokenBudgetOperationStatus.STALE_RESERVATION,
                        ledger.release(copied)),
                () -> Assertions.assertEquals(AiTokenBudgetOperationStatus.STALE_RESERVATION,
                        ledger.settleAttempt(drifted)),
                () -> Assertions.assertEquals(before, ledger.snapshot()),
                () -> Assertions.assertEquals(AiTokenBudgetOperationStatus.SETTLED,
                        ledger.settleAttempt(actual)),
                () -> Assertions.assertEquals(AiTokenBudgetOperationStatus.NOT_FOUND,
                        ledger.settleAttempt(actual)));
    }

    @Test
    void releasedReservationCannotAffectAReplacementThatReusesItsId() {
        MutableClock clock = new MutableClock(START);
        AiTokenBudgetScope scope = scope();
        UUID reusedId = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
        AiTokenBudgetLedger ledger = new AiTokenBudgetLedger(
                scope, policy(100L, 1), clock, () -> reusedId);
        AiTokenReservation first = reserve(ledger, binding(scope, REQUEST, 1L),
                accepted(10L, 20L), START.plusSeconds(5L));
        Assertions.assertEquals(AiTokenBudgetOperationStatus.RELEASED,
                ledger.release(first));
        AiTokenReservation replacement = reserve(ledger, binding(scope,
                        UUID.fromString("aaaaaaaa-0000-0000-0000-000000000005"), 2L),
                accepted(10L, 20L), START.plusSeconds(5L));

        Assertions.assertAll(
                () -> Assertions.assertEquals(first.reservationId(),
                        replacement.reservationId()),
                () -> Assertions.assertNotSame(first, replacement),
                () -> Assertions.assertEquals(AiTokenBudgetOperationStatus.STALE_RESERVATION,
                        ledger.release(first)),
                () -> Assertions.assertEquals(AiTokenBudgetOperationStatus.STALE_RESERVATION,
                        ledger.settleAttempt(first)),
                () -> Assertions.assertEquals(AiTokenBudgetOperationStatus.SETTLED,
                        ledger.settleAttempt(replacement)),
                () -> Assertions.assertEquals(30L, ledger.snapshot().committedTokens()));
    }

    @Test
    void closeReleasesOnlyActiveReservationsAndRejectsNewReservations() {
        MutableClock clock = new MutableClock(START);
        AiTokenBudgetScope scope = scope();
        AiTokenBudgetLedger ledger = ledger(scope, policy(100L, 2), clock);
        AiTokenReservation settled = reserve(ledger, binding(scope, REQUEST, 1L),
                accepted(10L, 20L), START.plusSeconds(5L));
        Assertions.assertEquals(AiTokenBudgetOperationStatus.SETTLED,
                ledger.settleAttempt(settled));
        AiTokenReservation active = reserve(ledger, binding(scope,
                        UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001"), 2L),
                accepted(20L, 10L), START.plusSeconds(5L));

        ledger.close();
        AiTokenBudgetSnapshot snapshot = ledger.snapshot();
        Assertions.assertAll(
                () -> Assertions.assertTrue(snapshot.closed()),
                () -> Assertions.assertEquals(0L, snapshot.reservedTokens()),
                () -> Assertions.assertEquals(30L, snapshot.committedTokens()),
                () -> Assertions.assertEquals(0, snapshot.activeReservations()),
                () -> Assertions.assertEquals(AiTokenBudgetOperationStatus.NOT_FOUND,
                        ledger.release(active)),
                () -> Assertions.assertEquals(AiTokenBudgetOperationStatus.NOT_FOUND,
                        ledger.settleAttempt(active)));
        assertRejected(ledger.reserve(binding(scope,
                        UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002"), 3L),
                        accepted(1L, 1L), START.plusSeconds(5L)),
                AiTokenBudgetOperationStatus.LEDGER_CLOSED);
    }

    @Test
    void clockRollbackAndInstantOverflowFailClosed() {
        MutableClock clock = new MutableClock(START);
        AiTokenBudgetScope scope = scope();
        AiTokenBudgetLedger ledger = ledger(scope, policy(100L, 1), clock);
        AiTokenReservation reservation = reserve(ledger, binding(scope, REQUEST, 1L),
                accepted(10L, 10L), START.plusSeconds(5L));
        AiTokenBudgetSnapshot beforeRollback = ledger.snapshot();

        clock.set(START.minusSeconds(1L));
        Assertions.assertAll(
                () -> Assertions.assertEquals(AiTokenBudgetOperationStatus.CLOCK_ROLLBACK,
                        ledger.settleAttempt(reservation)),
                () -> Assertions.assertThrows(IllegalStateException.class,
                        ledger::snapshot));
        clock.set(START);
        Assertions.assertEquals(beforeRollback, ledger.snapshot());

        Instant nearMaximum = Instant.MAX.minusSeconds(1L);
        MutableClock maximumClock = new MutableClock(nearMaximum);
        AiTokenBudgetLedger maximumLedger = ledger(scope, policy(100L, 1),
                maximumClock);
        assertRejected(maximumLedger.reserve(binding(scope,
                        UUID.fromString("aaaaaaaa-0000-0000-0000-000000000003"), 2L),
                        accepted(1L, 1L), Instant.MAX),
                AiTokenBudgetOperationStatus.INVALID_EXPIRATION);
        Assertions.assertEquals(0L, maximumLedger.snapshot().consumedTokens());
    }

    @Test
    void concurrentTerminalRacesAreLinearizedWithoutDoubleRefunds() throws Exception {
        MutableClock clock = new MutableClock(START);
        AiTokenBudgetScope scope = scope();
        AiTokenBudgetLedger ledger = ledger(scope, policy(100L, 1), clock);
        AiTokenReservation reservation = reserve(ledger, binding(scope, REQUEST, 1L),
                accepted(10L, 20L), START.plusSeconds(5L));

        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<AiTokenBudgetOperationStatus> settled = workers.submit(() -> {
                barrier.await();
                return ledger.settleAttempt(reservation);
            });
            Future<AiTokenBudgetOperationStatus> released = workers.submit(() -> {
                barrier.await();
                return ledger.release(reservation);
            });
            AiTokenBudgetOperationStatus settleStatus = settled.get(5L, TimeUnit.SECONDS);
            AiTokenBudgetOperationStatus releaseStatus = released.get(5L, TimeUnit.SECONDS);
            AiTokenBudgetSnapshot raced = ledger.snapshot();

            Assertions.assertAll(
                    () -> Assertions.assertTrue(
                            (settleStatus == AiTokenBudgetOperationStatus.SETTLED
                                    && releaseStatus
                                    == AiTokenBudgetOperationStatus.NOT_FOUND)
                                    || (settleStatus
                                    == AiTokenBudgetOperationStatus.NOT_FOUND
                                    && releaseStatus
                                    == AiTokenBudgetOperationStatus.RELEASED)),
                    () -> Assertions.assertEquals(0L, raced.reservedTokens()),
                    () -> Assertions.assertEquals(0, raced.activeReservations()),
                    () -> Assertions.assertEquals(
                            settleStatus == AiTokenBudgetOperationStatus.SETTLED
                                    ? 30L : 0L,
                            raced.committedTokens()));
        } finally {
            workers.shutdownNow();
        }

        MutableClock expiryClock = new MutableClock(START);
        AiTokenBudgetLedger expiryLedger = ledger(scope, policy(100L, 1), expiryClock);
        AiTokenReservation expiring = reserve(expiryLedger, binding(scope,
                        UUID.fromString("aaaaaaaa-0000-0000-0000-000000000004"), 2L),
                accepted(10L, 20L), START.plusSeconds(1L));
        expiryClock.set(START.plusSeconds(1L));
        ExecutorService expiryWorkers = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<AiTokenBudgetOperationStatus> settled = expiryWorkers.submit(() -> {
                barrier.await();
                return expiryLedger.settleAttempt(expiring);
            });
            Future<List<AiTokenReservation>> expired = expiryWorkers.submit(() -> {
                barrier.await();
                return expiryLedger.expireDue();
            });
            AiTokenBudgetOperationStatus settleStatus = settled.get(5L, TimeUnit.SECONDS);
            List<AiTokenReservation> expiredReservations = expired.get(5L,
                    TimeUnit.SECONDS);
            AiTokenBudgetSnapshot raced = expiryLedger.snapshot();

            Assertions.assertAll(
                    () -> Assertions.assertTrue(settleStatus
                            == AiTokenBudgetOperationStatus.EXPIRED
                            || settleStatus == AiTokenBudgetOperationStatus.NOT_FOUND),
                    () -> Assertions.assertEquals(1,
                            (settleStatus == AiTokenBudgetOperationStatus.EXPIRED ? 1 : 0)
                                    + expiredReservations.size()),
                    () -> Assertions.assertEquals(0L, raced.consumedTokens()),
                    () -> Assertions.assertEquals(0, raced.activeReservations()));
        } finally {
            expiryWorkers.shutdownNow();
        }
    }

    @Test
    void concurrentReservationsCannotCrossTokenOrActiveLimits() throws Exception {
        MutableClock clock = new MutableClock(START);
        AiTokenBudgetScope scope = scope();
        AiTokenBudgetLedger tokenLimited = ledger(scope, policy(30L, 2), clock);
        AiTokenBudgetReservationResult[] tokenResults = concurrentlyReserve(
                tokenLimited,
                binding(scope, REQUEST, 1L),
                binding(scope,
                        UUID.fromString("aaaaaaaa-0000-0000-0000-000000000006"), 2L),
                accepted(10L, 10L),
                START.plusSeconds(5L));

        Assertions.assertAll(
                () -> Assertions.assertEquals(1, countStatus(tokenResults,
                        AiTokenBudgetOperationStatus.RESERVED)),
                () -> Assertions.assertEquals(1, countStatus(tokenResults,
                        AiTokenBudgetOperationStatus.TOKEN_BUDGET_EXHAUSTED)),
                () -> Assertions.assertEquals(20L,
                        tokenLimited.snapshot().consumedTokens()),
                () -> Assertions.assertEquals(1,
                        tokenLimited.snapshot().activeReservations()));

        AiTokenBudgetLedger activeLimited = ledger(scope, policy(100L, 1), clock);
        AiTokenBudgetReservationResult[] activeResults = concurrentlyReserve(
                activeLimited,
                binding(scope,
                        UUID.fromString("aaaaaaaa-0000-0000-0000-000000000007"), 3L),
                binding(scope,
                        UUID.fromString("aaaaaaaa-0000-0000-0000-000000000008"), 4L),
                accepted(10L, 10L),
                START.plusSeconds(5L));

        Assertions.assertAll(
                () -> Assertions.assertEquals(1, countStatus(activeResults,
                        AiTokenBudgetOperationStatus.RESERVED)),
                () -> Assertions.assertEquals(1, countStatus(activeResults,
                        AiTokenBudgetOperationStatus.ACTIVE_RESERVATION_LIMIT)),
                () -> Assertions.assertEquals(20L,
                        activeLimited.snapshot().consumedTokens()),
                () -> Assertions.assertEquals(1,
                        activeLimited.snapshot().activeReservations()));
    }

    @Test
    void productionBoundaryAndDiagnosticsDoNotExposeUnsafeReferences() {
        MutableClock clock = new MutableClock(START);
        AiTokenBudgetScope scope = scope();
        AiTokenBudgetRequestBinding binding = binding(scope, REQUEST, 1L);
        AiTokenBudgetLedger ledger = ledger(scope, policy(100L, 1), clock);
        AiTokenBudgetReservationResult result = ledger.reserve(binding,
                accepted(10L, 20L), START.plusSeconds(5L));
        AiTokenReservation reservation = result.reservation().orElseThrow();

        assertDoesNotContainIdentity(scope, binding, reservation, result, ledger);
        assertNoForbiddenProductionReference(AiTokenBudgetLedger.class);
    }

    @Test
    void recordContractsRejectInvalidBoundedValues() {
        Assertions.assertAll(
                () -> Assertions.assertThrows(IllegalArgumentException.class,
                        () -> new AiTokenBudgetScope(new UUID(0L, 0L), BOT, AGENT)),
                () -> Assertions.assertThrows(IllegalArgumentException.class,
                        () -> new AiTokenBudgetRequestBinding(scope(), REQUEST, 0L)),
                () -> Assertions.assertThrows(IllegalArgumentException.class,
                        () -> new AiTokenBudgetPolicy(0L, 1, Duration.ofSeconds(1L))),
                () -> Assertions.assertThrows(IllegalArgumentException.class,
                        () -> new AiTokenBudgetPolicy(1L, 1, Duration.ZERO)),
                () -> Assertions.assertThrows(IllegalArgumentException.class,
                        () -> new AiTokenBudgetReservationResult(
                                AiTokenBudgetOperationStatus.RELEASED,
                                java.util.Optional.of(new AiTokenReservation(
                                        UUID.randomUUID(), binding(scope(), REQUEST, 1L),
                                        1L, 1L, 2L, START, START.plusSeconds(1L))))));
    }

    private static AiTokenBudgetScope scope() {
        return new AiTokenBudgetScope(OWNER, BOT, AGENT);
    }

    private static AiTokenBudgetRequestBinding binding(
            AiTokenBudgetScope scope, UUID requestId, long revision) {
        return new AiTokenBudgetRequestBinding(scope, requestId, revision);
    }

    private static AiTokenBudgetPolicy policy(long maximumTokens, int maximumActive) {
        return new AiTokenBudgetPolicy(maximumTokens, maximumActive,
                Duration.ofSeconds(30L));
    }

    private static AiTokenBudgetLedger ledger(
            AiTokenBudgetScope scope,
            AiTokenBudgetPolicy policy,
            MutableClock clock) {
        return new AiTokenBudgetLedger(scope, policy, clock, UUID::randomUUID);
    }

    private static AiModelAdmission accepted(long inputTokens, long outputTokens) {
        return new AiModelAdmission(AiModelAdmissionStatus.ACCEPTED,
                inputTokens, outputTokens, Math.addExact(inputTokens, outputTokens));
    }

    private static AiTokenReservation reserve(
            AiTokenBudgetLedger ledger,
            AiTokenBudgetRequestBinding binding,
            AiModelAdmission admission,
            Instant expiresAt) {
        AiTokenBudgetReservationResult result = ledger.reserve(
                binding, admission, expiresAt);
        Assertions.assertEquals(AiTokenBudgetOperationStatus.RESERVED,
                result.status());
        return result.reservation().orElseThrow();
    }

    private static void assertRejected(
            AiTokenBudgetReservationResult result,
            AiTokenBudgetOperationStatus expected) {
        Assertions.assertAll(
                () -> Assertions.assertEquals(expected, result.status()),
                () -> Assertions.assertTrue(result.reservation().isEmpty()),
                () -> Assertions.assertFalse(result.reserved()));
    }

    private static void assertDoesNotContainIdentity(Object... values) {
        for (Object value : values) {
            String text = value.toString();
            Assertions.assertAll(
                    () -> Assertions.assertFalse(text.contains(OWNER.toString())),
                    () -> Assertions.assertFalse(text.contains(BOT.toString())),
                    () -> Assertions.assertFalse(text.contains(AGENT.toString())),
                    () -> Assertions.assertFalse(text.contains(REQUEST.toString())));
        }
    }

    private static void assertNoForbiddenProductionReference(Class<?> type) {
        List<Class<?>> forbidden = List.of(
                AiProvider.class,
                AiRequestScheduler.class,
                RetryingAiProvider.class,
                AiTokenUsage.class);
        for (Field field : type.getDeclaredFields()) {
            Assertions.assertFalse(forbidden.contains(field.getType()),
                    () -> "forbidden field type " + field.getType().getName());
        }
        for (Method method : type.getDeclaredMethods()) {
            Assertions.assertFalse(forbidden.contains(method.getReturnType()),
                    () -> "forbidden return type "
                            + method.getReturnType().getName());
            for (Class<?> parameterType : method.getParameterTypes()) {
                Assertions.assertFalse(forbidden.contains(parameterType),
                        () -> "forbidden parameter type " + parameterType.getName());
            }
        }
    }

    private static AiTokenBudgetReservationResult[] concurrentlyReserve(
            AiTokenBudgetLedger ledger,
            AiTokenBudgetRequestBinding first,
            AiTokenBudgetRequestBinding second,
            AiModelAdmission admission,
            Instant expiresAt) throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<AiTokenBudgetReservationResult> firstFuture = workers.submit(() -> {
                barrier.await();
                return ledger.reserve(first, admission, expiresAt);
            });
            Future<AiTokenBudgetReservationResult> secondFuture = workers.submit(() -> {
                barrier.await();
                return ledger.reserve(second, admission, expiresAt);
            });
            return new AiTokenBudgetReservationResult[]{
                    firstFuture.get(5L, TimeUnit.SECONDS),
                    secondFuture.get(5L, TimeUnit.SECONDS)};
        } finally {
            workers.shutdownNow();
        }
    }

    private static long countStatus(
            AiTokenBudgetReservationResult[] results,
            AiTokenBudgetOperationStatus expected) {
        long count = 0L;
        for (AiTokenBudgetReservationResult result : results) {
            if (result.status() == expected) {
                count++;
            }
        }
        return count;
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
