package io.github.greytaiwolf.botplayer.skill.runtime.core;

import io.github.greytaiwolf.botplayer.action.ActionCancellationReason;
import io.github.greytaiwolf.botplayer.action.ActionEnvelope;
import io.github.greytaiwolf.botplayer.action.ActionEvidence;
import io.github.greytaiwolf.botplayer.action.ActionFailureCode;
import io.github.greytaiwolf.botplayer.action.ActionMailbox;
import io.github.greytaiwolf.botplayer.action.ActionOutcome;
import io.github.greytaiwolf.botplayer.action.ActionPriority;
import io.github.greytaiwolf.botplayer.action.ActionState;
import io.github.greytaiwolf.botplayer.action.WorldInteractionAction;
import io.github.greytaiwolf.botplayer.action.interaction.BlockCoordinates;
import io.github.greytaiwolf.botplayer.action.interaction.BlockHitTarget;
import io.github.greytaiwolf.botplayer.action.interaction.BlockStateFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.BlockTargetFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import io.github.greytaiwolf.botplayer.action.interaction.UseItemPreconditions;
import io.github.greytaiwolf.botplayer.action.interaction.WorldInteractionActionSpec;
import io.github.greytaiwolf.botplayer.action.interaction.menu.InventoryMenuSnapshot;
import io.github.greytaiwolf.botplayer.skill.core.SkillFailureCode;
import io.github.greytaiwolf.botplayer.skill.core.SkillId;
import io.github.greytaiwolf.botplayer.skill.core.SkillParameters;
import io.github.greytaiwolf.botplayer.skill.core.SkillSignal;
import io.github.greytaiwolf.botplayer.skill.core.SkillSignalInbox;
import io.github.greytaiwolf.botplayer.skill.core.SkillSignalStatus;
import io.github.greytaiwolf.botplayer.skill.core.SkillVersion;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanNode;
import io.github.greytaiwolf.botplayer.skill.reservation.ResourceReservationService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ActionBackedSkillNodeHandlerTest {
    private static final UUID RUN = new UUID(0L, 1L);
    private static final UUID BOT = new UUID(0L, 2L);
    private static final UUID NODE = new UUID(0L, 3L);

    @Test
    void consumesOnlyThePrivateFailedCompletionCapabilityForReplan() {
        RecordingGateway actions = new RecordingGateway();
        List<SkillSignal> signals = new ArrayList<>();
        ActionBackedSkillNodeHandler handler = new ActionBackedSkillNodeHandler(
                ignored -> Optional.of(operation()), actions, signal -> {
                    signals.add(signal);
                    return SkillSignalInbox.OfferStatus.ENQUEUED;
                });
        SkillNodeContext initial = context(0L, 0L);

        Assertions.assertEquals(SkillNodeDirective.Kind.WAIT_ACTION,
                handler.begin(initial).kind());
        actions.completeFailure();
        SkillSignal actual = signals.get(0);
        SkillNodeContext waiting = context(1L, 1L);
        SkillSignal forgedSignalId = new SkillSignal(
                new UUID(0L, 99L),
                actual.runId(),
                actual.botId(),
                actual.botGeneration(),
                actual.runRevision(),
                actual.operationId(),
                actual.type(),
                actual.status(),
                actual.failureCode(),
                actual.evidence(),
                actual.safeSummary(),
                actual.gameTick());
        SkillSignal forgedSuccess = new SkillSignal(
                new UUID(0L, 98L),
                actual.runId(),
                actual.botId(),
                actual.botGeneration(),
                actual.runRevision(),
                actual.operationId(),
                actual.type(),
                SkillSignalStatus.SUCCEEDED,
                SkillFailureCode.NONE,
                actual.evidence(),
                actual.safeSummary(),
                actual.gameTick());
        SkillNodeContext wrongNode = new SkillNodeContext(
                RUN,
                BOT,
                1L,
                1L,
                1L,
                2L,
                new SkillPlanNode(
                        new UUID(0L, 4L),
                        new SkillId("botplayer", "test"),
                        new SkillVersion(1, 0, 0),
                        SkillParameters.empty()),
                0,
                1L,
                100L,
                new ResourceReservationService(8, 100));

        Assertions.assertFalse(handler
                .consumeMarkedNoPacketPlaceBlockReplan(waiting, forgedSignalId)
                .permitsCurrentNodeReplan());
        Assertions.assertEquals(SkillNodeDirective.Kind.FAIL,
                handler.signal(waiting, forgedSuccess).kind());
        Assertions.assertFalse(handler
                .consumeMarkedNoPacketPlaceBlockReplan(wrongNode, actual)
                .permitsCurrentNodeReplan());

        SkillNodeHandler.FailedSignalDisposition authorization = handler
                .consumeMarkedNoPacketPlaceBlockReplan(waiting, actual);
        Assertions.assertAll(
                () -> Assertions.assertTrue(
                        authorization.permitsCurrentNodeReplan()),
                () -> Assertions.assertFalse(
                        ActionBackedSkillNodeHandler
                                .consumeNoSideEffectReplan(
                                        authorization, wrongNode, actual)),
                () -> Assertions.assertTrue(
                        ActionBackedSkillNodeHandler
                                .consumeNoSideEffectReplan(
                                        authorization, waiting, actual)),
                () -> Assertions.assertFalse(
                        ActionBackedSkillNodeHandler
                                .consumeNoSideEffectReplan(
                                        authorization, waiting, actual)));

        Assertions.assertEquals(SkillNodeDirective.Kind.WAIT_ACTION,
                handler.begin(context(3L, 2L)).kind());
        actions.completeFailure();
        Assertions.assertFalse(handler
                .consumeMarkedNoPacketPlaceBlockReplan(
                        context(4L, 3L), signals.get(1))
                .permitsCurrentNodeReplan(),
                "the bridge itself permits at most one marked replan per node");
    }

    @Test
    void strictNaturalUseCancellationUsesTheDedicatedEnvelopeGateway() {
        RecordingGateway actions = new RecordingGateway();
        ActionBackedSkillNodeHandler handler = new ActionBackedSkillNodeHandler(
                ignored -> Optional.of(strictNaturalUseOperation()), actions,
                ignored -> SkillSignalInbox.OfferStatus.ENQUEUED);
        SkillNodeContext context = context(0L, 0L);

        Assertions.assertEquals(SkillNodeDirective.Kind.WAIT_ACTION,
                handler.begin(context).kind());
        handler.cancelled(context, "test cancellation");

        Assertions.assertAll(
                () -> Assertions.assertEquals(actions.envelope,
                        actions.strictNaturalUseEnvelope),
                () -> Assertions.assertEquals(ActionCancellationReason.REQUESTED,
                        actions.strictNaturalUseReason),
                () -> Assertions.assertNull(actions.legacyCancelledBotId));
    }

    @Test
    void nonStrictActionsKeepTheLegacyCancellationGateway() {
        RecordingGateway actions = new RecordingGateway();
        ActionBackedSkillNodeHandler handler = new ActionBackedSkillNodeHandler(
                ignored -> Optional.of(operation()), actions,
                ignored -> SkillSignalInbox.OfferStatus.ENQUEUED);
        SkillNodeContext context = context(0L, 0L);

        Assertions.assertEquals(SkillNodeDirective.Kind.WAIT_ACTION,
                handler.begin(context).kind());
        handler.cancelled(context, "test cancellation");

        Assertions.assertAll(
                () -> Assertions.assertEquals(BOT,
                        actions.legacyCancelledBotId),
                () -> Assertions.assertEquals(actions.envelope.actionId(),
                        actions.legacyCancelledActionId),
                () -> Assertions.assertEquals(ActionCancellationReason.REQUESTED,
                        actions.legacyCancellationReason),
                () -> Assertions.assertNull(actions.strictNaturalUseEnvelope));
    }

    private static ActionBackedSkillNodeHandler.Operation operation() {
        return new ActionBackedSkillNodeHandler.Operation(
                "test-action",
                markedPlaceBlockAction(),
                ActionPriority.AUTONOMOUS,
                2,
                SkillNodeDirective.Kind.WAIT_ACTION,
                "等待测试动作",
                (context, signal) -> SkillNodeDirective.complete(
                        "测试动作完成"));
    }

    private static ActionBackedSkillNodeHandler.Operation
            strictNaturalUseOperation() {
        ItemStackFingerprint milk = ItemStackFingerprint.of(
                new ResourceId("minecraft:milk_bucket"), 1, 0,
                "b".repeat(64));
        List<ItemStackFingerprint> slots = new ArrayList<>();
        for (int index = 0; index < 41; index++) {
            slots.add(ItemStackFingerprint.empty());
        }
        slots.set(0, milk);
        UseItemPreconditions preconditions = new UseItemPreconditions(
                new InventoryMenuSnapshot(0, 0, 0,
                        ItemStackFingerprint.empty(), slots),
                List.of());
        return new ActionBackedSkillNodeHandler.Operation(
                "strict-natural-use",
                new WorldInteractionAction(
                        new WorldInteractionActionSpec.UseItem(
                                WorldInteractionActionSpec.Hand.MAIN_HAND,
                                milk,
                                WorldInteractionActionSpec.ItemUseMode
                                        .FINISH_NATURALLY,
                                0,
                                preconditions)),
                ActionPriority.AUTONOMOUS,
                2,
                SkillNodeDirective.Kind.WAIT_ACTION,
                "等待严格自然使用",
                (context, signal) -> SkillNodeDirective.complete(
                        "严格自然使用完成"));
    }

    private static WorldInteractionAction markedPlaceBlockAction() {
        BlockTargetFingerprint anchor = new BlockTargetFingerprint(
                new ResourceId("minecraft:overworld"),
                new BlockCoordinates(4, 64, 4),
                new BlockStateFingerprint(new ResourceId("minecraft:stone"),
                        java.util.Map.of()));
        BlockTargetFingerprint placed = new BlockTargetFingerprint(
                new ResourceId("minecraft:overworld"),
                new BlockCoordinates(4, 65, 4),
                new BlockStateFingerprint(new ResourceId("minecraft:furnace"),
                        java.util.Map.of("facing", "north", "lit", "false")));
        return new WorldInteractionAction(
                new WorldInteractionActionSpec.PlaceBlock(
                        new BlockHitTarget(anchor, BlockHitTarget.Face.UP,
                                0.5D, 1.0D, 0.5D, false),
                        placed,
                        ItemStackFingerprint.of(
                                new ResourceId("minecraft:furnace"), 1, 0,
                                "a".repeat(64))));
    }

    private static SkillNodeContext context(
            long stateRevision, long currentTick) {
        return new SkillNodeContext(
                RUN,
                BOT,
                1L,
                1L,
                stateRevision,
                stateRevision + 1L,
                new SkillPlanNode(
                        NODE,
                        new SkillId("botplayer", "test"),
                        new SkillVersion(1, 0, 0),
                        SkillParameters.empty()),
                0,
                currentTick,
                100L,
                new ResourceReservationService(8, 100));
    }

    private static final class RecordingGateway
            implements ActionBackedSkillNodeHandler.ActionGateway {
        private CompletableFuture<ActionOutcome> completion;
        private ActionEnvelope envelope;
        private UUID legacyCancelledBotId;
        private UUID legacyCancelledActionId;
        private ActionCancellationReason legacyCancellationReason;
        private ActionEnvelope strictNaturalUseEnvelope;
        private ActionCancellationReason strictNaturalUseReason;

        @Override
        public ActionMailbox.Submission submit(
                ActionEnvelope actionEnvelope, ActionPriority priority) {
            envelope = actionEnvelope;
            completion = new CompletableFuture<>();
            return new ActionMailbox.Submission(
                    ActionMailbox.SubmissionStatus.ENQUEUED,
                    Optional.<CompletionStage<ActionOutcome>>of(completion));
        }

        @Override
        public void cancel(
                UUID botId, UUID actionId, ActionCancellationReason reason) {
            legacyCancelledBotId = botId;
            legacyCancelledActionId = actionId;
            legacyCancellationReason = reason;
        }

        @Override
        public void cancelStrictNaturalUse(
                ActionEnvelope actionEnvelope,
                ActionCancellationReason reason) {
            strictNaturalUseEnvelope = actionEnvelope;
            strictNaturalUseReason = reason;
        }

        private void completeFailure() {
            completion.complete(new ActionOutcome(
                    envelope.actionId(),
                    ActionState.FAILED,
                    ActionFailureCode.PRECONDITION_FAILED,
                    1L,
                    1L,
                    List.of(new ActionEvidence(WorldInteractionActionSpec
                            .PlaceBlock
                            .PRE_DISPATCH_FACING_DRIFT_EVIDENCE_KEY,
                            WorldInteractionActionSpec.PlaceBlock
                                    .PRE_DISPATCH_FACING_DRIFT_EVIDENCE_VALUE)),
                    "测试动作失败"));
        }
    }
}
