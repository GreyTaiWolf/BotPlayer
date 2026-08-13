package io.github.greytaiwolf.botplayer.skill.core;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SkillRunStateTest {
    @Test
    void acceptsNormalWaitingVerificationAndSuccessPath() {
        SkillRunState.CREATED.requireTransitionTo(
                SkillRunState.PREPARING);
        SkillRunState.PREPARING.requireTransitionTo(
                SkillRunState.RUNNING);
        SkillRunState.RUNNING.requireTransitionTo(
                SkillRunState.WAITING_ACTION);
        SkillRunState.WAITING_ACTION.requireTransitionTo(
                SkillRunState.RUNNING);
        SkillRunState.RUNNING.requireTransitionTo(
                SkillRunState.VERIFYING);
        SkillRunState.VERIFYING.requireTransitionTo(
                SkillRunState.SUCCEEDED);
        Assertions.assertTrue(
                SkillRunState.SUCCEEDED.isTerminal());
    }

    @Test
    void acceptsPauseResumeAndRecoveryPaths() {
        Assertions.assertTrue(
                SkillRunState.CREATED.canTransitionTo(
                        SkillRunState.PAUSING));
        Assertions.assertTrue(
                SkillRunState.WAITING_NAVIGATION.canTransitionTo(
                        SkillRunState.PAUSING));
        Assertions.assertTrue(
                SkillRunState.PAUSING.canTransitionTo(
                        SkillRunState.PAUSED));
        Assertions.assertTrue(
                SkillRunState.PAUSED.canTransitionTo(
                        SkillRunState.RESUMING));
        Assertions.assertTrue(
                SkillRunState.RESUMING.canTransitionTo(
                        SkillRunState.RECOVERING));
        Assertions.assertTrue(
                SkillRunState.RECOVERING.canTransitionTo(
                        SkillRunState.PREPARING));
        Assertions.assertTrue(
                SkillRunState.VERIFYING.canTransitionTo(
                        SkillRunState.RECOVERING));
        Assertions.assertTrue(
                SkillRunState.VERIFYING.canTransitionTo(
                        SkillRunState.PAUSING));
        Assertions.assertTrue(
                SkillRunState.RESUMING.canTransitionTo(
                        SkillRunState.PAUSING));
    }

    @Test
    void rejectsSkippedPhasesAndEveryTerminalTransition() {
        Assertions.assertThrows(
                IllegalStateException.class,
                () -> SkillRunState.CREATED.requireTransitionTo(
                        SkillRunState.RUNNING));
        Assertions.assertThrows(
                IllegalStateException.class,
                () -> SkillRunState.PAUSED.requireTransitionTo(
                        SkillRunState.RUNNING));
        for (SkillRunState state : SkillRunState.values()) {
            if (!state.isTerminal()) {
                continue;
            }
            for (SkillRunState next : SkillRunState.values()) {
                Assertions.assertFalse(
                        state.canTransitionTo(next),
                        () -> state + " unexpectedly accepted " + next);
            }
        }
    }

    @Test
    void rejectsNullTransitionQueries() {
        Assertions.assertThrows(
                NullPointerException.class,
                () -> SkillRunState.RUNNING.canTransitionTo(null));
    }
}
