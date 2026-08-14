package io.github.greytaiwolf.botplayer.action.minecraft;

import io.github.greytaiwolf.botplayer.action.interaction.BlockCoordinates;
import io.github.greytaiwolf.botplayer.action.interaction.BlockStateFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.BlockTargetFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import io.github.greytaiwolf.botplayer.skill.menu.MenuFamily;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class MinecraftWorldInteractionBackendContainerTargetTest {
    @Test
    void acceptsOnlyTheP5bVanillaContainerLayoutPairs() {
        Assertions.assertTrue(allowed(
                "minecraft:chest",
                Map.of(
                        "facing", "north",
                        "type", "single",
                        "waterlogged", "false"),
                MenuFamily.CHEST_3X9));
        Assertions.assertTrue(allowed(
                "minecraft:chest",
                Map.of(
                        "facing", "north",
                        "type", "left",
                        "waterlogged", "false"),
                MenuFamily.CHEST_6X9));
        Assertions.assertTrue(allowed(
                "minecraft:barrel",
                Map.of("facing", "up", "open", "true"),
                MenuFamily.CHEST_3X9));
        Assertions.assertTrue(allowed(
                "minecraft:ender_chest",
                Map.of("facing", "north", "waterlogged", "false"),
                MenuFamily.CHEST_3X9));
        Assertions.assertTrue(allowed(
                "minecraft:purple_shulker_box",
                Map.of("facing", "down"),
                MenuFamily.CHEST_3X9));
    }

    @Test
    void rejectsWrongShapeMalformedEnderAndCustomContainerTargetsBeforeOpening() {
        Assertions.assertFalse(allowed(
                "minecraft:chest",
                Map.of(
                        "facing", "north",
                        "type", "left",
                        "waterlogged", "false"),
                MenuFamily.CHEST_3X9));
        Assertions.assertFalse(allowed(
                "minecraft:chest",
                Map.of(
                        "facing", "north",
                        "type", "single",
                        "waterlogged", "false"),
                MenuFamily.CHEST_6X9));
        Assertions.assertFalse(allowed(
                "minecraft:chest",
                Map.of(
                        "facing", "up",
                        "type", "single",
                        "waterlogged", "false"),
                MenuFamily.CHEST_3X9));
        Assertions.assertFalse(allowed(
                "minecraft:ender_chest", Map.of(), MenuFamily.CHEST_3X9));
        Assertions.assertFalse(allowed(
                "minecraft:ender_chest",
                Map.of("facing", "north"), MenuFamily.CHEST_3X9));
        Assertions.assertFalse(allowed(
                "minecraft:ender_chest",
                Map.of("facing", "up", "waterlogged", "false"),
                MenuFamily.CHEST_3X9));
        Assertions.assertFalse(allowed(
                "minecraft:ender_chest",
                Map.of(
                        "facing", "north",
                        "waterlogged", "false",
                        "open", "false"),
                MenuFamily.CHEST_3X9));
        Assertions.assertFalse(allowed(
                "minecraft:ender_chest",
                Map.of("facing", "north", "waterlogged", "maybe"),
                MenuFamily.CHEST_3X9));
        Assertions.assertFalse(allowed(
                "minecraft:ender_chest",
                Map.of("facing", "north", "waterlogged", "false"),
                MenuFamily.CHEST_6X9));
        Assertions.assertFalse(allowed(
                "example:looks_like_a_chest",
                Map.of(
                        "facing", "north",
                        "type", "single",
                        "waterlogged", "false"),
                MenuFamily.CHEST_3X9));
        Assertions.assertFalse(allowed(
                "minecraft:barrel",
                Map.of("facing", "north"),
                MenuFamily.CHEST_3X9));
    }

    private static boolean allowed(
            String blockId, Map<String, String> properties, MenuFamily family) {
        return MinecraftWorldInteractionBackend.isAllowedVanillaContainerTarget(
                new BlockTargetFingerprint(
                        new ResourceId("minecraft:overworld"),
                        new BlockCoordinates(1, 64, 1),
                        new BlockStateFingerprint(
                                new ResourceId(blockId), properties)),
                family);
    }
}
