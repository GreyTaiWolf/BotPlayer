package io.github.greytaiwolf.botplayer.technique.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.greytaiwolf.botplayer.action.ActionCancellationReason;
import io.github.greytaiwolf.botplayer.action.ActionCancellationReceipt;
import io.github.greytaiwolf.botplayer.action.ActionEvidence;
import io.github.greytaiwolf.botplayer.action.ActionEnvelope;
import io.github.greytaiwolf.botplayer.action.ActionFailureCode;
import io.github.greytaiwolf.botplayer.action.ActionMailbox;
import io.github.greytaiwolf.botplayer.action.ActionOutcome;
import io.github.greytaiwolf.botplayer.action.ActionPriority;
import io.github.greytaiwolf.botplayer.action.ActionRequest;
import io.github.greytaiwolf.botplayer.action.ActionState;
import io.github.greytaiwolf.botplayer.action.WaitAction;
import io.github.greytaiwolf.botplayer.action.WorldInteractionAction;
import io.github.greytaiwolf.botplayer.action.interaction.EntityTargetFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import io.github.greytaiwolf.botplayer.action.interaction.WorldInteractionActionSpec;
import io.github.greytaiwolf.botplayer.navigation.GridPoint;
import io.github.greytaiwolf.botplayer.safety.HazardAssessment;
import io.github.greytaiwolf.botplayer.safety.HazardSeverity;
import io.github.greytaiwolf.botplayer.safety.HazardType;
import io.github.greytaiwolf.botplayer.safety.SafetyFrame;
import io.github.greytaiwolf.botplayer.safety.SafetyHandoffDecision;
import io.github.greytaiwolf.botplayer.safety.SafetyHandoffRequest;
import io.github.greytaiwolf.botplayer.safety.SafetyRetreat;
import io.github.greytaiwolf.botplayer.safety.ThreatSummary;
import io.github.greytaiwolf.botplayer.skill.builtin.defense.DefenseActionKind;
import io.github.greytaiwolf.botplayer.skill.builtin.defense.DefenseObservation;
import io.github.greytaiwolf.botplayer.skill.builtin.defense.DefensePolicy;
import io.github.greytaiwolf.botplayer.skill.builtin.defense.DefenseTarget;
import io.github.greytaiwolf.botplayer.skill.builtin.defense.DefenseTargetClass;
import io.github.greytaiwolf.botplayer.skill.runtime.SelfDefenseSkillService;
import io.github.greytaiwolf.botplayer.skill.runtime.SelfDefenseSkillService.AuthorizedActionDispatch;
import io.github.greytaiwolf.botplayer.technique.combat.SingleMeleeStrikeTechnique;
import io.github.greytaiwolf.botplayer.technique.core.TechniqueFailureCode;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueChildDispatcher;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueChildTicket;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueState;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

class SelfDefenseTechniqueBridgeTest {
    private static final UUID BOT = UUID.fromString(
            "11111111-1111-1111-1111-111111111111");
    private static final UUID TARGET = UUID.fromString(
            "44444444-4444-4444-4444-444444444444");
    private static final UUID OTHER_TARGET = UUID.fromString(
            "55555555-5555-5555-5555-555555555555");
    private static final UUID INCIDENT = UUID.fromString(
            "22222222-2222-2222-2222-222222222222");
    private static final SafetyRetreat RETREAT = new SafetyRetreat(
            new GridPoint(-1, 64, 0), -1.0F, 0.0F, 3);

