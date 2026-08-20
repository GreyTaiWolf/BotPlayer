package io.github.greytaiwolf.botplayer.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.greytaiwolf.botplayer.action.ActionBackend;
import io.github.greytaiwolf.botplayer.action.ActionCancellationReason;
import io.github.greytaiwolf.botplayer.action.ActionCancellationReceipt;
import io.github.greytaiwolf.botplayer.action.ActionEnvelope;
import io.github.greytaiwolf.botplayer.action.ActionFailureCode;
import io.github.greytaiwolf.botplayer.action.ActionMailbox;
import io.github.greytaiwolf.botplayer.action.ActionOrigin;
import io.github.greytaiwolf.botplayer.action.ActionOutcome;
import io.github.greytaiwolf.botplayer.action.ActionPriority;
import io.github.greytaiwolf.botplayer.action.ActionRequest;
import io.github.greytaiwolf.botplayer.action.ActionState;
import io.github.greytaiwolf.botplayer.action.BotActionRuntime;
import io.github.greytaiwolf.botplayer.action.MoveInputAction;
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
import io.github.greytaiwolf.botplayer.skill.builtin.defense.DefenseActionRequest;
import io.github.greytaiwolf.botplayer.skill.builtin.defense.DefenseObservation;
import io.github.greytaiwolf.botplayer.skill.builtin.defense.DefensePolicy;
import io.github.greytaiwolf.botplayer.skill.builtin.defense.DefenseTarget;
import io.github.greytaiwolf.botplayer.skill.builtin.defense.DefenseTargetClass;
import io.github.greytaiwolf.botplayer.skill.runtime.SelfDefenseSkillService;
import io.github.greytaiwolf.botplayer.skill.runtime.SelfDefenseSkillService.AuthorizedActionDispatch;
import io.github.greytaiwolf.botplayer.skill.runtime.SelfDefenseSkillService.ClaimedActionDispatch;
import io.github.greytaiwolf.botplayer.technique.bridge.SelfDefenseTechniqueBridge;
import io.github.greytaiwolf.botplayer.technique.bridge.TechniqueLifecycleCoordinator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Pure P5C tests for the narrow lifecycle Action port. They deliberately fill
 * the independent cancellation/completion lanes before a synchronous safety
 * or lifecycle callback revokes the service capability inside Action submit.
 */
class SelfDefenseActionGatewayTest {
    private static final UUID BOT = UUID.fromString(
            "11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_BOT = UUID.fromString(
            "22222222-2222-2222-2222-222222222222");
    private static final UUID TARGET = UUID.fromString(
            "33333333-3333-3333-3333-333333333333");
    private static final UUID INCIDENT = UUID.fromString(
            "44444444-4444-4444-4444-444444444444");
    private static final SafetyRetreat RETREAT = new SafetyRetreat(
            new GridPoint(-1, 64, 0), -1.0F, 0.0F, 3);

