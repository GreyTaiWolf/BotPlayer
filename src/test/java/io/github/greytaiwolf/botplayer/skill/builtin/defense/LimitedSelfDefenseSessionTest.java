package io.github.greytaiwolf.botplayer.skill.builtin.defense;

import io.github.greytaiwolf.botplayer.navigation.GridPoint;
import io.github.greytaiwolf.botplayer.safety.SafetyRetreat;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class LimitedSelfDefenseSessionTest {
    private static final UUID RUN_ID = UUID.fromString(
            "11111111-1111-1111-1111-111111111111");
    private static final UUID TARGET_ID = UUID.fromString(
            "22222222-2222-2222-2222-222222222222");
    private static final DefensePolicy POLICY = new DefensePolicy(
            0.35D, 9.0D, 2, 1);
    private static final SafetyRetreat RETREAT = new SafetyRetreat(
            new GridPoint(-1, 64, 0), -1.0F, 0.0F, 3);

    @Test
    void onlyExplicitHostileCanEnterTheSession() {
        assertRejected(DefenseTargetClass.PLAYER,
                DefenseReason.PVP_TARGET_REJECTED);
        assertRejected(DefenseTargetClass.FRIENDLY,
                DefenseReason.FRIENDLY_TARGET_REJECTED);
        assertRejected(DefenseTargetClass.NEUTRAL,
                DefenseReason.TARGET_NOT_EXPLICIT_HOSTILE);
        assertRejected(DefenseTargetClass.UNKNOWN,
                DefenseReason.TARGET_NOT_EXPLICIT_HOSTILE);
    }

    @Test
    void attackBudgetIsConsumedOnIssueThenForcesOneBoundedRetreat() {
        LimitedSelfDefenseSession session = session(hostile(true, 4.0D));

        DefenseActionRequest first = session.next(healthy(hostile(true, 4.0D)))
                .action().orElseThrow();
        Assertions.assertEquals(DefenseActionKind.MELEE_ATTACK, first.kind());
        Assertions.assertEquals(1, session.decision().attackAttemptsRemaining());
        acknowledge(session, first, DefenseActionOutcome.FAILED);

        DefenseActionRequest second = session.next(healthy(hostile(true, 4.0D)))
                .action().orElseThrow();
        Assertions.assertEquals(DefenseActionKind.MELEE_ATTACK, second.kind());
        Assertions.assertEquals(0, session.decision().attackAttemptsRemaining());
        acknowledge(session, second, DefenseActionOutcome.FAILED);

        DefenseDecision retreat = session.next(healthy(hostile(true, 4.0D)));
        Assertions.assertEquals(DefenseState.RETREAT_IN_FLIGHT, retreat.state());
        Assertions.assertEquals(DefenseReason.RETREAT_ATTACK_BUDGET_EXHAUSTED,
                retreat.reason());
        Assertions.assertEquals(DefenseActionKind.RETREAT,
                retreat.action().orElseThrow().kind());
        Assertions.assertEquals(0, retreat.retreatAttemptsRemaining());

        acknowledge(session, retreat.action().orElseThrow(),
                DefenseActionOutcome.FAILED);
        DefenseDecision exhausted = session.next(healthy(hostile(true, 4.0D)));
        Assertions.assertEquals(DefenseState.EXHAUSTED, exhausted.state());
        Assertions.assertEquals(DefenseReason.RETREAT_BUDGET_EXHAUSTED,
                exhausted.reason());
    }

    @Test
    void lowHealthAndOutOfRangeNeverSpendAnAttackBudget() {
        LimitedSelfDefenseSession lowHealth = session(hostile(true, 4.0D));
        DefenseDecision retreatForHealth = lowHealth.next(observation(
                3.5D, hostile(true, 4.0D), false, 1, Optional.of(RETREAT)));
        Assertions.assertEquals(DefenseActionKind.RETREAT,
                retreatForHealth.action().orElseThrow().kind());
        Assertions.assertEquals(2,
                retreatForHealth.attackAttemptsRemaining());
        Assertions.assertEquals(DefenseReason.RETREAT_LOW_HEALTH,
                retreatForHealth.reason());

        LimitedSelfDefenseSession outOfRange = session(hostile(true, 16.0D));
        DefenseDecision retreatForDistance = outOfRange.next(
                healthy(hostile(true, 16.0D)));
        Assertions.assertEquals(DefenseActionKind.RETREAT,
                retreatForDistance.action().orElseThrow().kind());
        Assertions.assertEquals(2,
                retreatForDistance.attackAttemptsRemaining());
        Assertions.assertEquals(DefenseReason.RETREAT_OUT_OF_MELEE_RANGE,
                retreatForDistance.reason());
    }

    @Test
    void l0PreemptionIsTerminalAndMakesLateActionReceiptsHarmless() {
        LimitedSelfDefenseSession session = session(hostile(true, 4.0D));
        DefenseActionRequest action = session.next(healthy(hostile(true, 4.0D)))
                .action().orElseThrow();

        Assertions.assertTrue(session.preemptBySafety());
        Assertions.assertEquals(DefenseState.PREEMPTED,
                session.decision().state());
        Assertions.assertEquals(DefenseReason.L0_SAFETY_PREEMPTED,
                session.decision().reason());
        Assertions.assertEquals(DefenseReceiptStatus.STALE_IGNORED,
                session.acknowledge(new DefenseActionReceipt(
                        action, DefenseActionOutcome.SUCCEEDED)));
        Assertions.assertFalse(session.preemptBySafety());
        Assertions.assertTrue(session.decision().action().isEmpty());
    }

    @Test
    void targetIdentityOrClassificationChangeFailsClosed() {
        LimitedSelfDefenseSession changedTarget = session(hostile(true, 4.0D));
        DefenseTarget other = new DefenseTarget(
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                DefenseTargetClass.EXPLICIT_HOSTILE, true, 4.0D);
        DefenseDecision changed = changedTarget.next(healthy(other));
        Assertions.assertEquals(DefenseState.REJECTED, changed.state());
        Assertions.assertEquals(DefenseReason.TARGET_CHANGED, changed.reason());

        LimitedSelfDefenseSession becamePlayer = session(hostile(true, 4.0D));
        DefenseDecision player = becamePlayer.next(healthy(new DefenseTarget(
                TARGET_ID, DefenseTargetClass.PLAYER, true, 4.0D)));
        Assertions.assertEquals(DefenseState.REJECTED, player.state());
        Assertions.assertEquals(DefenseReason.PVP_TARGET_REJECTED,
                player.reason());
    }

    @Test
    void onlyMeleeAndRetreatCanEverReachAnActionAdapter() {
        Assertions.assertEquals(EnumSet.of(
                        DefenseActionKind.MELEE_ATTACK,
                        DefenseActionKind.RETREAT),
                EnumSet.allOf(DefenseActionKind.class));

        LimitedSelfDefenseSession session = session(hostile(false, 4.0D));
        DefenseDecision completed = session.next(healthy(hostile(false, 4.0D)));
        Assertions.assertEquals(DefenseState.COMPLETED, completed.state());
        Assertions.assertEquals(DefenseReason.TARGET_ELIMINATED,
                completed.reason());
        Assertions.assertTrue(completed.action().isEmpty());
    }

    @Test
    void verifiedSuccessfulMeleeEliminationClosesWithoutAnotherObservation() {
        LimitedSelfDefenseSession session = session(hostile(true, 4.0D));
        DefenseActionRequest attack = session.next(
                healthy(hostile(true, 4.0D))).action().orElseThrow();

        Assertions.assertEquals(DefenseReceiptStatus.ACCEPTED,
                session.acknowledge(new DefenseActionReceipt(
                        attack, DefenseActionOutcome.SUCCEEDED), true));
        Assertions.assertEquals(DefenseState.COMPLETED,
                session.decision().state());
        Assertions.assertEquals(DefenseReason.TARGET_ELIMINATED,
                session.decision().reason());
        Assertions.assertTrue(session.decision().action().isEmpty());
    }

    @Test
    void incompleteOrMultipleThreatsNeverIssueMelee() {
        LimitedSelfDefenseSession incomplete = session(hostile(true, 4.0D));
        DefenseDecision incompleteDecision = incomplete.next(observation(
                10.0D, hostile(true, 4.0D), true, 1, Optional.empty()));
        Assertions.assertEquals(DefenseState.EXHAUSTED,
                incompleteDecision.state());
        Assertions.assertEquals(
                DefenseReason.RETREAT_THREAT_COVERAGE_INCOMPLETE,
                incompleteDecision.reason());
        Assertions.assertTrue(incompleteDecision.action().isEmpty());

        LimitedSelfDefenseSession multiple = session(hostile(true, 4.0D));
        DefenseDecision multipleDecision = multiple.next(observation(
                10.0D, hostile(true, 4.0D), false, 2, Optional.of(RETREAT)));
        Assertions.assertEquals(DefenseState.RETREAT_IN_FLIGHT,
                multipleDecision.state());
        Assertions.assertEquals(DefenseReason.RETREAT_MULTIPLE_THREATS,
                multipleDecision.reason());
        Assertions.assertEquals(DefenseActionKind.RETREAT,
                multipleDecision.action().orElseThrow().kind());
        Assertions.assertEquals(RETREAT,
                multipleDecision.action().orElseThrow()
                        .safeRetreat().orElseThrow());

        LimitedSelfDefenseSession noPath = session(hostile(true, 4.0D));
        DefenseDecision noPathDecision = noPath.next(observation(
                10.0D, hostile(true, 4.0D), false, 1, Optional.empty()));
        Assertions.assertEquals(DefenseState.EXHAUSTED,
                noPathDecision.state());
        Assertions.assertEquals(DefenseReason.RETREAT_PATH_UNAVAILABLE,
                noPathDecision.reason());
        Assertions.assertTrue(noPathDecision.action().isEmpty());
    }

    @Test
    void retreatAckRequiresFreshSafeObservationBeforeCompletion() {
        LimitedSelfDefenseSession stillThreatened = session(hostile(true, 4.0D));
        DefenseActionRequest firstRetreat = stillThreatened.next(observation(
                3.5D, hostile(true, 4.0D), false, 1, Optional.of(RETREAT)))
                .action().orElseThrow();
        acknowledge(stillThreatened, firstRetreat, DefenseActionOutcome.SUCCEEDED);
        Assertions.assertEquals(DefenseState.READY,
                stillThreatened.decision().state());
        Assertions.assertEquals(DefenseReason.RETREAT_COMPLETED,
                stillThreatened.decision().reason());

        DefenseDecision unresolved = stillThreatened.next(healthy(
                hostile(true, 4.0D)));
        Assertions.assertEquals(DefenseState.EXHAUSTED, unresolved.state());
        Assertions.assertEquals(DefenseReason.RETREAT_BUDGET_EXHAUSTED,
                unresolved.reason());
        Assertions.assertTrue(unresolved.action().isEmpty());

        LimitedSelfDefenseSession safelySeparated = session(hostile(true, 4.0D));
        DefenseActionRequest safeRetreat = safelySeparated.next(observation(
                3.5D, hostile(true, 4.0D), false, 1, Optional.of(RETREAT)))
                .action().orElseThrow();
        acknowledge(safelySeparated, safeRetreat, DefenseActionOutcome.SUCCEEDED);
        DefenseDecision completed = safelySeparated.next(healthy(
                hostile(true, 25.0D)));
        Assertions.assertEquals(DefenseState.COMPLETED, completed.state());
        Assertions.assertEquals(DefenseReason.SAFE_RETREAT_CONFIRMED,
                completed.reason());

        LimitedSelfDefenseSession noThreatRemains = session(hostile(true, 4.0D));
        DefenseActionRequest clearedRetreat = noThreatRemains.next(observation(
                3.5D, hostile(true, 4.0D), false, 1, Optional.of(RETREAT)))
                .action().orElseThrow();
        acknowledge(noThreatRemains, clearedRetreat,
                DefenseActionOutcome.SUCCEEDED);
        DefenseDecision cleared = noThreatRemains.next(observation(
                10.0D,
                new DefenseTarget(
                        TARGET_ID, DefenseTargetClass.UNKNOWN, true, 4.0D),
                false,
                0,
                Optional.empty()));
        Assertions.assertEquals(DefenseState.COMPLETED, cleared.state());
        Assertions.assertEquals(DefenseReason.THREAT_CLEARED,
                cleared.reason());
    }

    private static void assertRejected(
            DefenseTargetClass targetClass, DefenseReason expectedReason) {
        LimitedSelfDefenseSession session = session(new DefenseTarget(
                TARGET_ID, targetClass, true, 4.0D));
        Assertions.assertEquals(DefenseState.REJECTED,
                session.decision().state());
        Assertions.assertEquals(expectedReason, session.decision().reason());
        Assertions.assertTrue(session.decision().action().isEmpty());
    }

    private static LimitedSelfDefenseSession session(DefenseTarget target) {
        return new LimitedSelfDefenseSession(
                new DefenseRequest(RUN_ID, target), POLICY);
    }

    private static DefenseObservation healthy(DefenseTarget target) {
        return observation(10.0D, target, false, 1, Optional.of(RETREAT));
    }

    private static DefenseObservation observation(
            double health,
            DefenseTarget target,
            boolean coverageIncomplete,
            int hostileThreatCount,
            Optional<SafetyRetreat> safeRetreat) {
        return new DefenseObservation(
                health,
                10.0D,
                target,
                coverageIncomplete,
                hostileThreatCount,
                safeRetreat);
    }

    private static DefenseTarget hostile(boolean alive, double distanceSquared) {
        return new DefenseTarget(TARGET_ID,
                DefenseTargetClass.EXPLICIT_HOSTILE, alive, distanceSquared);
    }

    private static void acknowledge(
            LimitedSelfDefenseSession session,
            DefenseActionRequest action,
            DefenseActionOutcome outcome) {
        Assertions.assertEquals(DefenseReceiptStatus.ACCEPTED,
                session.acknowledge(new DefenseActionReceipt(action, outcome)));
    }
}