    @Test
    void serviceCreatedAuthorizationBindsOneExactAttackAndSignalsBeforeNextDecision() {
        Harness harness = new Harness();
        harness.start();

        UUID actionId = harness.singleActionId();
        ActionEnvelope envelope = harness.gateway.submitted.get(actionId);
        assertEquals(actionId, envelope.actionId());
        assertEquals(BOT, envelope.botId());
        assertEquals(1L, envelope.botGeneration());
        assertEquals(ActionPriority.EMERGENCY,
                harness.gateway.priorities.get(actionId));
        assertTrue(envelope.action() instanceof WorldInteractionAction);
        WorldInteractionActionSpec.AttackEntity attack =
                (WorldInteractionActionSpec.AttackEntity)
                        ((WorldInteractionAction) envelope.action()).spec();
        assertEquals(TARGET, attack.target().entityId());
        assertEquals(SingleMeleeStrikeTechnique.ID,
                harness.coordinator.inspect(BOT).orElseThrow().techniqueId());
        assertEquals(TechniqueState.WAITING_CHILDREN,
                harness.coordinator.inspect(BOT).orElseThrow().state());

        harness.coordinator.finishTick(0L);
        harness.tick.value = 1L;
        harness.coordinator.tick(1L);
        harness.gateway.complete(success(actionId, 1L));
        harness.coordinator.drainCompletedChildren(1L);
        assertEquals(TechniqueState.RUNNING,
                harness.coordinator.inspect(BOT).orElseThrow().state());

        harness.service.tick(1L);
        assertEquals(SelfDefenseSkillService.RunStatus.COMPLETED,
                harness.service.latestView(BOT).orElseThrow().status());
        harness.coordinator.finishTick(1L);
        harness.tick.value = 2L;
        harness.coordinator.tick(2L);
        assertTrue(harness.coordinator.inspect(BOT).isEmpty());
        assertEquals(TechniqueFailureCode.NONE,
                harness.coordinator.latestOutcome(BOT).orElseThrow().failureCode());
    }

    @Test
    void publicIngressAcceptsOnlyTheOpaqueServiceCapability() {
        Method[] publicSubmitMethods = Arrays.stream(
                        SelfDefenseTechniqueBridge.class.getMethods())
                .filter(method -> method.getName().equals("submit"))
                .toArray(Method[]::new);
        assertTrue(Arrays.stream(publicSubmitMethods).anyMatch(method ->
                Arrays.equals(method.getParameterTypes(),
                        new Class<?>[] {AuthorizedActionDispatch.class})));
        Method submitPort = Arrays.stream(
                        SelfDefenseSkillService.ActionSubmitter.class.getMethods())
                .filter(method -> method.getName().equals("submit"))
                .findFirst()
                .orElseThrow();
        assertTrue(Arrays.equals(submitPort.getParameterTypes(),
                new Class<?>[] {AuthorizedActionDispatch.class}));
        assertFalse(Arrays.stream(publicSubmitMethods).anyMatch(method ->
                method.getParameterCount() == 1
                        && method.getParameterTypes()[0].getName().endsWith(
                                "$ActionDispatch")));
        assertFalse(Arrays.stream(publicSubmitMethods).anyMatch(method ->
                Arrays.equals(method.getParameterTypes(),
                        new Class<?>[] {TechniqueChildTicket.class})));
        assertFalse(TechniqueChildDispatcher.class.isAssignableFrom(
                SelfDefenseTechniqueBridge.class));
        assertFalse(Arrays.stream(SelfDefenseTechniqueBridge.class
                .getConstructors()).anyMatch(constructor -> Arrays.equals(
                        constructor.getParameterTypes(), new Class<?>[] {
                                SelfDefenseTechniqueBridge.ActionGateway.class,
                                LongSupplier.class})));
        for (Constructor<?> constructor :
                AuthorizedActionDispatch.class.getDeclaredConstructors()) {
            assertTrue(Modifier.isPrivate(constructor.getModifiers()),
                    "authorization construction must remain service-only");
        }
        Class<?> rawDispatch = Arrays.stream(
                        SelfDefenseSkillService.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals("ActionDispatch"))
                .findFirst()
                .orElseThrow();
        assertFalse(Modifier.isPublic(rawDispatch.getModifiers()));
        assertFalse(Arrays.stream(rawDispatch.getDeclaredConstructors())
                .anyMatch(constructor -> Modifier.isPublic(
                        constructor.getModifiers())));
        assertFalse(Arrays.stream(AuthorizedActionDispatch.class.getMethods())
                .anyMatch(method -> method.getReturnType() == rawDispatch));
    }

