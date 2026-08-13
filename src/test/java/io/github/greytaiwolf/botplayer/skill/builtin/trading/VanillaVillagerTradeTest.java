package io.github.greytaiwolf.botplayer.skill.builtin.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.greytaiwolf.botplayer.skill.core.SkillParameters;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlan;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VanillaVillagerTradeTest {
    private static final UUID BOT_ID = UUID.fromString(
            "11111111-1111-1111-1111-111111111111");
    private static final UUID VILLAGER_ID = UUID.fromString(
            "22222222-2222-2222-2222-222222222222");

    @Test
    void compilerProducesOneDeterministicClosedSchemaNode() {
        VanillaVillagerTrade.TradeRequest request =
                new VanillaVillagerTrade.TradeRequest(VILLAGER_ID, 3, 9, 0);

        SkillPlan plan = VanillaVillagerTrade.compile(BOT_ID, 7L, request);
        SkillPlan repeated = VanillaVillagerTrade.compile(BOT_ID, 7L, request);

        assertEquals(plan, repeated);
        assertEquals(1, plan.nodes().size());
        assertTrue(plan.edges().isEmpty());
        assertEquals(VanillaVillagerTrade.ID, plan.nodes().get(0).skillId());
        assertEquals(VanillaVillagerTrade.VERSION,
                plan.nodes().get(0).skillVersion());
        assertEquals(request, VanillaVillagerTrade.parse(
                plan.nodes().get(0).parameters()).orElseThrow());
        assertTrue(VanillaVillagerTrade.descriptor().parameterSchema().validate(
                plan.nodes().get(0).parameters()).valid());
    }

    @Test
    void parserRejectsAliasesCoercionsUnknownFieldsAndUnsafeSlots() {
        Map<String, Object> unknown = values();
        unknown.put("villager.dimension", "minecraft:overworld");
        assertTrue(VanillaVillagerTrade.parse(
                new SkillParameters(unknown)).isEmpty());

        Map<String, Object> coerced = values();
        coerced.put(VanillaVillagerTrade.OFFER_INDEX_PARAMETER, 3L);
        assertTrue(VanillaVillagerTrade.parse(
                new SkillParameters(coerced)).isEmpty());

        Map<String, Object> uppercase = values();
        uppercase.put(VanillaVillagerTrade.VILLAGER_ID_PARAMETER,
                "abcdefab-cdef-abcd-efab-cdefabcdefab".toUpperCase());
        assertTrue(VanillaVillagerTrade.parse(
                new SkillParameters(uppercase)).isEmpty());

        Map<String, Object> sameSlot = values();
        sameSlot.put(VanillaVillagerTrade.OUTPUT_INVENTORY_SLOT_PARAMETER, 9);
        assertTrue(VanillaVillagerTrade.parse(
                new SkillParameters(sameSlot)).isEmpty());

        Map<String, Object> tooLargeOffer = values();
        tooLargeOffer.put(VanillaVillagerTrade.OFFER_INDEX_PARAMETER,
                VanillaVillagerTrade.MAXIMUM_OFFER_INDEX + 1);
        assertTrue(VanillaVillagerTrade.parse(
                new SkillParameters(tooLargeOffer)).isEmpty());
    }

    @Test
    void requestRejectsZeroIdentityUnsafeFieldsAndNonPositiveRevision() {
        assertThrows(IllegalArgumentException.class, () ->
                new VanillaVillagerTrade.TradeRequest(new UUID(0L, 0L), 0, 9, 0));
        assertThrows(IllegalArgumentException.class, () ->
                new VanillaVillagerTrade.TradeRequest(VILLAGER_ID, 0, 36, 0));
        assertThrows(IllegalArgumentException.class, () ->
                VanillaVillagerTrade.compile(BOT_ID, 0L,
                        new VanillaVillagerTrade.TradeRequest(
                                VILLAGER_ID, 0, 9, 0)));
        assertFalse(VanillaVillagerTrade.parse(new SkillParameters(Map.of(
                VanillaVillagerTrade.VILLAGER_ID_PARAMETER,
                "00000000-0000-0000-0000-000000000000",
                VanillaVillagerTrade.OFFER_INDEX_PARAMETER, 0,
                VanillaVillagerTrade.SOURCE_INVENTORY_SLOT_PARAMETER, 9,
                VanillaVillagerTrade.OUTPUT_INVENTORY_SLOT_PARAMETER, 0))).isPresent());
    }

    private static Map<String, Object> values() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(VanillaVillagerTrade.VILLAGER_ID_PARAMETER,
                VILLAGER_ID.toString());
        values.put(VanillaVillagerTrade.OFFER_INDEX_PARAMETER, 3);
        values.put(VanillaVillagerTrade.SOURCE_INVENTORY_SLOT_PARAMETER, 9);
        values.put(VanillaVillagerTrade.OUTPUT_INVENTORY_SLOT_PARAMETER, 0);
        return values;
    }
}
