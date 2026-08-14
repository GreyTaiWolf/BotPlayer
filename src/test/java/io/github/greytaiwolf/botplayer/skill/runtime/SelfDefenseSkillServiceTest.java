package io.github.greytaiwolf.botplayer.skill.runtime;

import io.github.greytaiwolf.botplayer.action.ActionEvidence;
import io.github.greytaiwolf.botplayer.action.ActionCancellationReceipt;
import io.github.greytaiwolf.botplayer.action.ActionEnvelope;
import io.github.greytaiwolf.botplayer.action.ActionFailureCode;
import io.github.greytaiwolf.botplayer.action.ActionOrigin;
import io.github.greytaiwolf.botplayer.action.ActionOutcome;
import io.github.greytaiwolf.botplayer.action.ActionRequest;
import io.github.greytaiwolf.botplayer.action.ActionState;
import io.github.greytaiwolf.botplayer.action.ControllerKind;
import io.github.greytaiwolf.botplayer.action.WaitAction;
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
import io.github.greytaiwolf.botplayer.skill.builtin.defense.DefenseReason;
import io.github.greytaiwolf.botplayer.skill.builtin.defense.DefenseState;
import io.github.greytaiwolf.botplayer.skill.builtin.defense.DefenseTarget;
import io.github.greytaiwolf.botplayer.skill.builtin.defense.DefenseTargetClass;
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

class SelfDefenseSkillServiceTest {
    private static final UUID BOT_A = UUID.fromString(
            "00000000-0000-0000-0000-000000000701");
    private static final UUID BOT_B = UUID.fromString(
            "00000000-0000-0000-0000-000000000702");
    private static final UUID TARGET_A = UUID.fromString(
            "00000000-0000-0000-0000-000000000711");
    private static final UUID TARGET_B = UUID.fromString(
            "00000000-0000-0000-0000-000000000712");
    private static final UUID INCIDENT_A = UUID.fromString(
            "00000000-0000-0000-0000-000000000721");
    private static final UUID INCIDENT_B = UUID.fromString(
            "00000000-0000-0000-0000-000000000722");
    private static final SafetyRetreat RETREAT = new SafetyRetreat(
            new GridPoint(-1, 64, 0), -1.0F, 0.0F, 3);

    @Test
    void actionCompletionsOnlyAdvanceOnOwnerTickAndRespectBothBudgets() {
        Fixture fixture = fixture(new SelfDefenseSkillService.Limits(
                2, 8, 8, 20, 2));
        fixture.hostile(BOT_A, TARGET_A);

        Assertions.assertEquals(SafetyHandoffDecision.DELEGATED,
                fixture.service.request(request(BOT_A, TARGET_A, INCIDENT_A, 0L)));
        fixture.service.tick(0L);
        SelfDefenseSkillService.ActionDispatch attack =
                fixture.submitter.dispatches.get(0);
        Assertions.assertEquals(DefenseActionKind.MELEE_ATTACK,
                attack.defenseAction().kind());

        fixture.submitter.complete(attack, ActionState.FAILED, 0L);
        Assertions.assertEquals(1, fixture.submitter.dispatches.size(),
                "completion callback may not submit the next action directly");
        fixture.service.tick(1L);

        SelfDefenseSkillService.ActionDispatch retreat =
                fixture.submitter.dispatches.get(1);
        Assertions.assertEquals(DefenseActionKind.RETREAT,
                retreat.defenseAction().kind());
        fixture.submitter.complete(retreat, ActionState.SUCCEEDED, 1L);
        fixture.target(BOT_A, new DefenseTarget(
                TARGET_A, DefenseTargetClass.EXPLICIT_HOSTILE, true, 25.0D));
        fixture.service.tick(2L);

        SelfDefenseSkillService.RunView view = fixture.service
                .latestView(BOT_A).orElseThrow();
        Assertions.assertEquals(SelfDefenseSkillService.RunStatus.COMPLETED,
                view.status());
        Assertions.assertEquals(DefenseReason.SAFE_RETREAT_CONFIRMED,
                view.decision().reason());
        Assertions.assertTrue(view.failure().isEmpty());
        Assertions.assertEquals(0, fixture.service.activeRunCount());
    }

