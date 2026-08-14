package io.github.greytaiwolf.botplayer.skill.builtin.breeding;

import io.github.greytaiwolf.botplayer.skill.core.SkillParameters;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlan;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class VanillaCowBreedingTest {
    private static final UUID BOT_ID = UUID.fromString(
            "11111111-1111-1111-1111-111111111111");
    private static final UUID FIRST_COW_ID = UUID.fromString(
            "22222222-2222-2222-2222-222222222222");
    private static final UUID SECOND_COW_ID = UUID.fromString(
            "33333333-3333-3333-3333-333333333333");

    @Test
    void compilerProducesTwoStrictlyOrderedClosedSchemaNodes() {
        VanillaCowBreeding.CowPairRequest request =
                new VanillaCowBreeding.CowPairRequest(
                        FIRST_COW_ID, SECOND_COW_ID, 4);

        SkillPlan plan = VanillaCowBreeding.compile(BOT_ID, 9L, request);
        SkillPlan repeated = VanillaCowBreeding.compile(BOT_ID, 9L, request);

        Assertions.assertEquals(plan, repeated);
        Assertions.assertEquals(2, plan.nodes().size());
        Assertions.assertEquals(1, plan.edges().size());
        Assertions.assertEquals(
                plan.nodes().get(0).nodeId(),
                plan.edges().get(0).prerequisiteNodeId());
        Assertions.assertEquals(
                plan.nodes().get(1).nodeId(),
                plan.edges().get(0).dependentNodeId());
        Assertions.assertEquals(VanillaCowBreeding.ID,
                plan.nodes().get(0).skillId());
        Assertions.assertEquals(VanillaCowBreeding.VERSION,
                plan.nodes().get(0).skillVersion());

        VanillaCowBreeding.FeedRequest first = VanillaCowBreeding.parse(
                plan.nodes().get(0).parameters()).orElseThrow();
        VanillaCowBreeding.FeedRequest second = VanillaCowBreeding.parse(
                plan.nodes().get(1).parameters()).orElseThrow();
        Assertions.assertEquals(VanillaCowBreeding.Phase.FIRST,
                first.phase());
        Assertions.assertEquals(VanillaCowBreeding.Phase.SECOND,
                second.phase());
        Assertions.assertEquals(FIRST_COW_ID, first.targetCowId());
        Assertions.assertEquals(SECOND_COW_ID, second.targetCowId());
        Assertions.assertEquals(FIRST_COW_ID, second.partnerCowId());
        Assertions.assertTrue(VanillaCowBreeding.descriptor()
                .parameterSchema()
                .validate(plan.nodes().get(0).parameters())
                .valid());
    }

    @Test
    void parserRejectsUnknownCoercedAndNonCanonicalInputs() {
        Map<String, Object> unknown = values();
        unknown.put("target.dimension", "minecraft:overworld");
        Assertions.assertTrue(VanillaCowBreeding.parse(
                new SkillParameters(unknown)).isEmpty());

        Map<String, Object> coercedSlot = values();
        coercedSlot.put(VanillaCowBreeding.FOOD_SLOT_PARAMETER, 4L);
        Assertions.assertTrue(VanillaCowBreeding.parse(
                new SkillParameters(coercedSlot)).isEmpty());

        Map<String, Object> nonCanonical = values();
        nonCanonical.put(VanillaCowBreeding.FIRST_COW_UUID_PARAMETER,
                "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA");
        Assertions.assertTrue(VanillaCowBreeding.parse(
                new SkillParameters(nonCanonical)).isEmpty());

        Map<String, Object> duplicate = values();
        duplicate.put(VanillaCowBreeding.SECOND_COW_UUID_PARAMETER,
                FIRST_COW_ID.toString());
        Assertions.assertTrue(VanillaCowBreeding.parse(
                new SkillParameters(duplicate)).isEmpty());

        Map<String, Object> invalidPhase = values();
        invalidPhase.put(VanillaCowBreeding.PHASE_PARAMETER, "FIRST");
        Assertions.assertTrue(VanillaCowBreeding.parse(
                new SkillParameters(invalidPhase)).isEmpty());
    }

    @Test
    void pairRequestRejectsZeroDuplicateAndNonHotbarIdentities() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new VanillaCowBreeding.CowPairRequest(
                        new UUID(0L, 0L), SECOND_COW_ID, 0));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new VanillaCowBreeding.CowPairRequest(
                        FIRST_COW_ID, FIRST_COW_ID, 0));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new VanillaCowBreeding.CowPairRequest(
                        FIRST_COW_ID, SECOND_COW_ID, 9));
    }

    private static Map<String, Object> values() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(VanillaCowBreeding.FIRST_COW_UUID_PARAMETER,
                FIRST_COW_ID.toString());
        values.put(VanillaCowBreeding.SECOND_COW_UUID_PARAMETER,
                SECOND_COW_ID.toString());
        values.put(VanillaCowBreeding.FOOD_SLOT_PARAMETER, 4);
        values.put(VanillaCowBreeding.PHASE_PARAMETER, "first");
        return values;
    }
}
