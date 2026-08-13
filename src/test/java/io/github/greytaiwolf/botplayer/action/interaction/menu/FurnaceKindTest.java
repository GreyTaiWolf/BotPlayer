package io.github.greytaiwolf.botplayer.action.interaction.menu;

import io.github.greytaiwolf.botplayer.action.interaction.BlockStateFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class FurnaceKindTest {
    @Test
    void eachKindFreezesItsOwnBlockRecipeTypeAndBoundedWaitBudget() {
        Assertions.assertAll(
                () -> Assertions.assertEquals("minecraft:furnace",
                        FurnaceKind.FURNACE.blockId().value()),
                () -> Assertions.assertEquals("minecraft:smelting",
                        FurnaceKind.FURNACE.recipeTypeStableId()),
                () -> Assertions.assertEquals("minecraft:blast_furnace",
                        FurnaceKind.BLAST_FURNACE.blockId().value()),
                () -> Assertions.assertEquals("minecraft:blasting",
                        FurnaceKind.BLAST_FURNACE.recipeTypeStableId()),
                () -> Assertions.assertEquals("minecraft:smoker",
                        FurnaceKind.SMOKER.blockId().value()),
                () -> Assertions.assertEquals("minecraft:smoking",
                        FurnaceKind.SMOKER.recipeTypeStableId()),
                () -> Assertions.assertTrue(
                        FurnaceKind.FURNACE.minimumTransactionTicks()
                                > FurnaceKind.BLAST_FURNACE
                                        .minimumTransactionTicks()),
                () -> Assertions.assertTrue(
                        FurnaceKind.BLAST_FURNACE.minimumTransactionTicks()
                                > FurnaceKind.SMOKER
                                        .minimumTransactionTicks()),
                () -> Assertions.assertEquals(1,
                        P5ARecipe.RAW_IRON_TO_IRON_INGOTS
                                .furnaceOutputPerInput()),
                () -> Assertions.assertEquals(1,
                        P5ARecipe.RAW_IRON_TO_IRON_INGOTS_BLASTING
                                .furnaceOutputPerInput()),
                () -> Assertions.assertEquals(1,
                        P5ARecipe.RAW_CHICKEN_TO_COOKED_CHICKEN_SMOKING
                                .furnaceOutputPerInput()));
    }

    @Test
    void stateValidationDoesNotCrossAcceptTheSharedFurnaceLayout() {
        BlockStateFingerprint blast = new BlockStateFingerprint(
                new ResourceId("minecraft:blast_furnace"),
                Map.of("facing", "north", "lit", "false"));
        BlockStateFingerprint smoker = new BlockStateFingerprint(
                new ResourceId("minecraft:smoker"),
                Map.of("facing", "north", "lit", "true"));

        Assertions.assertAll(
                () -> Assertions.assertTrue(FurnaceKind.BLAST_FURNACE
                        .matchesWorkstationState(blast)),
                () -> Assertions.assertTrue(FurnaceKind.SMOKER
                        .matchesWorkstationState(smoker)),
                () -> Assertions.assertFalse(FurnaceKind.FURNACE
                        .matchesWorkstationState(blast)),
                () -> Assertions.assertFalse(FurnaceKind.BLAST_FURNACE
                        .matchesWorkstationState(smoker)),
                () -> Assertions.assertFalse(FurnaceKind.SMOKER
                        .matchesWorkstationState(new BlockStateFingerprint(
                                new ResourceId("minecraft:smoker"),
                                Map.of("facing", "up", "lit", "false")))));
    }
}
