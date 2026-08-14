package io.github.greytaiwolf.botplayer.skill.builtin.recovery;

import io.github.greytaiwolf.botplayer.skill.core.SkillParameters;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlan;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class VanillaMilkBucketRecoveryTest {
    private static final UUID BOT_ID = UUID.fromString(
            "11111111-1111-1111-1111-111111111111");

    @Test
    void compilerEmitsOneDeterministicClosedSchemaNode() {
        VanillaMilkBucketRecovery.MilkRequest request =
                new VanillaMilkBucketRecovery.MilkRequest(4);
        SkillPlan plan = VanillaMilkBucketRecovery.compile(BOT_ID, 9L,
                request);
        SkillPlan repeated = VanillaMilkBucketRecovery.compile(BOT_ID, 9L,
                request);

        Assertions.assertEquals(plan, repeated);
        Assertions.assertEquals(1, plan.nodes().size());
        Assertions.assertTrue(plan.edges().isEmpty());
        Assertions.assertEquals(VanillaMilkBucketRecovery.ID,
                plan.nodes().get(0).skillId());
        Assertions.assertEquals(VanillaMilkBucketRecovery.VERSION,
                plan.nodes().get(0).skillVersion());
        Assertions.assertEquals(request, VanillaMilkBucketRecovery.parse(
                plan.nodes().get(0).parameters()).orElseThrow());
        Assertions.assertTrue(VanillaMilkBucketRecovery.descriptor()
                .parameterSchema().validate(plan.nodes().get(0).parameters())
                .valid());
    }

    @Test
    void parserRejectsUnknownCoercedAndOutOfRangeValues() {
        Map<String, Object> unknown = values();
        unknown.put("effect.id", "minecraft:wither");
        Assertions.assertTrue(VanillaMilkBucketRecovery.parse(
                new SkillParameters(unknown)).isEmpty());

        Map<String, Object> coerced = values();
        coerced.put(VanillaMilkBucketRecovery.HOTBAR_SLOT_PARAMETER, 4L);
        Assertions.assertTrue(VanillaMilkBucketRecovery.parse(
                new SkillParameters(coerced)).isEmpty());

        Map<String, Object> outOfRange = values();
        outOfRange.put(VanillaMilkBucketRecovery.HOTBAR_SLOT_PARAMETER, 9);
        Assertions.assertTrue(VanillaMilkBucketRecovery.parse(
                new SkillParameters(outOfRange)).isEmpty());
    }

    @Test
    void compilerRejectsZeroIdentityAndNonHotbarRequest() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> VanillaMilkBucketRecovery.compile(new UUID(0L, 0L),
                        1L,
                        new VanillaMilkBucketRecovery.MilkRequest(0)));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> VanillaMilkBucketRecovery.compile(BOT_ID, 0L,
                        new VanillaMilkBucketRecovery.MilkRequest(0)));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new VanillaMilkBucketRecovery.MilkRequest(-1));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new VanillaMilkBucketRecovery.MilkRequest(9));
    }

    private static Map<String, Object> values() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(VanillaMilkBucketRecovery.HOTBAR_SLOT_PARAMETER, 4);
        return values;
    }
}
