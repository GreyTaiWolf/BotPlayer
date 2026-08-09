package io.github.greytaiwolf.botplayer.skill.builtin.defense;

import java.util.EnumSet;
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
        DefenseDecision retreatForHealth = lowHealth.next(new DefenseObservation(
                3.5D, 10.0D, hostile(true, 4.0D)));
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
        return new DefenseObservation(10.0D, 10.0D, target);
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
