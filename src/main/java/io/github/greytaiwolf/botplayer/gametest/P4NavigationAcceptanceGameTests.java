package io.github.greytaiwolf.botplayer.gametest;

import io.github.greytaiwolf.botplayer.BotPlayer;
import io.github.greytaiwolf.botplayer.config.BotPlayerConfig;
import io.github.greytaiwolf.botplayer.gametest.P2GameTestSupport.TestBot;
import io.github.greytaiwolf.botplayer.kernel.BotConnection;
import io.github.greytaiwolf.botplayer.navigation.GridPoint;
import io.github.greytaiwolf.botplayer.navigation.NavigationFailure;
import io.github.greytaiwolf.botplayer.navigation.NavigationOutcome;
import io.github.greytaiwolf.botplayer.navigation.NavigationPolicy;
import io.github.greytaiwolf.botplayer.navigation.NavigationState;
import io.github.greytaiwolf.botplayer.navigation.NavigationSubmission;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * P4 路线规划必须最终落实为真实 ServerPlayer 位移，不能以 outcome 代替身体事实。
 */
@GameTestHolder(BotPlayer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class P4NavigationAcceptanceGameTests {
    private static final String BATCH = "p4_navigation";
    private static final String TERRAIN_BREAK_BATCH =
            "p4_terrain_break";
    private static final String TERRAIN_PLACE_BATCH =
            "p4_terrain_place";
    private static final String TERRAIN_POLICY_BATCH =
            "p4_terrain_policy";
    private static final int TIMEOUT_TICKS = 400;

    private P4NavigationAcceptanceGameTests() {}

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void flatRouteMovesTheRealPlayerBody(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        TestBot bot = P2GameTestSupport.spawnBot(
                helper,
                null,
                "P4Flat",
                new Vec3(4.5D, 1.0D, 2.5D),
                0.0F);
        P2GameTestSupport.Cleanup cleanup = cleanup(bot);
        try {
            double startZ = bot.player().getZ();
            long teleportAcknowledgementsBeforeNavigation =
                    teleportAcknowledgements(bot);
            BlockPos target =
                    helper.absolutePos(new BlockPos(4, 1, 7));
            NavigationSubmission submission =
                    bot.manager().startNavigation(
                            bot.name(), GridPoint.from(target));
            P2GameTestSupport.require(
                    submission.status()
                            == NavigationSubmission.Status.ENQUEUED,
                    "P4 flat navigation was rejected: "
                            + submission.status());
            CompletableFuture<NavigationOutcome> completion =
                    submission.completion()
                            .orElseThrow()
                            .toCompletableFuture();

            P2GameTestSupport.awaitCondition(
                    helper,
                    TIMEOUT_TICKS - 20,
                    completion::isDone,
                    "P4 flat navigation did not complete",
                    cleanup,
                    () -> verifyNavigation(
                            helper,
                            cleanup,
                            bot,
                            completion.join(),
                            target,
                            startZ,
                            teleportAcknowledgementsBeforeNavigation));
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void boundedAStarWalksAroundAClosedColumn(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        helper.setBlock(new BlockPos(4, 1, 5), Blocks.STONE);
        helper.setBlock(new BlockPos(4, 2, 5), Blocks.STONE);
        TestBot bot = P2GameTestSupport.spawnBot(
                helper,
                null,
                "P4Around",
                new Vec3(4.5D, 1.0D, 2.5D),
                0.0F);
        P2GameTestSupport.Cleanup cleanup = cleanup(bot);
        try {
            double startZ = bot.player().getZ();
            long teleportAcknowledgementsBeforeNavigation =
                    teleportAcknowledgements(bot);
            BlockPos target =
                    helper.absolutePos(new BlockPos(4, 1, 7));
            NavigationSubmission submission =
                    bot.manager().startNavigation(
                            bot.name(), GridPoint.from(target));
            P2GameTestSupport.require(
                    submission.status()
                            == NavigationSubmission.Status.ENQUEUED,
                    "P4 obstacle navigation was rejected: "
                            + submission.status());
            CompletableFuture<NavigationOutcome> completion =
                    submission.completion()
                            .orElseThrow()
                            .toCompletableFuture();

            P2GameTestSupport.awaitCondition(
                    helper,
                    TIMEOUT_TICKS - 20,
                    completion::isDone,
                    "P4 did not route around the closed column",
                    cleanup,
                    () -> verifyNavigation(
                            helper,
                            cleanup,
                            bot,
                            completion.join(),
                            target,
                            startZ,
                            teleportAcknowledgementsBeforeNavigation));
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void dynamicWallInvalidatesTheOldRoute(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        TestBot bot = P2GameTestSupport.spawnBot(
                helper,
                null,
                "P4Dynamic",
                new Vec3(4.5D, 1.0D, 2.5D),
                0.0F);
        P2GameTestSupport.Cleanup cleanup = cleanup(bot);
        try {
            BlockPos target =
                    helper.absolutePos(new BlockPos(4, 1, 7));
            NavigationSubmission submission =
                    bot.manager().startNavigation(
                            bot.name(), GridPoint.from(target));
            P2GameTestSupport.require(
                    submission.status()
                            == NavigationSubmission.Status.ENQUEUED,
                    "P4 dynamic navigation was rejected");
            CompletableFuture<NavigationOutcome> completion =
                    submission.completion()
                            .orElseThrow()
                            .toCompletableFuture();

            P2GameTestSupport.awaitCondition(
                    helper,
                    100,
                    () -> bot.manager()
                            .navigationSession(bot.name())
                            .filter(view ->
                                    view.state()
                                            == NavigationState.FOLLOWING)
                            .isPresent(),
                    "P4 route never entered FOLLOWING before mutation",
                    cleanup,
                    () -> {
                        helper.setBlock(
                                new BlockPos(4, 1, 5), Blocks.STONE);
                        helper.setBlock(
                                new BlockPos(4, 2, 5), Blocks.STONE);
                        P2GameTestSupport.awaitCondition(
                                helper,
                                220,
                                completion::isDone,
                                "P4 did not recover after a dynamic wall",
                                cleanup,
                                () -> {
                                    NavigationOutcome outcome =
                                            completion.join();
                                    P2GameTestSupport.require(
                                            outcome.state()
                                                    == NavigationState
                                                            .SUCCEEDED,
                                            "P4 dynamic wall ended as "
                                                    + outcome.state()
                                                    + "/"
                                                    + outcome.failure());
                                    P2GameTestSupport.require(
                                            outcome.replans() > 0
                                                    || outcome.recoveryAttempts()
                                                            > 0,
                                            "Dynamic wall did not invalidate the old route");
                                    P2GameTestSupport.require(
                                            nearGoal(bot, target),
                                            "Bot did not physically reach the dynamic target");
                                    cleanup.run();
                                    helper.succeed();
                                });
                    });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void woodenDoorUsesVanillaInteractionBeforePassage(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        helper.setBlock(new BlockPos(4, 1, 5), Blocks.OAK_DOOR);
        TestBot bot = P2GameTestSupport.spawnBot(
                helper,
                null,
                "P4Door",
                new Vec3(4.5D, 1.0D, 2.5D),
                0.0F);
        P2GameTestSupport.Cleanup cleanup = cleanup(bot);
        try {
            BlockPos target =
                    helper.absolutePos(new BlockPos(4, 1, 7));
            NavigationSubmission submission =
                    bot.manager().startNavigation(
                            bot.name(), GridPoint.from(target));
            P2GameTestSupport.require(
                    submission.status()
                            == NavigationSubmission.Status.ENQUEUED,
                    "P4 wooden-door navigation was rejected");
            CompletableFuture<NavigationOutcome> completion =
                    submission.completion()
                            .orElseThrow()
                            .toCompletableFuture();
            P2GameTestSupport.awaitCondition(
                    helper,
                    240,
                    completion::isDone,
                    "P4 did not complete the wooden-door route",
                    cleanup,
                    () -> {
                        NavigationOutcome outcome = completion.join();
                        P2GameTestSupport.require(
                                outcome.state()
                                        == NavigationState.SUCCEEDED,
                                "P4 door route ended as "
                                        + outcome.state()
                                        + "/"
                                        + outcome.failure());
                        P2GameTestSupport.require(
                                helper.getBlockState(
                                                new BlockPos(4, 1, 5))
                                        .getValue(BlockStateProperties.OPEN),
                                "Wooden door was not opened through vanilla use");
                        P2GameTestSupport.require(
                                bot.player().getZ()
                                        > helper.absolutePos(
                                                        new BlockPos(
                                                                4, 1, 5))
                                                .getZ()
                                                + 0.5D,
                                "Bot did not physically pass the opened door");
                        cleanup.run();
                        helper.succeed();
                    });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void sealedStartReturnsNoPathWithoutWorldMutation(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        for (int x = 3; x <= 5; x++) {
            for (int z = 3; z <= 5; z++) {
                if (x == 4 && z == 4) {
                    continue;
                }
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
                helper.setBlock(new BlockPos(x, 2, z), Blocks.STONE);
            }
        }
        TestBot bot = P2GameTestSupport.spawnBot(
                helper,
                null,
                "P4NoPath",
                new Vec3(4.5D, 1.0D, 4.5D),
                0.0F);
        P2GameTestSupport.Cleanup cleanup = cleanup(bot);
        try {
            NavigationSubmission submission =
                    bot.manager().startNavigation(
                            bot.name(),
                            GridPoint.from(helper.absolutePos(
                                    new BlockPos(7, 1, 4))));
            P2GameTestSupport.require(
                    submission.status()
                            == NavigationSubmission.Status.ENQUEUED,
                    "P4 sealed navigation was rejected before planning");
            CompletableFuture<NavigationOutcome> completion =
                    submission.completion()
                            .orElseThrow()
                            .toCompletableFuture();
            P2GameTestSupport.awaitCondition(
                    helper,
                    160,
                    completion::isDone,
                    "P4 sealed route did not terminate within its bound",
                    cleanup,
                    () -> {
                        NavigationOutcome outcome = completion.join();
                        P2GameTestSupport.require(
                                outcome.state()
                                                == NavigationState.FAILED
                                        && outcome.failure()
                                                == NavigationFailure
                                                        .NO_PATH,
                                "P4 sealed route did not return honest NO_PATH: "
                                        + outcome.state()
                                        + "/"
                                        + outcome.failure());
                        for (int x = 3; x <= 5; x++) {
                            for (int z = 3; z <= 5; z++) {
                                if (x == 4 && z == 4) {
                                    continue;
                                }
                                P2GameTestSupport.require(
                                        helper.getBlockState(
                                                        new BlockPos(
                                                                x, 1, z))
                                                .is(Blocks.STONE),
                                        "Default P4 navigation modified the sealed wall");
                            }
                        }
                        cleanup.run();
                        helper.succeed();
                    });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void lowSupplyRejectsDistantTravelBeforePlanning(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        TestBot bot = P2GameTestSupport.spawnBot(
                helper,
                null,
                "P4Supply",
                new Vec3(4.5D, 1.0D, 4.5D),
                0.0F);
        P2GameTestSupport.Cleanup cleanup = cleanup(bot);
        try {
            bot.player().getFoodData().setFoodLevel(4);
            NavigationSubmission submission =
                    bot.manager().startNavigation(
                            bot.name(),
                            GridPoint.from(helper.absolutePos(
                                    new BlockPos(4, 1, 20))));
            P2GameTestSupport.require(
                    submission.status()
                            == NavigationSubmission.Status.SUPPLY_REQUIRED,
                    "P4 low-supply travel was not rejected before planning: "
                            + submission.status());
            P2GameTestSupport.require(
                    bot.manager()
                            .navigationSession(bot.name())
                            .isEmpty(),
                    "Rejected low-supply travel created a navigation session");
            cleanup.run();
            helper.succeed();
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void forcedOneBlockStepUsesRealJumpPhysics(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        for (int z = 2; z <= 7; z++) {
            for (int y = 1; y <= 2; y++) {
                helper.setBlock(new BlockPos(3, y, z), Blocks.STONE);
                helper.setBlock(new BlockPos(5, y, z), Blocks.STONE);
            }
        }
        helper.setBlock(new BlockPos(4, 1, 5), Blocks.STONE);
        TestBot bot = P2GameTestSupport.spawnBot(
                helper,
                null,
                "P4Jump",
                new Vec3(4.5D, 1.0D, 2.5D),
                0.0F);
        P2GameTestSupport.Cleanup cleanup = cleanup(bot);
        try {
            double startY = bot.player().getY();
            boolean[] observedRise = {false};
            BlockPos target =
                    helper.absolutePos(new BlockPos(4, 1, 7));
            NavigationSubmission submission =
                    bot.manager().startNavigation(
                            bot.name(), GridPoint.from(target));
            P2GameTestSupport.require(
                    submission.status()
                            == NavigationSubmission.Status.ENQUEUED,
                    "P4 forced-jump navigation was rejected");
            CompletableFuture<NavigationOutcome> completion =
                    submission.completion()
                            .orElseThrow()
                            .toCompletableFuture();
            P2GameTestSupport.awaitCondition(
                    helper,
                    260,
                    () -> {
                        observedRise[0] |=
                                bot.player().getY() > startY + 0.45D;
                        return completion.isDone();
                    },
                    "P4 did not finish the forced one-block step",
                    cleanup,
                    () -> {
                        NavigationOutcome outcome = completion.join();
                        P2GameTestSupport.require(
                                outcome.state()
                                        == NavigationState.SUCCEEDED,
                                "P4 forced jump ended as "
                                        + outcome.state()
                                        + "/"
                                        + outcome.failure());
                        P2GameTestSupport.require(
                                observedRise[0],
                                "P4 crossed the step without observable jump physics");
                        P2GameTestSupport.require(
                                nearGoal(bot, target),
                                "P4 jump route did not reach its target");
                        cleanup.run();
                        helper.succeed();
                    });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void waterLaneRouteUsesRealSwimmingState(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        for (int x = 3; x <= 5; x++) {
            for (int z = 2; z <= 7; z++) {
                helper.setBlock(
                        new BlockPos(x, 1, z), Blocks.WATER);
                helper.setBlock(
                        new BlockPos(x, 2, z), Blocks.WATER);
            }
        }
        TestBot bot = P2GameTestSupport.spawnBot(
                helper,
                null,
                "P4Water",
                new Vec3(4.5D, 1.05D, 2.5D),
                0.0F);
        P2GameTestSupport.Cleanup cleanup = cleanup(bot);
        try {
            boolean[] observedWater = {false};
            BlockPos target =
                    helper.absolutePos(new BlockPos(4, 1, 4));
            NavigationSubmission submission =
                    bot.manager().startNavigation(
                            bot.name(), GridPoint.from(target));
            P2GameTestSupport.require(
                    submission.status()
                            == NavigationSubmission.Status.ENQUEUED,
                    "P4 shallow-water navigation was rejected");
            CompletableFuture<NavigationOutcome> completion =
                    submission.completion()
                            .orElseThrow()
                            .toCompletableFuture();
            awaitWaterRoute(
                    helper,
                    bot,
                    target,
                    completion,
                    observedWater,
                    cleanup,
                    360);
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    private static void awaitWaterRoute(
            GameTestHelper helper,
            TestBot bot,
            BlockPos target,
            CompletableFuture<NavigationOutcome> completion,
            boolean[] observedWater,
            P2GameTestSupport.Cleanup cleanup,
            int remainingTicks) {
        observedWater[0] |= bot.player().isInWater();
        if (completion.isDone()) {
            NavigationOutcome outcome = completion.join();
            P2GameTestSupport.require(
                    outcome.state() == NavigationState.SUCCEEDED,
                    "P4 water route ended as "
                            + outcome.state()
                            + "/"
                            + outcome.failure()
                            + ": "
                            + outcome.safeSummary());
            P2GameTestSupport.require(
                    observedWater[0],
                    "P4 route never entered authoritative water state");
            P2GameTestSupport.require(
                    nearGoal(bot, target),
                    "P4 water route did not reach its target");
            cleanup.run();
            helper.succeed();
            return;
        }
        if (remainingTicks <= 0) {
            String session = bot.manager()
                    .navigationSession(bot.name())
                    .map(view ->
                            view.state()
                                    + "/"
                                    + view.terminalFailure()
                                    + " summary="
                                    + view.safeSummary()
                                    + " position="
                                    + GridPoint.from(
                                            bot.player()
                                                    .blockPosition())
                                    + " next="
                                    + view.nextWaypoint()
                                    + " route="
                                    + view.routeIndex()
                                    + "/"
                                    + view.routeSize()
                                    + " replans="
                                    + view.replans()
                                    + " recoveries="
                                    + view.recoveryAttempts())
                    .orElse("missing-session");
            cleanup.run();
            helper.fail(
                    "P4 water-lane route timed out: " + session);
            return;
        }
        helper.runAfterDelay(
                1L,
                () -> awaitWaterRoute(
                        helper,
                        bot,
                        target,
                        completion,
                        observedWater,
                        cleanup,
                        remainingTicks - 1));
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void ladderRouteRaisesTheRealPlayerBody(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        for (int y = 1; y <= 4; y++) {
            helper.setBlock(new BlockPos(4, y, 5), Blocks.STONE);
        }
        for (int y = 1; y <= 4; y++) {
            helper.setBlock(
                    new BlockPos(4, y, 4),
                    Blocks.LADDER
                            .defaultBlockState()
                            .setValue(
                                    BlockStateProperties
                                            .HORIZONTAL_FACING,
                                    Direction.NORTH));
        }
        TestBot bot = P2GameTestSupport.spawnBot(
                helper,
                null,
                "P4Ladder",
                new Vec3(4.5D, 1.0D, 4.5D),
                0.0F);
        P2GameTestSupport.Cleanup cleanup = cleanup(bot);
        try {
            double startY = bot.player().getY();
            BlockPos target =
                    helper.absolutePos(new BlockPos(4, 4, 4));
            NavigationSubmission submission =
                    bot.manager().startNavigation(
                            bot.name(), GridPoint.from(target));
            P2GameTestSupport.require(
                    submission.status()
                            == NavigationSubmission.Status.ENQUEUED,
                    "P4 ladder navigation was rejected");
            CompletableFuture<NavigationOutcome> completion =
                    submission.completion()
                            .orElseThrow()
                            .toCompletableFuture();
            P2GameTestSupport.awaitCondition(
                    helper,
                    260,
                    completion::isDone,
                    "P4 did not finish the ladder route",
                    cleanup,
                    () -> {
                        NavigationOutcome outcome = completion.join();
                        P2GameTestSupport.require(
                                outcome.state()
                                        == NavigationState.SUCCEEDED,
                                "P4 ladder route ended as "
                                        + outcome.state()
                                        + "/"
                                        + outcome.failure());
                        P2GameTestSupport.require(
                                bot.player().getY() > startY + 0.8D,
                                "P4 ladder route did not raise the real player body");
                        cleanup.run();
                        helper.succeed();
                    });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = TERRAIN_BREAK_BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void explicitTerrainBreakClearsOnlyTheBodyCorridor(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        for (int x = 0; x <= 8; x++) {
            helper.setBlock(new BlockPos(x, 1, 3), Blocks.STONE);
            helper.setBlock(new BlockPos(x, 2, 3), Blocks.STONE);
        }
        TestBot bot = P2GameTestSupport.spawnBot(
                helper,
                null,
                "P4AssistBreak",
                new Vec3(4.5D, 1.0D, 2.5D),
                0.0F);
        P2GameTestSupport.Cleanup cleanup = cleanup(bot);
        boolean previousAllow =
                BotPlayerConfig.NAVIGATION_ALLOW_TERRAIN_BREAK.get();
        int previousMaximum = BotPlayerConfig
                .NAVIGATION_MAXIMUM_TERRAIN_BLOCKS_BROKEN
                .get();
        cleanup.add(() -> {
            BotPlayerConfig.NAVIGATION_ALLOW_TERRAIN_BREAK
                    .set(previousAllow);
            BotPlayerConfig
                    .NAVIGATION_MAXIMUM_TERRAIN_BLOCKS_BROKEN
                    .set(previousMaximum);
        });
        try {
            BotPlayerConfig.NAVIGATION_ALLOW_TERRAIN_BREAK.set(true);
            BotPlayerConfig
                    .NAVIGATION_MAXIMUM_TERRAIN_BLOCKS_BROKEN
                    .set(2);
            bot.player().getInventory().selected = 0;
            bot.player().getInventory().setItem(
                    0, new ItemStack(Items.DIAMOND_PICKAXE));
            BlockPos lower =
                    helper.absolutePos(new BlockPos(4, 1, 3));
            BlockPos upper = lower.above();
            BlockPos target =
                    helper.absolutePos(new BlockPos(4, 1, 7));
            long teleportsBefore = teleportAcknowledgements(bot);
            NavigationSubmission submission =
                    bot.manager().startNavigation(
                            bot.name(),
                            GridPoint.from(target),
                            NavigationPolicy.safeDefault()
                                    .withTerrainAssist(
                                            true,
                                            2,
                                            false,
                                            0));
            P2GameTestSupport.require(
                    submission.status()
                            == NavigationSubmission.Status.ENQUEUED,
                    "Explicit break navigation was rejected");
            CompletableFuture<NavigationOutcome> completion =
                    submission.completion()
                            .orElseThrow()
                            .toCompletableFuture();
            P2GameTestSupport.awaitCondition(
                    helper,
                    TIMEOUT_TICKS - 20,
                    completion::isDone,
                    "P4 terrain break navigation did not finish",
                    cleanup,
                    () -> {
                        NavigationOutcome outcome = completion.join();
                        P2GameTestSupport.require(
                                outcome.state()
                                                == NavigationState.SUCCEEDED
                                        && outcome.blocksBroken() == 2
                                        && outcome.blocksPlaced() == 0,
                                "P4 terrain break outcome was "
                                        + outcome.state()
                                        + "/"
                                        + outcome.failure()
                                        + " break="
                                        + outcome.blocksBroken()
                                        + ": "
                                        + outcome.safeSummary());
                        P2GameTestSupport.require(
                                bot.player()
                                                .serverLevel()
                                                .getBlockState(lower)
                                                .isAir()
                                        && bot.player()
                                                .serverLevel()
                                                .getBlockState(upper)
                                                .isAir(),
                                "Terrain Assist did not clear exactly the two body blocks");
                        P2GameTestSupport.require(
                                teleportAcknowledgements(bot)
                                        == teleportsBefore,
                                "Terrain Assist break used teleportation");
                        cleanup.run();
                        helper.succeed();
                    });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = TERRAIN_PLACE_BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void explicitTerrainPlaceBuildsVerifiedShortBridge(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        for (int x = 0; x <= 8; x++) {
            for (int z = 3; z <= 6; z++) {
                helper.setBlock(
                        new BlockPos(x, 0, z), Blocks.AIR);
                helper.setBlock(
                        new BlockPos(x, -4, z), Blocks.STONE);
            }
        }
        for (int z = 1; z <= 9; z++) {
            for (int y = 1; y <= 2; y++) {
                helper.setBlock(
                        new BlockPos(3, y, z), Blocks.STONE);
                helper.setBlock(
                        new BlockPos(5, y, z), Blocks.STONE);
            }
            helper.setBlock(
                    new BlockPos(4, 3, z), Blocks.STONE);
        }
        TestBot bot = P2GameTestSupport.spawnBot(
                helper,
                null,
                "P4AssistPlace",
                new Vec3(4.5D, 1.0D, 2.5D),
                0.0F);
        P2GameTestSupport.Cleanup cleanup = cleanup(bot);
        boolean previousAllow =
                BotPlayerConfig.NAVIGATION_ALLOW_TERRAIN_PLACE.get();
        int previousMaximum = BotPlayerConfig
                .NAVIGATION_MAXIMUM_TERRAIN_BLOCKS_PLACED
                .get();
        cleanup.add(() -> {
            BotPlayerConfig.NAVIGATION_ALLOW_TERRAIN_PLACE
                    .set(previousAllow);
            BotPlayerConfig
                    .NAVIGATION_MAXIMUM_TERRAIN_BLOCKS_PLACED
                    .set(previousMaximum);
        });
        try {
            BotPlayerConfig.NAVIGATION_ALLOW_TERRAIN_PLACE.set(true);
            BotPlayerConfig
                    .NAVIGATION_MAXIMUM_TERRAIN_BLOCKS_PLACED
                    .set(4);
            bot.player().getInventory().selected = 0;
            bot.player().getInventory().setItem(
                    0, new ItemStack(Items.COBBLESTONE, 6));
            BlockPos target =
                    helper.absolutePos(new BlockPos(4, 1, 8));
            NavigationSubmission submission =
                    bot.manager().startNavigation(
                            bot.name(),
                            GridPoint.from(target),
                            NavigationPolicy.safeDefault()
                                    .withTerrainAssist(
                                            false,
                                            0,
                                            true,
                                            4));
            P2GameTestSupport.require(
                    submission.status()
                            == NavigationSubmission.Status.ENQUEUED,
                    "Explicit bridge navigation was rejected");
            CompletableFuture<NavigationOutcome> completion =
                    submission.completion()
                            .orElseThrow()
                            .toCompletableFuture();
            P2GameTestSupport.awaitCondition(
                    helper,
                    TIMEOUT_TICKS - 20,
                    completion::isDone,
                    "P4 terrain bridge navigation did not finish",
                    cleanup,
                    () -> {
                        NavigationOutcome outcome = completion.join();
                        P2GameTestSupport.require(
                                outcome.state()
                                                == NavigationState.SUCCEEDED
                                        && outcome.blocksPlaced() == 4
                                        && outcome.blocksBroken() == 0,
                                "P4 terrain place outcome was "
                                        + outcome.state()
                                        + "/"
                                        + outcome.failure()
                                        + " place="
                                        + outcome.blocksPlaced()
                                        + ": "
                                        + outcome.safeSummary());
                        for (int z = 3; z <= 6; z++) {
                            BlockPos bridge = helper.absolutePos(
                                    new BlockPos(4, 0, z));
                            P2GameTestSupport.require(
                                    bot.player()
                                            .serverLevel()
                                            .getBlockState(bridge)
                                            .is(Blocks.COBBLESTONE),
                                    "Terrain Assist missed bridge block z="
                                            + z);
                        }
                        P2GameTestSupport.require(
                                bot.player()
                                                .getInventory()
                                                .getSelected()
                                                .getCount()
                                        == 2,
                                "Terrain Assist bridge did not conserve inventory");
                        cleanup.run();
                        helper.succeed();
                    });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = TERRAIN_POLICY_BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void serverGateRejectsRequestedBreakWithoutMutation(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        for (int x = 0; x <= 8; x++) {
            helper.setBlock(new BlockPos(x, 1, 3), Blocks.STONE);
            helper.setBlock(new BlockPos(x, 2, 3), Blocks.STONE);
        }
        TestBot bot = P2GameTestSupport.spawnBot(
                helper,
                null,
                "P4AssistGate",
                new Vec3(4.5D, 1.0D, 2.5D),
                0.0F);
        P2GameTestSupport.Cleanup cleanup = cleanup(bot);
        boolean previousAllow =
                BotPlayerConfig.NAVIGATION_ALLOW_TERRAIN_BREAK.get();
        cleanup.add(() -> BotPlayerConfig
                .NAVIGATION_ALLOW_TERRAIN_BREAK
                .set(previousAllow));
        try {
            BotPlayerConfig.NAVIGATION_ALLOW_TERRAIN_BREAK.set(false);
            bot.player().getInventory().selected = 0;
            bot.player().getInventory().setItem(
                    0, new ItemStack(Items.DIAMOND_PICKAXE));
            BlockPos lower =
                    helper.absolutePos(new BlockPos(4, 1, 3));
            BlockPos target =
                    helper.absolutePos(new BlockPos(4, 1, 7));
            NavigationSubmission submission =
                    bot.manager().startNavigation(
                            bot.name(),
                            GridPoint.from(target),
                            NavigationPolicy.safeDefault()
                                    .withTerrainAssist(
                                            true,
                                            2,
                                            false,
                                            0));
            P2GameTestSupport.require(
                    submission.status()
                            == NavigationSubmission.Status.ENQUEUED,
                    "Server-gated terrain request was rejected before policy evaluation");
            CompletableFuture<NavigationOutcome> completion =
                    submission.completion()
                            .orElseThrow()
                            .toCompletableFuture();
            P2GameTestSupport.awaitCondition(
                    helper,
                    180,
                    completion::isDone,
                    "P4 server terrain gate did not terminate the request",
                    cleanup,
                    () -> {
                        NavigationOutcome outcome = completion.join();
                        P2GameTestSupport.require(
                                outcome.state()
                                                == NavigationState.FAILED
                                        && outcome.failure()
                                                == NavigationFailure
                                                        .POLICY_BLOCKED,
                                "Server terrain gate returned "
                                        + outcome.state()
                                        + "/"
                                        + outcome.failure());
                        P2GameTestSupport.require(
                                bot.player()
                                        .serverLevel()
                                        .getBlockState(lower)
                                        .is(Blocks.STONE),
                                "Server-disabled Terrain Assist mutated the wall");
                        cleanup.run();
                        helper.succeed();
                    });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    private static void verifyNavigation(
            GameTestHelper helper,
            P2GameTestSupport.Cleanup cleanup,
            TestBot bot,
            NavigationOutcome outcome,
            BlockPos target,
            double minimumStartZ,
            long teleportAcknowledgementsBeforeNavigation) {
        P2GameTestSupport.require(
                outcome.state() == NavigationState.SUCCEEDED,
                "P4 navigation ended as "
                        + outcome.state()
                        + "/"
                        + outcome.failure()
                        + ": "
                        + outcome.safeSummary());
        P2GameTestSupport.require(
                bot.player().getZ() > minimumStartZ + 1.0D,
                "P4 outcome succeeded without physical forward displacement");
        P2GameTestSupport.require(
                Math.abs(bot.player().getX() - (target.getX() + 0.5D))
                                <= 1.0D
                        && Math.abs(
                                        bot.player().getZ()
                                                - (target.getZ() + 0.5D))
                                <= 1.0D,
                "P4 terminal body position did not match the goal");
        if (bot.player()
                        .connection
                        .getConnection()
                instanceof BotConnection connection) {
            P2GameTestSupport.require(
                    connection.snapshot()
                                    .teleportAcknowledgementCount()
                            == teleportAcknowledgementsBeforeNavigation,
                    "P4 navigation used a teleport acknowledgement");
        }
        cleanup.run();
        helper.succeed();
    }

    private static boolean nearGoal(TestBot bot, BlockPos target) {
        return Math.abs(
                                bot.player().getX()
                                        - (target.getX() + 0.5D))
                        <= 1.0D
                && Math.abs(
                                bot.player().getZ()
                                        - (target.getZ() + 0.5D))
                        <= 1.0D;
    }

    private static long teleportAcknowledgements(TestBot bot) {
        if (bot.player()
                        .connection
                        .getConnection()
                instanceof BotConnection connection) {
            return connection.snapshot()
                    .teleportAcknowledgementCount();
        }
        throw new IllegalStateException(
                "P4 navigation bot has no virtual BotConnection");
    }

    private static P2GameTestSupport.Cleanup cleanup(
            TestBot bot) {
        P2GameTestSupport.Cleanup cleanup =
                new P2GameTestSupport.Cleanup();
        cleanup.add(() -> P2GameTestSupport.removeBot(
                bot, "P4 navigation GameTest completed"));
        return cleanup;
    }
}
