package io.github.greytaiwolf.botplayer.skill.runtime;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SurvivalSkillSubmissionTest {
    @Test
    void onlyStartedSubmissionCarriesRunIdentity() {
        UUID runId = UUID.randomUUID();
        SurvivalSkillSubmission started =
                SurvivalSkillSubmission.started(
                        runId, "基础盔甲技能已启动");
        SurvivalSkillSubmission rejected =
                SurvivalSkillSubmission.rejected(
                        SurvivalSkillSubmission.Status.NO_UPGRADE,
                        "没有可用的盔甲升级");

        Assertions.assertTrue(started.accepted());
        Assertions.assertEquals(
                Optional.of(runId), started.runId());
        Assertions.assertFalse(rejected.accepted());
        Assertions.assertTrue(rejected.runId().isEmpty());
    }

    @Test
    void rejectsStatusIdentityMismatchAndUnsafeSummary() {
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new SurvivalSkillSubmission(
                        SurvivalSkillSubmission.Status.STARTED,
                        Optional.empty(),
                        "缺少 run"));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> SurvivalSkillSubmission.rejected(
                        SurvivalSkillSubmission.Status.STARTED,
                        "错误状态"));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> SurvivalSkillSubmission.rejected(
                        SurvivalSkillSubmission.Status.NO_UPGRADE,
                        " 含空白 "));
    }
}