    @Test
    void verifiedRemovedMeleeOutcomeCompletesBeforeASecondTargetRead() {
        Fixture fixture = fixture(new SelfDefenseSkillService.Limits(
                2, 8, 8, 20, 2));
        fixture.hostile(BOT_A, TARGET_A);
        Assertions.assertEquals(SafetyHandoffDecision.DELEGATED,
                fixture.service.request(request(BOT_A, TARGET_A, INCIDENT_A, 0L)));
        fixture.service.tick(0L);
        SelfDefenseSkillService.ActionDispatch attack =
                fixture.submitter.dispatches.get(0);

        fixture.submitter.complete(attack, ActionState.SUCCEEDED, 0L,
                List.of(
                        new ActionEvidence("entity.id", TARGET_A.toString()),
                        new ActionEvidence("entity.removed", "true")));
        fixture.resolver.targets.remove(BOT_A);
        fixture.service.tick(1L);

        SelfDefenseSkillService.RunView view = fixture.service
                .latestView(BOT_A).orElseThrow();
        Assertions.assertEquals(SelfDefenseSkillService.RunStatus.COMPLETED,
                view.status());
        Assertions.assertEquals(DefenseState.COMPLETED,
                view.decision().state());
        Assertions.assertEquals(DefenseReason.TARGET_ELIMINATED,
                view.decision().reason());
        Assertions.assertTrue(view.failure().isEmpty());
        Assertions.assertEquals(0, fixture.service.activeRunCount());
    }

    @Test
    void aTerminalRunCannotBeReplacedDuringTheSameIncident() {
        Fixture fixture = fixture(new SelfDefenseSkillService.Limits(
                2, 8, 8, 20, 2));
        fixture.hostile(BOT_A, TARGET_A);
        Assertions.assertEquals(SafetyHandoffDecision.DELEGATED,
                fixture.service.request(request(BOT_A, TARGET_A, INCIDENT_A, 0L)));
        fixture.service.tick(0L);
        SelfDefenseSkillService.ActionDispatch first =
                fixture.submitter.dispatches.get(0);
        fixture.submitter.complete(first, ActionState.SUCCEEDED, 0L,
                List.of(
                        new ActionEvidence("entity.id", TARGET_A.toString()),
                        new ActionEvidence("entity.removed", "true")));
        fixture.service.tick(1L);

        UUID firstRunId = fixture.service.latestView(BOT_A)
                .orElseThrow().runId();
        Assertions.assertEquals(SafetyHandoffDecision.FALLBACK,
                fixture.service.request(request(BOT_A, TARGET_A, INCIDENT_A, 2L)));
        Assertions.assertEquals(0, fixture.service.activeRunCount());
        Assertions.assertEquals(firstRunId, fixture.service.latestView(BOT_A)
                .orElseThrow().runId());

        Assertions.assertEquals(SafetyHandoffDecision.DELEGATED,
                fixture.service.request(request(BOT_A, TARGET_A, INCIDENT_B, 2L)));
        Assertions.assertEquals(1, fixture.service.activeRunCount());
        Assertions.assertNotEquals(firstRunId, fixture.service.latestView(BOT_A)
                .orElseThrow().runId());
    }

    @Test
    void pvpFriendlyAndSourceMismatchAllFallBackBeforeAnyActionIsCreated() {
        Fixture fixture = fixture(new SelfDefenseSkillService.Limits(
                2, 8, 8, 20, 2));
        fixture.target(BOT_A, new DefenseTarget(
                TARGET_A, DefenseTargetClass.PLAYER, true, 4.0D));

        Assertions.assertEquals(SafetyHandoffDecision.FALLBACK,
                fixture.service.request(request(BOT_A, TARGET_A, INCIDENT_A, 0L)));

        fixture.target(BOT_A, new DefenseTarget(
                TARGET_B, DefenseTargetClass.EXPLICIT_HOSTILE, true, 4.0D));
        Assertions.assertEquals(SafetyHandoffDecision.FALLBACK,
                fixture.service.request(request(BOT_A, TARGET_A, INCIDENT_A, 0L)));

        fixture.target(BOT_A, new DefenseTarget(
                TARGET_A, DefenseTargetClass.FRIENDLY, true, 4.0D));
        Assertions.assertEquals(SafetyHandoffDecision.FALLBACK,
                fixture.service.request(request(BOT_A, TARGET_A, INCIDENT_A, 0L)));
        Assertions.assertEquals(0, fixture.service.activeRunCount());
        Assertions.assertTrue(fixture.submitter.dispatches.isEmpty());
    }