    @Test
    void changedMeleeTargetFailsClosedBeforeTheBridgeSubmitsAnAction() {
        Harness harness = new Harness(OTHER_TARGET);

        harness.start();

        assertTrue(harness.gateway.submitted.isEmpty());
        assertTrue(harness.gateway.cancellations.isEmpty());
        assertTrue(harness.coordinator.isGenerationSafe(BOT, 1L));
        assertEquals(SelfDefenseSkillService.RunStatus.FAILED,
                harness.service.latestView(BOT).orElseThrow().status());
        assertEquals(SelfDefenseSkillService.Failure.ACTION_SUBMISSION_REJECTED,
                harness.service.latestView(BOT).orElseThrow().failure()
                        .orElseThrow());
        assertEquals(SelfDefenseSkillService.AuthorizationRevocation.SUBMISSION_REJECTED,
                harness.submittedAuthorization.revocation().orElseThrow());
    }

    @Test
    void generationCloseRetractsALateSuccessfulServiceRoutedChild() {
        Harness harness = new Harness();
        harness.start();
        harness.coordinator.finishTick(0L);

        harness.tick.value = 1L;
        harness.coordinator.closeGeneration(BOT, 1L, 1L);
        assertTrue(harness.service.closeGeneration(BOT, 1L, 1L));
        assertEquals(ActionCancellationReason.LIFECYCLE,
                harness.gateway.cancellations.get(0).reason());

        harness.gateway.complete(success(harness.singleActionId(), 1L));
        harness.coordinator.drainCompletedChildren(1L);
        assertEquals(ActionState.CANCELLED,
                harness.submittedCompletion.toCompletableFuture().join().state());
        assertEquals(TechniqueFailureCode.GENERATION_CHANGED,
                harness.coordinator.latestOutcome(BOT).orElseThrow().failureCode());
        assertEquals(SelfDefenseSkillService.RunStatus.CLOSED,
                harness.service.latestView(BOT).orElseThrow().status());
        assertEquals(SelfDefenseSkillService.AuthorizationRevocation.GENERATION_CLOSED,
                harness.submittedAuthorization.revocation().orElseThrow());
    }

    @Test
    void gatewayRejectionRevokesTheClaimedServiceCapabilityAndFailsTheRun() {
        Harness harness = new Harness();
        harness.gateway.submissionStatus = ActionMailbox.SubmissionStatus.MAILBOX_FULL;

        harness.start();

        assertTrue(harness.gateway.submitted.isEmpty());
        assertEquals(SelfDefenseSkillService.RunStatus.FAILED,
                harness.service.latestView(BOT).orElseThrow().status());
        assertEquals(SelfDefenseSkillService.Failure.ACTION_SUBMISSION_REJECTED,
                harness.service.latestView(BOT).orElseThrow().failure()
                        .orElseThrow());
        assertEquals(SelfDefenseSkillService.AuthorizationRevocation.SUBMISSION_REJECTED,
                harness.submittedAuthorization.revocation().orElseThrow());
        assertTrue(harness.submittedAuthorization.claim().isEmpty());
    }

    @Test
    void safetyPreemptionRetractsALateSuccessWithoutDuplicateCancellation() {
        Harness harness = new Harness();
        harness.start();
        harness.coordinator.finishTick(0L);

        harness.tick.value = 1L;
        assertTrue(harness.service.preempt(BOT, 1L, 1L));
        assertTrue(harness.bridge.preemptForSafety(BOT, 1L, 1L));
        assertEquals(1, harness.gateway.cancellations.size());
        assertEquals(ActionCancellationReason.REQUESTED,
                harness.gateway.cancellations.get(0).reason());

        harness.gateway.complete(success(harness.singleActionId(), 1L));
        harness.coordinator.drainCompletedChildren(1L);

        assertEquals(ActionState.PREEMPTED,
                harness.submittedCompletion.toCompletableFuture().join().state());
        assertEquals(TechniqueFailureCode.SAFETY_PREEMPTED,
                harness.coordinator.latestOutcome(BOT).orElseThrow().failureCode());
        assertEquals(SelfDefenseSkillService.RunStatus.PREEMPTED,
                harness.service.latestView(BOT).orElseThrow().status());
    }

