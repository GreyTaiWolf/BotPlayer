package io.github.greytaiwolf.botplayer.lifecycle;

import io.github.greytaiwolf.botplayer.action.ActionCancellationReason;
import io.github.greytaiwolf.botplayer.action.ActionCancellationReceipt;
import io.github.greytaiwolf.botplayer.action.ActionEnvelope;
import io.github.greytaiwolf.botplayer.action.ActionMailbox;
import io.github.greytaiwolf.botplayer.action.ActionOutcome;
import io.github.greytaiwolf.botplayer.action.ActionPriority;
import io.github.greytaiwolf.botplayer.action.BotActionRuntime;
import io.github.greytaiwolf.botplayer.technique.bridge.SelfDefenseTechniqueBridge;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Lifecycle-owned P5C Action port.
 *
 * <p>It deliberately exposes no generic Action route beyond the bridge's
 * already-frozen envelope. Both melee and the manager's direct RETREAT path
 * use the same {@link BotActionRuntime#cancelOrContain} contract, so a full
 * cancellation lane cannot turn a locally revoked self-defense capability
 * into a later physical world action.
 */
final class SelfDefenseActionGateway
        implements SelfDefenseTechniqueBridge.ActionGateway {
    private static final int MAX_RETAINED_CANCELLATION_RECEIPTS = 256;
    private final BotActionRuntime runtime;
    /** A containment failure must not leave this P5C port usable for that body. */
    private final Set<GenerationKey> containmentUnsafeGenerations =
            new HashSet<>();
    /** Duplicate lifecycle order must observe the first exact semantic receipt. */
    private final Map<ActionCancellationReceipt.Identity,
            ActionCancellationReceipt> cancellationReceipts =
            new LinkedHashMap<>();

    SelfDefenseActionGateway(BotActionRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    @Override
    public ActionMailbox.Submission submit(ActionEnvelope envelope,
            ActionPriority priority) {
        ActionEnvelope required = Objects.requireNonNull(envelope, "envelope");
        if (containmentUnsafeGenerations.contains(new GenerationKey(
                required.botId(), required.botGeneration()))) {
            return new ActionMailbox.Submission(
                    ActionMailbox.SubmissionStatus.BOT_GENERATION_CLOSED,
                    Optional.empty());
        }
        return runtime.submit(required, Objects.requireNonNull(priority,
                "priority"));
    }

    @Override
    public ActionCancellationReceipt cancelOrContain(
            ActionEnvelope envelope, ActionCancellationReason reason,
            long currentTick) {
        ActionEnvelope required = Objects.requireNonNull(envelope, "envelope");
        return cancelOrContain(required.botId(), required.botGeneration(),
                required.actionId(), reason, currentTick);
    }

    /** The direct RETREAT path has only its already-issued identity, not a raw action DTO. */
    ActionCancellationReceipt cancelOrContain(
            UUID botId, long botGeneration, UUID actionId,
            ActionCancellationReason reason, long currentTick) {
        UUID requiredBotId = Objects.requireNonNull(botId, "botId");
        UUID requiredActionId = Objects.requireNonNull(actionId, "actionId");
        Objects.requireNonNull(reason, "reason");
        GenerationKey key = new GenerationKey(requiredBotId, botGeneration);
        ActionCancellationReceipt.Identity identity =
                new ActionCancellationReceipt.Identity(requiredBotId,
                        botGeneration, requiredActionId);
        ActionCancellationReceipt prior = cancellationReceipts.get(identity);
        if (prior != null) {
            return prior;
        }
        ActionCancellationReceipt receipt;
        try {
            BotActionRuntime.CancellationContainmentResult result =
                    runtime.cancelOrContain(requiredBotId, botGeneration,
                            requiredActionId, reason, currentTick);
            receipt = result.receipt();
        } catch (RuntimeException exception) {
            receipt = ActionCancellationReceipt.unsafe(requiredBotId,
                    botGeneration, requiredActionId,
                    ActionCancellationReceipt.Disposition.UNKNOWN);
        }
        if (!receipt.matches(requiredBotId, botGeneration, requiredActionId)) {
            receipt = ActionCancellationReceipt.unsafe(requiredBotId,
                    botGeneration, requiredActionId,
                    ActionCancellationReceipt.Disposition.UNKNOWN);
        }
        rememberCancellationReceipt(identity, receipt);
        if (!receipt.safelyRetracted()) {
            containmentUnsafeGenerations.add(key);
        }
        return receipt;
    }

    @Override
    public Optional<ActionOutcome> completedOutcome(UUID botId,
            UUID actionId) {
        return runtime.completedOutcome(Objects.requireNonNull(botId, "botId"),
                Objects.requireNonNull(actionId, "actionId"));
    }

    private void rememberCancellationReceipt(
            ActionCancellationReceipt.Identity identity,
            ActionCancellationReceipt receipt) {
        cancellationReceipts.put(Objects.requireNonNull(identity, "identity"),
                Objects.requireNonNull(receipt, "receipt"));
        while (cancellationReceipts.size()
                > MAX_RETAINED_CANCELLATION_RECEIPTS) {
            ActionCancellationReceipt.Identity oldest = cancellationReceipts
                    .keySet().iterator().next();
            cancellationReceipts.remove(oldest);
        }
    }

    private record GenerationKey(UUID botId, long botGeneration) {
        private GenerationKey {
            Objects.requireNonNull(botId, "botId");
            if (botId.getMostSignificantBits() == 0L
                    && botId.getLeastSignificantBits() == 0L) {
                throw new IllegalArgumentException("botId must not be zero");
            }
            if (botGeneration <= 0L) {
                throw new IllegalArgumentException(
                        "botGeneration must be positive");
            }
        }
    }
}
