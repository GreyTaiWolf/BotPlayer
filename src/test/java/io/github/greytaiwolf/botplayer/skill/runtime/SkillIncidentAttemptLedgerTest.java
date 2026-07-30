package io.github.greytaiwolf.botplayer.skill.runtime;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SkillIncidentAttemptLedgerTest {
    private static final UUID BOT = new UUID(0L, 1L);
    private static final UUID OTHER_BOT =
            new UUID(0L, 2L);
    private static final UUID INCIDENT =
            new UUID(0L, 3L);
    private static final UUID NEXT_INCIDENT =
            new UUID(0L, 4L);

    @Test
    void appliesBackoffAndAnExactAttemptLimit() {
        SkillIncidentAttemptLedger ledger =
                new SkillIncidentAttemptLedger(2, 2, 10);

        Assertions.assertEquals(
                SkillIncidentAttemptLedger.AllowStatus.ALLOWED,
                ledger.allow(BOT, 1L, INCIDENT, 5L));
        Assertions.assertEquals(
                SkillIncidentAttemptLedger.AllowStatus.BACKOFF,
                ledger.allow(BOT, 1L, INCIDENT, 14L));
        Assertions.assertEquals(
                SkillIncidentAttemptLedger.AllowStatus.ALLOWED,
                ledger.allow(BOT, 1L, INCIDENT, 15L));
        Assertions.assertEquals(
                SkillIncidentAttemptLedger.AllowStatus.EXHAUSTED,
                ledger.allow(BOT, 1L, INCIDENT, 25L));
    }

    @Test
    void aNewIncidentOrGenerationGetsAFreshBudget() {
        SkillIncidentAttemptLedger ledger =
                new SkillIncidentAttemptLedger(2, 1, 5);
        ledger.allow(BOT, 1L, INCIDENT, 1L);

        Assertions.assertEquals(
                SkillIncidentAttemptLedger.AllowStatus.ALLOWED,
                ledger.allow(
                        BOT,
                        1L,
                        NEXT_INCIDENT,
                        2L));
        Assertions.assertEquals(
                SkillIncidentAttemptLedger.AllowStatus.ALLOWED,
                ledger.allow(
                        BOT,
                        2L,
                        NEXT_INCIDENT,
                        3L));
    }

    @Test
    void capacityAndGenerationCleanupFailClosed() {
        SkillIncidentAttemptLedger ledger =
                new SkillIncidentAttemptLedger(1, 1, 5);
        ledger.allow(BOT, 1L, INCIDENT, 1L);

        Assertions.assertEquals(
                SkillIncidentAttemptLedger.AllowStatus
                        .CAPACITY_EXHAUSTED,
                ledger.allow(
                        OTHER_BOT,
                        1L,
                        NEXT_INCIDENT,
                        1L));
        Assertions.assertFalse(
                ledger.closeGeneration(BOT, 2L));
        Assertions.assertTrue(
                ledger.closeGeneration(BOT, 1L));
        Assertions.assertEquals(0, ledger.size());
    }

    @Test
    void rejectsBackwardTicksAndForeignThreads()
            throws InterruptedException {
        SkillIncidentAttemptLedger ledger =
                new SkillIncidentAttemptLedger(1, 1, 5);
        ledger.allow(BOT, 1L, INCIDENT, 5L);
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> ledger.allow(
                        BOT, 1L, INCIDENT, 4L));

        AtomicReference<Throwable> failure =
                new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                ledger.size();
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        thread.start();
        thread.join();
        Assertions.assertInstanceOf(
                IllegalStateException.class,
                failure.get());
    }
}