    @Test
    void l0PreemptionCancelsActionAndLateCompletionCannotReopenTheRun() {
        Fixture fixture = fixture(new SelfDefenseSkillService.Limits(
                2, 8, 8, 20, 2));
        fixture.hostile(BOT_A, TARGET_A);
        fixture.service.request(request(BOT_A, TARGET_A, INCIDENT_A, 0L));
        fixture.service.tick(0L);
        SelfDefenseSkillService.ActionDispatch dispatch =
                fixture.submitter.dispatches.get(0);

        Assertions.assertTrue(fixture.service.preempt(BOT_A, 1L, 1L));
        Assertions.assertEquals(List.of(dispatch), fixture.cancelled);
        fixture.submitter.complete(dispatch, ActionState.SUCCEEDED, 1L);
        fixture.service.tick(1L);

        SelfDefenseSkillService.RunView view = fixture.service
                .latestView(BOT_A).orElseThrow();
        Assertions.assertEquals(SelfDefenseSkillService.RunStatus.PREEMPTED,
                view.status());
        Assertions.assertEquals(0, fixture.service.activeRunCount());
        Assertions.assertFalse(fixture.service.preempt(BOT_A, 1L, 2L));
    }

    @Test
    void unsafeFrameRepreemptsAnInFlightAttackBeforeItCanComplete() {
        Fixture fixture = fixture(new SelfDefenseSkillService.Limits(
                2, 8, 8, 20, 2));
        fixture.hostile(BOT_A, TARGET_A);
        fixture.service.request(request(BOT_A, TARGET_A, INCIDENT_A, 0L));
        fixture.service.tick(0L);
        SelfDefenseSkillService.ActionDispatch attack =
                fixture.submitter.dispatches.get(0);

        Assertions.assertTrue(fixture.service.preempt(requestWithFrame(
                BOT_A,
                TARGET_A,
                INCIDENT_A,
                1L,
                3.0F,
                false,
                List.of(threat(TARGET_A)),
                Optional.of(RETREAT))));
        Assertions.assertEquals(List.of(attack), fixture.cancelled);
        fixture.submitter.complete(attack, ActionState.SUCCEEDED, 1L);
        fixture.service.tick(1L);

        SelfDefenseSkillService.RunView view = fixture.service
                .latestView(BOT_A).orElseThrow();
        Assertions.assertEquals(SelfDefenseSkillService.RunStatus.PREEMPTED,
                view.status());
        Assertions.assertEquals(0, fixture.service.activeRunCount());
    }

    @Test
    void lowHealthMultipleThreatsAndIncompleteCoverageFallBackBeforeDispatch() {
        Fixture fixture = fixture(new SelfDefenseSkillService.Limits(
                2, 8, 8, 20, 2));
        fixture.hostile(BOT_A, TARGET_A);

        Assertions.assertEquals(SafetyHandoffDecision.FALLBACK,
                fixture.service.request(requestWithFrame(
                        BOT_A,
                        TARGET_A,
                        INCIDENT_A,
                        0L,
                        3.0F,
                        false,
                        List.of(threat(TARGET_A)),
                        Optional.of(RETREAT))));
        Assertions.assertEquals(SafetyHandoffDecision.FALLBACK,
                fixture.service.request(requestWithFrame(
                        BOT_A,
                        TARGET_A,
                        INCIDENT_B,
                        1L,
                        20.0F,
                        false,
                        List.of(threat(TARGET_A), threat(TARGET_B)),
                        Optional.of(RETREAT))));
        Assertions.assertEquals(SafetyHandoffDecision.FALLBACK,
                fixture.service.request(requestWithFrame(
                        BOT_A,
                        TARGET_A,
                        UUID.fromString("00000000-0000-0000-0000-000000000723"),
                        2L,
                        20.0F,
                        true,
                        List.of(threat(TARGET_A)),
                        Optional.empty())));
        Assertions.assertEquals(0, fixture.service.activeRunCount());
        Assertions.assertTrue(fixture.submitter.dispatches.isEmpty());
    }

    @Test
    void cancelledAttackCanOnlyTransitionToRetreatThenFailsWithoutFreshSafety() {
        Fixture fixture = fixture(new SelfDefenseSkillService.Limits(
                2, 8, 8, 20, 2));
        fixture.hostile(BOT_A, TARGET_A);
        fixture.service.request(request(BOT_A, TARGET_A, INCIDENT_A, 0L));
        fixture.service.tick(0L);
        SelfDefenseSkillService.ActionDispatch attack =
                fixture.submitter.dispatches.get(0);

        fixture.submitter.complete(attack, ActionState.CANCELLED, 0L);
        fixture.service.tick(1L);
        SelfDefenseSkillService.ActionDispatch retreat =
                fixture.submitter.dispatches.get(1);
        Assertions.assertEquals(DefenseActionKind.RETREAT,
                retreat.defenseAction().kind());

        fixture.submitter.complete(retreat, ActionState.SUCCEEDED, 1L);
        fixture.service.tick(2L);
        SelfDefenseSkillService.RunView view = fixture.service
                .latestView(BOT_A).orElseThrow();
        Assertions.assertEquals(SelfDefenseSkillService.RunStatus.FAILED,
                view.status());
        Assertions.assertEquals(SelfDefenseSkillService.Failure.BUDGET_EXHAUSTED,
                view.failure().orElseThrow());
        Assertions.assertEquals(2, fixture.submitter.dispatches.size());
    }

