package io.github.greytaiwolf.botplayer.skill.builtin.farming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.greytaiwolf.botplayer.action.interaction.BlockCoordinates;
import io.github.greytaiwolf.botplayer.action.interaction.InventoryContentsSnapshot;
import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import io.github.greytaiwolf.botplayer.skill.core.SkillDescriptor;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlan;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SugarCaneFarmingPlanCompilerTest {
    private static final UUID BOT_ID = new UUID(83L, 17L);
    private static final String DIGEST =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void compilesOnlyOneStableUpperCaneHarvestNode() {
        BlockCoordinates target = new BlockCoordinates(12, 64, -8);
        SkillPlan plan = SugarCaneFarmingPlanCompiler.compile(BOT_ID, 7L,
                target);
        SkillPlan same = SugarCaneFarmingPlanCompiler.compile(BOT_ID, 7L,
                target);
        SkillPlan nextRevision = SugarCaneFarmingPlanCompiler.compile(BOT_ID,
                8L, target);

        assertEquals(1, plan.nodes().size());
        assertEquals(SugarCaneFarmingSkillIds.HARVEST_UPPER_SUGAR_CANE,
                plan.nodes().get(0).skillId());
        assertEquals(SugarCaneFarmingSkillIds.VERSION,
                plan.nodes().get(0).skillVersion());
        assertEquals(SugarCaneFarmingPlanCompiler.targetParameters(target),
                plan.nodes().get(0).parameters());
        assertTrue(plan.edges().isEmpty());
        assertEquals(plan, same);
        assertNotEquals(plan.planId(), nextRevision.planId());
        assertNotEquals(plan.nodes().get(0).nodeId(), nextRevision.nodes()
                .get(0).nodeId());

        for (SkillDescriptor descriptor : SugarCaneFarmingPlanCompiler
                .descriptors()) {
            assertTrue(descriptor.parameterSchema().validate(
                    SugarCaneFarmingPlanCompiler.targetParameters(target))
                    .valid());
            assertFalse(descriptor.resumable());
            assertEquals(0, descriptor.maximumRetries());
        }
    }

    @Test
    void addsOnlyTheProvenOneCaneReceiptToTheInventoryLedger() {
        ItemStackFingerprint dirt = item("minecraft:dirt", 5);
        ItemStackFingerprint cane = item("minecraft:sugar_cane", 1);
        InventoryContentsSnapshot before = new InventoryContentsSnapshot(
                List.of(dirt));

        InventoryContentsSnapshot after = SugarCaneFarmingInventoryConservation
                .afterCollectedDrop(before, cane).orElseThrow();

        assertEquals(5, after.matchingCount(dirt));
        assertEquals(1, after.matchingCount(cane));
        assertTrue(SugarCaneFarmingInventoryConservation.afterCollectedDrop(
                before, ItemStackFingerprint.empty()).isEmpty());
    }

    @Test
    void refusesAnAdditionalIdentityWhenTheBoundedInventoryIsFull() {
        java.util.ArrayList<ItemStackFingerprint> full =
                new java.util.ArrayList<>();
        for (int index = 0;
                index < InventoryContentsSnapshot.MAX_INPUT_STACKS;
                index++) {
            full.add(ItemStackFingerprint.of(
                    new ResourceId("minecraft:stone"),
                    1,
                    index,
                    String.format("%064x", index)));
        }
        InventoryContentsSnapshot before = new InventoryContentsSnapshot(full);

        assertTrue(SugarCaneFarmingInventoryConservation.afterCollectedDrop(
                before, item("minecraft:sugar_cane", 1)).isEmpty());
    }

    private static ItemStackFingerprint item(String id, int count) {
        return ItemStackFingerprint.of(new ResourceId(id), count, 0, DIGEST);
    }
}
