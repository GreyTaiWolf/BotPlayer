package io.github.greytaiwolf.botplayer.technique.bridge;

import io.github.greytaiwolf.botplayer.action.ActionCancellationReason;
import io.github.greytaiwolf.botplayer.action.ActionCancellationReceipt;
import io.github.greytaiwolf.botplayer.action.ActionEnvelope;
import io.github.greytaiwolf.botplayer.action.ActionMailbox;
import io.github.greytaiwolf.botplayer.action.ActionOutcome;
import io.github.greytaiwolf.botplayer.action.ActionPriority;
import io.github.greytaiwolf.botplayer.action.BotActionRuntime;
import java.util.Objects;
import java.util.Optional;

/**
 * Package-private lifecycle seam for the one approved Technique Action port.
 *
 * <p>Routes and Techniques never receive this interface. Its only test seam
 * permits synchronous lifecycle reentry to be verified without exposing a raw
 * {@link BotActionRuntime} or an Action completion future to Technique code.
 */
interface TechniqueActionRuntimeGateway {
    ActionMailbox.Submission submit(ActionEnvelope envelope,
            ActionPriority priority);

    ActionCancellationReceipt cancelOrContain(ActionEnvelope envelope,
            ActionCancellationReason reason, long currentTick);

    Optional<ActionOutcome> completedOutcomeExact(ActionEnvelope expected);

    static TechniqueActionRuntimeGateway from(BotActionRuntime runtime) {
        BotActionRuntime required = Objects.requireNonNull(runtime, "runtime");
        return new TechniqueActionRuntimeGateway() {
            @Override
            public ActionMailbox.Submission submit(ActionEnvelope envelope,
                    ActionPriority priority) {
                return required.submit(envelope, priority);
            }

            @Override
            public ActionCancellationReceipt cancelOrContain(
                    ActionEnvelope envelope, ActionCancellationReason reason,
                    long currentTick) {
                return required.cancelOrContainExact(envelope, reason,
                        currentTick).receipt();
            }

            @Override
            public Optional<ActionOutcome> completedOutcomeExact(
                    ActionEnvelope expected) {
                return required.completedOutcomeExact(expected);
            }
        };
    }
}