    @Test
    void timeoutCancelsTheOnlyOutstandingActionAndFailsClosed() {
        Fixture fixture = fixture(new SelfDefenseSkillService.Limits(
                2, 8, 8, 10, 2));
        fixture.hostile(BOT_A, TARGET_A);
        fixture.service.request(request(BOT_A, TARGET_A, INCIDENT_A, 0L));
        fixture.service.tick(0L);
        SelfDefenseSkillService.ActionDispatch dispatch =
                fixture.submitter.dispatches.get(0);

        fixture.service.tick(10L);

        SelfDefenseSkillService.RunView view = fixture.service
                .latestView(BOT_A).orElseThrow();
        Assertions.assertEquals(SelfDefenseSkillService.RunStatus.FAILED,
                view.status());
        Assertions.assertEquals(SelfDefenseSkillService.Failure.TIMEOUT,
                view.failure().orElseThrow());
        Assertions.assertEquals(List.of(dispatch), fixture.cancelled);
    }

    @Test
    void boundedCompletionQueueOverflowFailsTheAffectedRunClosed() {
        Fixture fixture = fixture(new SelfDefenseSkillService.Limits(
                2, 1, 1, 20, 2));
        fixture.hostile(BOT_A, TARGET_A);
        fixture.hostile(BOT_B, TARGET_B);
        fixture.service.request(request(BOT_A, TARGET_A, INCIDENT_A, 0L));
        fixture.service.request(request(BOT_B, TARGET_B, INCIDENT_B, 0L));
        fixture.service.tick(0L);
        SelfDefenseSkillService.ActionDispatch first =
                fixture.submitter.dispatches.get(0);
        SelfDefenseSkillService.ActionDispatch second =
                fixture.submitter.dispatches.get(1);

        fixture.submitter.complete(first, ActionState.FAILED, 0L);
        fixture.submitter.complete(second, ActionState.FAILED, 0L);
        fixture.service.tick(1L);

        SelfDefenseSkillService.RunView secondView = fixture.service
                .latestView(BOT_B).orElseThrow();
        Assertions.assertEquals(SelfDefenseSkillService.RunStatus.FAILED,
                secondView.status());
        Assertions.assertEquals(
                SelfDefenseSkillService.Failure.COMPLETION_QUEUE_OVERFLOW,
                secondView.failure().orElseThrow());
        Assertions.assertTrue(fixture.cancelled.contains(second));
        Assertions.assertEquals(
                SelfDefenseSkillService.AuthorizationRevocation.CANCELLED,
                fixture.submitter.authorizations.get(1).revocation().orElseThrow());
    }

    @Test
    void authorizationIsOneShotAndPreemptionRevokesTheExactIssuedCapability() {
        Fixture fixture = fixture(new SelfDefenseSkillService.Limits(
                2, 8, 8, 20, 2));
        fixture.hostile(BOT_A, TARGET_A);
        Assertions.assertEquals(SafetyHandoffDecision.DELEGATED,
                fixture.service.request(request(BOT_A, TARGET_A, INCIDENT_A, 0L)));
        fixture.service.tick(0L);

        SelfDefenseSkillService.AuthorizedActionDispatch authorization =
                fixture.submitter.authorizations.get(0);
        Assertions.assertTrue(authorization.claim().isEmpty(),
                "the submitter already consumed the only claim");
        Assertions.assertTrue(fixture.service.preempt(BOT_A, 1L, 1L));
        Assertions.assertEquals(
                SelfDefenseSkillService.AuthorizationRevocation.SAFETY_PREEMPTION,
                authorization.revocation().orElseThrow());
        Assertions.assertTrue(authorization.claim().isEmpty(),
                "a revoked capability must remain unclaimable");
    }

