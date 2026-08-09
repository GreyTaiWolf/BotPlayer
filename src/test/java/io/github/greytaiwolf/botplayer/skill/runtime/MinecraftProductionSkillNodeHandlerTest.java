package io.github.greytaiwolf.botplayer.skill.runtime;

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
import io.github.greytaiwolf.botplayer.action.interaction.WorldInteractionActionSpec;
import io.github.greytaiwolf.botplayer.action.interaction.menu.P5ARecipe;
import io.github.greytaiwolf.botplayer.skill.builtin.P5ABuiltinSkillIds;
import io.github.greytaiwolf.botplayer.skill.builtin.production.ProductionLedger;
import io.github.greytaiwolf.botplayer.skill.builtin.production.ProductionMaterials;
import io.github.greytaiwolf.botplayer.skill.builtin.production.ProductionPreconditionSnapshot;
import io.github.greytaiwolf.botplayer.skill.builtin.production.ProductionSkillPlanCompiler;
import io.github.greytaiwolf.botplayer.skill.builtin.production.ResourceAcquisition;
import io.github.greytaiwolf.botplayer.skill.core.SkillFailureCode;
import io.github.greytaiwolf.botplayer.skill.core.SkillParameters;
import io.github.greytaiwolf.botplayer.skill.core.SkillSignal;
import io.github.greytaiwolf.botplayer.skill.core.SkillSignalInbox;
import io.github.greytaiwolf.botplayer.skill.menu.MenuFamily;
import io.github.greytaiwolf.botplayer.skill.menu.MenuTransactionLimits;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanNode;
import io.github.greytaiwolf.botplayer.skill.reservation.ReservationKey;
import io.github.greytaiwolf.botplayer.skill.reservation.ReservationRequest;
import io.github.greytaiwolf.botplayer.skill.reservation.ResourceReservationService;
import io.github.greytaiwolf.botplayer.skill.runtime.core.ActionBackedSkillNodeHandler;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillNodeContext;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillNodeDirective;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorLimits;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class MinecraftProductionSkillNodeHandlerTest {
    private static final UUID BOT = new UUID(0L, 1L);
    private static final UUID RUN = new UUID(0L, 2L);
    private static final UUID NODE = new UUID(0L, 3L);

    @Test
    void decodesOnlyTheCompilerApprovedExactOperationParameter() {
        String operation = operation("harvest_logs");
        MinecraftProductionSkillNodeHandler.ApprovedOperation approved =
                MinecraftProductionSkillNodeHandler.approvedOperation(
                        parameters(operation)).orElseThrow();

        Assertions.assertEquals(operation, approved.operationId());
        Assertions.assertEquals("harvest_logs.fragment-1-of-4",
                approved.resolved().node().nodeId());
        Assertions.assertEquals(1, ((ResourceAcquisition) approved.resolved()
                .node().operation()).expectedGain().quantityOf(
                        ProductionMaterials.OAK_LOG));

        Map<String, Object> extra = new HashMap<>(
                parameters(operation).values());
        extra.put("target.x", 0);
        Assertions.assertTrue(MinecraftProductionSkillNodeHandler
                .approvedOperation(new SkillParameters(extra)).isEmpty());
        Assertions.assertTrue(MinecraftProductionSkillNodeHandler
                .approvedOperation(parameters("p5a.unknown.operation"))
                .isEmpty());
        Assertions.assertTrue(MinecraftProductionSkillNodeHandler
                .approvedOperation(new SkillParameters(Map.of(
                        P5ABuiltinSkillIds
                                .BOOTSTRAP_IRON_OPERATION_ID_PARAMETER,
                        4)))
                .isEmpty());
    }

    @Test
    void reservesNativeInventoryAndAConservativeOperationSegment() {
        MinecraftProductionSkillNodeHandler handler = handler(
                ignored -> Optional.empty(),
                unused -> Optional.empty());
        String acquisition = operation("harvest_logs");
        String recipe = operation("craft_planks");

        List<ReservationRequest> acquisitionReservations =
                handler.requiredReservations(context(acquisition, 10L, 0L));
        List<ReservationRequest> recipeReservations =
                handler.requiredReservations(context(recipe, 10L, 0L));

        Assertions.assertAll(
                () -> Assertions.assertEquals(2,
                        acquisitionReservations.size()),
                () -> Assertions.assertEquals(
                        ReservationKey.Kind.CONTAINER,
                        acquisitionReservations.get(0).key().kind()),
                () -> Assertions.assertEquals("bot:" + BOT,
                        acquisitionReservations.get(0).key().subject()),
                () -> Assertions.assertEquals(
                        ReservationKey.Kind.WORK_AREA,
                        acquisitionReservations.get(1).key().kind()),
                () -> Assertions.assertEquals(acquisition,
                        acquisitionReservations.get(1).key().subject()),
                () -> Assertions.assertEquals(
                        ReservationKey.Kind.CONTAINER,
                        recipeReservations.get(1).key().kind()),
                () -> Assertions.assertEquals("inventory_2x2",
                        recipeReservations.get(1).key().subject()));
    }

    @Test
    void rejectsInsufficientBalanceBeforeAnyActionFactoryIsCalled() {
        int[] factoryCalls = {0};
        MinecraftProductionSkillNodeHandler handler = handler(
                ignored -> Optional.of(recipePreflight(10L,
                        ProductionLedger.empty())),
                ticket -> {
                    factoryCalls[0]++;
                    return Optional.of(testAction());
                });

        SkillNodeDirective result = handler.begin(context(
                operation("craft_planks"), 10L, 0L));

        Assertions.assertAll(
                () -> Assertions.assertEquals(SkillNodeDirective.Kind.FAIL,
                        result.kind()),
                () -> Assertions.assertEquals(SkillFailureCode.MISSING_ITEM,
                        result.failureCode().orElseThrow()),
                () -> Assertions.assertEquals(0, factoryCalls[0]));
    }

    @Test
    void refusesToPretendThatAnUnwiredAcquisitionSucceeded() {
        MinecraftProductionSkillNodeHandler handler = handler(
                ignored -> Optional.of(acquisitionPreflight(10L)),
                unused -> Optional.empty());

        SkillNodeDirective result = handler.begin(context(
                operation("harvest_logs"), 10L, 0L));

        Assertions.assertAll(
                () -> Assertions.assertEquals(SkillNodeDirective.Kind.FAIL,
                        result.kind()),
                () -> Assertions.assertEquals(
                        SkillFailureCode.ACTION_REJECTED,
                result.failureCode().orElseThrow()));
    }

    @Test
    void acceptsOnlyTheExactWhitelistedRecipeActionAndItsReviewedBatchCount() {
        MinecraftProductionSkillNodeHandler accepted = handler(
                ignored -> Optional.of(recipePreflight(10L,
                        ProductionLedger.of(ProductionMaterials.OAK_LOG, 4))),
                unused -> Optional.of(recipeAction(4)));
        MinecraftProductionSkillNodeHandler wrongBatch = handler(
                ignored -> Optional.of(recipePreflight(10L,
                        ProductionLedger.of(ProductionMaterials.OAK_LOG, 4))),
                unused -> Optional.of(recipeAction(1)));

        SkillNodeDirective acceptedDirective = accepted.begin(context(
                operation("craft_planks"), 10L, 0L));
        SkillNodeDirective rejectedDirective = wrongBatch.begin(context(
                operation("craft_planks"), 10L, 0L));

        Assertions.assertAll(
                () -> Assertions.assertEquals(SkillNodeDirective.Kind.WAIT_MENU,
                        acceptedDirective.kind()),
                () -> Assertions.assertEquals(SkillNodeDirective.Kind.FAIL,
                        rejectedDirective.kind()),
                () -> Assertions.assertEquals(SkillFailureCode.ACTION_REJECTED,
                rejectedDirective.failureCode().orElseThrow()));
    }

    @Test
    void acceptsNativeInventoryBaselineBeforeAtomicallyOpeningAWorkbench() {
        MinecraftProductionSkillNodeHandler handler = handler(
                ignored -> Optional.of(recipePreflight(10L,
                        new ProductionLedger(Map.of(
                                ProductionMaterials.OAK_PLANKS, 3,
                                ProductionMaterials.STICK, 2)))),
                unused -> Optional.of(workbenchRecipeAction()));

        SkillNodeDirective result = handler.begin(context(
                operation("wooden_pickaxe"), 10L, 0L));

        Assertions.assertEquals(SkillNodeDirective.Kind.WAIT_MENU,
                result.kind());
    }

    @Test
    void rejectsSuccessfulActionReceiptWhenTheFrozenWorldBindingDrifts() {
        RecordingGateway actions = new RecordingGateway();
        List<SkillSignal> signals = new ArrayList<>();
        MinecraftProductionSkillNodeHandler.ProductionObservationPort
                observations = new MinecraftProductionSkillNodeHandler
                        .ProductionObservationPort() {
                    @Override
                    public Optional<MinecraftProductionSkillNodeHandler
                            .PreflightObservation> observeForDispatch(
                                    MinecraftProductionSkillNodeHandler
                                            .ActiveBot bot,
                                    SkillNodeContext context,
                                    MinecraftProductionSkillNodeHandler
                                            .ApprovedOperation operation) {
                        return Optional.of(acquisitionPreflight(
                                context.currentTick()));
                    }

                    @Override
                    public Optional<MinecraftProductionSkillNodeHandler
                            .CompletionObservation> observeAfterAction(
                                    MinecraftProductionSkillNodeHandler
                                            .ExecutionTicket ticket,
                                    SkillNodeContext context,
                                    SkillSignal signal) {
                        return Optional.of(new MinecraftProductionSkillNodeHandler
                                .CompletionObservation(
                                        new ProductionPreconditionSnapshot(
                                                2L,
                                                ProductionLedger.of(
                                                        ProductionMaterials
                                                                .OAK_LOG,
                                                        4),
                                                Optional.empty(),
                                                true,
                                                Optional.empty()),
                                        context.currentTick(),
                                        "harvest-log-target",
                                        "native-inventory",
                                        false,
                                        true));
                    }
                };
        MinecraftProductionSkillNodeHandler handler = new MinecraftProductionSkillNodeHandler(
                (botId, generation) -> Optional.of(
                        new MinecraftProductionSkillNodeHandler.ActiveBot(
                                botId, generation)),
                observations,
                taskSensors(),
                (ticket, sensors) -> Optional.of(testAction()),
                ticket -> Optional.empty(),
                actions,
                signal -> {
                    signals.add(signal);
                    return SkillSignalInbox.OfferStatus.ENQUEUED;
                });
        SkillNodeContext begin = context(operation("harvest_logs"),
                10L, 0L);

        SkillNodeDirective started = handler.begin(begin);
        Assertions.assertEquals(SkillNodeDirective.Kind.WAIT_ACTION,
                started.kind());
        actions.completeSuccess();
        SkillSignal signal = Assertions.assertDoesNotThrow(
                () -> signals.get(0));

        SkillNodeDirective verified = handler.signal(context(
                operation("harvest_logs"), 11L, 1L), signal);
        Assertions.assertAll(
                () -> Assertions.assertEquals(SkillNodeDirective.Kind.FAIL,
                        verified.kind()),
                () -> Assertions.assertEquals(SkillFailureCode.WORLD_CHANGED,
                verified.failureCode().orElseThrow()));
    }

    @Test
    void pollsAVisibleVanillaBreakDropForOnlyABoundedNumberOfTicks() {
        RecordingGateway actions = new RecordingGateway();
        List<SkillSignal> signals = new ArrayList<>();
        int[] completionReads = {0};
        MinecraftProductionSkillNodeHandler handler =
                new MinecraftProductionSkillNodeHandler(
                        (botId, generation) -> Optional.of(
                                new MinecraftProductionSkillNodeHandler
                                        .ActiveBot(botId, generation)),
                        new MinecraftProductionSkillNodeHandler
                                .ProductionObservationPort() {
                            @Override
                            public Optional<MinecraftProductionSkillNodeHandler
                                    .PreflightObservation> observeForDispatch(
                                            MinecraftProductionSkillNodeHandler
                                                    .ActiveBot bot,
                                            SkillNodeContext context,
                                            MinecraftProductionSkillNodeHandler
                                                    .ApprovedOperation operation) {
                                return Optional.of(acquisitionPreflight(
                                        context.currentTick()));
                            }

                            @Override
                            public Optional<MinecraftProductionSkillNodeHandler
                                    .CompletionObservation> observeAfterAction(
                                            MinecraftProductionSkillNodeHandler
                                                    .ExecutionTicket ticket,
                                            SkillNodeContext context,
                                            SkillSignal signal) {
                                boolean pickedUp = completionReads[0]++ >= 2;
                                return Optional.of(acquisitionCompletion(
                                        context.currentTick(), pickedUp
                                                ? ProductionLedger.of(
                                                        ProductionMaterials
                                                                .OAK_LOG,
                                                        1)
                                                : ProductionLedger.empty()));
                            }
                        },
                        taskSensors(),
                        (ticket, sensors) -> Optional.of(testAction()),
                        ticket -> Optional.empty(),
                        actions,
                        signal -> {
                            signals.add(signal);
                            return SkillSignalInbox.OfferStatus.ENQUEUED;
                        });

        Assertions.assertEquals(SkillNodeDirective.Kind.WAIT_ACTION,
                handler.begin(context(operation("harvest_logs"), 10L, 0L))
                        .kind());
        actions.completeSuccess();
        SkillNodeDirective waitingForPickup = handler.signal(context(
                operation("harvest_logs"), 11L, 1L), signals.get(0));
        SkillNodeDirective completed = handler.tick(context(
                operation("harvest_logs"), 12L, 2L));

        Assertions.assertAll(
                () -> Assertions.assertEquals(SkillNodeDirective.Kind.CONTINUE,
                        waitingForPickup.kind()),
                () -> Assertions.assertEquals(SkillNodeDirective.Kind.COMPLETE,
                        completed.kind()),
                () -> Assertions.assertEquals(3, completionReads[0]));
    }

    private static MinecraftProductionSkillNodeHandler handler(
            java.util.function.Function<SkillNodeContext, Optional<
                    MinecraftProductionSkillNodeHandler.PreflightObservation>>
                    preflight,
            java.util.function.Function<MinecraftProductionSkillNodeHandler
                    .ExecutionTicket, Optional<MinecraftProductionSkillNodeHandler
                    .ProductionAction>> menuActions) {
        return new MinecraftProductionSkillNodeHandler(
                (botId, generation) -> Optional.of(
                        new MinecraftProductionSkillNodeHandler.ActiveBot(
                                botId, generation)),
                new MinecraftProductionSkillNodeHandler
                        .ProductionObservationPort() {
                    @Override
                    public Optional<MinecraftProductionSkillNodeHandler
                            .PreflightObservation> observeForDispatch(
                                    MinecraftProductionSkillNodeHandler
                                            .ActiveBot bot,
                                    SkillNodeContext context,
                                    MinecraftProductionSkillNodeHandler
                                            .ApprovedOperation operation) {
                        return preflight.apply(context);
                    }

                    @Override
                    public Optional<MinecraftProductionSkillNodeHandler
                            .CompletionObservation> observeAfterAction(
                                    MinecraftProductionSkillNodeHandler
                                            .ExecutionTicket ticket,
                                    SkillNodeContext context,
                                    SkillSignal signal) {
                        return Optional.empty();
                    }
                },
                taskSensors(),
                (ticket, sensors) -> menuActions.apply(ticket),
                menuActions::apply,
                new RecordingGateway(),
                signal -> SkillSignalInbox.OfferStatus.ENQUEUED);
    }

    private static MinecraftProductionSkillNodeHandler.PreflightObservation
            acquisitionPreflight(long tick) {
        return new MinecraftProductionSkillNodeHandler.PreflightObservation(
                new ProductionPreconditionSnapshot(
                        1L,
                        ProductionLedger.empty(),
                        Optional.empty(),
                        true,
                        Optional.empty()),
                tick,
                "harvest-log-target",
                "native-inventory");
    }

    private static MinecraftProductionSkillNodeHandler.CompletionObservation
            acquisitionCompletion(long tick, ProductionLedger ledger) {
        return new MinecraftProductionSkillNodeHandler.CompletionObservation(
                new ProductionPreconditionSnapshot(
                        2L,
                        ledger,
                        Optional.empty(),
                        true,
                        Optional.empty()),
                tick,
                "harvest-log-target",
                "native-inventory",
                true,
                true);
    }

    private static MinecraftProductionSkillNodeHandler.PreflightObservation
            recipePreflight(long tick, ProductionLedger ledger) {
        return new MinecraftProductionSkillNodeHandler.PreflightObservation(
                new ProductionPreconditionSnapshot(
                        1L,
                        ledger,
                        Optional.of(MenuFamily.INVENTORY_2X2),
                        true,
                        Optional.empty()),
                tick,
                "native-crafting",
                "inventory-menu");
    }

    private static MinecraftProductionSkillNodeHandler.ProductionAction
            testAction() {
        return new MinecraftProductionSkillNodeHandler.ProductionAction(
                new WorldInteractionAction(
                        new WorldInteractionActionSpec.BreakBlock(
                                new BlockHitTarget(
                                        new BlockTargetFingerprint(
                                                new ResourceId(
                                                        "minecraft:overworld"),
                                                new BlockCoordinates(4, 64, 4),
                                                new BlockStateFingerprint(
                                                        new ResourceId(
                                                                "minecraft:oak_log"),
                                                        Map.of("axis", "y"))),
                                        BlockHitTarget.Face.UP,
                                        0.5D,
                                        1.0D,
                                        0.5D,
                                        false),
                                ItemStackFingerprint.empty())),
                20,
                "等待测试原版世界动作");
    }

    private static MinecraftProductionSkillNodeHandler.ProductionAction
            recipeAction(int batches) {
        return new MinecraftProductionSkillNodeHandler.ProductionAction(
                new WorldInteractionAction(
                        new WorldInteractionActionSpec.WorldMenuRecipe(
                                WorldInteractionActionSpec.Hand.MAIN_HAND,
                                Optional.empty(),
                                ItemStackFingerprint.empty(),
                                P5ARecipe.OAK_LOG_TO_PLANKS,
                                batches,
                                MenuTransactionLimits.defaults())),
                160,
                "等待测试原版多批 crafting 动作");
    }

    private static MinecraftProductionSkillNodeHandler.ProductionAction
            workbenchRecipeAction() {
        BlockTargetFingerprint table = new BlockTargetFingerprint(
                new ResourceId("minecraft:overworld"),
                new BlockCoordinates(4, 64, 4),
                new BlockStateFingerprint(
                        new ResourceId("minecraft:crafting_table"),
                        Map.of()));
        return new MinecraftProductionSkillNodeHandler.ProductionAction(
                new WorldInteractionAction(
                        new WorldInteractionActionSpec.WorldMenuRecipe(
                                WorldInteractionActionSpec.Hand.MAIN_HAND,
                                Optional.of(new BlockHitTarget(
                                        table,
                                        BlockHitTarget.Face.UP,
                                        0.5D,
                                        1.0D,
                                        0.5D,
                                        false)),
                                ItemStackFingerprint.empty(),
                                P5ARecipe.WOODEN_PICKAXE,
                                1,
                                MenuTransactionLimits.defaults())),
                160,
                "等待测试原版工作台 crafting 动作");
    }

    private static SkillNodeContext context(
            String operation, long tick, long stateRevision) {
        return new SkillNodeContext(
                RUN,
                BOT,
                1L,
                1L,
                stateRevision,
                stateRevision + 1L,
                new SkillPlanNode(
                        NODE,
                        P5ABuiltinSkillIds.BOOTSTRAP_IRON,
                        P5ABuiltinSkillIds.VERSION,
                        parameters(operation)),
                0,
                tick,
                1_000L,
                new ResourceReservationService(16, 100));
    }

    private static SkillParameters parameters(String operation) {
        return new SkillParameters(Map.of(
                P5ABuiltinSkillIds.BOOTSTRAP_IRON_OPERATION_ID_PARAMETER,
                operation));
    }

    private static String operation(String nodeId) {
        return ProductionSkillPlanCompiler.operationIdForProductionNode(nodeId)
                .orElseThrow();
    }

    private static TaskSensorService taskSensors() {
        return new TaskSensorService(
                (botId, runId) -> Optional.empty(),
                TaskSensorLimits.defaults());
    }

    private static final class RecordingGateway
            implements ActionBackedSkillNodeHandler.ActionGateway {
        private final CompletableFuture<ActionOutcome> completion =
                new CompletableFuture<>();
        private ActionEnvelope envelope;

        @Override
        public ActionMailbox.Submission submit(
                ActionEnvelope actionEnvelope, ActionPriority priority) {
            envelope = actionEnvelope;
            return new ActionMailbox.Submission(
                    ActionMailbox.SubmissionStatus.ENQUEUED,
                    Optional.<CompletionStage<ActionOutcome>>of(completion));
        }

        @Override
        public void cancel(
                UUID botId,
                UUID actionId,
                ActionCancellationReason reason) {
            // 该测试不触发取消；handler 的取消边界由 ActionBacked 单独覆盖。
        }

        private void completeSuccess() {
            Assertions.assertNotNull(envelope,
                    "handler must submit a frozen action before completion");
            completion.complete(new ActionOutcome(
                    envelope.actionId(),
                    ActionState.SUCCEEDED,
                    ActionFailureCode.NONE,
                    10L,
                    10L,
                    List.of(new ActionEvidence("production.receipt", "ok")),
                    "测试原版动作已完成"));
        }
    }
}
