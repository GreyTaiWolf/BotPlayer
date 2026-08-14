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
                                12, 64, -8, 4, 35, 5,
                                "chest_to_player"))
                        .orElseThrow();

        Assertions.assertEquals(
                new BlockCoordinates(12, 64, -8), request.target());
        Assertions.assertEquals(4, request.sourceSlot());
        Assertions.assertEquals(35, request.targetSlot());
        Assertions.assertEquals(5, request.amount());
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
                                -30, 70, 31, 62, 26, 7,
                                "player_to_chest"))
                        .orElseThrow();

        Assertions.assertEquals(62, request.sourceSlot());
        Assertions.assertEquals(26, request.targetSlot());
        Assertions.assertEquals(7, request.amount());
        Assertions.assertEquals(
                MinecraftSingleChestTransferSkillNodeHandler
                        .TransferDirection.PLAYER_TO_CHEST,
                request.direction());
    }

    @Test
    void rejectsUnknownCoercedAndSemanticallyInvalidParameters() {
        Map<String, Object> extra = values(
                0, 64, 0, 0, 27, 1, "chest_to_player");
        extra.put("target.dimension", "minecraft:overworld");
        Assertions.assertTrue(MinecraftSingleChestTransferSkillNodeHandler
                .parseParameters(new SkillParameters(extra)).isEmpty());

        Map<String, Object> coercedCoordinate = values(
                0, 64, 0, 0, 27, 1, "chest_to_player");
        coercedCoordinate.put(
                MinecraftSingleChestTransferSkillNodeHandler
                        .TARGET_X_PARAMETER,
                0L);
        Assertions.assertTrue(MinecraftSingleChestTransferSkillNodeHandler
                .parseParameters(new SkillParameters(coercedCoordinate))
                .isEmpty());

        Assertions.assertTrue(MinecraftSingleChestTransferSkillNodeHandler
                .parseParameters(parameters(
                        0, 64, 0, 0, 27, 1, "CHEST_TO_PLAYER"))
                .isEmpty());
        Assertions.assertTrue(MinecraftSingleChestTransferSkillNodeHandler
                .parseParameters(parameters(
                        0, 64, 0, 0, 27, 33, "chest_to_player"))
                .isEmpty());
        Assertions.assertTrue(MinecraftSingleChestTransferSkillNodeHandler
                .parseParameters(parameters(
                        0, 64, 0, 27, 0, 1, "chest_to_player"))
                .isEmpty());
        Assertions.assertTrue(MinecraftSingleChestTransferSkillNodeHandler
                .parseParameters(parameters(
                        0, 64, 0, 26, 62, 1, "player_to_chest"))
                .isEmpty());
        Assertions.assertTrue(MinecraftSingleChestTransferSkillNodeHandler
                .parseParameters(parameters(
                        0, 64, 0, 0, 90, 1, "chest_to_player"))
                .isEmpty());
    }

    @Test
    void acceptsFullAndExactTransfersOnlyForTheObservedContainerShape() {
        MinecraftSingleChestTransferSkillNodeHandler.TransferRequest threeByNine =
                MinecraftSingleChestTransferSkillNodeHandler
                        .parseParameters(parameters(
                                0, 64, 0, 0, 27, 0,
                                "chest_to_player"))
                        .orElseThrow();
        MinecraftSingleChestTransferSkillNodeHandler.TransferRequest sixByNine =
                MinecraftSingleChestTransferSkillNodeHandler
                        .parseParameters(parameters(
                                0, 64, 0, 53, 54, 5,
                                "chest_to_player"))
                        .orElseThrow();
        MinecraftSingleChestTransferSkillNodeHandler.TransferRequest deposit =
                MinecraftSingleChestTransferSkillNodeHandler
                        .parseParameters(parameters(
                                0, 64, 0, 54, 53, 0,
                                "player_to_chest"))
                        .orElseThrow();

        Assertions.assertEquals(0, threeByNine.amount());
        Assertions.assertTrue(threeByNine.matchesFamily(
                MenuFamily.CHEST_3X9));
        Assertions.assertFalse(threeByNine.matchesFamily(
                MenuFamily.CHEST_6X9));
        Assertions.assertTrue(sixByNine.matchesFamily(
                MenuFamily.CHEST_6X9));
        Assertions.assertFalse(sixByNine.matchesFamily(
                MenuFamily.CHEST_3X9));
        Assertions.assertTrue(deposit.matchesFamily(MenuFamily.CHEST_6X9));
        Assertions.assertFalse(deposit.matchesFamily(MenuFamily.CHEST_3X9));
    }

    @Test
    void constructsOnlyAnEmptyMainHandExactThreeByNineChestTransfer() {
        MinecraftSingleChestTransferSkillNodeHandler.TransferRequest request =
                MinecraftSingleChestTransferSkillNodeHandler
                        .parseParameters(parameters(
                                12, 64, -8, 4, 35, 5,
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
        Assertions.assertEquals(5, action.requestedAmount());
        Assertions.assertEquals(target, action.opener().target());
        Assertions.assertEquals(
                io.github.greytaiwolf.botplayer.action.interaction
                        .BlockHitTarget.Face.UP,
                action.opener().face());
        Assertions.assertEquals(0.5D, action.opener().localX());
        Assertions.assertEquals(1.0D, action.opener().localY());
        Assertions.assertEquals(0.5D, action.opener().localZ());
        Assertions.assertEquals(34, action.limits().maxClicks());
        Assertions.assertEquals(240L, action.limits().maxTicks());
    }

    @Test
    void actionAcceptsOnlyWhitelistedContainersAndMatchingLayouts() {
        MinecraftSingleChestTransferSkillNodeHandler.TransferRequest request =
                MinecraftSingleChestTransferSkillNodeHandler
                        .parseParameters(parameters(
                                12, 64, -8, 4, 35, 5,
                                "chest_to_player"))
                        .orElseThrow();

        Assertions.assertThrows(IllegalArgumentException.class,
                () -> MinecraftSingleChestTransferSkillNodeHandler
                        .transferAction(request, chestAt(13, 64, -8)));
        Assertions.assertEquals(MenuFamily.CHEST_3X9,
                MinecraftSingleChestTransferSkillNodeHandler.transferAction(
                        request, barrelAt(12, 64, -8)).family());
        Assertions.assertEquals(MenuFamily.CHEST_3X9,
                MinecraftSingleChestTransferSkillNodeHandler.transferAction(
                        request, enderChestAt(12, 64, -8)).family());
        Assertions.assertEquals(MenuFamily.CHEST_3X9,
                MinecraftSingleChestTransferSkillNodeHandler.transferAction(
                        request, shulkerAt(12, 64, -8)).family());
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> MinecraftSingleChestTransferSkillNodeHandler
                        .transferAction(request, new BlockTargetFingerprint(
                                new ResourceId("minecraft:overworld"),
                                request.target(),
                                new BlockStateFingerprint(
                                        new ResourceId("minecraft:barrel"),
                                        Map.of()))));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> MinecraftSingleChestTransferSkillNodeHandler
                        .transferAction(request, doubleChestAt(12, 64, -8)));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> MinecraftSingleChestTransferSkillNodeHandler
                        .transferAction(request, new BlockTargetFingerprint(
                                new ResourceId("minecraft:overworld"),
                                request.target(),
                                new BlockStateFingerprint(
                                        new ResourceId("example:container"),
                                        Map.of()))));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> MinecraftSingleChestTransferSkillNodeHandler
                        .transferAction(request, new BlockTargetFingerprint(
                                new ResourceId("minecraft:overworld"),
                                request.target(),
                                new BlockStateFingerprint(
                                        new ResourceId("minecraft:ender_chest"),
                                        Map.of("facing", "north")))));
    }

    @Test
    void actionBindsDoubleChestSlotsToTheSixByNineMenu() {
        MinecraftSingleChestTransferSkillNodeHandler.TransferRequest request =
                MinecraftSingleChestTransferSkillNodeHandler
                        .parseParameters(parameters(
                                12, 64, -8, 53, 54, 0,
                                "chest_to_player"))
                        .orElseThrow();

        WorldInteractionActionSpec.WorldMenuTransfer action =
                MinecraftSingleChestTransferSkillNodeHandler.transferAction(
                        request,
                        doubleChestAt(12, 64, -8),
                        doubleChestPartnerAt(13, 64, -8));

        Assertions.assertEquals(MenuFamily.CHEST_6X9, action.family());
        Assertions.assertEquals(53, action.sourceSlot());
        Assertions.assertEquals(54, action.targetSlot());
        Assertions.assertEquals(0, action.requestedAmount());
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> MinecraftSingleChestTransferSkillNodeHandler
                        .transferAction(request,
                                doubleChestAt(12, 64, -8),
                                doubleChestPartnerAt(14, 64, -8)));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> MinecraftSingleChestTransferSkillNodeHandler
                        .transferAction(request,
                                doubleChestAt(12, 64, -8),
                                doubleChestPartnerAt(11, 64, -8)));
    }

    @Test
    void canonicalizesBothDoubleChestHalvesToOneReservationSubject() {
        BlockCoordinates first = new BlockCoordinates(12, 64, -8);
        BlockCoordinates second = new BlockCoordinates(13, 64, -8);

        Assertions.assertEquals("12,64,-8",
                MinecraftSingleChestTransferSkillNodeHandler
                        .canonicalReservationSubject(first, second));
        Assertions.assertEquals("12,64,-8",
                MinecraftSingleChestTransferSkillNodeHandler
                        .canonicalReservationSubject(second, first));
    }

    @Test
    void rejectsWithdrawalsThatWouldFillTheCurrentlySelectedHotbarSlot() {
        MinecraftSingleChestTransferSkillNodeHandler.TransferRequest threeByNine =
                MinecraftSingleChestTransferSkillNodeHandler
                        .parseParameters(parameters(
                                12, 64, -8, 0, 54, 0,
                                "chest_to_player"))
                        .orElseThrow();
        MinecraftSingleChestTransferSkillNodeHandler.TransferRequest sixByNine =
                MinecraftSingleChestTransferSkillNodeHandler
                        .parseParameters(parameters(
                                12, 64, -8, 53, 81, 5,
                                "chest_to_player"))
                        .orElseThrow();

        Assertions.assertTrue(MinecraftSingleChestTransferSkillNodeHandler
                .writesToSelectedHotbar(
                        threeByNine, MenuFamily.CHEST_3X9, 0));
        Assertions.assertTrue(MinecraftSingleChestTransferSkillNodeHandler
                .writesToSelectedHotbar(
                        sixByNine, MenuFamily.CHEST_6X9, 0));
        Assertions.assertFalse(MinecraftSingleChestTransferSkillNodeHandler
                .writesToSelectedHotbar(
                        sixByNine, MenuFamily.CHEST_6X9, 1));
        Assertions.assertFalse(MinecraftSingleChestTransferSkillNodeHandler
                .writesToSelectedHotbar(
                        threeByNine, MenuFamily.CHEST_3X9, 9));
    }

    private static SkillParameters parameters(
            int x,
            int y,
            int z,
            int source,
            int target,
            int amount,
            String direction) {
        return new SkillParameters(values(
                x, y, z, source, target, amount, direction));
    }

    private static Map<String, Object> values(
            int x,
            int y,
            int z,
            int source,
            int target,
            int amount,
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
                .AMOUNT_PARAMETER, amount);
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

    private static BlockTargetFingerprint doubleChestAt(int x, int y, int z) {
        return new BlockTargetFingerprint(
                new ResourceId("minecraft:overworld"),
                new BlockCoordinates(x, y, z),
                new BlockStateFingerprint(
                        new ResourceId("minecraft:chest"),
                        Map.of(
                                "facing", "north",
                                "type", "left",
                                "waterlogged", "false")));
    }

    private static BlockTargetFingerprint barrelAt(int x, int y, int z) {
        return new BlockTargetFingerprint(
                new ResourceId("minecraft:overworld"),
                new BlockCoordinates(x, y, z),
                new BlockStateFingerprint(
                        new ResourceId("minecraft:barrel"),
                        Map.of("facing", "north", "open", "false")));
    }

    private static BlockTargetFingerprint enderChestAt(int x, int y, int z) {
        return new BlockTargetFingerprint(
                new ResourceId("minecraft:overworld"),
                new BlockCoordinates(x, y, z),
                new BlockStateFingerprint(
                        new ResourceId("minecraft:ender_chest"),
                        Map.of(
                                "facing", "north",
                                "waterlogged", "false")));
    }

    private static BlockTargetFingerprint shulkerAt(int x, int y, int z) {
        return new BlockTargetFingerprint(
                new ResourceId("minecraft:overworld"),
                new BlockCoordinates(x, y, z),
                new BlockStateFingerprint(
                        new ResourceId("minecraft:purple_shulker_box"),
                        Map.of("facing", "up")));
    }

    private static BlockTargetFingerprint doubleChestPartnerAt(
            int x, int y, int z) {
        return new BlockTargetFingerprint(
                new ResourceId("minecraft:overworld"),
                new BlockCoordinates(x, y, z),
                new BlockStateFingerprint(
                        new ResourceId("minecraft:chest"),
                        Map.of(
                                "facing", "north",
                                "type", "right",
                                "waterlogged", "false")));
    }
}