    @Test
    void completedAuthorizationRejectsALateClaim() {
        Fixture fixture = fixture(new SelfDefenseSkillService.Limits(
                2, 8, 8, 20, 2));
        fixture.hostile(BOT_A, TARGET_A);
        Assertions.assertEquals(SafetyHandoffDecision.DELEGATED,
                fixture.service.request(request(BOT_A, TARGET_A, INCIDENT_A, 0L)));
        fixture.service.tick(0L);

        SelfDefenseSkillService.AuthorizedActionDispatch authorization =
                fixture.submitter.authorizations.get(0);
        fixture.submitter.complete(fixture.submitter.dispatches.get(0),
                ActionState.FAILED, 0L);
        fixture.service.tick(1L);

        Assertions.assertEquals(
                SelfDefenseSkillService.AuthorizationRevocation.COMPLETED,
                authorization.revocation().orElseThrow());
        Assertions.assertTrue(authorization.claim().isEmpty(),
                "a completed capability must reject a late claim");
    }

    @Test
    void retreatAuthorizationIsConsumedOnceByTheDirectActionRoute() {
        Fixture fixture = fixture(new SelfDefenseSkillService.Limits(
                2, 8, 8, 20, 2));
        fixture.hostile(BOT_A, TARGET_A);
        Assertions.assertEquals(SafetyHandoffDecision.DELEGATED,
                fixture.service.request(request(BOT_A, TARGET_A, INCIDENT_A, 0L)));
        fixture.service.tick(0L);
        fixture.submitter.complete(fixture.submitter.dispatches.get(0),
                ActionState.FAILED, 0L);

        fixture.service.tick(1L);

        SelfDefenseSkillService.AuthorizedActionDispatch authorization =
                fixture.submitter.authorizations.get(1);
        ActionEnvelope directEnvelope = fixture.submitter.directRetreats.get(0);
        Assertions.assertEquals(DefenseActionKind.RETREAT, authorization.kind());
        Assertions.assertEquals(authorization.actionId(), directEnvelope.actionId());
        Assertions.assertEquals(BOT_A, directEnvelope.botId());
        Assertions.assertEquals(1L, directEnvelope.botGeneration());
        Assertions.assertEquals(ControllerKind.SAFETY,
                directEnvelope.origin().controller().orElseThrow().kind());
        Assertions.assertTrue(authorization.claim().isEmpty(),
                "the direct route must consume the only retreat claim");
    }

    @Test
    void unclaimedAuthorizationFailsClosedBeforeAnyCompletionCanKeepTheRunAlive() {
        FakeResolver resolver = new FakeResolver();
        resolver.target(BOT_A, new DefenseTarget(TARGET_A,
                DefenseTargetClass.EXPLICIT_HOSTILE, true, 4.0D));
        List<SelfDefenseSkillService.AuthorizedActionDispatch> issued =
                new ArrayList<>();
        List<SelfDefenseSkillService.AuthorizedActionDispatch> cancelled =
                new ArrayList<>();
        SelfDefenseSkillService service = new SelfDefenseSkillService(
                resolver,
                (instruction, observation) -> Optional.of(new WaitAction(1)),
                authorization -> {
                    issued.add(authorization);
                    return new CompletableFuture<>();
                },
                authorization -> {
                    cancelled.add(authorization);
                    return safeCancellation(authorization);
                },
                new DefensePolicy(0.35D, 9.0D, 1, 1),
                new SelfDefenseSkillService.Limits(2, 8, 8, 20, 2));

        Assertions.assertEquals(SafetyHandoffDecision.DELEGATED,
                service.request(request(BOT_A, TARGET_A, INCIDENT_A, 0L)));
        service.tick(0L);

        Assertions.assertEquals(1, issued.size());
        Assertions.assertEquals(1, cancelled.size());
        Assertions.assertEquals(
                SelfDefenseSkillService.AuthorizationRevocation.SUBMISSION_REJECTED,
                issued.get(0).revocation().orElseThrow());
        Assertions.assertTrue(issued.get(0).claim().isEmpty());
        SelfDefenseSkillService.RunView view = service.latestView(BOT_A)
                .orElseThrow();
        Assertions.assertEquals(SelfDefenseSkillService.RunStatus.FAILED,
                view.status());
        Assertions.assertEquals(
                SelfDefenseSkillService.Failure.ACTION_SUBMISSION_REJECTED,
                view.failure().orElseThrow());
    }

