package io.github.greytaiwolf.botplayer.gametest;

import io.github.greytaiwolf.botplayer.BotPlayer;
import io.github.greytaiwolf.botplayer.action.interaction.BlockCoordinates;
import io.github.greytaiwolf.botplayer.action.interaction.BlockStateFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import io.github.greytaiwolf.botplayer.building.blueprint.Blueprint;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintCell;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintMaterialClass;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintOffset;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintPlacementRole;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintReplacePolicy;
import io.github.greytaiwolf.botplayer.building.construction.ConstructionWorkPlan;
import io.github.greytaiwolf.botplayer.building.site.ConstructionSiteAnchor;
import io.github.greytaiwolf.botplayer.building.site.ConstructionSiteAssessment;
import io.github.greytaiwolf.botplayer.building.site.ConstructionSiteBinding;
import io.github.greytaiwolf.botplayer.building.site.ConstructionSiteSurvey;
import io.github.greytaiwolf.botplayer.building.site.minecraft.MinecraftConstructionSiteSurveySampler;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.IntStream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game acceptance coverage for the narrow, read-only P5D-A5 site-survey adapter.
 *
 * <p>These tests intentionally do not start a bot, Technique, Action, Skill, lease, or placement.
 */
@GameTestHolder(BotPlayer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class P5ConstructionSiteSurveyGameTests {
    private static final String BATCH = "p5_construction_site_survey";
    private static final int TIMEOUT_TICKS = 40;
    private static final UUID BLUEPRINT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000d05");
    private static final UUID SITE_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000d06");
    private static final BlockStateFingerprint STONE_STATE = new BlockStateFingerprint(
            new ResourceId("minecraft:stone"), Map.of());
    private static final BlockStateFingerprint DIRT_STATE = new BlockStateFingerprint(
            new ResourceId("minecraft:dirt"), Map.of());
    private static final BlockStateFingerprint OAK_STAIRS_WEST_STATE =
            new BlockStateFingerprint(new ResourceId("minecraft:oak_stairs"), Map.of(
                    "facing", "west",
                    "half", "bottom",
                    "shape", "straight",
                    "waterlogged", "false"));

    private P5ConstructionSiteSurveyGameTests() {}

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void samplesLoadedAirMatchingAndMismatchingFullStatesWithoutWriting(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        ServerLevel level = helper.getLevel();
        BlockPos relativeOrigin = new BlockPos(3, 1, 3);
        BlockPos relativeStairs = relativeOrigin.east();
        BlockPos relativeMismatch = relativeOrigin.east(2);
        BlockState stairs = Blocks.OAK_STAIRS.defaultBlockState().setValue(
                BlockStateProperties.HORIZONTAL_FACING, Direction.WEST);
        helper.setBlock(relativeOrigin, Blocks.AIR);
        helper.setBlock(relativeStairs, stairs);
        helper.setBlock(relativeMismatch, Blocks.DIRT);

        BlockPos origin = helper.absolutePos(relativeOrigin);
        BlockPos absoluteStairs = helper.absolutePos(relativeStairs);
        BlockPos absoluteMismatch = helper.absolutePos(relativeMismatch);
        BlockState airBefore = level.getBlockState(origin);
        BlockState stairsBefore = level.getBlockState(absoluteStairs);
        BlockState mismatchBefore = level.getBlockState(absoluteMismatch);
        ConstructionSiteBinding binding = binding(level, origin, 1L, List.of(
                cell(new BlueprintOffset(2, 0, 0), STONE_STATE),
                cell(new BlueprintOffset(0, 0, 0), STONE_STATE),
                cell(new BlueprintOffset(1, 0, 0), OAK_STAIRS_WEST_STATE)));

        long beforeTick = level.getServer().getTickCount();
        ConstructionSiteSurvey survey = MinecraftConstructionSiteSurveySampler.sample(
                level, binding);
        long afterTick = level.getServer().getTickCount();

        P2GameTestSupport.require(
                survey.observedTick() >= beforeTick && survey.observedTick() <= afterTick,
                "Construction-site survey did not retain the current authoritative server tick");
        P2GameTestSupport.require(
                survey.observations().equals(List.of(
                        ConstructionSiteSurvey.TargetObservation.empty(new BlueprintOffset(0, 0, 0)),
                        ConstructionSiteSurvey.TargetObservation.occupied(new BlueprintOffset(1, 0, 0),
                                OAK_STAIRS_WEST_STATE),
                        ConstructionSiteSurvey.TargetObservation.occupied(new BlueprintOffset(2, 0, 0),
                                DIRT_STATE))),
                "Survey did not retain exact loaded air and complete native block-state evidence");
        ConstructionSiteAssessment assessment = survey.assess();
        P2GameTestSupport.require(
                assessment.status() == ConstructionSiteAssessment.Status.BLOCKED
                        && assessment.findings().equals(List.of(
                                new ConstructionSiteAssessment.Finding(
                                        new BlueprintOffset(2, 0, 0),
                                        ConstructionSiteAssessment.FindingKind
                                                .PRESERVE_EXISTING_CONFLICT))),
                "Survey did not distinguish matching and mismatching occupied states");
        P2GameTestSupport.require(
                level.getBlockState(origin).equals(airBefore)
                        && level.getBlockState(absoluteStairs).equals(stairsBefore)
                        && level.getBlockState(absoluteMismatch).equals(mismatchBefore),
                "Read-only construction-site survey changed a GameTest block");
        helper.succeed();
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void samplesLoadedCompatibleTargetsAsAcceptedCandidateWithoutWriting(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        ServerLevel level = helper.getLevel();
        BlockPos relativeOrigin = new BlockPos(3, 1, 3);
        BlockPos relativeStairs = relativeOrigin.east();
        BlockState stairs = Blocks.OAK_STAIRS.defaultBlockState().setValue(
                BlockStateProperties.HORIZONTAL_FACING, Direction.WEST);
        helper.setBlock(relativeOrigin, Blocks.AIR);
        helper.setBlock(relativeStairs, stairs);

        BlockPos origin = helper.absolutePos(relativeOrigin);
        BlockPos absoluteStairs = helper.absolutePos(relativeStairs);
        BlockState airBefore = level.getBlockState(origin);
        BlockState stairsBefore = level.getBlockState(absoluteStairs);
        P2GameTestSupport.require(
                level.isLoaded(origin) && level.isLoaded(absoluteStairs),
                "Compatible construction-site fixture did not start with loaded targets");
        ConstructionSiteBinding binding = binding(level, origin, 7L, List.of(
                cell(new BlueprintOffset(1, 0, 0), OAK_STAIRS_WEST_STATE),
                cell(new BlueprintOffset(0, 0, 0), STONE_STATE)));

        long beforeTick = level.getServer().getTickCount();
        ConstructionSiteSurvey survey = MinecraftConstructionSiteSurveySampler.sample(
                level, binding);
        long afterTick = level.getServer().getTickCount();
        ConstructionSiteAssessment assessment = survey.assess();

        P2GameTestSupport.require(
                survey.observedTick() >= beforeTick && survey.observedTick() <= afterTick,
                "Compatible survey did not retain the current authoritative server tick");
        P2GameTestSupport.require(
                survey.observations().equals(List.of(
                        ConstructionSiteSurvey.TargetObservation.empty(new BlueprintOffset(0, 0, 0)),
                        ConstructionSiteSurvey.TargetObservation.occupied(new BlueprintOffset(1, 0, 0),
                                OAK_STAIRS_WEST_STATE))),
                "Compatible survey did not retain canonical loaded empty and full-state evidence");
        P2GameTestSupport.require(
                assessment.status() == ConstructionSiteAssessment.Status.ACCEPTED_CANDIDATE
                        && assessment.findings().isEmpty(),
                "Compatible loaded survey did not derive its data-only accepted candidate: "
                        + assessment);
        P2GameTestSupport.require(
                level.getBlockState(origin).equals(airBefore)
                        && level.getBlockState(absoluteStairs).equals(stairsBefore),
                "Read-only compatible construction-site survey changed a GameTest block");
        helper.succeed();
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void leavesFarUnloadedAndOutOfBuildHeightTargetsUnknown(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        ServerLevel level = helper.getLevel();
        BlockPos farUnloaded = new BlockPos(1_000_000, 64, 1_000_000);
        P2GameTestSupport.require(!level.isLoaded(farUnloaded),
                "Far survey fixture unexpectedly began with a loaded chunk");

        ConstructionSiteSurvey farSurvey = MinecraftConstructionSiteSurveySampler.sample(level,
                binding(level, farUnloaded, 2L, List.of(
                        cell(new BlueprintOffset(0, 0, 0), STONE_STATE))));
        BlockPos tooHigh = new BlockPos(
                helper.absolutePos(BlockPos.ZERO).getX(), level.getMaxBuildHeight(),
                helper.absolutePos(BlockPos.ZERO).getZ());
        ConstructionSiteSurvey highSurvey = MinecraftConstructionSiteSurveySampler.sample(level,
                binding(level, tooHigh, 3L, List.of(
                        cell(new BlueprintOffset(0, 0, 0), STONE_STATE))));

        P2GameTestSupport.require(
                farSurvey.observations().equals(List.of(
                        ConstructionSiteSurvey.TargetObservation.unknown(new BlueprintOffset(0, 0, 0))))
                        && !level.isLoaded(farUnloaded),
                "Sampling a far unloaded target loaded a chunk or inferred an observed state");
        P2GameTestSupport.require(
                highSurvey.observations().equals(List.of(
                        ConstructionSiteSurvey.TargetObservation.unknown(new BlueprintOffset(0, 0, 0)))),
                "An out-of-build-height target was not fail-closed to unknown");
        helper.succeed();
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void samplesExactlyTheMaximumCanonicalCellCount(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(1, 1, 1));
        List<BlueprintCell> cells = IntStream.range(0, Blueprint.MAX_CELLS)
                .mapToObj(index -> cell(new BlueprintOffset(
                        index % 16, index / 16, 0), STONE_STATE))
                .toList();
        ConstructionSiteBinding binding = binding(level, origin, 4L, cells);

        ConstructionSiteSurvey survey = MinecraftConstructionSiteSurveySampler.sample(level,
                binding);

        P2GameTestSupport.require(
                survey.observations().size() == Blueprint.MAX_CELLS
                        && survey.observations().stream()
                                .map(ConstructionSiteSurvey.TargetObservation::offset)
                                .distinct()
                                .count() == Blueprint.MAX_CELLS
                        && survey.observations().stream()
                                .map(ConstructionSiteSurvey.TargetObservation::offset)
                                .toList()
                                .equals(cells.stream().map(BlueprintCell::offset).sorted().toList()),
                "Survey did not read exactly the blueprint's maximum canonical target set");
        helper.succeed();
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void rejectsWrongDimensionAndOffThreadSamplingBeforeWorldRead(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(3, 1, 3));
        ConstructionSiteBinding wrongDimension = ConstructionSiteBinding.bind(SITE_ID,
                ConstructionWorkPlan.partition(new Blueprint(BLUEPRINT_ID,
                        Blueprint.CURRENT_SCHEMA_VERSION, 5L, List.of(
                                cell(new BlueprintOffset(0, 0, 0), STONE_STATE)))),
                new ConstructionSiteAnchor(new ResourceId("minecraft:the_nether"),
                        new BlockCoordinates(origin.getX(), origin.getY(), origin.getZ())));
        ConstructionSiteBinding correctDimension = binding(level, origin, 6L, List.of(
                cell(new BlueprintOffset(0, 0, 0), STONE_STATE)));

        Throwable wrongDimensionFailure = captureFailure(() ->
                MinecraftConstructionSiteSurveySampler.sample(level, wrongDimension));
        Throwable offThreadFailure = captureFailure(() -> CompletableFuture.runAsync(() ->
                MinecraftConstructionSiteSurveySampler.sample(level, correctDimension)).join());

        P2GameTestSupport.require(wrongDimensionFailure instanceof IllegalArgumentException,
                "Sampler did not fail closed when the binding dimension differed from the level");
        P2GameTestSupport.require(offThreadFailure instanceof CompletionException
                        && offThreadFailure.getCause() instanceof IllegalStateException,
                "Sampler did not reject an off-thread world-sampling request before reading state");
        helper.succeed();
    }

    private static ConstructionSiteBinding binding(
            ServerLevel level, BlockPos origin, long revision, List<BlueprintCell> cells) {
        return ConstructionSiteBinding.bind(SITE_ID, ConstructionWorkPlan.partition(
                new Blueprint(BLUEPRINT_ID, Blueprint.CURRENT_SCHEMA_VERSION, revision, cells)),
                new ConstructionSiteAnchor(new ResourceId(
                        level.dimension().location().toString()), new BlockCoordinates(
                        origin.getX(), origin.getY(), origin.getZ())));
    }

    private static BlueprintCell cell(
            BlueprintOffset offset, BlockStateFingerprint expectedState) {
        return new BlueprintCell(offset, expectedState, BlueprintPlacementRole.FOUNDATION,
                BlueprintReplacePolicy.PRESERVE_EXISTING, BlueprintMaterialClass.PERMANENT);
    }

    private static Throwable captureFailure(Runnable action) {
        try {
            action.run();
        } catch (Throwable throwable) {
            return throwable;
        }
        throw new IllegalStateException("Expected construction-site sampler to fail closed");
    }
}
