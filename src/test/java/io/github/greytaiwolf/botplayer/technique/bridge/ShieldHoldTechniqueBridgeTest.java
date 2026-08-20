package io.github.greytaiwolf.botplayer.technique.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.greytaiwolf.botplayer.action.ActionCancellationReason;
import io.github.greytaiwolf.botplayer.action.ActionCancellationReceipt;
import io.github.greytaiwolf.botplayer.action.ActionEnvelope;
import io.github.greytaiwolf.botplayer.action.ActionFailureCode;
import io.github.greytaiwolf.botplayer.action.ActionMailbox;
import io.github.greytaiwolf.botplayer.action.ActionOrigin;
import io.github.greytaiwolf.botplayer.action.ActionOutcome;
import io.github.greytaiwolf.botplayer.action.ActionPriority;
import io.github.greytaiwolf.botplayer.action.ActionState;
import io.github.greytaiwolf.botplayer.action.TechniqueChildOrigin;
import io.github.greytaiwolf.botplayer.action.WorldInteractionAction;
import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import io.github.greytaiwolf.botplayer.action.interaction.WorldInteractionActionSpec;
import io.github.greytaiwolf.botplayer.technique.combat.ShieldHoldTechnique;
import io.github.greytaiwolf.botplayer.technique.core.TechniqueFailureCode;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueChildTicket;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueRunView;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueState;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueSubmission;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class ShieldHoldTechniqueBridgeTest {
    private static final UUID BOT = UUID.fromString(
            "11111111-1111-1111-1111-111111111111");
    private static final ItemStackFingerprint SHIELD = ItemStackFingerprint.of(
            new ResourceId("minecraft:shield"), 1, 0,
            "0000000000000000000000000000000000000000000000000000000000000000");
    private static final ItemStackFingerprint NOT_A_SHIELD =
            ItemStackFingerprint.of(new ResourceId("minecraft:stick"), 1, 0,
                    "0000000000000000000000000000000000000000000000000000000000000000");

    @Test
    void bindsOneFixedOffHandUseAndRetainsTheRunUntilTheNextTechniqueTick() {
        Harness harness = new Harness();

        TechniqueSubmission submission = harness.bridge.start(BOT, 1L,
                SHIELD, 0L);

        assertEquals(TechniqueSubmission.Status.ACCEPTED,
                submission.status());
        ActionEnvelope envelope = harness.gateway.onlySubmitted();
        assertEquals(ActionPriority.OWNER_TASK,
                harness.gateway.priorityByEnvelope.get(envelope));
        assertTrue(envelope.action() instanceof WorldInteractionAction);
        WorldInteractionActionSpec.UseItem use =
                (WorldInteractionActionSpec.UseItem) ((WorldInteractionAction)
                        envelope.action()).spec();
        assertEquals(WorldInteractionActionSpec.Hand.OFF_HAND, use.hand());
        assertEquals(SHIELD, use.expectedHeldItem());
        assertEquals(WorldInteractionActionSpec.ItemUseMode.RELEASE_AFTER_HOLD,
                use.mode());
        assertEquals(ShieldHoldTechniqueBridge.HOLD_TICKS, use.holdTicks());
        assertTrue(use.strictPreconditions().isEmpty());
        assertEquals(ShieldHoldTechniqueBridge.ACTION_MAXIMUM_TICKS,
                envelope.maxTicks());
        assertEquals(ShieldHoldTechniqueBridge.ACTION_MAXIMUM_TICKS,
                envelope.deadlineTick());
        assertEquals(ShieldHoldTechnique.CHANNELS, envelope.action().channels());
        TechniqueRunView run = harness.coordinator.inspect(BOT).orElseThrow();
        TechniqueChildTicket ticket = run.childTickets().getFirst();
        assertEquals(TechniqueActionPermit.idempotencyKeyFor(ticket),
                envelope.idempotencyKey());
        assertEquals(TechniqueActionPermit.originFor(run, ticket),
                envelope.origin());
        ActionOrigin origin = envelope.origin();
        TechniqueChildOrigin childOrigin = origin.techniqueChild().orElseThrow();
        assertTrue(origin.skillRunId().isPresent());
        assertEquals("technique/" + childOrigin.techniqueRunId() + "/"
                        + childOrigin.techniqueChildTicketId() + "/"
                        + childOrigin.techniqueChildRevision(),
                envelope.idempotencyKey());

        harness.coordinator.finishTick(0L);
        harness.gateway.complete(envelope, ActionState.SUCCEEDED,
                ActionFailureCode.NONE, 1L);
        harness.coordinator.tick(1L);
        harness.coordinator.drainCompletedChildren(1L);

        assertEquals(TechniqueState.RUNNING,
                harness.coordinator.inspect(BOT).orElseThrow().state(),
                "child release must not discard the parent run before its next tick");
        harness.coordinator.finishTick(1L);
        harness.coordinator.tick(2L);

        assertTrue(harness.coordinator.inspect(BOT).isEmpty());
        assertEquals(TechniqueFailureCode.NONE,
                harness.coordinator.latestOutcome(BOT).orElseThrow()
                        .failureCode());
        assertTrue(harness.coordinator.isGenerationSafe(BOT, 1L));
    }

    @Test
    void sameActionIdInAForeignEnvelopeCannotCompleteTheBoundChild() {
        Harness harness = new Harness();
        assertEquals(TechniqueSubmission.Status.ACCEPTED,
                harness.bridge.start(BOT, 1L, SHIELD, 0L).status());
        ActionEnvelope expected = harness.gateway.onlySubmitted();
        ActionEnvelope foreign = new ActionEnvelope(expected.actionId(), BOT,
                1L, "foreign/shield", expected.deadlineTick(),
                expected.maxTicks(), expected.action(), ActionOrigin.none());

        harness.gateway.complete(foreign, ActionState.SUCCEEDED,
                ActionFailureCode.NONE, 1L);
        harness.coordinator.drainCompletedChildren(1L);

        assertEquals(expected, harness.gateway.lastCompletedLookup);
        assertEquals(TechniqueState.WAITING_CHILDREN,
                harness.coordinator.inspect(BOT).orElseThrow().state());

        harness.gateway.complete(expected, ActionState.SUCCEEDED,
                ActionFailureCode.NONE, 1L);
        harness.coordinator.drainCompletedChildren(1L);
        assertEquals(TechniqueState.RUNNING,
                harness.coordinator.inspect(BOT).orElseThrow().state());
    }

    @Test
    void unsafeControlTerminalMapsToThePreciseTechniqueFailure() {
        Harness harness = new Harness();
        assertEquals(TechniqueSubmission.Status.ACCEPTED,
                harness.bridge.start(BOT, 1L, SHIELD, 0L).status());
        ActionEnvelope envelope = harness.gateway.onlySubmitted();

        harness.coordinator.finishTick(0L);
        harness.gateway.complete(envelope, ActionState.FAILED,
                ActionFailureCode.UNSAFE_CONTROL_STATE, 1L);
        harness.coordinator.tick(1L);
        harness.coordinator.drainCompletedChildren(1L);
        harness.coordinator.finishTick(1L);
        harness.coordinator.tick(2L);

        assertTrue(harness.coordinator.inspect(BOT).isEmpty());
        assertEquals(TechniqueFailureCode.ACTION_CLEANUP_UNSAFE,
                harness.coordinator.latestOutcome(BOT).orElseThrow()
                        .failureCode());
    }

    @Test
    void reentrantGenerationCloseCannotLeaveAnAcceptedShieldRouteBehind() {
        Harness harness = new Harness();
        harness.gateway.onSubmit = () -> harness.coordinator.closeGeneration(
                BOT, 1L, 0L);

        TechniqueSubmission submission = harness.bridge.start(BOT, 1L,
                SHIELD, 0L);

        assertFalse(submission.status() == TechniqueSubmission.Status.ACCEPTED);
        assertEquals(1, harness.gateway.cancelCount);
        assertTrue(harness.coordinator.inspect(BOT).isEmpty());
        assertTrue(harness.coordinator.isGenerationSafe(BOT, 1L));
        harness.gateway.complete(harness.gateway.onlySubmitted(),
                ActionState.SUCCEEDED, ActionFailureCode.NONE, 1L);
        harness.coordinator.drainCompletedChildren(1L);
        assertEquals(TechniqueFailureCode.GENERATION_CHANGED,
                harness.coordinator.latestOutcome(BOT).orElseThrow()
                        .failureCode());
    }

    @Test
    void rejectedIngressAndNonShieldInputDoNotLeaveALiveRoute() {
        Harness rejected = new Harness();
        rejected.gateway.status = ActionMailbox.SubmissionStatus.MAILBOX_FULL;

        assertFalse(rejected.bridge.start(BOT, 1L, SHIELD, 0L).status()
                == TechniqueSubmission.Status.ACCEPTED);
        assertTrue(rejected.coordinator.inspect(BOT).isEmpty());
        assertTrue(rejected.coordinator.isGenerationSafe(BOT, 1L));

        Harness wrongItem = new Harness();
        assertEquals(TechniqueSubmission.Status.INVALID_REQUEST,
                wrongItem.bridge.start(BOT, 1L, NOT_A_SHIELD, 0L).status());
        assertTrue(wrongItem.gateway.submitted.isEmpty());
    }

    private static final class Harness {
        private final TechniqueLifecycleCoordinator coordinator =
                new TechniqueLifecycleCoordinator();
        private final RecordingGateway gateway = new RecordingGateway();
        private final ShieldHoldTechniqueBridge bridge =
                new ShieldHoldTechniqueBridge(coordinator, gateway);
    }

    private static final class RecordingGateway
            implements TechniqueActionRuntimeGateway {
        private ActionMailbox.SubmissionStatus status =
                ActionMailbox.SubmissionStatus.ENQUEUED;
        private final Map<ActionEnvelope, ActionPriority> priorityByEnvelope =
                new LinkedHashMap<>();
        private final Map<ActionEnvelope, ActionOutcome> outcomes =
                new LinkedHashMap<>();
        private Runnable onSubmit;
        private ActionEnvelope lastCompletedLookup;
        private int cancelCount;

        @Override
        public ActionMailbox.Submission submit(ActionEnvelope envelope,
                ActionPriority priority) {
            priorityByEnvelope.put(envelope, priority);
            Runnable callback = onSubmit;
            if (callback != null) {
                callback.run();
            }
            return new ActionMailbox.Submission(status,
                    status == ActionMailbox.SubmissionStatus.ENQUEUED
                            ? Optional.of(new CompletableFuture<ActionOutcome>())
                            : Optional.empty());
        }

        @Override
        public ActionCancellationReceipt cancelOrContain(
                ActionEnvelope envelope, ActionCancellationReason reason,
                long currentTick) {
            cancelCount++;
            return ActionCancellationReceipt.fencedBeforeStart(
                    envelope.botId(), envelope.botGeneration(),
                    envelope.actionId());
        }

        @Override
        public Optional<ActionOutcome> completedOutcomeExact(
                ActionEnvelope expected) {
            lastCompletedLookup = expected;
            return Optional.ofNullable(outcomes.get(expected));
        }

        private ActionEnvelope onlySubmitted() {
            assertEquals(1, priorityByEnvelope.size());
            return priorityByEnvelope.keySet().iterator().next();
        }

        private void complete(ActionEnvelope envelope, ActionState state,
                ActionFailureCode failureCode, long tick) {
            outcomes.put(envelope, new ActionOutcome(envelope.actionId(), state,
                    failureCode, 0L, tick, List.of(), "synthetic terminal"));
        }
    }
}