    @Test
    void claimedAuthorizationWhoseSubmitterThrowsAlsoFailsClosed() {
        FakeResolver resolver = new FakeResolver();
        resolver.target(BOT_A, new DefenseTarget(TARGET_A,
                DefenseTargetClass.EXPLICIT_HOSTILE, true, 4.0D));
        List<SelfDefenseSkillService.AuthorizedActionDispatch> issued =
                new ArrayList<>();
        List<SelfDefenseSkillService.AuthorizedActionDispatch> cancelled =
                new ArrayList<>();
        SelfDefenseSkillService service = new SelfDefenseSkillService(
                resolver,
                (instruction, observation) -> Optional.of(new WaitAction(1)),
                authorization -> {
                    issued.add(authorization);
                    authorization.claim().orElseThrow();
                    throw new IllegalStateException("test submission rejection");
                },
                authorization -> {
                    cancelled.add(authorization);
                    return safeCancellation(authorization);
                },
                new DefensePolicy(0.35D, 9.0D, 1, 1),
                new SelfDefenseSkillService.Limits(2, 8, 8, 20, 2));

        Assertions.assertEquals(SafetyHandoffDecision.DELEGATED,
                service.request(request(BOT_A, TARGET_A, INCIDENT_A, 0L)));
        service.tick(0L);

        Assertions.assertEquals(1, issued.size());
        Assertions.assertEquals(List.of(issued.get(0)), cancelled);
        Assertions.assertEquals(
                SelfDefenseSkillService.AuthorizationRevocation.SUBMISSION_REJECTED,
                issued.get(0).revocation().orElseThrow());
        Assertions.assertTrue(issued.get(0).claim().isEmpty());
        SelfDefenseSkillService.RunView view = service.latestView(BOT_A)
                .orElseThrow();
        Assertions.assertEquals(SelfDefenseSkillService.RunStatus.FAILED,
                view.status());
        Assertions.assertEquals(
                SelfDefenseSkillService.Failure.ACTION_SUBMISSION_REJECTED,
                view.failure().orElseThrow());
    }

    @Test
    void serviceCloseRevokesTheOutstandingCapabilityBeforeCancellingIt() {
        Fixture fixture = fixture(new SelfDefenseSkillService.Limits(
                2, 8, 8, 20, 2));
        fixture.hostile(BOT_A, TARGET_A);
        Assertions.assertEquals(SafetyHandoffDecision.DELEGATED,
                fixture.service.request(request(BOT_A, TARGET_A, INCIDENT_A, 0L)));
        fixture.service.tick(0L);

        SelfDefenseSkillService.AuthorizedActionDispatch authorization =
                fixture.submitter.authorizations.get(0);
        SelfDefenseSkillService.ActionDispatch dispatch =
                fixture.submitter.dispatches.get(0);
        fixture.service.close();

        Assertions.assertEquals(
                SelfDefenseSkillService.AuthorizationRevocation.SERVER_STOP,
                authorization.revocation().orElseThrow());
        Assertions.assertTrue(authorization.claim().isEmpty());
        Assertions.assertEquals(List.of(dispatch), fixture.cancelled);
        SelfDefenseSkillService.RunView view = fixture.service.latestView(BOT_A)
                .orElseThrow();
        Assertions.assertEquals(SelfDefenseSkillService.RunStatus.CLOSED,
                view.status());
        Assertions.assertEquals(SelfDefenseSkillService.Failure.RUNTIME_CLOSED,
                view.failure().orElseThrow());
    }

    @Test
    void unsafeCancellationReceiptOverridesSafetyPreemptionTerminalView() {
        FakeResolver resolver = new FakeResolver();
        resolver.target(BOT_A, new DefenseTarget(TARGET_A,
                DefenseTargetClass.EXPLICIT_HOSTILE, true, 4.0D));
        RecordingSubmitter submitter = new RecordingSubmitter();
        SelfDefenseSkillService service = new SelfDefenseSkillService(
                resolver,
                (instruction, observation) -> Optional.of(new WaitAction(1)),
                submitter,
                authorization -> ActionCancellationReceipt.unsafe(
                        authorization.botId(), authorization.botGeneration(),
                        authorization.actionId(),
                        ActionCancellationReceipt.Disposition.STARTED),
                new DefensePolicy(0.35D, 9.0D, 1, 1),
                new SelfDefenseSkillService.Limits(2, 8, 8, 20, 2));

        Assertions.assertEquals(SafetyHandoffDecision.DELEGATED,
                service.request(request(BOT_A, TARGET_A, INCIDENT_A, 0L)));
        service.tick(0L);
        Assertions.assertTrue(service.preempt(BOT_A, 1L, 1L));

        SelfDefenseSkillService.RunView view = service.latestView(BOT_A)
                .orElseThrow();
        Assertions.assertEquals(SelfDefenseSkillService.RunStatus.FAILED,
                view.status());
        Assertions.assertEquals(SelfDefenseSkillService.Failure.UNSAFE_CONTROL_STATE,
                view.failure().orElseThrow());
    }

