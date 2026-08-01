package io.github.greytaiwolf.botplayer.action;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class GenerationDrainStatusTest {
    @Test
    void unsafeDominatesOutstandingAuthority() {
        Assertions.assertEquals(
                GenerationDrainStatus.UNSAFE,
                GenerationDrainStatus.classify(true, true, true));
    }

    @Test
    void outstandingTicketOrLeaseIsPending() {
        Assertions.assertEquals(
                GenerationDrainStatus.PENDING,
                GenerationDrainStatus.classify(false, true, false));
        Assertions.assertEquals(
                GenerationDrainStatus.PENDING,
                GenerationDrainStatus.classify(false, false, true));
    }

    @Test
    void noUnsafeStateOrOutstandingAuthorityIsComplete() {
        Assertions.assertEquals(
                GenerationDrainStatus.COMPLETE,
                GenerationDrainStatus.classify(false, false, false));
    }
}
