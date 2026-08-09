package io.github.greytaiwolf.botplayer.gametest;

import io.github.greytaiwolf.botplayer.BotPlayer;
import io.github.greytaiwolf.botplayer.persistence.BotRosterSavedData;
import io.github.greytaiwolf.botplayer.profile.BotProfile;
import io.github.greytaiwolf.botplayer.skill.checkpoint.SkillCheckpoint;
import io.github.greytaiwolf.botplayer.skill.checkpoint.SkillCheckpointScope;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillRunView;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Second server process for the P5A two-start recovery lane.
 *
 * <p>The assertion ends at accepting a fresh suffix run for the same durable
 * identity. This lane intentionally does not claim an iron-pickaxe completion.
 */
@GameTestHolder(P5ARestartGameTestSupport.PHASE_TWO_NAMESPACE)
@PrefixGameTestTemplate(false)
public final class P5ARestartPhaseTwoGameTests {
    private static final String BATCH = "p5a_restart_phase_two";
    private static final int TIMEOUT_TICKS = 240;

    private P5ARestartPhaseTwoGameTests() {}

    @GameTest(
            templateNamespace = BotPlayer.MOD_ID,
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void restoresSameWorldIdentityAndSubmitsSuffix(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        MinecraftServer server = helper.getLevel().getServer();
        BotRosterSavedData roster = BotRosterSavedData.get(server);
        BotProfile profile = roster.findByName(
                P5ARestartGameTestSupport.BOT_NAME).orElseThrow(() ->
                        new IllegalStateException(
                                "P5A phase two did not load the phase-one roster identity"));
        UUID botId = profile.botId();
        SkillCheckpoint prior = P5ARestartGameTestSupport.checkpointFor(
                server, botId).orElseThrow(() -> new IllegalStateException(
                        "P5A phase two did not load the phase-one checkpoint"));
        assertPreRestartCheckpoint(
                prior, botId, roster.serverInstanceId());

        P5ARestartGameTestSupport.RestoredBot bot =
                P5ARestartGameTestSupport.spawnRestored(helper);
        P2GameTestSupport.Cleanup cleanup = P5GameTestSupport.cleanup();
        cleanup.add(() -> P5ARestartGameTestSupport.removeRestored(
                bot, "P5A restart phase-two GameTest completed"));
        try {
            P5ARestartGameTestSupport.loadCheckpointScopeChunks(bot.player());
            assertRestoredBody(bot, prior, botId);
            long generation = bot.player().runtimeHandle().generation();
            P2GameTestSupport.awaitCondition(
                    helper,
                    TIMEOUT_TICKS - 20,
                    () -> recoveredSuffixView(bot, prior, generation) != null,
                    "P5A phase two did not submit a fresh recovered suffix run",
                    cleanup,
                    () -> {
                        SkillRunView recovered = recoveredSuffixView(
                                bot, prior, generation);
                        P2GameTestSupport.require(
                                recovered != null,
                                "Recovered suffix run disappeared before verification");
                        assertRecoveredSuffix(recovered, prior, generation);
                        cleanup.run();
                        helper.succeed();
                    });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    private static void assertPreRestartCheckpoint(
            SkillCheckpoint checkpoint,
            UUID botId,
            UUID serverInstanceId) {
        P2GameTestSupport.require(
                !checkpoint.continuationState().isTerminal()
                        && checkpoint.scope().isPresent(),
                "P5A phase-two checkpoint is not restartable with a scope");
        P2GameTestSupport.require(
                checkpoint.serverInstanceId().equals(serverInstanceId)
                        && checkpoint.botId().equals(botId)
                        && checkpoint.playerId().equals(botId),
                "P5A phase-two checkpoint does not belong to the retained world and roster");
    }

    private static void assertRestoredBody(
            P5ARestartGameTestSupport.RestoredBot bot,
            SkillCheckpoint prior,
            UUID botId) {
        P2GameTestSupport.require(
                bot.player().getUUID().equals(botId),
                "P5A phase two spawned a body for a different Bot identity");
        P2GameTestSupport.require(
                bot.player().runtimeHandle().generation()
                        > prior.generation(),
                "P5A phase two did not advance the durable Bot generation");
        SkillCheckpointScope scope = prior.scope().orElseThrow();
        P5ARestartGameTestSupport.requireRestoredBodyMatchesScope(
                bot.player(), scope);
        P2GameTestSupport.require(
                P5ARestartGameTestSupport.count(bot.player(), Items.DIAMOND)
                        == 1,
                "P5A phase two cleared or failed to save the phase-one inventory");
        P2GameTestSupport.require(
                P5ARestartGameTestSupport.count(bot.player(), Items.OAK_LOG)
                        >= 4,
                "P5A phase two did not retain the harvested safe-checkpoint resources");
    }

    private static SkillRunView recoveredSuffixView(
            P5ARestartGameTestSupport.RestoredBot bot,
            SkillCheckpoint prior,
            long generation) {
        SkillRunView view = bot.manager().skillRun(
                bot.player().getUUID()).orElse(null);
        if (view == null
                || view.botGeneration() != generation
                || view.runId().equals(prior.runId())
                || view.planId().equals(prior.plan().planId())) {
            return null;
        }
        assertRecoveredSuffix(view, prior, generation);
        return view;
    }

    private static void assertRecoveredSuffix(
            SkillRunView recovered,
            SkillCheckpoint prior,
            long generation) {
        P2GameTestSupport.require(
                recovered.botGeneration() == generation
                        && !recovered.runId().equals(prior.runId())
                        && !recovered.planId().equals(
                                prior.plan().planId())
                        && recovered.planRevision()
                                == prior.plan().revision(),
                "P5A phase two did not submit the expected new-generation suffix");
    }
}