    @Test
    void fullOrdinaryCancellationLaneStillSafelyRetractsAnExactQueuedAttack() {
        CountingBackend backend = new CountingBackend();
        BotActionRuntime runtime = runtime(backend);
        SelfDefenseActionGateway physicalGateway = new SelfDefenseActionGateway(
                runtime);
        ReentrantGateway gateway = new ReentrantGateway(physicalGateway);
        TechniqueLifecycleCoordinator coordinator =
                new TechniqueLifecycleCoordinator();
        SelfDefenseTechniqueBridge bridge = new SelfDefenseTechniqueBridge(
                coordinator, gateway, () -> 0L);
        AtomicReference<SelfDefenseSkillService> serviceReference =
                new AtomicReference<>();
        SelfDefenseSkillService service = new SelfDefenseSkillService(
                resolver(target(4.0D)),
                (instruction, observation) -> Optional.of(attack()),
                bridge::submit,
                bridge::cancelDispatch,
                new DefensePolicy(0.35D, 9.0D, 1, 1),
                new SelfDefenseSkillService.Limits(2, 8, 8, 20, 2));
        serviceReference.set(service);
        gateway.afterSubmit = () -> assertTrue(serviceReference.get().preempt(
                BOT, 1L, 0L));

        /* capacity 8 exposes a one-entry cancellation lane; leave it full. */
        ActionMailbox.Cancellation laneOccupier = runtime.cancel(OTHER_BOT,
                UUID.fromString("55555555-5555-5555-5555-555555555555"),
                ActionCancellationReason.REQUESTED);
        assertEquals(ActionMailbox.CancellationStatus.ENQUEUED,
                laneOccupier.status());

        assertEquals(SafetyHandoffDecision.DELEGATED,
                service.request(request(0L)));
        service.tick(0L);

        ActionEnvelope attack = gateway.singleEnvelope();
        ActionMailbox.Submission attackSubmission = gateway.singleSubmission();
        assertTrue(attack.action() instanceof WorldInteractionAction);
        assertTrue(((WorldInteractionAction) attack.action()).spec()
                instanceof WorldInteractionActionSpec.AttackEntity);
        assertEquals(SelfDefenseSkillService.RunStatus.PREEMPTED,
                service.latestView(BOT).orElseThrow().status());
        assertEquals(1, gateway.cancellations.size());
        assertEquals(ActionCancellationReceipt.Disposition
                .EXACT_QUEUED_RETRACTED, gateway.cancellations.getFirst()
                .disposition());
        assertTrue(gateway.cancellations.getFirst().safelyRetracted());

        ActionOutcome outcome = outcome(attackSubmission);
        assertEquals(ActionState.CANCELLED, outcome.state());
        assertNotEquals(ActionState.SUCCEEDED, outcome.state());
        assertEquals(0, backend.startCount(attack.actionId()));
        assertEquals(ActionMailbox.SubmissionStatus.ENQUEUED,
                runtime.submit(
                        afterContainment(attack.actionId(), new WaitAction(1)),
                        ActionPriority.OWNER_TASK).status());
    }

    @Test
    void reentrantGenerationCloseSafelyRetractsAnExactQueuedRetreat()
            throws InterruptedException {
        CountingBackend backend = new CountingBackend();
        BotActionRuntime runtime = runtime(backend);
        SelfDefenseActionGateway gateway = new SelfDefenseActionGateway(runtime);

        /* Fill all seven submission completion permits before the RETREAT. */
        for (int index = 1; index <= 6; index++) {
            ActionMailbox.Submission filler = runtime.submit(new ActionEnvelope(
                    UUID.fromString(String.format(
                            "66666666-6666-6666-6666-%012d", index)),
                    OTHER_BOT, 1L, "filler-" + index, 100L, 10,
                    new WaitAction(10), ActionOrigin.none()),
                    ActionPriority.BACKGROUND);
            assertEquals(ActionMailbox.SubmissionStatus.ENQUEUED,
                    filler.status());
        }
        ActionMailbox.Cancellation cancellationPermitOccupier = runtime.cancel(
                OTHER_BOT,
                UUID.fromString("77777777-7777-7777-7777-777777777777"),
                ActionCancellationReason.REQUESTED);
        assertEquals(ActionMailbox.CancellationStatus.ENQUEUED,
                cancellationPermitOccupier.status());

        AtomicReference<SelfDefenseSkillService> serviceReference =
                new AtomicReference<>();
        AtomicReference<ActionEnvelope> retreatEnvelope = new AtomicReference<>();
        AtomicReference<ActionMailbox.Submission> retreatSubmission =
                new AtomicReference<>();
        List<ActionCancellationReceipt> cancellations =
                new ArrayList<>();
        SelfDefenseSkillService service = new SelfDefenseSkillService(
                resolver(target(16.0D)),
                (instruction, observation) -> Optional.of(retreat(instruction)),
                authorization -> submitRetreatThenClose(
                        authorization, gateway, retreatEnvelope,
                        retreatSubmission, serviceReference),
                authorization -> {
                    ActionCancellationReceipt result =
                            gateway.cancelOrContain(authorization.botId(),
                                    authorization.botGeneration(),
                                    authorization.actionId(),
                                    ActionCancellationReason.LIFECYCLE, 0L);
                    cancellations.add(result);
                    assertTrue(result.safelyRetracted());
                    return result;
                },
                new DefensePolicy(0.35D, 9.0D, 1, 1),
                new SelfDefenseSkillService.Limits(2, 8, 8, 20, 2));
        serviceReference.set(service);

        assertEquals(SafetyHandoffDecision.DELEGATED,
                service.request(request(0L)));
        service.tick(0L);

        ActionEnvelope queuedRetreat = retreatEnvelope.get();
        assertTrue(queuedRetreat.action() instanceof MoveInputAction);
        assertEquals(SelfDefenseSkillService.RunStatus.CLOSED,
                service.latestView(BOT).orElseThrow().status());
        assertEquals(1, cancellations.size());
        assertEquals(ActionCancellationReceipt.Disposition
                .EXACT_QUEUED_RETRACTED, cancellations.getFirst().disposition());

        ActionOutcome outcome = outcome(retreatSubmission.get());
        assertEquals(ActionState.CANCELLED, outcome.state());
        assertNotEquals(ActionState.SUCCEEDED, outcome.state());
        assertEquals(0, backend.startCount(queuedRetreat.actionId()));
        awaitPendingCompletionCount(runtime, 7);
        assertEquals(ActionMailbox.SubmissionStatus.ENQUEUED,
                runtime.submit(
                        afterContainment(queuedRetreat.actionId(),
                                new MoveInputAction(-1.0F, 0.0F, false, true,
                                        false, 1, 1)),
                        ActionPriority.OWNER_TASK).status());
    }

