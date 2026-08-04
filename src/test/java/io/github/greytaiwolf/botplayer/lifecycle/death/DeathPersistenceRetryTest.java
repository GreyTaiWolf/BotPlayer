package io.github.greytaiwolf.botplayer.lifecycle.death;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class DeathPersistenceRetryTest {
    @Test
    void retriesFourTimesAtTwentyTickIntervals() {
        DeathPersistenceRetry retry =
                DeathPersistenceRetry.open(10L, 200L);

        for (int attempt = 1;
                attempt <= DeathPersistenceRetry.MAX_ATTEMPTS;
                attempt++) {
            long tick = 10L
                    + (attempt - 1L)
                            * DeathPersistenceRetry.RETRY_INTERVAL_TICKS;
            Assertions.assertTrue(retry.canAttempt(tick));
            retry = retry.afterFailure(tick);
            Assertions.assertEquals(attempt, retry.failures());
            if (attempt < DeathPersistenceRetry.MAX_ATTEMPTS) {
                Assertions.assertFalse(retry.canAttempt(tick));
            }
        }

        Assertions.assertTrue(retry.exhausted(70L));
        Assertions.assertFalse(retry.canAttempt(90L));
    }

    @Test
    void neverSchedulesPastTheRetirementDeadline() {
        DeathPersistenceRetry retry =
                DeathPersistenceRetry.open(90L, 100L)
                        .afterFailure(90L);

        Assertions.assertEquals(100L, retry.nextAttemptTick());
        Assertions.assertTrue(retry.canAttempt(100L));
        Assertions.assertTrue(retry.exhausted(101L));
    }
}
