package io.github.greytaiwolf.botplayer.gametest;

import io.github.greytaiwolf.botplayer.persistence.BotRosterSavedData;
import io.github.greytaiwolf.botplayer.profile.BotProfile;
import io.github.greytaiwolf.botplayer.skill.checkpoint.SkillCheckpoint;
import io.github.greytaiwolf.botplayer.skill.checkpoint.SkillCheckpointScope;
import io.github.greytaiwolf.botplayer.skill.core.SkillRunState;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillRunView;
import java.util.List;
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
 * <p>恢复后必须由同一个持久身份运行全量 suffix 到终态，并用真实原版物品账本证明恰好
 * 生成一个铁镐。phase one 已在首次提交前放置后缀资源；phase two 不重新赠送任何物品或
 * 修改 checkpoint，只重新附着持久 body。
 */
@GameTestHolder(P5ARestartGameTestSupport.PHASE_TWO_NAMESPACE)
@PrefixGameTestTemplate(false)
public final class P5ARestartPhaseTwoGameTests {
    private static final String BATCH = "p5a_restart_phase_two";
    private static final int TIMEOUT_TICKS = 2_800;

    private P5ARestartPhaseTwoGameTests() {}

    @GameTest(
            templateNamespace = P5ARestartGameTestSupport.PHASE_TWO_NAMESPACE,
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void restoresSameWorldIdentityAndCompletesSuffix(
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
                    () -> terminalRecoveredSuffixView(
                            bot, prior, generation) != null,
                    () -> "P5A phase two did not complete its recovered suffix: "
                            + recoveredSuffixDetails(bot, prior, generation),
                    cleanup,
                    () -> {
                        SkillRunView recovered = terminalRecoveredSuffixView(
                                bot, prior, generation);
                        P2GameTestSupport.require(
                                recovered != null,
                                "Recovered suffix run disappeared before terminal verification");
                        assertRecoveredSuffix(recovered, prior, generation);
                        assertSuccessfulRecoveredBootstrap(bot, recovered);
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

    private static SkillRunView terminalRecoveredSuffixView(
            P5ARestartGameTestSupport.RestoredBot bot,
            SkillCheckpoint prior,
            long generation) {
        SkillRunView recovered = recoveredSuffixView(bot, prior, generation);
        return recovered != null && recovered.state().isTerminal()
                ? recovered
                : null;
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

    /**
     * 终态断言必须同时检查 DAG、主手、所有中间物和 phase-one 已保存的无关物品；否则
     * "恢复成功" 可能只是重复提交或部分消耗了一份输入账本。
     */
    private static void assertSuccessfulRecoveredBootstrap(
            P5ARestartGameTestSupport.RestoredBot bot,
            SkillRunView recovered) {
        P2GameTestSupport.require(
                recovered.state() == SkillRunState.SUCCEEDED
                        && recovered.completedNodes()
                                == recovered.totalNodes(),
                "Recovered P5A suffix did not finish every node: "
                        + recovered);
        P2GameTestSupport.require(
                bot.player().containerMenu == bot.player().inventoryMenu
                        && bot.player().inventoryMenu.getCarried().isEmpty(),
                "Recovered P5A suffix did not close to native inventory with an empty cursor");
        P2GameTestSupport.require(
                P5ARestartGameTestSupport.count(bot.player(), Items.DIAMOND)
                                == 1
                        && P5ARestartGameTestSupport.count(
                                bot.player(), Items.IRON_PICKAXE) == 1
                        && bot.player().getMainHandItem().is(
                                Items.IRON_PICKAXE),
                "Recovered P5A suffix did not retain one persisted diamond and exactly one equipped iron pickaxe");
        P2GameTestSupport.require(
                P5ARestartGameTestSupport.count(bot.player(),
                        Items.WOODEN_PICKAXE) == 1
                        && P5ARestartGameTestSupport.count(bot.player(),
                                Items.STONE_PICKAXE) == 1
                        && P5ARestartGameTestSupport.count(bot.player(),
                                Items.STICK) == 2
                        && P5ARestartGameTestSupport.count(bot.player(),
                                Items.OAK_PLANKS) == 5,
                "Recovered P5A suffix did not retain the exact canonical tool and material remainder");
        for (net.minecraft.world.item.Item consumed : List.of(
                Items.OAK_LOG,
                Items.CRAFTING_TABLE,
                Items.COBBLESTONE,
                Items.FURNACE,
                Items.RAW_IRON,
                Items.COAL,
                Items.IRON_INGOT)) {
            P2GameTestSupport.require(
                    P5ARestartGameTestSupport.count(bot.player(), consumed)
                            == 0,
                    "Recovered P5A suffix left a duplicated canonical input or output: "
                            + consumed);
        }
    }

    private static String recoveredSuffixDetails(
            P5ARestartGameTestSupport.RestoredBot bot,
            SkillCheckpoint prior,
            long generation) {
        SkillRunView view = recoveredSuffixView(bot, prior, generation);
        if (view == null) {
            return "no matching recovered run";
        }
        return "state=" + view.state()
                + ", completedNodes=" + view.completedNodes()
                + "/" + view.totalNodes()
                + ", failureCode=" + view.failureCode()
                        .map(Object::toString).orElse("none")
                + ", safeSummary=" + view.safeSummary();
    }
}
