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
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanEdge;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WheatFarmingPlanCompilerTest {
    private static final UUID BOT_ID = new UUID(81L, 11L);
    private static final String DIGEST =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void compilesOnlyTheOrderedHarvestThenPlantPlan() {
        BlockCoordinates target = new BlockCoordinates(12, 64, -8);
        SkillPlan plan = WheatFarmingPlanCompiler.compile(BOT_ID, 7L, target);
        SkillPlan same = WheatFarmingPlanCompiler.compile(BOT_ID, 7L, target);
        SkillPlan nextRevision = WheatFarmingPlanCompiler.compile(
                BOT_ID, 8L, target);

        assertEquals(2, plan.nodes().size());
        assertEquals(WheatFarmingSkillIds.HARVEST_MATURE_WHEAT,
                plan.nodes().get(0).skillId());
        assertEquals(WheatFarmingSkillIds.PLANT_WHEAT,
                plan.nodes().get(1).skillId());
        assertEquals(WheatFarmingSkillIds.VERSION,
                plan.nodes().get(0).skillVersion());
        assertEquals(WheatFarmingPlanCompiler.targetParameters(target),
                plan.nodes().get(0).parameters());
        assertEquals(plan.nodes().get(0).parameters(),
                plan.nodes().get(1).parameters());
        assertEquals(List.of(new SkillPlanEdge(
                        plan.nodes().get(0).nodeId(),
                        plan.nodes().get(1).nodeId())),
                plan.edges());
        assertEquals(plan, same);
        assertNotEquals(plan.planId(), nextRevision.planId());
        assertNotEquals(plan.nodes().get(0).nodeId(),
                nextRevision.nodes().get(0).nodeId());

        for (SkillDescriptor descriptor : WheatFarmingPlanCompiler
                .descriptors()) {
            assertTrue(descriptor.parameterSchema().validate(
                    WheatFarmingPlanCompiler.targetParameters(target))
                    .valid());
            assertFalse(descriptor.resumable());
            assertEquals(0, descriptor.maximumRetries());
        }
    }

    @Test
    void onlyRemovesOneMatchingSeedAndLeavesEveryOtherIdentityUntouched() {
        ItemStackFingerprint seed = item("minecraft:wheat_seeds", 3);
        ItemStackFingerprint dirt = item("minecraft:dirt", 5);
        InventoryContentsSnapshot before = new InventoryContentsSnapshot(
                List.of(seed, dirt));

        InventoryContentsSnapshot after = WheatFarmingInventoryConservation
                .afterOneSeedConsumed(before, seed).orElseThrow();

        assertEquals(2, after.matchingCount(seed));
        assertEquals(5, after.matchingCount(dirt));
        assertEquals(item("minecraft:wheat_seeds", 2),
                WheatFarmingInventoryConservation
                        .selectedAfterOneSeedConsumed(seed));
        assertTrue(WheatFarmingInventoryConservation.afterOneSeedConsumed(
                before, item("minecraft:carrot", 1)).isEmpty());
    }

    @Test
    void consumingTheLastHeldSeedProducesTheCanonicalEmptyFingerprint() {
        ItemStackFingerprint seed = item("minecraft:wheat_seeds", 1);
        InventoryContentsSnapshot before = new InventoryContentsSnapshot(
                List.of(seed));

        assertTrue(WheatFarmingInventoryConservation.afterOneSeedConsumed(
                before, seed).orElseThrow().itemTotals().isEmpty());
        assertEquals(ItemStackFingerprint.empty(),
                WheatFarmingInventoryConservation
                        .selectedAfterOneSeedConsumed(seed));
    }

    @Test
    void collectedDropsAreAddedExactlyBeforeThePlantNodeDebitsOneSeed() {
        ItemStackFingerprint seed = item("minecraft:wheat_seeds", 2);
        ItemStackFingerprint wheat = item("minecraft:wheat", 1);
        InventoryContentsSnapshot harvestBefore = new InventoryContentsSnapshot(
                List.of(seed));

        InventoryContentsSnapshot afterHarvest =
                WheatFarmingInventoryConservation.afterCollectedDrops(
                        harvestBefore,
                        List.of(wheat, item("minecraft:wheat_seeds", 1),
                                item("minecraft:wheat_seeds", 1)))
                        .orElseThrow();
        InventoryContentsSnapshot afterPlant =
                WheatFarmingInventoryConservation.afterOneSeedConsumed(
                        afterHarvest, seed).orElseThrow();

        assertEquals(1, afterPlant.matchingCount(wheat));
        assertEquals(3, afterPlant.matchingCount(seed));
    }

    @Test
    void collectedDropsFailClosedWhenTheyWouldExceedPlayerInventoryCapacity() {
        java.util.ArrayList<ItemStackFingerprint> full =
                new java.util.ArrayList<>();
        /* Use 41 distinct item/component identities, so capacity—not an invalid
         * resource name—is the condition under test. */
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

        assertTrue(WheatFarmingInventoryConservation.afterCollectedDrops(
                before, List.of(item("minecraft:wheat", 1))).isEmpty());
    }

    private static ItemStackFingerprint item(String id, int count) {
        return ItemStackFingerprint.of(new ResourceId(id), count, 0, DIGEST);
    }
}