    @Test
    void backendStartSynchronousSafetyPreemptQuarantinesAttackAndFailsServiceView() {
        CountingBackend backend = new CountingBackend();
        BotActionRuntime runtime = runtime(backend);
        SelfDefenseActionGateway physicalGateway = new SelfDefenseActionGateway(
                runtime);
        ReentrantGateway gateway = new ReentrantGateway(physicalGateway);
        TechniqueLifecycleCoordinator coordinator =
                new TechniqueLifecycleCoordinator();
        SelfDefenseTechniqueBridge bridge = new SelfDefenseTechniqueBridge(
                coordinator, gateway, () -> 1L);
        AtomicReference<SelfDefenseSkillService> serviceReference =
                new AtomicReference<>();
        SelfDefenseSkillService service = new SelfDefenseSkillService(
                resolver(target(4.0D)),
                (instruction, observation) -> Optional.of(attack()),
                bridge::submit,
                bridge::cancelDispatch,
                new DefensePolicy(0.35D, 9.0D, 1, 1),
                new SelfDefenseSkillService.Limits(2, 8, 8, 20, 2));
        serviceReference.set(service);
        backend.onStart(() -> assertTrue(serviceReference.get().preempt(
                BOT, 1L, 1L)));

        assertEquals(SafetyHandoffDecision.DELEGATED,
                service.request(request(1L)));
        service.tick(1L);
        ActionEnvelope attack = gateway.singleEnvelope();
        ActionMailbox.Submission submission = gateway.singleSubmission();

        coordinator.tick(1L);
        runtime.tick(1L);
        coordinator.drainCompletedChildren(1L);

        assertEquals(1, backend.startCount(attack.actionId()));
        assertEquals(ActionState.FAILED, outcome(submission).state());
        assertEquals(ActionFailureCode.UNSAFE_CONTROL_STATE,
                outcome(submission).failureCode());
        assertEquals(SelfDefenseSkillService.RunStatus.FAILED,
                service.latestView(BOT).orElseThrow().status());
        assertEquals(SelfDefenseSkillService.Failure.UNSAFE_CONTROL_STATE,
                service.latestView(BOT).orElseThrow().failure().orElseThrow());
        assertFalse(physicalGateway.cancelOrContain(attack,
                ActionCancellationReason.REQUESTED, 1L).safelyRetracted());
    }

