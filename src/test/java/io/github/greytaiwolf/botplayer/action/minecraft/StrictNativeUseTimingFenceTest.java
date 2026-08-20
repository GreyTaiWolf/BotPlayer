package io.github.greytaiwolf.botplayer.action.minecraft;

import io.github.greytaiwolf.botplayer.action.ActionEnvelope;
import io.github.greytaiwolf.botplayer.action.ActionOrigin;
import io.github.greytaiwolf.botplayer.action.LookAtAction;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class StrictNativeUseTimingFenceTest {
    private static final UUID ACTION_ID = new UUID(0L, 1L);
    private static final UUID BOT_ID = new UUID(0L, 2L);

    @Test
    void rejectsNativeUseAtTheExclusiveDeadline() {
        ActionEnvelope envelope = envelope(120L, 64);

        Assertions.assertAll(
                () -> Assertions.assertTrue(StrictNativeUseTimingFence.allows(
                        envelope, 100L, 119L)),
                () -> Assertions.assertFalse(StrictNativeUseTimingFence.allows(
                        envelope, 100L, 120L)));
    }

    @Test
    void rejectsNativeUseAtTheExhaustedTickBudgetBoundary() {
        ActionEnvelope envelope = envelope(1_000L, 32);

        Assertions.assertAll(
                () -> Assertions.assertTrue(StrictNativeUseTimingFence.allows(
                        envelope, 100L, 131L)),
                () -> Assertions.assertFalse(StrictNativeUseTimingFence.allows(
                        envelope, 100L, 132L)));
    }

    @Test
    void failsClosedForMalformedOrBackwardsNativeTiming() {
        ActionEnvelope envelope = envelope(1_000L, 32);

        Assertions.assertAll(
                () -> Assertions.assertFalse(StrictNativeUseTimingFence.allows(
                        envelope, -1L, 100L)),
                () -> Assertions.assertFalse(StrictNativeUseTimingFence.allows(
                        envelope, 0L, -1L)),
                () -> Assertions.assertFalse(StrictNativeUseTimingFence.allows(
                        envelope, 100L, 99L)),
                () -> Assertions.assertFalse(StrictNativeUseTimingFence.allows(
                        null, 100L, 100L)));
    }

    private static ActionEnvelope envelope(long deadlineTick, int maxTicks) {
        return new ActionEnvelope(
                ACTION_ID,
                BOT_ID,
                1L,
                "strict-native-use-timing",
                deadlineTick,
                maxTicks,
                new LookAtAction(1.0D, 2.0D, 3.0D),
                ActionOrigin.none());
    }
}