    @Test
    void bridgeShutdownRetractsALateSuccessAfterTheTickBoundary() {
        Harness harness = new Harness();
        harness.start();
        harness.coordinator.finishTick(0L);

        harness.coordinator.shutdown(0L);

        assertEquals(ActionCancellationReason.RUNTIME_SHUTDOWN,
                harness.gateway.cancellations.get(0).reason());
        harness.gateway.complete(success(harness.singleActionId(), 0L));
        harness.coordinator.drainCompletedChildren(0L);

        assertEquals(ActionState.CANCELLED,
                harness.submittedCompletion.toCompletableFuture().join().state());
        assertEquals(TechniqueFailureCode.ACTION_CLEANUP_UNSAFE,
                harness.coordinator.latestOutcome(BOT).orElseThrow().failureCode());
        assertFalse(harness.coordinator.isGenerationSafe(BOT, 1L));
    }

    @Test
    void reentrantSafetyPreemptionDuringGatewaySubmissionCancelsTheExactNewAction() {
        Harness harness = new Harness();
        harness.gateway.beforeSubmit = () -> {
            assertTrue(harness.service.preempt(BOT, 1L, harness.tick.value));
            harness.bridge.preemptForSafety(BOT, 1L, harness.tick.value);
        };

        harness.start();

        UUID actionId = harness.singleActionId();
        assertEquals(1, harness.gateway.cancellations.size());
        assertEquals(actionId, harness.gateway.cancellations.get(0).actionId());
        assertEquals(ActionCancellationReason.REQUESTED,
                harness.gateway.cancellations.get(0).reason());
        assertEquals(SelfDefenseSkillService.RunStatus.PREEMPTED,
                harness.service.latestView(BOT).orElseThrow().status());
        harness.gateway.complete(success(actionId, 0L));
        harness.coordinator.drainCompletedChildren(0L);
        assertFalse(harness.service.latestView(BOT).orElseThrow().status()
                == SelfDefenseSkillService.RunStatus.COMPLETED);
    }

    @Test
    void reentrantGenerationCloseDuringGatewaySubmissionCancelsTheExactNewAction() {
        Harness harness = new Harness();
        harness.gateway.beforeSubmit = () -> {
            harness.coordinator.closeGeneration(BOT, 1L, harness.tick.value);
            assertTrue(harness.service.closeGeneration(BOT, 1L,
                    harness.tick.value));
        };

        harness.start();

        UUID actionId = harness.singleActionId();
        assertEquals(1, harness.gateway.cancellations.size());
        assertEquals(actionId, harness.gateway.cancellations.get(0).actionId());
        assertEquals(ActionCancellationReason.LIFECYCLE,
                harness.gateway.cancellations.get(0).reason());
        assertEquals(SelfDefenseSkillService.RunStatus.CLOSED,
                harness.service.latestView(BOT).orElseThrow().status());
        harness.gateway.complete(success(actionId, 0L));
        harness.coordinator.drainCompletedChildren(0L);
        assertFalse(harness.service.latestView(BOT).orElseThrow().status()
                == SelfDefenseSkillService.RunStatus.COMPLETED);
    }

    @Test
    void unprovenReentrantCancellationFailsTheServiceReceiptClosed() {
        Harness harness = new Harness();
        harness.gateway.cancellationDisposition =
                ActionCancellationReceipt.Disposition.UNKNOWN;
        harness.gateway.beforeSubmit = () -> assertTrue(
                harness.service.preempt(BOT, 1L, harness.tick.value));

        harness.start();

        ActionOutcome outcome = harness.submittedCompletion
                .toCompletableFuture().join();
        assertEquals(ActionState.FAILED, outcome.state());
        assertEquals(ActionFailureCode.UNSAFE_CONTROL_STATE,
                outcome.failureCode());
        assertEquals(SelfDefenseSkillService.RunStatus.FAILED,
                harness.service.latestView(BOT).orElseThrow().status());
        assertEquals(SelfDefenseSkillService.Failure.UNSAFE_CONTROL_STATE,
                harness.service.latestView(BOT).orElseThrow().failure()
                        .orElseThrow());
        assertFalse(harness.coordinator.isGenerationSafe(BOT, 1L));
    }

