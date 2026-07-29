package io.github.greytaiwolf.botplayer.gametest;

import io.github.greytaiwolf.botplayer.BotPlayer;
import io.github.greytaiwolf.botplayer.gametest.P2GameTestSupport.TestBot;
import io.github.greytaiwolf.botplayer.kernel.BotConnection;
import io.github.greytaiwolf.botplayer.navigation.GridPoint;
import io.github.greytaiwolf.botplayer.navigation.NavigationOutcome;
import io.github.greytaiwolf.botplayer.navigation.NavigationState;
import io.github.greytaiwolf.botplayer.navigation.NavigationSubmission;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
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
