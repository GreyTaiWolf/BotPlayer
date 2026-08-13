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
import io.github.greytaiwolf.botplayer.navigation.GridPoint;
import io.github.greytaiwolf.botplayer.navigation.NavigationArrivalRequirement;
import io.github.greytaiwolf.botplayer.navigation.NavigationFailure;
import io.github.greytaiwolf.botplayer.navigation.NavigationGoal;
import io.github.greytaiwolf.botplayer.navigation.NavigationOutcome;
import io.github.greytaiwolf.botplayer.navigation.NavigationRequest;
import io.github.greytaiwolf.botplayer.navigation.NavigationState;
import io.github.greytaiwolf.botplayer.navigation.NavigationSubmission;
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
        String placement = operation("place_crafting_table");

        List<ReservationRequest> acquisitionReservations =
                handler.requiredReservations(context(acquisition, 10L, 0L));
        List<ReservationRequest> recipeReservations =
                handler.requiredReservations(context(recipe, 10L, 0L));
        List<ReservationRequest> placementReservations =
                handler.requiredReservations(context(placement, 10L, 0L));

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
                        recipeReservations.get(1).key().subject()),
                () -> Assertions.assertEquals(
                        ReservationKey.Kind.WORK_AREA,
                        placementReservations.get(1).key().kind()),
                () -> Assertions.assertEquals(placement,
                        placementReservations.get(1).key().subject()));
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
                result.failureCode().orElseThrow()),
                () -> Assertions.assertEquals(
                        "资源采集端口未能冻结当前 tick 的原版 BREAK_BLOCK 动作",
                        result.safeSummary()));
    }

    @Test
    void preservesSecondPreflightFailureInsteadOfCollapsingItToActionRejected() {
        int[] observations = {0};
        int[] factoryCalls = {0};
        RecordingGateway actions = new RecordingGateway();
        MinecraftProductionSkillNodeHandler handler = handler(
                ignored -> observations[0]++ == 0
                        ? Optional.of(acquisitionPreflight(10L))
                        : Optional.empty(),
                unused -> {
                    factoryCalls[0]++;
                    return Optional.of(testAction());
                },
                actions);

        SkillNodeDirective result = handler.begin(context(
                operation("harvest_logs"), 10L, 0L));

        Assertions.assertAll(
                () -> Assertions.assertEquals(SkillNodeDirective.Kind.FAIL,
                        result.kind()),
                () -> Assertions.assertEquals(SkillFailureCode.WORLD_CHANGED,
                        result.failureCode().orElseThrow()),
                () -> Assertions.assertEquals(
                        "生产节点没有当前 tick 的完整世界与菜单观察",
                        result.safeSummary()),
                () -> Assertions.assertEquals(2, observations[0]),
                () -> Assertions.assertEquals(0, factoryCalls[0]),
                () -> Assertions.assertNull(actions.envelope));
    }

    @Test
    void acceptsOnlyTheExactWhitelistedRecipeActionAndItsReviewedBatchCount() {
        MinecraftProductionSkillNodeHandler accepted = handler(
                ignored -> Optional.of(recipePreflight(10L,
                        ProductionLedger.of(ProductionMaterials.OAK_LOG, 4))),
                unused -> Optional.of(recipeAction(1)));
        MinecraftProductionSkillNodeHandler wrongBatch = handler(
                ignored -> Optional.of(recipePreflight(10L,
                        ProductionLedger.of(ProductionMaterials.OAK_LOG, 4))),
                unused -> Optional.of(recipeAction(4)));

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
    void acceptsOnlyTheExactReviewedWorkstationPlaceBlockAction() {
        MinecraftProductionSkillNodeHandler accepted = handler(
                ignored -> Optional.of(placementPreflight(10L,
                        ProductionLedger.of(
                                ProductionMaterials.CRAFTING_TABLE, 1))),
                unused -> Optional.of(craftingTablePlacementAction()));
        MinecraftProductionSkillNodeHandler wrongState = handler(
                ignored -> Optional.of(placementPreflight(10L,
                        ProductionLedger.of(
                                ProductionMaterials.CRAFTING_TABLE, 1))),
                unused -> Optional.of(furnacePlacementWithCraftingTable()));

        SkillNodeDirective acceptedDirective = accepted.begin(context(
                operation("place_crafting_table"), 10L, 0L));
        SkillNodeDirective rejectedDirective = wrongState.begin(context(
                operation("place_crafting_table"), 10L, 0L));

        Assertions.assertAll(
                () -> Assertions.assertEquals(SkillNodeDirective.Kind.WAIT_ACTION,
                        acceptedDirective.kind()),
                () -> Assertions.assertEquals(SkillNodeDirective.Kind.FAIL,
                        rejectedDirective.kind()),
                () -> Assertions.assertEquals(SkillFailureCode.ACTION_REJECTED,
                        rejectedDirective.failureCode().orElseThrow()));
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
        actions.completeSuccess(resourceDropEvidence(new UUID(0L, 43L)));
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

    @Test
    void redirectsFreshUuidDropWhenItMovesBeforePickup() {
        SequencedGateway actions = new SequencedGateway();
        RecordingDropNavigation navigation = new RecordingDropNavigation();
        List<SkillSignal> signals = new ArrayList<>();
        UUID dropId = new UUID(0L, 44L);
        MinecraftProductionSkillNodeHandler.ResourceDropCandidate initialDrop =
                new MinecraftProductionSkillNodeHandler.ResourceDropCandidate(
                        dropId, "minecraft:overworld",
                        new GridPoint(4, 1, 4), "minecraft:oak_log", 1);
        MinecraftProductionSkillNodeHandler.ResourceDropCandidate movedDrop =
                new MinecraftProductionSkillNodeHandler.ResourceDropCandidate(
                        dropId, "minecraft:overworld",
                        new GridPoint(5, 1, 4), "minecraft:oak_log", 1);
        int[] dropObservations = {0};
        MinecraftProductionSkillNodeHandler.ResourceDropCandidate[]
                pickupCandidate = {null};
        boolean[] pickedUp = {false};
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
                                return Optional.of(acquisitionCompletion(
                                        context.currentTick(), pickedUp[0]
                                                ? ProductionLedger.of(
                                                        ProductionMaterials
                                                                .OAK_LOG,
                                                        1)
                                                : ProductionLedger.empty()));
                            }
                        },
                        taskSensors(),
                        new MinecraftProductionSkillNodeHandler
                                .ResourceAcquisitionActionPort() {
                            @Override
                            public Optional<MinecraftProductionSkillNodeHandler
                                    .ProductionAction> plan(
                                            MinecraftProductionSkillNodeHandler
                                                    .ExecutionTicket ticket,
                                            TaskSensorService suppliedSensors) {
                                return Optional.of(testAction());
                            }

                            @Override
                            public MinecraftProductionSkillNodeHandler
                                    .ResourceDropObservation observeResourceDrop(
                                    MinecraftProductionSkillNodeHandler
                                                    .ExecutionTicket ticket,
                                            SkillNodeContext context) {
                                return MinecraftProductionSkillNodeHandler
                                        .ResourceDropObservation.found(
                                                dropObservations[0]++ == 0
                                                        ? initialDrop
                                                        : movedDrop);
                            }

                            @Override
                            public Optional<MinecraftProductionSkillNodeHandler
                                    .ProductionAction> planResourceDropPickup(
                                            MinecraftProductionSkillNodeHandler
                                                    .ExecutionTicket ticket,
                                            SkillNodeContext context,
                                            MinecraftProductionSkillNodeHandler
                                                    .ResourceDropCandidate candidate) {
                                pickupCandidate[0] = candidate;
                                return candidate.equals(movedDrop)
                                        ? Optional.of(pickupAction(dropId))
                                        : Optional.empty();
                            }
                        },
                        ticket -> Optional.empty(),
                        navigation,
                        actions,
                        signal -> {
                            signals.add(signal);
                            return SkillSignalInbox.OfferStatus.ENQUEUED;
                        });

        String operation = operation("harvest_logs");
        Assertions.assertEquals(SkillNodeDirective.Kind.WAIT_ACTION,
                handler.begin(context(operation, 10L, 0L)).kind());
        actions.completeNextSuccess(resourceDropEvidence(dropId));

        Assertions.assertEquals(SkillNodeDirective.Kind.CONTINUE,
                handler.signal(context(operation, 11L, 1L), signals.get(0))
                        .kind());
        Assertions.assertEquals(SkillNodeDirective.Kind.WAIT_NAVIGATION,
                handler.tick(context(operation, 12L, 2L)).kind());
        Assertions.assertAll(
                () -> Assertions.assertInstanceOf(
                        NavigationGoal.ExactPosition.class,
                        navigation.request.goal()),
                () -> Assertions.assertEquals(
                        NavigationArrivalRequirement.GROUNDED_GRID_CELL,
                        navigation.request.arrivalRequirement()),
                () -> Assertions.assertEquals(initialDrop.position(),
                        ((NavigationGoal.ExactPosition) navigation.request.goal())
                                .center()));

        navigation.succeed(13L);
        SkillSignal navigationSignal = signals.get(1);
        Assertions.assertEquals(SkillNodeDirective.Kind.CONTINUE,
                handler.signal(context(operation, 13L, 3L), navigationSignal)
                        .kind());
        Assertions.assertEquals(SkillNodeDirective.Kind.WAIT_NAVIGATION,
                handler.tick(context(operation, 14L, 4L)).kind());
        Assertions.assertAll(
                () -> Assertions.assertEquals(2, navigation.requests.size()),
                () -> Assertions.assertEquals(1, actions.submitted.size()),
                () -> Assertions.assertEquals(movedDrop.position(),
                        ((NavigationGoal.ExactPosition) navigation.request.goal())
                                .center()),
                () -> Assertions.assertNull(pickupCandidate[0]));

        navigation.succeed(15L);
        Assertions.assertEquals(SkillNodeDirective.Kind.CONTINUE,
                handler.signal(context(operation, 15L, 5L), signals.get(2))
                        .kind());
        Assertions.assertEquals(SkillNodeDirective.Kind.WAIT_ACTION,
                handler.tick(context(operation, 16L, 6L)).kind());
        WorldInteractionAction pickupWorldAction =
                (WorldInteractionAction) actions.submitted.get(1).action();
        Assertions.assertInstanceOf(WorldInteractionActionSpec.PickupWait.class,
                pickupWorldAction.spec());
        WorldInteractionActionSpec.PickupWait pickup =
                (WorldInteractionActionSpec.PickupWait) pickupWorldAction.spec();
        Assertions.assertAll(
                () -> Assertions.assertEquals(Optional.of(dropId),
                        pickup.expectedItemEntityId()),
                () -> Assertions.assertEquals(movedDrop, pickupCandidate[0]),
                () -> Assertions.assertEquals(3, dropObservations[0]));

        pickedUp[0] = true;
        actions.completeNextSuccess(List.of(new ActionEvidence("entity.id",
                dropId.toString())));
        Assertions.assertEquals(SkillNodeDirective.Kind.COMPLETE,
                handler.signal(context(operation, 17L, 7L), signals.get(3))
                        .kind());
    }

    @Test
    void rejectsWhenFreshDropPickupPortCannotFreezeUuidPickupWait() {
        SequencedGateway actions = new SequencedGateway();
        RecordingDropNavigation navigation = new RecordingDropNavigation();
        List<SkillSignal> signals = new ArrayList<>();
        UUID dropId = new UUID(0L, 45L);
        MinecraftProductionSkillNodeHandler.ResourceDropCandidate drop =
                new MinecraftProductionSkillNodeHandler.ResourceDropCandidate(
                        dropId, "minecraft:overworld",
                        new GridPoint(4, 1, 4), "minecraft:oak_log", 1);
        int[] pickupPlanningCalls = {0};
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
                                return Optional.of(acquisitionCompletion(
                                        context.currentTick(),
                                        ProductionLedger.empty()));
                            }
                        },
                        taskSensors(),
                        new MinecraftProductionSkillNodeHandler
                                .ResourceAcquisitionActionPort() {
                            @Override
                            public Optional<MinecraftProductionSkillNodeHandler
                                    .ProductionAction> plan(
                                            MinecraftProductionSkillNodeHandler
                                                    .ExecutionTicket ticket,
                                            TaskSensorService suppliedSensors) {
                                return Optional.of(testAction());
                            }

                            @Override
                            public MinecraftProductionSkillNodeHandler
                                    .ResourceDropObservation observeResourceDrop(
                                            MinecraftProductionSkillNodeHandler
                                                    .ExecutionTicket ticket,
                                            SkillNodeContext context) {
                                return MinecraftProductionSkillNodeHandler
                                        .ResourceDropObservation.found(drop);
                            }

                            @Override
                            public Optional<MinecraftProductionSkillNodeHandler
                                    .ProductionAction> planResourceDropPickup(
                                            MinecraftProductionSkillNodeHandler
                                                    .ExecutionTicket ticket,
                                            SkillNodeContext context,
                                            MinecraftProductionSkillNodeHandler
                                                    .ResourceDropCandidate candidate) {
                                pickupPlanningCalls[0]++;
                                return Optional.empty();
                            }
                        },
                        ticket -> Optional.empty(),
                        navigation,
                        actions,
                        signal -> {
                            signals.add(signal);
                            return SkillSignalInbox.OfferStatus.ENQUEUED;
                        });

        String operation = operation("harvest_logs");
        Assertions.assertEquals(SkillNodeDirective.Kind.WAIT_ACTION,
                handler.begin(context(operation, 10L, 0L)).kind());
        actions.completeNextSuccess(resourceDropEvidence(dropId));
        Assertions.assertEquals(SkillNodeDirective.Kind.CONTINUE,
                handler.signal(context(operation, 11L, 1L), signals.get(0))
                        .kind());
        Assertions.assertEquals(SkillNodeDirective.Kind.WAIT_NAVIGATION,
                handler.tick(context(operation, 12L, 2L)).kind());

        navigation.succeed(13L);
        Assertions.assertEquals(SkillNodeDirective.Kind.CONTINUE,
                handler.signal(context(operation, 13L, 3L), signals.get(1))
                        .kind());
        SkillNodeDirective rejected = handler.tick(context(operation, 14L, 4L));

        Assertions.assertAll(
                () -> Assertions.assertEquals(SkillNodeDirective.Kind.FAIL,
                        rejected.kind()),
                () -> Assertions.assertEquals(Optional.of(
                        SkillFailureCode.ACTION_REJECTED),
                        rejected.failureCode()),
                () -> Assertions.assertEquals(
                        "资源掉落实体拾取端口未能冻结当前 tick 的 UUID PickupWait 动作",
                        rejected.safeSummary()),
                () -> Assertions.assertEquals(1, pickupPlanningCalls[0]),
                () -> Assertions.assertEquals(1, actions.submitted.size()));
    }

    private static MinecraftProductionSkillNodeHandler handler(
            java.util.function.Function<SkillNodeContext, Optional<
                    MinecraftProductionSkillNodeHandler.PreflightObservation>>
                    preflight,
            java.util.function.Function<MinecraftProductionSkillNodeHandler
                    .ExecutionTicket, Optional<MinecraftProductionSkillNodeHandler
                    .ProductionAction>> menuActions) {
        return handler(preflight, menuActions, new RecordingGateway());
    }

    private static MinecraftProductionSkillNodeHandler handler(
            java.util.function.Function<SkillNodeContext, Optional<
                    MinecraftProductionSkillNodeHandler.PreflightObservation>>
                    preflight,
            java.util.function.Function<MinecraftProductionSkillNodeHandler
                    .ExecutionTicket, Optional<MinecraftProductionSkillNodeHandler
                    .ProductionAction>> menuActions,
            ActionBackedSkillNodeHandler.ActionGateway actions) {
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
                actions,
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

    private static MinecraftProductionSkillNodeHandler.PreflightObservation
            placementPreflight(long tick, ProductionLedger ledger) {
        return new MinecraftProductionSkillNodeHandler.PreflightObservation(
                new ProductionPreconditionSnapshot(
                        1L,
                        ledger,
                        Optional.empty(),
                        true,
                        Optional.empty()),
                tick,
                "workstation-placement",
                "native-inventory");
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
            pickupAction(UUID itemEntityId) {
        return new MinecraftProductionSkillNodeHandler.ProductionAction(
                new WorldInteractionAction(
                        new WorldInteractionActionSpec.PickupWait(
                                80, Optional.of(itemEntityId))),
                81,
                "等待测试 UUID 绑定掉落实体进入背包");
    }

    private static List<ActionEvidence> resourceDropEvidence(UUID itemEntityId) {
        return List.of(
                new ActionEvidence("block.after", "minecraft:air"),
                new ActionEvidence("block.drop.entity.id",
                        itemEntityId.toString()),
                new ActionEvidence("block.drop.item", "minecraft:oak_log"),
                new ActionEvidence("block.drop.count", "1"));
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

    private static MinecraftProductionSkillNodeHandler.ProductionAction
            craftingTablePlacementAction() {
        return placementAction(new BlockStateFingerprint(
                new ResourceId("minecraft:crafting_table"), Map.of()));
    }

    private static MinecraftProductionSkillNodeHandler.ProductionAction
            furnacePlacementWithCraftingTable() {
        return placementAction(new BlockStateFingerprint(
                new ResourceId("minecraft:furnace"),
                Map.of("facing", "north", "lit", "false")));
    }

    private static MinecraftProductionSkillNodeHandler.ProductionAction
            placementAction(BlockStateFingerprint placedState) {
        BlockTargetFingerprint anchor = new BlockTargetFingerprint(
                new ResourceId("minecraft:overworld"),
                new BlockCoordinates(4, 64, 4),
                new BlockStateFingerprint(new ResourceId("minecraft:stone"),
                        Map.of()));
        BlockTargetFingerprint placed = new BlockTargetFingerprint(
                new ResourceId("minecraft:overworld"),
                new BlockCoordinates(4, 65, 4),
                placedState);
        ItemStackFingerprint held = ItemStackFingerprint.of(
                new ResourceId("minecraft:crafting_table"),
                1,
                0,
                "a".repeat(64));
        return new MinecraftProductionSkillNodeHandler.ProductionAction(
                new WorldInteractionAction(
                        new WorldInteractionActionSpec.PlaceBlock(
                                new BlockHitTarget(
                                        anchor,
                                        BlockHitTarget.Face.UP,
                                        0.5D,
                                        1.0D,
                                        0.5D,
                                        false),
                                placed,
                                held)),
                160,
                "等待测试原版工作站放置动作");
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
            completeSuccess(List.of(new ActionEvidence("production.receipt",
                    "ok")));
        }

        private void completeSuccess(List<ActionEvidence> evidence) {
            Assertions.assertNotNull(envelope,
                    "handler must submit a frozen action before completion");
            completion.complete(new ActionOutcome(
                    envelope.actionId(),
                    ActionState.SUCCEEDED,
                    ActionFailureCode.NONE,
                    10L,
                    10L,
                    evidence,
                    "测试原版动作已完成"));
        }
    }

    private static final class SequencedGateway
            implements ActionBackedSkillNodeHandler.ActionGateway {
        private final List<ActionEnvelope> submitted = new ArrayList<>();
        private final List<CompletableFuture<ActionOutcome>> completions =
                new ArrayList<>();
        private int nextCompletion;

        @Override
        public ActionMailbox.Submission submit(
                ActionEnvelope actionEnvelope, ActionPriority priority) {
            CompletableFuture<ActionOutcome> completion =
                    new CompletableFuture<>();
            submitted.add(actionEnvelope);
            completions.add(completion);
            return new ActionMailbox.Submission(
                    ActionMailbox.SubmissionStatus.ENQUEUED,
                    Optional.<CompletionStage<ActionOutcome>>of(completion));
        }

        @Override
        public void cancel(
                UUID botId,
                UUID actionId,
                ActionCancellationReason reason) {
            // The focused happy path never cancels an action.
        }

        private void completeNextSuccess(List<ActionEvidence> evidence) {
            ActionEnvelope envelope = submitted.get(nextCompletion);
            completions.get(nextCompletion++).complete(new ActionOutcome(
                    envelope.actionId(), ActionState.SUCCEEDED,
                    ActionFailureCode.NONE, 10L, 10L, evidence,
                    "测试原版动作已完成"));
        }
    }

    private static final class RecordingDropNavigation
            implements MinecraftProductionSkillNodeHandler
                    .ResourceDropNavigationGateway {
        private final List<CompletableFuture<NavigationOutcome>> completions =
                new ArrayList<>();
        private final List<NavigationRequest> requests = new ArrayList<>();
        private NavigationRequest request;
        private int nextCompletion;

        @Override
        public NavigationSubmission submit(
                NavigationRequest navigationRequest, long currentTick) {
            request = navigationRequest;
            CompletableFuture<NavigationOutcome> completion =
                    new CompletableFuture<>();
            requests.add(navigationRequest);
            completions.add(completion);
            return NavigationSubmission.enqueued(completion);
        }

        @Override
        public boolean cancel(
                UUID navigationId, long currentTick, String reason) {
            return false;
        }

        private void succeed(long tick) {
            NavigationRequest nextRequest = requests.get(nextCompletion);
            NavigationGoal.ExactPosition goal =
                    (NavigationGoal.ExactPosition) nextRequest.goal();
            completions.get(nextCompletion++).complete(new NavigationOutcome(
                    nextRequest.navigationId(), NavigationState.SUCCEEDED,
                    NavigationFailure.NONE, goal.center(), tick, tick,
                    0, 0, 0, 0L, 0, 0, "测试掉落实体导航成功"));
        }
    }
}
