package io.github.greytaiwolf.botplayer.gametest;

import io.github.greytaiwolf.botplayer.BotPlayer;
import io.github.greytaiwolf.botplayer.action.interaction.BlockStateFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import io.github.greytaiwolf.botplayer.building.blueprint.Blueprint;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintCell;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintMaterialClass;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintOffset;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintPlacementRole;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintReplacePolicy;
import io.github.greytaiwolf.botplayer.building.material.BlueprintPlaceableItemEvidence;
import io.github.greytaiwolf.botplayer.building.material.PlaceableItemEvidence;
import io.github.greytaiwolf.botplayer.building.material.minecraft.MinecraftBlueprintPlaceableItemEvidenceValidator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game coverage for the narrow P5D-A4-R1 native-registry consistency check.
 *
 * <p>These tests do not start a bot, touch an inventory, reserve materials, survey a site, or
 * place a block. The adapter only inspects already-explicit declarations on the server thread.
 */
@GameTestHolder(BotPlayer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class P5BlueprintPlaceableItemEvidenceGameTests {
    private static final String BATCH = "p5_blueprint_item_evidence";
    private static final int TIMEOUT_TICKS = 40;
    private static final UUID BLUEPRINT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000d41");
    private static final ResourceId STONE = new ResourceId("minecraft:stone");
    private static final ResourceId OAK_STAIRS = new ResourceId("minecraft:oak_stairs");
    private static final ResourceId STICK = new ResourceId("minecraft:stick");
    private static final ResourceId AIR = new ResourceId("minecraft:air");
    private static final ResourceId MISSING_ITEM = new ResourceId(
            "minecraft:botplayer_missing_blueprint_item");
    private static final BlockStateFingerprint STONE_STATE = new BlockStateFingerprint(STONE,
            Map.of());
    private static final BlockStateFingerprint AIR_STATE = new BlockStateFingerprint(AIR,
            Map.of());
    private static final BlockStateFingerprint OAK_STAIRS_NORTH = new BlockStateFingerprint(
            OAK_STAIRS, Map.of(
                    "facing", "north",
                    "half", "bottom",
                    "shape", "straight",
                    "waterlogged", "false"));
    private static final BlockStateFingerprint OAK_STAIRS_EAST = new BlockStateFingerprint(
            OAK_STAIRS, Map.of(
                    "facing", "east",
                    "half", "bottom",
                    "shape", "straight",
                    "waterlogged", "false"));
    private static final BlockStateFingerprint OAK_STAIRS_PARTIAL = new BlockStateFingerprint(
            OAK_STAIRS, Map.of("facing", "north"));

    private P5BlueprintPlaceableItemEvidenceGameTests() {}

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void acceptsExactRegisteredDefaultStatesWithoutChangingWitness(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        BlockPos witness = helper.absolutePos(new BlockPos(3, 1, 3));
        BlockState before = level.getBlockState(witness);
        BlueprintPlaceableItemEvidence declared = declared(1L, List.of(
                cell(new BlueprintOffset(1, 0, 0), OAK_STAIRS_NORTH),
                cell(new BlueprintOffset(0, 0, 0), STONE_STATE)), List.of(
                new PlaceableItemEvidence(STONE_STATE, STONE),
                new PlaceableItemEvidence(OAK_STAIRS_NORTH, OAK_STAIRS)));

        P2GameTestSupport.require(
                MinecraftBlueprintPlaceableItemEvidenceValidator.matchesRegisteredDefaultBlockItems(
                        server, declared),
                "Exact explicit native block-item declarations were not accepted");
        P2GameTestSupport.require(
                declared.declaredItemRequirements().totalDeclaredItems() == 2
                        && level.getBlockState(witness).equals(before),
                "Registry declaration validation mutated a blueprint quantity or GameTest witness block");
        helper.succeed();
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void rejectsNonBlockMissingAirAndNonDefaultDeclarations(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        MinecraftServer server = helper.getLevel().getServer();

        P2GameTestSupport.require(!MinecraftBlueprintPlaceableItemEvidenceValidator
                        .matchesRegisteredDefaultBlockItems(server, declared(2L,
                                List.of(cell(new BlueprintOffset(0, 0, 0), STONE_STATE)),
                                List.of(new PlaceableItemEvidence(STONE_STATE, STICK)))),
                "A non-BlockItem declaration was accepted as construction evidence");
        P2GameTestSupport.require(!MinecraftBlueprintPlaceableItemEvidenceValidator
                        .matchesRegisteredDefaultBlockItems(server, declared(3L,
                                List.of(cell(new BlueprintOffset(0, 0, 0), STONE_STATE)),
                                List.of(new PlaceableItemEvidence(STONE_STATE, MISSING_ITEM)))),
                "A missing registry item was accepted as construction evidence");
        P2GameTestSupport.require(!MinecraftBlueprintPlaceableItemEvidenceValidator
                        .matchesRegisteredDefaultBlockItems(server, declared(4L,
                                List.of(cell(new BlueprintOffset(0, 0, 0), AIR_STATE)),
                                List.of(new PlaceableItemEvidence(AIR_STATE, AIR)))),
                "Air was accepted as a placeable construction declaration");
        P2GameTestSupport.require(!MinecraftBlueprintPlaceableItemEvidenceValidator
                        .matchesRegisteredDefaultBlockItems(server, declared(5L,
                                List.of(cell(new BlueprintOffset(0, 0, 0), OAK_STAIRS_EAST)),
                                List.of(new PlaceableItemEvidence(OAK_STAIRS_EAST, OAK_STAIRS)))),
                "A context-dependent non-default stair state was accepted without a placement proof");
        P2GameTestSupport.require(!MinecraftBlueprintPlaceableItemEvidenceValidator
                        .matchesRegisteredDefaultBlockItems(server, declared(6L,
                                List.of(cell(new BlueprintOffset(0, 0, 0), OAK_STAIRS_PARTIAL)),
                                List.of(new PlaceableItemEvidence(OAK_STAIRS_PARTIAL, OAK_STAIRS)))),
                "A partial target-state fingerprint was accepted as an exact default state");
        helper.succeed();
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void rejectsOffThreadRegistryValidationBeforeLookup(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        MinecraftServer server = helper.getLevel().getServer();
        BlueprintPlaceableItemEvidence declared = declared(7L,
                List.of(cell(new BlueprintOffset(0, 0, 0), STONE_STATE)),
                List.of(new PlaceableItemEvidence(STONE_STATE, STONE)));

        Throwable failure = captureFailure(() -> CompletableFuture.runAsync(() ->
                MinecraftBlueprintPlaceableItemEvidenceValidator.matchesRegisteredDefaultBlockItems(
                        server, declared)).join());
        P2GameTestSupport.require(failure instanceof CompletionException
                        && failure.getCause() instanceof IllegalStateException,
                "Registry validation did not reject an off-thread request before a lookup");
        helper.succeed();
    }

    private static BlueprintPlaceableItemEvidence declared(long revision,
            List<BlueprintCell> cells, List<PlaceableItemEvidence> entries) {
        return new BlueprintPlaceableItemEvidence(new Blueprint(BLUEPRINT_ID,
                Blueprint.CURRENT_SCHEMA_VERSION, revision, cells), entries);
    }

    private static BlueprintCell cell(BlueprintOffset offset, BlockStateFingerprint state) {
        return new BlueprintCell(offset, state, BlueprintPlacementRole.FOUNDATION,
                BlueprintReplacePolicy.PRESERVE_EXISTING, BlueprintMaterialClass.PERMANENT);
    }

    private static Throwable captureFailure(Runnable action) {
        try {
            action.run();
        } catch (Throwable throwable) {
            return throwable;
        }
        throw new IllegalStateException("Expected registry declaration validation to fail closed");
    }
}