    @Test
    void backendStartSynchronousGenerationCloseQuarantinesAttackAndFailsServiceView() {
        CountingBackend backend = new CountingBackend();
        BotActionRuntime runtime = runtime(backend);
        SelfDefenseActionGateway physicalGateway = new SelfDefenseActionGateway(
                runtime);
        ReentrantGateway gateway = new ReentrantGateway(physicalGateway);
        TechniqueLifecycleCoordinator coordinator =
                new TechniqueLifecycleCoordinator();
        SelfDefenseTechniqueBridge bridge = new SelfDefenseTechniqueBridge(
                coordinator, gateway, () -> 1L);
        AtomicReference<SelfDefenseSkillService> serviceReference =
                new AtomicReference<>();
        SelfDefenseSkillService service = new SelfDefenseSkillService(
                resolver(target(4.0D)),
                (instruction, observation) -> Optional.of(attack()),
                bridge::submit,
                bridge::cancelDispatch,
                new DefensePolicy(0.35D, 9.0D, 1, 1),
                new SelfDefenseSkillService.Limits(2, 8, 8, 20, 2));
        serviceReference.set(service);
        backend.onStart(() -> assertTrue(serviceReference.get().closeGeneration(
                BOT, 1L, 1L)));

        assertEquals(SafetyHandoffDecision.DELEGATED,
                service.request(request(1L)));
        service.tick(1L);
        ActionEnvelope attack = gateway.singleEnvelope();
        ActionMailbox.Submission submission = gateway.singleSubmission();

        coordinator.tick(1L);
        runtime.tick(1L);
        coordinator.drainCompletedChildren(1L);

        assertEquals(1, backend.startCount(attack.actionId()));
        assertEquals(ActionFailureCode.UNSAFE_CONTROL_STATE,
                outcome(submission).failureCode());
        assertEquals(SelfDefenseSkillService.RunStatus.FAILED,
                service.latestView(BOT).orElseThrow().status());
        assertEquals(SelfDefenseSkillService.Failure.UNSAFE_CONTROL_STATE,
                service.latestView(BOT).orElseThrow().failure().orElseThrow());
    }

    @Test
    void exactEnvelopeCancellationNeverReusesAForeignSameTripleReceipt() {
        BotActionRuntime runtime = runtime(new CountingBackend());
        SelfDefenseActionGateway gateway = new SelfDefenseActionGateway(runtime);
        UUID actionId = UUID.fromString("55555555-5555-5555-5555-555555555555");
        ActionEnvelope foreign = new ActionEnvelope(actionId, BOT, 1L,
                "foreign/collision", 100L, 10, new WaitAction(1),
                ActionOrigin.none());
        ActionEnvelope expected = new ActionEnvelope(actionId, BOT, 1L,
                "expected/collision", 100L, 10, new WaitAction(1),
                ActionOrigin.none());
        assertEquals(ActionMailbox.SubmissionStatus.ENQUEUED,
                gateway.submit(foreign, ActionPriority.OWNER_TASK).status());

        assertTrue(gateway.cancelOrContain(foreign,
                ActionCancellationReason.REQUESTED, 0L).safelyRetracted());
        ActionCancellationReceipt collision = gateway.cancelOrContain(expected,
                ActionCancellationReason.REQUESTED, 1L);

        assertFalse(collision.safelyRetracted(),
                "a foreign envelope must never supply a safe exact receipt");
        assertEquals(ActionState.CANCELLED,
                runtime.completedOutcomeExact(foreign).orElseThrow().state());
        assertTrue(runtime.completedOutcomeExact(expected).isEmpty(),
                "a foreign terminal is not retained for the expected envelope");
    }

