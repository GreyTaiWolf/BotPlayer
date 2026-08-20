package io.github.greytaiwolf.botplayer.action.minecraft;

import io.github.greytaiwolf.botplayer.action.ActionEnvelope;
import java.util.Objects;

/**
 * Pure timing fence shared by both native strict-use injection points.
 *
 * <p>Native {@code updateUsingItem()} runs before {@code BotActionRuntime} advances the action
 * for the same server tick. It must therefore reject at the runtime's exclusive deadline and
 * tick-budget boundaries instead of permitting a consumable that will immediately be reported as
 * timed out. Invalid state and backwards time fail closed.
 */
final class StrictNativeUseTimingFence {
    private StrictNativeUseTimingFence() {
    }

    static boolean allows(ActionEnvelope envelope, long startedTick, long currentTick) {
        try {
            ActionEnvelope checked = Objects.requireNonNull(envelope, "envelope");
            return startedTick >= 0L
                    && currentTick >= startedTick
                    && !checked.isExpiredAt(currentTick)
                    && !checked.hasExhaustedTickBudget(startedTick, currentTick);
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
