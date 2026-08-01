package io.github.greytaiwolf.botplayer.lifecycle.retirement;

import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class GenerationRetirementSessionTest {
    private static final UUID RETIREMENT = new UUID(1L, 1L);
    private static final UUID BOT = new UUID(2L, 2L);
    private static final GenerationRetirementKey KEY =
            new GenerationRetirementKey(
                    RETIREMENT,
                    BOT,
                    7L,
                    GenerationRetirementContinuation.DISCONNECT_PRE_SAVE);

    @Test
    void startsPendingAndKeepsTheOriginalSnapshotImmutable() {
        GenerationRetirementSession original = session();
        GenerationRetirementTicket first = first();

        GenerationRetirementSession.Update update = original.observe(
                first,
                GenerationRetirementStatus.PENDING,
                1L,
                11L);

        Assertions.assertEquals(
                GenerationRetirementStatus.PENDING,
                original.status());
        Assertions.assertEquals(0, original.attempt());
        Assertions.assertTrue(original.lastReceipt().isEmpty());
        Assertions.assertEquals(
                GenerationRetirementStatus.PENDING,
                update.session().status());
        Assertions.assertEquals(1, update.session().attempt());
        Assertions.assertEquals(1L, update.session().progressRevision());
    }

    @Test
    void replaysTheExactTicketWithoutAcceptingDifferentObservations() {
        GenerationRetirementSession.Update firstUpdate = session().observe(
                first(),
                GenerationRetirementStatus.PENDING,
                1L,
                11L);

        GenerationRetirementSession.Update replay =
                firstUpdate.session().observe(
                        first(),
                        GenerationRetirementStatus.COMPLETE,
                        99L,
                        -1L);

        Assertions.assertEquals(
                GenerationRetirementSession.UpdateKind.REPLAY,
                replay.kind());
        Assertions.assertTrue(firstUpdate.session() == replay.session());
        Assertions.assertTrue(firstUpdate.receipt() == replay.receipt());
        Assertions.assertEquals(
                GenerationRetirementStatus.PENDING,
                replay.receipt().status());
        Assertions.assertEquals(1L, replay.receipt().progressRevision());
    }

    @Test
    void advancesPendingToPendingWithMonotonicCounters() {
        GenerationRetirementSession.Update firstUpdate = pendingOne();
        GenerationRetirementTicket second = first().next(
                firstUpdate.receipt(), 11L);

        GenerationRetirementSession.Update secondUpdate =
                firstUpdate.session().observe(
                        second,
                        GenerationRetirementStatus.PENDING,
                        3L,
                        14L);

        Assertions.assertEquals(2, secondUpdate.receipt().attempt());
        Assertions.assertEquals(3L, secondUpdate.receipt().progressRevision());
        Assertions.assertEquals(14L, secondUpdate.receipt().nextRetryTick());
    }

    @Test
    void advancesPendingToCompleteAndKeepsCompleteSticky() {
        GenerationRetirementSession.Update firstUpdate = pendingOne();
        GenerationRetirementTicket second = first().next(
                firstUpdate.receipt(), 11L);
        GenerationRetirementSession.Update completed =
                firstUpdate.session().observe(
                        second,
                        GenerationRetirementStatus.COMPLETE,
                        2L,
                        -1L);
        GenerationRetirementTicket forged =
                new GenerationRetirementTicket(
                        KEY, 10L, 15L, 12L, 3);

        GenerationRetirementSession.Update sticky =
                completed.session().observe(
                        forged,
                        GenerationRetirementStatus.PENDING,
                        50L,
                        13L);

        Assertions.assertEquals(
                GenerationRetirementStatus.COMPLETE,
                completed.receipt().status());
        Assertions.assertEquals(
                GenerationRetirementSession.UpdateKind.STICKY,
                sticky.kind());
        Assertions.assertTrue(completed.session() == sticky.session());
        Assertions.assertTrue(completed.receipt() == sticky.receipt());
    }

    @Test
    void advancesPendingToUnsafeAndKeepsUnsafeSticky() {
        GenerationRetirementSession.Update firstUpdate = pendingOne();
        GenerationRetirementTicket second = first().next(
                firstUpdate.receipt(), 11L);
        GenerationRetirementSession.Update unsafe =
                firstUpdate.session().observe(
                        second,
                        GenerationRetirementStatus.UNSAFE,
                        1L,
                        -1L);

        GenerationRetirementSession.Update sticky =
                unsafe.session().observe(
                        second,
                        GenerationRetirementStatus.COMPLETE,
                        9L,
                        -1L);

        Assertions.assertEquals(
                GenerationRetirementStatus.UNSAFE,
                sticky.receipt().status());
        Assertions.assertEquals(
                GenerationRetirementFailure.OBSERVED_UNSAFE,
                sticky.receipt().failure());
        Assertions.assertTrue(unsafe.session() == sticky.session());
    }

    @Test
    void receiptFailureKindMatchesOnlyUnsafeStatus() {
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new GenerationRetirementReceipt(
                        KEY,
                        10L,
                        15L,
                        10L,
                        1,
                        GenerationRetirementStatus.COMPLETE,
                        GenerationRetirementFailure.OBSERVED_UNSAFE,
                        0L,
                        -1L));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new GenerationRetirementReceipt(
                        KEY,
                        10L,
                        15L,
                        10L,
                        1,
                        GenerationRetirementStatus.UNSAFE,
                        GenerationRetirementFailure.NONE,
                        0L,
                        -1L));
    }

    @Test
    void continuationConflictFailsClosedWhilePending() {
        GenerationRetirementKey conflict =
                new GenerationRetirementKey(
                        RETIREMENT,
                        BOT,
                        7L,
                        GenerationRetirementContinuation.DEATH_RESPAWN);
        GenerationRetirementTicket ticket =
                GenerationRetirementTicket.first(
                        conflict, 10L, 15L);

        GenerationRetirementSession.Update update = session().observe(
                ticket,
                GenerationRetirementStatus.COMPLETE,
                0L,
                -1L);

        Assertions.assertEquals(
                GenerationRetirementStatus.UNSAFE,
                update.session().status());
        Assertions.assertEquals(
                GenerationRetirementFailure.AUTHORITY_CONFLICT,
                update.receipt().failure());
        Assertions.assertEquals(
                GenerationRetirementSession.UpdateKind.FAILED_CLOSED,
                update.kind());
        Assertions.assertEquals(KEY, update.receipt().key());
    }

    @Test
    void retirementIdentityConflictFailsClosedWhilePending() {
        GenerationRetirementKey conflict =
                new GenerationRetirementKey(
                        new UUID(9L, 9L),
                        BOT,
                        7L,
                        GenerationRetirementContinuation.DISCONNECT_PRE_SAVE);

        GenerationRetirementSession.Update update = session().observe(
                GenerationRetirementTicket.first(
                        conflict, 10L, 15L),
                GenerationRetirementStatus.PENDING,
                0L,
                11L);

        Assertions.assertEquals(
                GenerationRetirementStatus.UNSAFE,
                update.receipt().status());
        Assertions.assertEquals(
                GenerationRetirementFailure.AUTHORITY_CONFLICT,
                update.receipt().failure());
    }

    @Test
    void scheduleConflictFailsClosedWhilePending() {
        GenerationRetirementTicket conflict =
                GenerationRetirementTicket.first(
                        KEY, 10L, 16L);

        GenerationRetirementSession.Update update = session().observe(
                conflict,
                GenerationRetirementStatus.PENDING,
                0L,
                11L);

        Assertions.assertEquals(
                GenerationRetirementStatus.UNSAFE,
                update.receipt().status());
        Assertions.assertEquals(
                GenerationRetirementFailure.AUTHORITY_CONFLICT,
                update.receipt().failure());
    }

    @Test
    void inclusiveDeadlineStillAllowsCompletion() {
        GenerationRetirementSession.Update firstUpdate = pendingOne();
        GenerationRetirementTicket atDeadline = first().next(
                firstUpdate.receipt(), 15L);

        GenerationRetirementSession.Update update =
                firstUpdate.session().observe(
                        atDeadline,
                        GenerationRetirementStatus.COMPLETE,
                        2L,
                        -1L);

        Assertions.assertEquals(
                GenerationRetirementStatus.COMPLETE,
                update.receipt().status());
        Assertions.assertEquals(15L, update.receipt().attemptedTick());
    }

    @Test
    void firstTickAfterDeadlineExpiresToUnsafe() {
        GenerationRetirementSession.Update firstUpdate = pendingOne();
        GenerationRetirementTicket afterDeadline = first().next(
                firstUpdate.receipt(), 16L);

        GenerationRetirementSession.Update update =
                firstUpdate.session().observe(
                        afterDeadline,
                        GenerationRetirementStatus.COMPLETE,
                        2L,
                        -1L);

        Assertions.assertEquals(
                GenerationRetirementStatus.UNSAFE,
                update.receipt().status());
        Assertions.assertEquals(
                GenerationRetirementFailure.DEADLINE_EXCEEDED,
                update.receipt().failure());
        Assertions.assertEquals(16L, update.receipt().attemptedTick());
    }

    @Test
    void expiredTicketCannotBeKeptPendingByAStaleRevision() {
        GenerationRetirementSession shortSession =
                GenerationRetirementSession.open(KEY, 10L, 10L);
        GenerationRetirementTicket first =
                GenerationRetirementTicket.first(KEY, 10L, 10L);
        GenerationRetirementSession.Update pending =
                shortSession.observe(
                        first,
                        GenerationRetirementStatus.PENDING,
                        5L,
                        11L);
        GenerationRetirementTicket expired = first.next(
                pending.receipt(), 11L);

        GenerationRetirementSession.Update update =
                pending.session().observe(
                        expired,
                        GenerationRetirementStatus.COMPLETE,
                        1L,
                        -1L);

        Assertions.assertEquals(
                GenerationRetirementStatus.UNSAFE,
                update.receipt().status());
        Assertions.assertEquals(
                GenerationRetirementFailure.DEADLINE_EXCEEDED,
                update.receipt().failure());
        Assertions.assertEquals(
                5L,
                update.receipt().progressRevision());
    }

    @Test
    void rejectsAttemptRollbackAndAttemptSkipping() {
        GenerationRetirementSession.Update firstUpdate = pendingOne();
        GenerationRetirementTicket rollback =
                new GenerationRetirementTicket(
                        KEY, 10L, 15L, 11L, 1);
        GenerationRetirementTicket skipped =
                new GenerationRetirementTicket(
                        KEY, 10L, 15L, 11L, 3);

        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> firstUpdate.session().observe(
                        rollback,
                        GenerationRetirementStatus.PENDING,
                        2L,
                        12L));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> firstUpdate.session().observe(
                        skipped,
                        GenerationRetirementStatus.PENDING,
                        2L,
                        12L));
    }

    @Test
    void rejectsProgressRevisionRollback() {
        GenerationRetirementSession.Update firstUpdate = pendingOne();
        GenerationRetirementTicket second = first().next(
                firstUpdate.receipt(), 11L);

        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> firstUpdate.session().observe(
                        second,
                        GenerationRetirementStatus.PENDING,
                        0L,
                        12L));
    }

    @Test
    void rejectsRetryTickRollbackAndUnboundedRetry() {
        GenerationRetirementSession.Update firstUpdate = pendingOne();
        GenerationRetirementTicket second = first().next(
                firstUpdate.receipt(), 11L);

        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> firstUpdate.session().observe(
                        second,
                        GenerationRetirementStatus.PENDING,
                        1L,
                        11L));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> firstUpdate.session().observe(
                        second,
                        GenerationRetirementStatus.PENDING,
                        1L,
                        17L));
    }

    @Test
    void rejectsRetryBeforeTheAuthorizedTick() {
        GenerationRetirementSession.Update firstUpdate = pendingOne();

        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> first().next(firstUpdate.receipt(), 10L));
        GenerationRetirementTicket forgedEarly =
                new GenerationRetirementTicket(
                        KEY, 10L, 15L, 10L, 2);
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> firstUpdate.session().observe(
                        forgedEarly,
                        GenerationRetirementStatus.PENDING,
                        2L,
                        12L));
    }

    @Test
    void terminalReceiptCannotCarryARetryTick() {
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> session().observe(
                        first(),
                        GenerationRetirementStatus.COMPLETE,
                        0L,
                        11L));
    }

    @Test
    void pendingReceiptCannotEscapeTheInclusiveDeadlineCheck() {
        GenerationRetirementSession.Update atDeadline = session().observe(
                new GenerationRetirementTicket(
                        KEY, 10L, 15L, 15L, 1),
                GenerationRetirementStatus.PENDING,
                0L,
                16L);

        Assertions.assertEquals(
                GenerationRetirementStatus.PENDING,
                atDeadline.receipt().status());
        GenerationRetirementTicket expiry =
                new GenerationRetirementTicket(
                        KEY, 10L, 15L, 16L, 2);
        GenerationRetirementSession.Update expired =
                atDeadline.session().observe(
                        expiry,
                        GenerationRetirementStatus.COMPLETE,
                        0L,
                        -1L);
        Assertions.assertEquals(
                GenerationRetirementStatus.UNSAFE,
                expired.receipt().status());
    }

    @Test
    void deadlineChecksDoNotOverflowAtMaximumTick() {
        long startedTick = Long.MAX_VALUE - 2L;
        long deadlineTick = Long.MAX_VALUE;
        GenerationRetirementSession maximum =
                GenerationRetirementSession.open(
                        KEY, startedTick, deadlineTick);
        GenerationRetirementTicket first =
                GenerationRetirementTicket.first(
                        KEY, startedTick, deadlineTick);

        GenerationRetirementSession.Update pending = maximum.observe(
                first,
                GenerationRetirementStatus.PENDING,
                0L,
                Long.MAX_VALUE - 1L);
        GenerationRetirementTicket second = first.next(
                pending.receipt(), Long.MAX_VALUE - 1L);
        GenerationRetirementSession.Update completed =
                pending.session().observe(
                        second,
                        GenerationRetirementStatus.COMPLETE,
                        0L,
                        -1L);

        Assertions.assertEquals(
                GenerationRetirementStatus.COMPLETE,
                completed.receipt().status());
    }

    @Test
    void pendingAtMaximumTickFailsClosedWithoutANextTick() {
        long startedTick = Long.MAX_VALUE - 1L;
        GenerationRetirementSession maximum =
                GenerationRetirementSession.open(
                        KEY, startedTick, Long.MAX_VALUE);
        GenerationRetirementTicket first =
                GenerationRetirementTicket.first(
                        KEY, startedTick, Long.MAX_VALUE);
        GenerationRetirementSession.Update pending = maximum.observe(
                first,
                GenerationRetirementStatus.PENDING,
                1L,
                Long.MAX_VALUE);
        GenerationRetirementTicket atMaximum = first.next(
                pending.receipt(), Long.MAX_VALUE);

        GenerationRetirementSession.Update update =
                pending.session().observe(
                        atMaximum,
                        GenerationRetirementStatus.PENDING,
                        1L,
                        -1L);

        Assertions.assertEquals(
                GenerationRetirementStatus.UNSAFE,
                update.receipt().status());
        Assertions.assertEquals(
                GenerationRetirementFailure.DEADLINE_EXCEEDED,
                update.receipt().failure());
    }

    @Test
    void finalAttemptCannotRemainPending() {
        GenerationRetirementSession current =
                GenerationRetirementSession.open(
                        KEY, 0L, 300L);
        GenerationRetirementTicket ticket =
                GenerationRetirementTicket.first(
                        KEY, 0L, 300L);
        for (int attempt = 1;
                attempt <= GenerationRetirementTicket.MAX_ATTEMPTS;
                attempt++) {
            GenerationRetirementSession.Update update = current.observe(
                    ticket,
                    GenerationRetirementStatus.PENDING,
                    attempt,
                    attempt + 1L);
            current = update.session();
            if (attempt
                    == GenerationRetirementTicket.MAX_ATTEMPTS) {
                Assertions.assertEquals(
                        GenerationRetirementStatus.UNSAFE,
                        update.receipt().status());
                Assertions.assertEquals(
                        GenerationRetirementFailure
                                .ATTEMPT_BUDGET_EXHAUSTED,
                        update.receipt().failure());
                Assertions.assertEquals(
                        attempt,
                        update.receipt().attempt());
                break;
            }
            ticket = ticket.next(
                    update.receipt(),
                    update.receipt().nextRetryTick());
        }
    }

    private static GenerationRetirementSession session() {
        return GenerationRetirementSession.open(KEY, 10L, 15L);
    }

    private static GenerationRetirementTicket first() {
        return GenerationRetirementTicket.first(KEY, 10L, 15L);
    }

    private static GenerationRetirementSession.Update pendingOne() {
        return session().observe(
                first(),
                GenerationRetirementStatus.PENDING,
                1L,
                11L);
    }
}