    private static CompletionStage<ActionOutcome> submitRetreatThenClose(
            AuthorizedActionDispatch authorization,
            SelfDefenseActionGateway gateway,
            AtomicReference<ActionEnvelope> envelopeReference,
            AtomicReference<ActionMailbox.Submission> submissionReference,
            AtomicReference<SelfDefenseSkillService> serviceReference) {
        ClaimedActionDispatch dispatch = authorization.claim().orElseThrow();
        assertEquals(DefenseActionKind.RETREAT, dispatch.defenseAction().kind());
        ActionEnvelope envelope = new ActionEnvelope(dispatch.actionId(),
                dispatch.botId(), dispatch.botGeneration(),
                dispatch.idempotencyKey(), dispatch.deadlineTick(),
                dispatch.maximumTicks(), dispatch.action(),
                ActionOrigin.fromController(
                        io.github.greytaiwolf.botplayer.action.ControllerKind.SAFETY,
                        dispatch.selfDefenseRunId()));
        envelopeReference.set(envelope);
        ActionMailbox.Submission submission = gateway.submit(envelope,
                ActionPriority.EMERGENCY);
        submissionReference.set(submission);

        /* This runs after Action ingress accepted but before submit returns. */
        assertTrue(serviceReference.get().closeGeneration(BOT, 1L, 0L));
        return submission.completion().orElseThrow();
    }

    private static BotActionRuntime runtime(CountingBackend backend) {
        return new BotActionRuntime(backend, 8, 32, 8, 16, 8);
    }

    private static ActionOutcome outcome(ActionMailbox.Submission submission) {
        return submission.completion().orElseThrow().toCompletableFuture().join();
    }

    private static void awaitPendingCompletionCount(BotActionRuntime runtime,
            int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
        while (runtime.pendingCompletionCount() != expected
                && System.nanoTime() < deadline) {
            Thread.sleep(1L);
        }
        assertEquals(expected, runtime.pendingCompletionCount());
    }

