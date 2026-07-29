package io.github.greytaiwolf.botplayer.gametest;

import io.github.greytaiwolf.botplayer.BotPlayer;
import io.github.greytaiwolf.botplayer.gametest.P2GameTestSupport.TestBot;
import io.github.greytaiwolf.botplayer.kernel.BotConnection;
import io.github.greytaiwolf.botplayer.navigation.GridPoint;
import io.github.greytaiwolf.botplayer.navigation.NavigationFailure;
import io.github.greytaiwolf.botplayer.navigation.NavigationOutcome;
import io.github.greytaiwolf.botplayer.navigation.NavigationState;
import io.github.greytaiwolf.botplayer.navigation.NavigationSubmission;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
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
    public static void shallowWaterRouteUsesRealSwimmingState(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        for (int z = 4; z <= 6; z++) {
            helper.setBlock(new BlockPos(4, 1, z), Blocks.WATER);
        }
        TestBot bot = P2GameTestSupport.spawnBot(
                helper,
                null,
                "P4Water",
                new Vec3(4.5D, 1.0D, 2.5D),
                0.0F);
        P2GameTestSupport.Cleanup cleanup = cleanup(bot);
        try {
            boolean[] observedWater = {false};
            BlockPos target =
                    helper.absolutePos(new BlockPos(4, 1, 7));
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
            P2GameTestSupport.awaitCondition(
                    helper,
                    260,
                    () -> {
                        observedWater[0] |= bot.player().isInWater();
                        return completion.isDone();
                    },
                    "P4 did not finish the shallow-water route",
                    cleanup,
                    () -> {
                        NavigationOutcome outcome = completion.join();
                        P2GameTestSupport.require(
                                outcome.state()
                                        == NavigationState.SUCCEEDED,
                                "P4 water route ended as "
                                        + outcome.state()
                                        + "/"
                                        + outcome.failure());
                        P2GameTestSupport.require(
                                observedWater[0],
                                "P4 route never entered authoritative water state");
                        P2GameTestSupport.require(
                                nearGoal(bot, target),
                                "P4 water route did not reach its target");
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
    public static void ladderRouteRaisesTheRealPlayerBody(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        for (int y = 1; y <= 4; y++) {
            helper.setBlock(new BlockPos(4, y, 5), Blocks.STONE);
        }
        for (int y = 1; y <= 3; y++) {
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
                    helper.absolutePos(new BlockPos(4, 3, 4));
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