    @Test
    void safeLookingMismatchedCancellationReceiptFailsTheServiceRunClosed() {
        Harness harness = new Harness();
        harness.gateway.returnMismatchedSafeReceipt = true;
        harness.gateway.beforeSubmit = () -> assertTrue(
                harness.service.preempt(BOT, 1L, harness.tick.value));

        harness.start();

        ActionOutcome outcome = harness.submittedCompletion
                .toCompletableFuture().join();
        assertEquals(ActionState.FAILED, outcome.state());
        assertEquals(ActionFailureCode.UNSAFE_CONTROL_STATE,
                outcome.failureCode());
        assertEquals(SelfDefenseSkillService.RunStatus.FAILED,
                harness.service.latestView(BOT).orElseThrow().status());
        assertEquals(SelfDefenseSkillService.Failure.UNSAFE_CONTROL_STATE,
                harness.service.latestView(BOT).orElseThrow().failure()
                        .orElseThrow());
        assertFalse(harness.coordinator.isGenerationSafe(BOT, 1L));
    }

    private static ActionOutcome success(UUID actionId, long tick) {
        return new ActionOutcome(actionId, ActionState.SUCCEEDED,
                ActionFailureCode.NONE, 0L, tick, List.of(
                        new ActionEvidence("entity.id", TARGET.toString()),
                        new ActionEvidence("entity.removed", "true")),
                "Action succeeded");
    }

    private static SafetyHandoffRequest request(long tick) {
        return new SafetyHandoffRequest(INCIDENT, BOT, 1L, tick,
                new HazardAssessment(HazardType.HOSTILE_TARGETING,
                        HazardSeverity.WARNING, 0, true, false,
                        Optional.of(TARGET), "bridge test"),
                new SafetyFrame(BOT, 1L, tick, "minecraft:overworld",
                        new GridPoint(0, 64, 0), 0.0D, 0.0D, 0.0D, true,
                        0.0F, 20.0F, 20.0F, 0.0F, 0, 0.0D, 0.0D, 0.1D, 3,
                        0.0F, 300, 300, false, false, false, false, 0, false,
                        false, List.of(), false, List.of(new ThreatSummary(
                                TARGET, ThreatSummary.Kind.HOSTILE,
                                new GridPoint(1, 64, 0), 2.0D, 0.0D, true)),
                        false, Optional.of(RETREAT), Optional.empty(), 0.0F));
    }

    private static final class Harness {
        private final MutableTick tick = new MutableTick();
        private final RecordingGateway gateway = new RecordingGateway();
        private final TechniqueLifecycleCoordinator coordinator;
        private final SelfDefenseTechniqueBridge bridge;
        private final SelfDefenseSkillService service;
        private AuthorizedActionDispatch submittedAuthorization;
        private CompletionStage<ActionOutcome> submittedCompletion;

        private Harness() {
            this(TARGET);
        }

        private Harness(UUID actionTarget) {
            coordinator = new TechniqueLifecycleCoordinator();
            bridge = new SelfDefenseTechniqueBridge(coordinator, gateway, tick);
            service = new SelfDefenseSkillService(
                    new SelfDefenseSkillService.TargetResolver() {
                        @Override
                        public Optional<DefenseTarget> resolveTarget(
                                SafetyHandoffRequest ignored) {
                            return Optional.of(target());
                        }

                        @Override
                        public Optional<DefenseObservation> observe(
                                UUID botId, long generation,
                                DefenseTarget ignored) {
                            return Optional.of(new DefenseObservation(20.0D,
                                    20.0D, target(), false, 1,
                                    Optional.of(RETREAT)));
                        }
                    },
                    (instruction, observation) -> Optional.of(
                            instruction.kind() == DefenseActionKind.MELEE_ATTACK
                                    ? attack(actionTarget)
                                    : new WaitAction(1)),
                    authorization -> {
                        submittedAuthorization = authorization;
                        if (authorization.kind()
                                == DefenseActionKind.MELEE_ATTACK) {
                            submittedCompletion = bridge.submit(authorization);
                            return submittedCompletion;
                        }
                        throw new IllegalStateException(
                                "bridge harness did not expect retreat");
                    },
                    authorization -> {
                        if (authorization.kind()
                                == DefenseActionKind.MELEE_ATTACK) {
                            return bridge.cancelDispatch(authorization);
                        }
                        return ActionCancellationReceipt.unsafe(
                                authorization.botId(),
                                authorization.botGeneration(),
                                authorization.actionId(),
                                ActionCancellationReceipt.Disposition.UNKNOWN);
                    },
                    new DefensePolicy(0.35D, 9.0D, 1, 1),
                    new SelfDefenseSkillService.Limits(2, 8, 8, 20, 2));
        }