    private static ActionEnvelope afterContainment(UUID priorActionId,
            ActionRequest action) {
        return new ActionEnvelope(UUID.nameUUIDFromBytes(
                ("after-" + priorActionId).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                BOT, 1L, "after-" + priorActionId, 100L, 5, action,
                ActionOrigin.none());
    }

    private static SelfDefenseSkillService.TargetResolver resolver(
            DefenseTarget target) {
        return new SelfDefenseSkillService.TargetResolver() {
            @Override
            public Optional<DefenseTarget> resolveTarget(
                    SafetyHandoffRequest request) {
                return Optional.of(target);
            }

            @Override
            public Optional<DefenseObservation> observe(UUID botId,
                    long generation, DefenseTarget requested) {
                return Optional.of(new DefenseObservation(20.0D, 20.0D,
                        target, false, 1, Optional.of(RETREAT)));
            }
        };
    }

    private static DefenseTarget target(double distanceSquared) {
        return new DefenseTarget(TARGET,
                DefenseTargetClass.EXPLICIT_HOSTILE, true, distanceSquared);
    }

    private static ActionRequest attack() {
        return new WorldInteractionAction(
                new WorldInteractionActionSpec.AttackEntity(
                        new EntityTargetFingerprint(
                                new ResourceId("minecraft:overworld"), TARGET,
                                new ResourceId("minecraft:zombie"))));
    }

    private static ActionRequest retreat(DefenseActionRequest instruction) {
        SafetyRetreat retreat = instruction.safeRetreat().orElseThrow();
        return new MoveInputAction(retreat.forwardInput(), retreat.strafeInput(),
                false, true, false, retreat.inputTicks(), retreat.inputTicks());
    }

    private static SafetyHandoffRequest request(long tick) {
        return new SafetyHandoffRequest(INCIDENT, BOT, 1L, tick,
                new HazardAssessment(HazardType.HOSTILE_TARGETING,
                        HazardSeverity.WARNING, 0, true, false,
                        Optional.of(TARGET), "physical cancellation test"),
                new SafetyFrame(BOT, 1L, tick, "minecraft:overworld",
                        new GridPoint(0, 64, 0), 0.0D, 0.0D, 0.0D, true,
                        0.0F, 20.0F, 20.0F, 0.0F, 0, 0.0D, 0.0D, 0.1D, 3,
                        0.0F, 300, 300, false, false, false, false, 0,
                        false, false, List.of(), false, List.of(
                                new ThreatSummary(TARGET,
                                        ThreatSummary.Kind.HOSTILE,
                                        new GridPoint(1, 64, 0), 2.0D, 0.0D,
                                        true)), false, Optional.of(RETREAT),
                        Optional.empty(), 0.0F));
    }

    private static final class ReentrantGateway
            implements SelfDefenseTechniqueBridge.ActionGateway {
        private final SelfDefenseActionGateway delegate;
        private final Map<UUID, ActionEnvelope> envelopes = new LinkedHashMap<>();
        private final Map<UUID, ActionMailbox.Submission> submissions =
                new LinkedHashMap<>();
        private final List<ActionCancellationReceipt>
                cancellations = new ArrayList<>();
        private Runnable afterSubmit = () -> {
        };

        private ReentrantGateway(SelfDefenseActionGateway delegate) {
            this.delegate = delegate;
        }

        @Override
        public ActionMailbox.Submission submit(ActionEnvelope envelope,
                ActionPriority priority) {
            ActionMailbox.Submission submission = delegate.submit(envelope,
                    priority);
            envelopes.put(envelope.actionId(), envelope);
            submissions.put(envelope.actionId(), submission);
            afterSubmit.run();
            return submission;
        }

        @Override
        public ActionCancellationReceipt cancelOrContain(
                ActionEnvelope envelope, ActionCancellationReason reason,
                long currentTick) {
            ActionCancellationReceipt result =
                    delegate.cancelOrContain(envelope, reason, currentTick);
            cancellations.add(result);
            return result;
        }

        @Override
        public Optional<ActionOutcome> completedOutcomeExact(
                ActionEnvelope expected) {
            return delegate.completedOutcomeExact(expected);
        }

        @Override
        public void release(ActionEnvelope envelope) {
            delegate.release(envelope);
        }

        private ActionEnvelope singleEnvelope() {
            assertEquals(1, envelopes.size());
            return envelopes.values().iterator().next();
        }

        private ActionMailbox.Submission singleSubmission() {
            assertEquals(1, submissions.size());
            return submissions.values().iterator().next();
        }

    }

    private static final class CountingBackend implements ActionBackend {
        private final Map<UUID, Integer> starts = new LinkedHashMap<>();
        private Runnable startHook = () -> {
        };

        @Override
        public BackendResult validate(ActionEnvelope envelope,
                long currentTick) {
            return BackendResult.accepted(envelope);
        }

        @Override
        public BackendResult start(ActionEnvelope envelope, long currentTick) {
            starts.merge(envelope.actionId(), 1, Integer::sum);
            startHook.run();
            return BackendResult.running(envelope);
        }

        @Override
        public BackendResult tick(ActionEnvelope envelope, long startedTick,
                long currentTick) {
            return BackendResult.running(envelope);
        }

        @Override
        public BackendResult verify(ActionEnvelope envelope,
                long currentTick) {
            return BackendResult.succeeded(envelope, List.of(), "verified");
        }

        @Override
        public void cleanup(ActionEnvelope envelope,
                io.github.greytaiwolf.botplayer.action.ActionCleanupReason reason,
                long currentTick) {
            // The target must never start, so no physical cleanup is needed here.
        }

        @Override
        public boolean forceSafeReset(UUID botId, long generation,
                long currentTick) {
            return true;
        }

        private int startCount(UUID actionId) {
            return starts.getOrDefault(actionId, 0);
        }

        private void onStart(Runnable hook) {
            startHook = Objects.requireNonNull(hook, "hook");
        }
    }
}