    @Test
    void unsafeCancellationReceiptOverridesLifecycleCloseTerminalView() {
        FakeResolver resolver = new FakeResolver();
        resolver.target(BOT_A, new DefenseTarget(TARGET_A,
                DefenseTargetClass.EXPLICIT_HOSTILE, true, 4.0D));
        RecordingSubmitter submitter = new RecordingSubmitter();
        SelfDefenseSkillService service = new SelfDefenseSkillService(
                resolver,
                (instruction, observation) -> Optional.of(new WaitAction(1)),
                submitter,
                authorization -> ActionCancellationReceipt.unsafe(
                        authorization.botId(), authorization.botGeneration(),
                        authorization.actionId(),
                        ActionCancellationReceipt.Disposition.TERMINAL),
                new DefensePolicy(0.35D, 9.0D, 1, 1),
                new SelfDefenseSkillService.Limits(2, 8, 8, 20, 2));

        Assertions.assertEquals(SafetyHandoffDecision.DELEGATED,
                service.request(request(BOT_A, TARGET_A, INCIDENT_A, 0L)));
        service.tick(0L);
        Assertions.assertTrue(service.closeGeneration(BOT_A, 1L, 1L));

        SelfDefenseSkillService.RunView view = service.latestView(BOT_A)
                .orElseThrow();
        Assertions.assertEquals(SelfDefenseSkillService.RunStatus.FAILED,
                view.status());
        Assertions.assertEquals(SelfDefenseSkillService.Failure.UNSAFE_CONTROL_STATE,
                view.failure().orElseThrow());
    }

    private static Fixture fixture(SelfDefenseSkillService.Limits limits) {
        FakeResolver resolver = new FakeResolver();
        RecordingSubmitter submitter = new RecordingSubmitter();
        List<SelfDefenseSkillService.ActionDispatch> cancelled =
                new ArrayList<>();
        SelfDefenseSkillService service = new SelfDefenseSkillService(
                resolver,
                (instruction, observation) -> Optional.of(new WaitAction(1)),
                submitter,
                authorization -> {
                    submitter.dispatchFor(authorization.actionId())
                            .ifPresent(cancelled::add);
                    return safeCancellation(authorization);
                },
                new DefensePolicy(0.35D, 9.0D, 1, 1),
                limits);
        return new Fixture(service, resolver, submitter, cancelled);
    }

    private static SafetyHandoffRequest request(
            UUID botId, UUID targetId, UUID incidentId, long tick) {
        return requestWithFrame(
                botId,
                targetId,
                incidentId,
                tick,
                20.0F,
                false,
                List.of(threat(targetId)),
                Optional.of(RETREAT));
    }

    private static ActionCancellationReceipt safeCancellation(
            SelfDefenseSkillService.AuthorizedActionDispatch authorization) {
        return ActionCancellationReceipt.fencedBeforeStart(
                authorization.botId(), authorization.botGeneration(),
                authorization.actionId());
    }

    private static SafetyHandoffRequest requestWithFrame(
            UUID botId,
            UUID targetId,
            UUID incidentId,
            long tick,
            float health,
            boolean threatCoverageIncomplete,
            List<ThreatSummary> threats,
            Optional<SafetyRetreat> safeRetreat) {
        return new SafetyHandoffRequest(
                incidentId,
                botId,
                1L,
                tick,
                new HazardAssessment(
                        HazardType.HOSTILE_TARGETING,
                        HazardSeverity.WARNING,
                        0,
                        true,
                        false,
                        Optional.of(targetId),
                        "测试有限自卫交接"),
                frame(
                        botId,
                        tick,
                        health,
                        threatCoverageIncomplete,
                        threats,
                        safeRetreat));
    }

    private static SafetyFrame frame(
            UUID botId,
            long tick,
            float health,
            boolean threatCoverageIncomplete,
            List<ThreatSummary> threats,
            Optional<SafetyRetreat> safeRetreat) {
        return new SafetyFrame(
                botId,
                1L,
                tick,
                "minecraft:overworld",
                new GridPoint(0, 64, 0),
                0.0D,
                0.0D,
                0.0D,
                true,
                0.0F,
                health,
                20.0F,
                0.0F,
                0,
                0.0D,
                0.0D,
                0.1D,
                3,
                0.0F,
                300,
                300,
                false,
                false,
                false,
                false,
                0,
                false,
                false,
                List.of(),
                false,
                threats,
                threatCoverageIncomplete,
                safeRetreat,
                Optional.empty(),
                0.0F);
    }

