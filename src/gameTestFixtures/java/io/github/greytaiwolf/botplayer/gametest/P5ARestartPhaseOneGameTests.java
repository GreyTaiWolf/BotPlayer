package io.github.greytaiwolf.botplayer.gametest;

import io.github.greytaiwolf.botplayer.gametest.P2GameTestSupport.TestBot;
import io.github.greytaiwolf.botplayer.skill.checkpoint.SkillCheckpoint;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillRunSubmission;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * First server process for the P5A two-start recovery lane.
 *
 * <p>This deliberately stops after the four real log-break fragments have
 * reached their first restartable safe checkpoint. It does not claim to finish
 * the wood-to-iron production plan.
 */
@GameTestHolder(P5ARestartGameTestSupport.PHASE_ONE_NAMESPACE)
@PrefixGameTestTemplate(false)
public final class P5ARestartPhaseOneGameTests {
    private static final String BATCH = "p5a_restart_phase_one";
    private static final int TIMEOUT_TICKS = 420;

    private P5ARestartPhaseOneGameTests() {}

    @GameTest(
            templateNamespace = P5ARestartGameTestSupport.PHASE_ONE_NAMESPACE,
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void persistsSafeCheckpointThenNormallyShutsDown(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        placeLogTargets(helper);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.persistentFixture(
                        helper,
                        P5ARestartGameTestSupport.IDENTITY_NAMESPACE,
                        "two-start-recovery");
        P2GameTestSupport.require(
                fixture.botName(P5ARestartGameTestSupport.ROLE).equals(
                        P5ARestartGameTestSupport.BOT_NAME),
                "P5A restart fixture identity no longer matches phase two");
        TestBot bot = fixture.spawn(P5ARestartGameTestSupport.ROLE);
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        MinecraftServer server = helper.getLevel().getServer();
        try {
            P5ARestartGameTestSupport.loadCheckpointScopeChunks(bot.player());
            bot.player().getInventory().setItem(
                    P5ARestartGameTestSupport.PERSISTED_ITEM_SLOT,
                    new ItemStack(Items.DIAMOND));
            SkillRunSubmission submission = bot.manager()
                    .startBootstrapIron(bot.name());
            P2GameTestSupport.require(
                    submission.status() == SkillRunSubmission.Status.ACCEPTED,
                    "P5A restart phase one rejected bootstrap submission: "
                            + submission.status());
            UUID botId = bot.player().getUUID();
            P5ARestartGameTestSupport.armPhaseOneShutdown(
                    server, bot.manager(), botId);
            P2GameTestSupport.awaitCondition(
                    helper,
                    TIMEOUT_TICKS - 20,
                    () -> phaseOneShutdownCompleted(server),
                    "P5A restart phase one never reached a restartable safe checkpoint",
                    () -> cleanupAfterFailure(server, cleanup),
                    () -> {
                        SkillCheckpoint observed = P5ARestartGameTestSupport
                                .phaseOneCheckpoint(server).orElseThrow(() ->
                                        new IllegalStateException(
                                                "P5A phase one did not retain its observed checkpoint"));
                        SkillCheckpoint durable = P5ARestartGameTestSupport
                                .checkpointFor(server, botId).orElseThrow(() ->
                                        new IllegalStateException(
                                                "P5A phase one checkpoint was not durable after normal shutdown"));
                        assertRetainedCheckpoint(observed, durable, botId);
                        P5ARestartGameTestSupport.clearPhaseOneShutdown(server);
                        helper.succeed();
                    });
        } catch (RuntimeException | AssertionError exception) {
            cleanupAfterFailure(server, cleanup);
            throw exception;
        }
    }

    private static boolean phaseOneShutdownCompleted(MinecraftServer server) {
        P5ARestartGameTestSupport.phaseOneFailure(server).ifPresent(value -> {
            throw new IllegalStateException(
                    "P5A phase-one normal shutdown failed: " + value);
        });
        return P5ARestartGameTestSupport.phaseOneShutdownIssued(server)
                && P5ARestartGameTestSupport.phaseOneCheckpoint(server)
                        .isPresent();
    }

    private static void assertRetainedCheckpoint(
            SkillCheckpoint observed, SkillCheckpoint durable, UUID botId) {
        P2GameTestSupport.require(
                !durable.continuationState().isTerminal()
                        && durable.scope().isPresent(),
                "Normal shutdown did not retain a restartable scoped checkpoint");
        P2GameTestSupport.require(
                durable.botId().equals(botId)
                        && durable.playerId().equals(botId),
                "Retained checkpoint identity does not match the phase-one Bot");
        P2GameTestSupport.require(
                durable.serverInstanceId().equals(observed.serverInstanceId())
                        && durable.checkpointId().equals(
                                observed.checkpointId())
                        && durable.runId().equals(observed.runId())
                        && durable.generation() == observed.generation()
                        && durable.plan().equals(observed.plan())
                        && durable.stateRevision()
                                == observed.stateRevision()
                        && durable.scope().equals(observed.scope()),
                "Normal shutdown changed or fenced the observed safe checkpoint");
    }

    private static void cleanupAfterFailure(
            MinecraftServer server, P2GameTestSupport.Cleanup cleanup) {
        P5ARestartGameTestSupport.clearPhaseOneShutdown(server);
        cleanup.run();
    }

    private static void placeLogTargets(GameTestHelper helper) {
        helper.setBlock(new BlockPos(3, 1, 3), Blocks.OAK_LOG);
        helper.setBlock(new BlockPos(3, 1, 4), Blocks.OAK_LOG);
        helper.setBlock(new BlockPos(3, 1, 5), Blocks.OAK_LOG);
        helper.setBlock(new BlockPos(4, 1, 3), Blocks.OAK_LOG);
    }
}