        private void start() {
            assertEquals(SafetyHandoffDecision.DELEGATED,
                    service.request(request(tick.value)));
            service.tick(tick.value);
        }

        private UUID singleActionId() {
            assertEquals(1, gateway.submitted.size());
            return gateway.submitted.keySet().iterator().next();
        }

        private static DefenseTarget target() {
            return new DefenseTarget(TARGET,
                    DefenseTargetClass.EXPLICIT_HOSTILE, true, 4.0D);
        }

        private static ActionRequest attack(UUID targetId) {
            return new WorldInteractionAction(
                    new WorldInteractionActionSpec.AttackEntity(
                            new EntityTargetFingerprint(
                                    new ResourceId("minecraft:overworld"),
                                    targetId,
                                    new ResourceId("minecraft:zombie"))));
        }
    }

    private static final class MutableTick
            implements java.util.function.LongSupplier {
        private long value;

        @Override
        public long getAsLong() {
            return value;
        }
    }

    private static final class RecordingGateway
            implements SelfDefenseTechniqueBridge.ActionGateway {
        private final Map<UUID, ActionEnvelope> submitted = new LinkedHashMap<>();
        private final Map<UUID, ActionPriority> priorities = new LinkedHashMap<>();
        private final Map<UUID, ActionOutcome> completed = new LinkedHashMap<>();
        private final List<Cancellation> cancellations = new ArrayList<>();
        private Runnable beforeSubmit;
        private ActionMailbox.SubmissionStatus submissionStatus =
                ActionMailbox.SubmissionStatus.ENQUEUED;
        private ActionCancellationReceipt.Disposition cancellationDisposition =
                ActionCancellationReceipt.Disposition.EXACT_QUEUED_RETRACTED;
        private boolean returnMismatchedSafeReceipt;

        @Override
        public ActionMailbox.Submission submit(ActionEnvelope envelope,
                ActionPriority priority) {
            if (submissionStatus != ActionMailbox.SubmissionStatus.ENQUEUED) {
                return new ActionMailbox.Submission(submissionStatus,
                        Optional.empty());
            }
            submitted.put(envelope.actionId(), envelope);
            priorities.put(envelope.actionId(), priority);
            if (beforeSubmit != null) {
                beforeSubmit.run();
            }
            return new ActionMailbox.Submission(
                    ActionMailbox.SubmissionStatus.ENQUEUED,
                    Optional.of(new CompletableFuture<ActionOutcome>()
                            .minimalCompletionStage()));
        }

        @Override
        public ActionCancellationReceipt cancelOrContain(
                ActionEnvelope envelope, ActionCancellationReason reason,
                long currentTick) {
            cancellations.add(new Cancellation(envelope.botId(),
                    envelope.actionId(), reason));
            if (returnMismatchedSafeReceipt) {
                return ActionCancellationReceipt.exactQueuedRetracted(
                        envelope.botId(), envelope.botGeneration() + 1L,
                        envelope.actionId());
            }
            return switch (cancellationDisposition) {
                case EXACT_QUEUED_RETRACTED ->
                        ActionCancellationReceipt.exactQueuedRetracted(
                                envelope.botId(), envelope.botGeneration(),
                                envelope.actionId());
                case FENCED_BEFORE_START ->
                        ActionCancellationReceipt.fencedBeforeStart(
                                envelope.botId(), envelope.botGeneration(),
                                envelope.actionId());
                case STARTED, TERMINAL, ALIAS, UNKNOWN ->
                        ActionCancellationReceipt.unsafe(envelope.botId(),
                                envelope.botGeneration(), envelope.actionId(),
                                cancellationDisposition);
            };
        }

        @Override
        public Optional<ActionOutcome> completedOutcome(UUID botId,
                UUID actionId) {
            return Optional.ofNullable(completed.get(actionId));
        }

        private void complete(ActionOutcome outcome) {
            completed.put(outcome.actionId(), outcome);
        }
    }

    private record Cancellation(UUID botId, UUID actionId,
            ActionCancellationReason reason) {
    }
}
