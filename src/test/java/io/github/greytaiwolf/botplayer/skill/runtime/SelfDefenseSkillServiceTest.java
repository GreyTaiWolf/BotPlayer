package io.github.greytaiwolf.botplayer.skill.runtime;

import io.github.greytaiwolf.botplayer.action.ActionFailureCode;
import io.github.greytaiwolf.botplayer.action.ActionOutcome;
import io.github.greytaiwolf.botplayer.action.ActionRequest;
import io.github.greytaiwolf.botplayer.action.ActionState;
import io.github.greytaiwolf.botplayer.action.WaitAction;
import io.github.greytaiwolf.botplayer.navigation.GridPoint;
import io.github.greytaiwolf.botplayer.safety.HazardAssessment;
import io.github.greytaiwolf.botplayer.safety.HazardSeverity;
import io.github.greytaiwolf.botplayer.safety.HazardType;
import io.github.greytaiwolf.botplayer.safety.SafetyFrame;
import io.github.greytaiwolf.botplayer.safety.SafetyHandoffDecision;
import io.github.greytaiwolf.botplayer.safety.SafetyHandoffRequest;
import io.github.greytaiwolf.botplayer.skill.builtin.defense.DefenseActionKind;
import io.github.greytaiwolf.botplayer.skill.builtin.defense.DefenseObservation;
import io.github.greytaiwolf.botplayer.skill.builtin.defense.DefensePolicy;
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
        fixture.service.tick(2L);

        SelfDefenseSkillService.RunView view = fixture.service
                .latestView(BOT_A).orElseThrow();
        Assertions.assertEquals(SelfDefenseSkillService.RunStatus.COMPLETED,
                view.status());
        Assertions.assertTrue(view.failure().isEmpty());
        Assertions.assertEquals(0, fixture.service.activeRunCount());
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
                cancelled::add,
                new DefensePolicy(0.35D, 9.0D, 1, 1),
                limits);
        return new Fixture(service, resolver, submitter, cancelled);
    }

    private static SafetyHandoffRequest request(
            UUID botId, UUID targetId, UUID incidentId, long tick) {
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
                frame(botId, tick));
    }

    private static SafetyFrame frame(UUID botId, long tick) {
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
                20.0F,
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
                List.of(),
                false,
                Optional.empty(),
                0.0F);
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
                            20.0D, 20.0D, current));
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

        @Override
        public CompletionStage<ActionOutcome> submit(
                SelfDefenseSkillService.ActionDispatch dispatch) {
            CompletableFuture<ActionOutcome> future = new CompletableFuture<>();
            dispatches.add(dispatch);
            futures.put(dispatch.actionId(), future);
            return future;
        }

        private void complete(
                SelfDefenseSkillService.ActionDispatch dispatch,
                ActionState state,
                long tick) {
            ActionFailureCode code = state == ActionState.SUCCEEDED
                    ? ActionFailureCode.NONE
                    : ActionFailureCode.INTERNAL_ERROR;
            futures.get(dispatch.actionId()).complete(new ActionOutcome(
                    dispatch.actionId(), state, code, tick, tick,
                    List.of(), "test completion"));
        }
    }
}