    private static ThreatSummary threat(UUID entityId) {
        return new ThreatSummary(
                entityId,
                ThreatSummary.Kind.HOSTILE,
                new GridPoint(1, 64, 0),
                2.0D,
                0.0D,
                true);
    }

    private record Fixture(
            SelfDefenseSkillService service,
            FakeResolver resolver,
            RecordingSubmitter submitter,
            List<SelfDefenseSkillService.ActionDispatch> cancelled) {
        private void hostile(UUID botId, UUID targetId) {
            target(botId, new DefenseTarget(
                    targetId,
                    DefenseTargetClass.EXPLICIT_HOSTILE,
                    true,
                    4.0D));
        }

        private void target(UUID botId, DefenseTarget target) {
            resolver.target(botId, target);
        }
    }

    private static final class FakeResolver
            implements SelfDefenseSkillService.TargetResolver {
        private final Map<UUID, DefenseTarget> targets = new HashMap<>();

        @Override
        public Optional<DefenseTarget> resolveTarget(
                SafetyHandoffRequest request) {
            return Optional.ofNullable(targets.get(request.botId()));
        }

        @Override
        public Optional<DefenseObservation> observe(
                UUID botId, long generation, DefenseTarget target) {
            DefenseTarget current = targets.get(botId);
            return current == null
                    ? Optional.empty()
                    : Optional.of(new DefenseObservation(
                            20.0D,
                            20.0D,
                            current,
                            false,
                            1,
                            Optional.of(RETREAT)));
        }

        private void target(UUID botId, DefenseTarget target) {
            targets.put(botId, target);
        }
    }

    private static final class RecordingSubmitter
            implements SelfDefenseSkillService.ActionSubmitter {
        private final List<SelfDefenseSkillService.ActionDispatch> dispatches =
                new ArrayList<>();
        private final Map<UUID, CompletableFuture<ActionOutcome>> futures =
                new HashMap<>();
        private final Map<UUID, SelfDefenseSkillService.ActionDispatch>
                dispatchesByActionId = new HashMap<>();
        private final List<SelfDefenseSkillService.AuthorizedActionDispatch>
                authorizations = new ArrayList<>();
        private final List<ActionEnvelope> directRetreats = new ArrayList<>();

        @Override
        public CompletionStage<ActionOutcome> submit(
                SelfDefenseSkillService.AuthorizedActionDispatch authorization) {
            authorizations.add(authorization);
            SelfDefenseSkillService.ClaimedActionDispatch claim =
                    authorization.claim().orElseThrow();
            if (claim.kind() == DefenseActionKind.RETREAT) {
                directRetreats.add(new ActionEnvelope(
                        claim.actionId(),
                        claim.botId(),
                        claim.botGeneration(),
                        claim.idempotencyKey(),
                        claim.deadlineTick(),
                        claim.maximumTicks(),
                        claim.action(),
                        ActionOrigin.fromController(ControllerKind.SAFETY,
                                claim.selfDefenseRunId())));
            }
            SelfDefenseSkillService.ActionDispatch dispatch =
                    claim.rawDispatch();
            CompletableFuture<ActionOutcome> future = new CompletableFuture<>();
            dispatches.add(dispatch);
            dispatchesByActionId.put(dispatch.actionId(), dispatch);
            futures.put(dispatch.actionId(), future);
            return future;
        }

        private Optional<SelfDefenseSkillService.ActionDispatch> dispatchFor(
                UUID actionId) {
            return Optional.ofNullable(dispatchesByActionId.get(actionId));
        }

        private void complete(
                SelfDefenseSkillService.ActionDispatch dispatch,
                ActionState state,
                long tick) {
            complete(dispatch, state, tick, List.of());
        }

        private void complete(
                SelfDefenseSkillService.ActionDispatch dispatch,
                ActionState state,
                long tick,
                List<ActionEvidence> evidence) {
            ActionFailureCode code = switch (state) {
                case SUCCEEDED -> ActionFailureCode.NONE;
                case CANCELLED -> ActionFailureCode.CANCELLED;
                case PREEMPTED -> ActionFailureCode.PREEMPTED;
                case STALE -> ActionFailureCode.STALE_GENERATION;
                case FAILED -> ActionFailureCode.INTERNAL_ERROR;
                case QUEUED, VALIDATING, RUNNING, VERIFYING ->
                        throw new IllegalArgumentException(
                                "test completion requires a terminal state");
            };
            futures.get(dispatch.actionId()).complete(new ActionOutcome(
                    dispatch.actionId(), state, code, tick, tick,
                    evidence, "test completion"));
        }
    }
}
