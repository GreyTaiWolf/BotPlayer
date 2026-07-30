package io.github.greytaiwolf.botplayer.skill.runtime;

import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SurvivalSkillPolicyTest {
    @Test
    void choosesNutritiousSafeFoodAndRejectsHarmfulFood() {
        int chosen = SurvivalSkillPolicy.chooseFood(List.of(
                        new SurvivalSkillPolicy.FoodOption(
                                9, 1, 0.2F, true, true, false),
                        new SurvivalSkillPolicy.FoodOption(
                                10, 5, 6.0F, false, true, false),
                        new SurvivalSkillPolicy.FoodOption(
                                2, 4, 8.0F, false, true, true)))
                .orElseThrow();

        Assertions.assertEquals(10, chosen);
    }

    @Test
    void foodTiePrefersAnItemAlreadyInHandThenLowerSlot() {
        int chosen = SurvivalSkillPolicy.chooseFood(List.of(
                        new SurvivalSkillPolicy.FoodOption(
                                12, 5, 6.0F, false, true, false),
                        new SurvivalSkillPolicy.FoodOption(
                                4, 5, 6.0F, false, true, true),
                        new SurvivalSkillPolicy.FoodOption(
                                3, 5, 6.0F, false, true, true)))
                .orElseThrow();

        Assertions.assertEquals(3, chosen);
    }

    @Test
    void refusesFoodThatIsUnsafeOrCannotCurrentlyBeEaten() {
        Assertions.assertTrue(
                SurvivalSkillPolicy.chooseFood(List.of(
                                new SurvivalSkillPolicy.FoodOption(
                                        9,
                                        8,
                                        12.0F,
                                        true,
                                        true,
                                        false),
                                new SurvivalSkillPolicy.FoodOption(
                                        10,
                                        8,
                                        12.0F,
                                        false,
                                        false,
                                        false)))
                        .isEmpty());
    }

}
