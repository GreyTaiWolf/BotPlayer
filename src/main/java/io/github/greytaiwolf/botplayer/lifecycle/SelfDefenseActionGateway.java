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
 * already-frozen envelope. The melee bridge delegates its full immutable
 * envelope to the exact Action containment API; the manager's direct RETREAT
 * path retains its pre-existing identity-only boundary. Neither path may turn
 * a locally revoked self-defense capability into a later physical world
 * action.
 */
final class SelfDefenseActionGateway
        implements SelfDefenseTechniqueBridge.ActionGateway {
    private static final int MAX_RETAINED_LEGACY_CANCELLATION_RECEIPTS = 256;
    private static final int MAX_RETAINED_EXACT_CANCELLATION_RECEIPTS = 256;
    private final BotActionRuntime runtime;
    /** A containment failure must not leave this P5C port usable for that body. */
    private final Set<GenerationKey> containmentUnsafeGenerations =
            new HashSet<>();
    /** Direct RETREAT calls have only the pre-existing identity-level contract. */
    private final Map<ActionCancellationReceipt.Identity,
            ActionCancellationReceipt> legacyCancellationReceipts =
            new LinkedHashMap<>();
    /** Bridge children retain receipts by their full immutable envelope. */
    private final Map<ActionEnvelope, ActionCancellationReceipt>
            exactCancellationReceipts = new LinkedHashMap<>();

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
        ActionCancellationReason requiredReason = Objects.requireNonNull(reason,
                "reason");
        ActionCancellationReceipt prior = exactCancellationReceipts.get(
                required);
        if (prior != null) {
            return prior;
        }
        GenerationKey key = new GenerationKey(required.botId(),
                required.botGeneration());
        if (exactCancellationReceipts.size()
                >= MAX_RETAINED_EXACT_CANCELLATION_RECEIPTS) {
            ActionCancellationReceipt receipt = ActionCancellationReceipt.unsafe(
                    required.botId(), required.botGeneration(),
                    required.actionId(),
                    ActionCancellationReceipt.Disposition.UNKNOWN);
            containmentUnsafeGenerations.add(key);
            return receipt;
        }
        ActionCancellationReceipt receipt;
        try {
            receipt = runtime.cancelOrContainExact(required, requiredReason,
                    currentTick).receipt();
        } catch (RuntimeException exception) {
            receipt = ActionCancellationReceipt.unsafe(required.botId(),
                    required.botGeneration(), required.actionId(),
                    ActionCancellationReceipt.Disposition.UNKNOWN);
        }
        if (!receipt.matches(required.botId(), required.botGeneration(),
                required.actionId())) {
            receipt = ActionCancellationReceipt.unsafe(required.botId(),
                    required.botGeneration(), required.actionId(),
                    ActionCancellationReceipt.Disposition.UNKNOWN);
        }
        exactCancellationReceipts.put(required, receipt);
        if (!receipt.safelyRetracted()) {
            containmentUnsafeGenerations.add(key);
        }
        return receipt;
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
        ActionCancellationReceipt prior = legacyCancellationReceipts.get(identity);
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
        rememberLegacyCancellationReceipt(identity, receipt);
        if (!receipt.safelyRetracted()) {
            containmentUnsafeGenerations.add(key);
        }
        return receipt;
    }

    @Override
    public Optional<ActionOutcome> completedOutcomeExact(ActionEnvelope expected) {
        return runtime.completedOutcomeExact(Objects.requireNonNull(expected,
                "expected"));
    }

    @Override
    public void release(ActionEnvelope envelope) {
        exactCancellationReceipts.remove(Objects.requireNonNull(envelope,
                "envelope"));
    }

    private void rememberLegacyCancellationReceipt(
            ActionCancellationReceipt.Identity identity,
            ActionCancellationReceipt receipt) {
        legacyCancellationReceipts.put(Objects.requireNonNull(identity,
                "identity"), Objects.requireNonNull(receipt, "receipt"));
        while (legacyCancellationReceipts.size()
                > MAX_RETAINED_LEGACY_CANCELLATION_RECEIPTS) {
            ActionCancellationReceipt.Identity oldest = legacyCancellationReceipts
                    .keySet().iterator().next();
            legacyCancellationReceipts.remove(oldest);
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
