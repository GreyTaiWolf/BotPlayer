package io.github.greytaiwolf.botplayer.skill.runtime;

import io.github.greytaiwolf.botplayer.skill.core.SkillRunState;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SurvivalSkillRunViewTest {
    private static final UUID RUN_ID = new UUID(1L, 1L);
    private static final UUID BOT_ID = new UUID(1L, 2L);

    @Test
    void exposesExplicitStateRevision() {
        SurvivalSkillRunView view = new SurvivalSkillRunView(
                RUN_ID,
                BOT_ID,
                3L,
                SurvivalSkillKind.EAT_FOOD,
                SkillRunState.WAITING_ACTION,
                7L,
                10L,
                12L,
                30L,
                2,
                Optional.empty(),
                "等待动作");

        Assertions.assertEquals(7L, view.stateRevision());
    }

    @Test
    void rejectsNegativeStateRevision() {
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new SurvivalSkillRunView(
                        RUN_ID,
                        BOT_ID,
                        3L,
                        SurvivalSkillKind.EAT_FOOD,
                        SkillRunState.WAITING_ACTION,
                        -1L,
                        10L,
                        12L,
                        30L,
                        2,
                        Optional.empty(),
                        "等待动作"));
    }
}
