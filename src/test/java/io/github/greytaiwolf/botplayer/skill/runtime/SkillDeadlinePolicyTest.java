package io.github.greytaiwolf.botplayer.skill.runtime;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SkillDeadlinePolicyTest {
    @Test
    void hardDeadlineStartsAtTheExactBoundary() {
        Assertions.assertEquals(
                SkillDeadlinePolicy
                        .PreSignalDecision
                        .PROCESS_SIGNALS,
                SkillDeadlinePolicy.beforeSignals(
                        159L, 160L));
        Assertions.assertEquals(
                SkillDeadlinePolicy
                        .PreSignalDecision
                        .HARD_TIMEOUT,
                SkillDeadlinePolicy.beforeSignals(
                        160L, 160L));
        Assertions.assertEquals(
                SkillDeadlinePolicy
                        .PreSignalDecision
                        .HARD_TIMEOUT,
                SkillDeadlinePolicy.beforeSignals(
                        161L, 160L));
    }

    @Test
    void delayedSuccessCannotOverrideHardTimeout() {
        Assertions.assertEquals(
                TerminalChoice.TIMEOUT,
                chooseTerminalAtTick(
                        160L,
                        160L,
                        TerminalChoice.SUCCESS));
    }

    @Test
    void delayedFailureCannotOverrideHardTimeout() {
        Assertions.assertEquals(
                TerminalChoice.TIMEOUT,
                chooseTerminalAtTick(
                        160L,
                        160L,
                        TerminalChoice.FAILURE));
    }

    @Test
    void workDeadlineStartsAtTheExactBoundary() {
        Assertions.assertFalse(
                SkillDeadlinePolicy
                        .shouldRequestWorkTimeout(
                                119L,
                                120L,
                                false,
                                false));
        Assertions.assertTrue(
                SkillDeadlinePolicy
                        .shouldRequestWorkTimeout(
                                120L,
                                120L,
                                false,
                                false));
        Assertions.assertTrue(
                SkillDeadlinePolicy
                        .shouldRequestWorkTimeout(
                                121L,
                                120L,
                                false,
                                false));
    }

    @Test
    void workDeadlineDoesNotDuplicateTerminalOrCleanupWork() {
        Assertions.assertFalse(
                SkillDeadlinePolicy
                        .shouldRequestWorkTimeout(
                                120L,
                                120L,
                                true,
                                false));
        Assertions.assertFalse(
                SkillDeadlinePolicy
                        .shouldRequestWorkTimeout(
                                120L,
                                120L,
                                false,
                                true));
    }

    private static TerminalChoice chooseTerminalAtTick(
            long currentTick,
            long hardDeadlineTick,
            TerminalChoice delayedSignalTerminal) {
        SkillDeadlinePolicy.PreSignalDecision decision =
                SkillDeadlinePolicy.beforeSignals(
                        currentTick, hardDeadlineTick);
        return decision
                        == SkillDeadlinePolicy
                                .PreSignalDecision
                                .HARD_TIMEOUT
                ? TerminalChoice.TIMEOUT
                : delayedSignalTerminal;
    }

    private enum TerminalChoice {
        SUCCESS,
        FAILURE,
        TIMEOUT
    }
}
