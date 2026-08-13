package io.github.greytaiwolf.botplayer.skill.runtime;

import io.github.greytaiwolf.botplayer.action.interaction.BlockCoordinates;
import io.github.greytaiwolf.botplayer.action.interaction.BlockStateFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.BlockTargetFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import io.github.greytaiwolf.botplayer.action.interaction.WorldInteractionActionSpec;
import io.github.greytaiwolf.botplayer.skill.core.SkillParameters;
import io.github.greytaiwolf.botplayer.skill.menu.MenuFamily;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class MinecraftSingleChestTransferSkillNodeHandlerTest {
    @Test
    void parsesOnlyTheExactCrossMenuChestToPlayerSchema() {
        MinecraftSingleChestTransferSkillNodeHandler.TransferRequest request =
                MinecraftSingleChestTransferSkillNodeHandler
                        .parseParameters(parameters(
                                12, 64, -8, 4, 35,
                                "chest_to_player"))
                        .orElseThrow();

        Assertions.assertEquals(
                new BlockCoordinates(12, 64, -8), request.target());
        Assertions.assertEquals(4, request.sourceSlot());
        Assertions.assertEquals(35, request.targetSlot());
        Assertions.assertEquals(
                MinecraftSingleChestTransferSkillNodeHandler
                        .TransferDirection.CHEST_TO_PLAYER,
                request.direction());
    }

    @Test
    void parsesOnlyTheExactCrossMenuPlayerToChestSchema() {
        MinecraftSingleChestTransferSkillNodeHandler.TransferRequest request =
                MinecraftSingleChestTransferSkillNodeHandler
                        .parseParameters(parameters(
                                -30, 70, 31, 62, 26,
                                "player_to_chest"))
                        .orElseThrow();

        Assertions.assertEquals(62, request.sourceSlot());
        Assertions.assertEquals(26, request.targetSlot());
        Assertions.assertEquals(
                MinecraftSingleChestTransferSkillNodeHandler
                        .TransferDirection.PLAYER_TO_CHEST,
                request.direction());
    }

    @Test
    void rejectsUnknownCoercedAndSemanticallyInvalidParameters() {
        Map<String, Object> extra = values(
                0, 64, 0, 0, 27, "chest_to_player");
        extra.put("target.dimension", "minecraft:overworld");
        Assertions.assertTrue(MinecraftSingleChestTransferSkillNodeHandler
                .parseParameters(new SkillParameters(extra)).isEmpty());

        Map<String, Object> coercedCoordinate = values(
                0, 64, 0, 0, 27, "chest_to_player");
        coercedCoordinate.put(
                MinecraftSingleChestTransferSkillNodeHandler
                        .TARGET_X_PARAMETER,
                0L);
        Assertions.assertTrue(MinecraftSingleChestTransferSkillNodeHandler
                .parseParameters(new SkillParameters(coercedCoordinate))
                .isEmpty());

        Assertions.assertTrue(MinecraftSingleChestTransferSkillNodeHandler
                .parseParameters(parameters(
                        0, 64, 0, 0, 27, "CHEST_TO_PLAYER"))
                .isEmpty());
        Assertions.assertTrue(MinecraftSingleChestTransferSkillNodeHandler
                .parseParameters(parameters(
                        0, 64, 0, 27, 0, "chest_to_player"))
                .isEmpty());
        Assertions.assertTrue(MinecraftSingleChestTransferSkillNodeHandler
                .parseParameters(parameters(
                        0, 64, 0, 26, 62, "player_to_chest"))
                .isEmpty());
        Assertions.assertTrue(MinecraftSingleChestTransferSkillNodeHandler
                .parseParameters(parameters(
                        0, 64, 0, 0, 63, "chest_to_player"))
                .isEmpty());
    }

    @Test
    void constructsOnlyAnEmptyMainHandExactThreeByNineChestTransfer() {
        MinecraftSingleChestTransferSkillNodeHandler.TransferRequest request =
                MinecraftSingleChestTransferSkillNodeHandler
                        .parseParameters(parameters(
                                12, 64, -8, 4, 35,
                                "chest_to_player"))
                        .orElseThrow();
        BlockTargetFingerprint target = chestAt(12, 64, -8);

        WorldInteractionActionSpec.WorldMenuTransfer action =
                MinecraftSingleChestTransferSkillNodeHandler.transferAction(
                        request, target);

        Assertions.assertEquals(
                WorldInteractionActionSpec.Hand.MAIN_HAND, action.hand());
        Assertions.assertEquals(ItemStackFingerprint.empty(),
                action.expectedHeldItem());
        Assertions.assertEquals(MenuFamily.CHEST_3X9, action.family());
        Assertions.assertEquals(4, action.sourceSlot());
        Assertions.assertEquals(35, action.targetSlot());
        Assertions.assertEquals(target, action.opener().target());
        Assertions.assertEquals(
                io.github.greytaiwolf.botplayer.action.interaction
                        .BlockHitTarget.Face.UP,
                action.opener().face());
        Assertions.assertEquals(0.5D, action.opener().localX());
        Assertions.assertEquals(1.0D, action.opener().localY());
        Assertions.assertEquals(0.5D, action.opener().localZ());
        Assertions.assertEquals(3, action.limits().maxClicks());
        Assertions.assertEquals(240L, action.limits().maxTicks());
    }

    @Test
    void actionRefusesDifferentCoordinatesOrNonSingleChestSnapshots() {
        MinecraftSingleChestTransferSkillNodeHandler.TransferRequest request =
                MinecraftSingleChestTransferSkillNodeHandler
                        .parseParameters(parameters(
                                12, 64, -8, 4, 35,
                                "chest_to_player"))
                        .orElseThrow();

        Assertions.assertThrows(IllegalArgumentException.class,
                () -> MinecraftSingleChestTransferSkillNodeHandler
                        .transferAction(request, chestAt(13, 64, -8)));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> MinecraftSingleChestTransferSkillNodeHandler
                        .transferAction(request, new BlockTargetFingerprint(
                                new ResourceId("minecraft:overworld"),
                                request.target(),
                                new BlockStateFingerprint(
                                        new ResourceId("minecraft:barrel"),
                                        Map.of()))));
    }

    private static SkillParameters parameters(
            int x,
            int y,
            int z,
            int source,
            int target,
            String direction) {
        return new SkillParameters(values(
                x, y, z, source, target, direction));
    }

    private static Map<String, Object> values(
            int x,
            int y,
            int z,
            int source,
            int target,
            String direction) {
        Map<String, Object> values = new HashMap<>();
        values.put(MinecraftSingleChestTransferSkillNodeHandler
                .TARGET_X_PARAMETER, x);
        values.put(MinecraftSingleChestTransferSkillNodeHandler
                .TARGET_Y_PARAMETER, y);
        values.put(MinecraftSingleChestTransferSkillNodeHandler
                .TARGET_Z_PARAMETER, z);
        values.put(MinecraftSingleChestTransferSkillNodeHandler
                .SOURCE_SLOT_PARAMETER, source);
        values.put(MinecraftSingleChestTransferSkillNodeHandler
                .TARGET_SLOT_PARAMETER, target);
        values.put(MinecraftSingleChestTransferSkillNodeHandler
                .DIRECTION_PARAMETER, direction);
        return values;
    }

    private static BlockTargetFingerprint chestAt(int x, int y, int z) {
        return new BlockTargetFingerprint(
                new ResourceId("minecraft:overworld"),
                new BlockCoordinates(x, y, z),
                new BlockStateFingerprint(
                        new ResourceId("minecraft:chest"),
                        Map.of(
                                "facing", "north",
                                "type", "single",
                                "waterlogged", "false")));
    }
}
