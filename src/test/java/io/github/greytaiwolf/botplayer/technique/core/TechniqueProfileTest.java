package io.github.greytaiwolf.botplayer.technique.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class TechniqueProfileTest {
    private static final UUID BOT_ID = UUID.fromString(
            "11111111-1111-1111-1111-111111111111");
    private static final UUID SKILL_RUN_ID = UUID.fromString(
            "22222222-2222-2222-2222-222222222222");
    private static final UUID TECHNIQUE_RUN_ID = UUID.fromString(
            "33333333-3333-3333-3333-333333333333");

    @Test
    void profileSeedIsStableAndBoundToAllIdentityInputs() {
        long first = TechniqueDeterministicSeed.derive(BOT_ID, SKILL_RUN_ID,
                TECHNIQUE_RUN_ID, "approach", 7L);
        assertEquals(first, TechniqueDeterministicSeed.derive(BOT_ID,
                SKILL_RUN_ID, TECHNIQUE_RUN_ID, "approach", 7L));
        assertNotEquals(first, TechniqueDeterministicSeed.derive(BOT_ID,
                SKILL_RUN_ID, TECHNIQUE_RUN_ID, "recover", 7L));
        assertNotEquals(first, TechniqueDeterministicSeed.derive(BOT_ID,
                SKILL_RUN_ID, TECHNIQUE_RUN_ID, "approach", 8L));
    }

    @Test
    void profileRejectsUnsafePresentationInputs() {
        assertThrows(IllegalArgumentException.class, () -> new TechniqueProfile(
                TechniqueSkillLevel.NOVICE, CombatStyle.DEFENSIVE,
                BuildingStyle.CAUTIOUS, Float.NaN, 0.5F, 0, 1, 10.0F,
                10.0F, 0L));
        assertThrows(IllegalArgumentException.class, () -> new TechniqueProfile(
                TechniqueSkillLevel.NOVICE, CombatStyle.DEFENSIVE,
                BuildingStyle.CAUTIOUS, 0.5F, 0.5F, 2, 1, 10.0F,
                10.0F, 0L));
    }
}
