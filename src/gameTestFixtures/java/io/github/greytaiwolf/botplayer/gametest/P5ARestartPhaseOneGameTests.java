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
        placeDeferredSourceVein(helper);
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
        /*
         * The LOWEST-priority support callback records the safe checkpoint
         * before it invokes manager.shutdown(). The expected cancellation of
         * that already-safe run is therefore evidence of a normal phase-one
         * stop, not a production failure.
         */
        if (P5ARestartGameTestSupport.phaseOneShutdownIssued(server)
                && P5ARestartGameTestSupport.phaseOneCheckpoint(server)
                        .isPresent()) {
            return true;
        }
        /*
         * A terminal run cannot become a later restartable checkpoint. Report
         * its structured view immediately instead of hiding a concrete
         * production failure behind the outer timeout.
         */
        P5ARestartGameTestSupport.phaseOneRun(server)
                .filter(view -> view.state().isTerminal())
                .ifPresent(view -> {
                    throw new IllegalStateException(
                            "P5A phase-one run became terminal before a restartable safe checkpoint: "
                                    + view);
                });
        return false;
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
        /*
         * The production acquisition handler may only wait for an existing
         * drop; it does not move a Bot toward one. Keep each test log one
         * cardinal block from the stationary phase-one body so the vanilla
         * drop is in deterministic pickup range. The prior diagonal third
         * target could be broken successfully but remain uncollected.
         */
        helper.setBlock(new BlockPos(3, 1, 4), Blocks.OAK_LOG);
        helper.setBlock(new BlockPos(4, 1, 3), Blocks.OAK_LOG);
        helper.setBlock(new BlockPos(4, 1, 5), Blocks.OAK_LOG);
        helper.setBlock(new BlockPos(5, 1, 4), Blocks.OAK_LOG);
    }

    /**
     * 后缀所需的非木头资源在首次提交前就已存在，但刻意放在 phase-two 空模板之外。
     * 这样第二个 GameTest 进程重载自己的 9×5×9 模板时不会把持久世界中的真实资源
     * 偷换为第二阶段直接赠送；所有位置仍处于 checkpoint 与生产 TaskSensor 的半径八内。
     */
    private static void placeDeferredSourceVein(GameTestHelper helper) {
        java.util.List<SourceBlock> sources = java.util.List.of(
                new SourceBlock(new BlockPos(9, 1, 2), Blocks.COBBLESTONE),
                new SourceBlock(new BlockPos(10, 1, 2), Blocks.COBBLESTONE),
                new SourceBlock(new BlockPos(11, 1, 2), Blocks.COBBLESTONE),
                new SourceBlock(new BlockPos(9, 1, 3), Blocks.COBBLESTONE),
                new SourceBlock(new BlockPos(10, 1, 3), Blocks.COBBLESTONE),
                new SourceBlock(new BlockPos(11, 1, 3), Blocks.COBBLESTONE),
                new SourceBlock(new BlockPos(9, 1, 4), Blocks.COBBLESTONE),
                new SourceBlock(new BlockPos(10, 1, 4), Blocks.COBBLESTONE),
                new SourceBlock(new BlockPos(11, 1, 4), Blocks.COBBLESTONE),
                new SourceBlock(new BlockPos(9, 1, 5), Blocks.COBBLESTONE),
                new SourceBlock(new BlockPos(10, 1, 5), Blocks.COBBLESTONE),
                new SourceBlock(new BlockPos(12, 1, 2), Blocks.IRON_ORE),
                new SourceBlock(new BlockPos(12, 1, 3), Blocks.IRON_ORE),
                new SourceBlock(new BlockPos(12, 1, 4), Blocks.IRON_ORE),
                new SourceBlock(new BlockPos(12, 1, 5), Blocks.COAL_ORE));
        for (SourceBlock source : sources) {
            helper.setBlock(source.position().below(), Blocks.STONE);
            helper.setBlock(source.position(), source.block());
        }
    }

    private record SourceBlock(BlockPos position,
            net.minecraft.world.level.block.Block block) {
        private SourceBlock {
            position = java.util.Objects.requireNonNull(position, "position");
            block = java.util.Objects.requireNonNull(block, "block");
        }
    }
}
