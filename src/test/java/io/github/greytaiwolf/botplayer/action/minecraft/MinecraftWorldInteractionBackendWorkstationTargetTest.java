package io.github.greytaiwolf.botplayer.action.minecraft;

import io.github.greytaiwolf.botplayer.action.interaction.BlockCoordinates;
import io.github.greytaiwolf.botplayer.action.interaction.BlockStateFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.BlockTargetFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import io.github.greytaiwolf.botplayer.action.interaction.menu.FurnaceKind;
import io.github.greytaiwolf.botplayer.action.interaction.menu.P5ARecipe;
import java.util.Map;
import java.util.Optional;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.BlastFurnaceMenu;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.inventory.SmokerMenu;
import net.minecraft.world.item.crafting.RecipeType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class MinecraftWorldInteractionBackendWorkstationTargetTest {
    @Test
    void acceptsOnlyExactVanillaWorkstationFingerprintsForWorldRecipes() {
        Assertions.assertTrue(workstationAllowed(
                "minecraft:crafting_table",
                Map.of(),
                P5ARecipe.WOODEN_PICKAXE));
        Assertions.assertTrue(workstationAllowed(
                "minecraft:furnace",
                Map.of("facing", "west", "lit", "false"),
                P5ARecipe.RAW_IRON_TO_IRON_INGOTS));
        Assertions.assertTrue(workstationAllowed(
                "minecraft:furnace",
                Map.of("facing", "east", "lit", "true"),
                P5ARecipe.RAW_IRON_TO_IRON_INGOTS));
        Assertions.assertTrue(workstationAllowed(
                "minecraft:blast_furnace",
                Map.of("facing", "east", "lit", "true"),
                P5ARecipe.RAW_IRON_TO_IRON_INGOTS_BLASTING));
        Assertions.assertTrue(workstationAllowed(
                "minecraft:smoker",
                Map.of("facing", "south", "lit", "false"),
                P5ARecipe.RAW_CHICKEN_TO_COOKED_CHICKEN_SMOKING));
    }

    @Test
    void rejectsCrossFamilyVariantAndMalformedWorkstationTargets() {
        Assertions.assertFalse(workstationAllowed(
                "minecraft:furnace",
                Map.of("facing", "north", "lit", "false"),
                P5ARecipe.WOODEN_PICKAXE));
        Assertions.assertFalse(workstationAllowed(
                "minecraft:blast_furnace",
                Map.of("facing", "north", "lit", "false"),
                P5ARecipe.RAW_IRON_TO_IRON_INGOTS));
        Assertions.assertFalse(workstationAllowed(
                "minecraft:furnace",
                Map.of("facing", "north", "lit", "false"),
                P5ARecipe.RAW_IRON_TO_IRON_INGOTS_BLASTING));
        Assertions.assertFalse(workstationAllowed(
                "minecraft:smoker",
                Map.of("facing", "north", "lit", "false"),
                P5ARecipe.RAW_IRON_TO_IRON_INGOTS));
        Assertions.assertFalse(workstationAllowed(
                "minecraft:blast_furnace",
                Map.of("facing", "north", "lit", "false"),
                P5ARecipe.RAW_CHICKEN_TO_COOKED_CHICKEN_SMOKING));
        Assertions.assertFalse(workstationAllowed(
                "minecraft:crafting_table",
                Map.of("lit", "false"),
                P5ARecipe.WOODEN_PICKAXE));
        Assertions.assertFalse(workstationAllowed(
                "minecraft:furnace",
                Map.of("facing", "up", "lit", "false"),
                P5ARecipe.RAW_IRON_TO_IRON_INGOTS));
        Assertions.assertFalse(workstationAllowed(
                "example:crafting_table",
                Map.of(),
                P5ARecipe.WOODEN_PICKAXE));
    }

    @Test
    void mapsOnlyExactMojangFurnaceMenuClassesToTheirTypedContracts() {
        Assertions.assertAll(
                () -> Assertions.assertEquals(Optional.of(
                        FurnaceKind.FURNACE),
                        MinecraftWorldInteractionBackend
                                .exactVanillaFurnaceKind(
                                        FurnaceMenu.class)),
                () -> Assertions.assertEquals(Optional.of(
                        FurnaceKind.BLAST_FURNACE),
                        MinecraftWorldInteractionBackend
                                .exactVanillaFurnaceKind(
                                        BlastFurnaceMenu.class)),
                () -> Assertions.assertEquals(Optional.of(
                        FurnaceKind.SMOKER),
                        MinecraftWorldInteractionBackend
                                .exactVanillaFurnaceKind(
                                        SmokerMenu.class)),
                () -> Assertions.assertTrue(MinecraftWorldInteractionBackend
                        .exactVanillaFurnaceKind(
                                AbstractContainerMenu.class).isEmpty()),
                () -> Assertions.assertTrue(MinecraftWorldInteractionBackend
                        .exactVanillaFurnaceKind(
                                AbstractFurnaceMenu.class).isEmpty()),
                () -> Assertions.assertEquals(RecipeType.SMELTING,
                        MinecraftWorldInteractionBackend
                                .exactVanillaFurnaceRecipeType(
                                        FurnaceKind.FURNACE)),
                () -> Assertions.assertEquals(RecipeType.BLASTING,
                        MinecraftWorldInteractionBackend
                                .exactVanillaFurnaceRecipeType(
                                        FurnaceKind.BLAST_FURNACE)),
                () -> Assertions.assertEquals(RecipeType.SMOKING,
                        MinecraftWorldInteractionBackend
                                .exactVanillaFurnaceRecipeType(
                                        FurnaceKind.SMOKER)));
    }

    private static boolean workstationAllowed(
            String blockId,
            Map<String, String> properties,
            P5ARecipe recipe) {
        return MinecraftWorldInteractionBackend
                .isAllowedVanillaWorkstationTarget(
                        new BlockTargetFingerprint(
                                new ResourceId("minecraft:overworld"),
                                new BlockCoordinates(1, 64, 1),
                                new BlockStateFingerprint(
                                        new ResourceId(blockId),
                                        properties)),
                        recipe);
    }
}
