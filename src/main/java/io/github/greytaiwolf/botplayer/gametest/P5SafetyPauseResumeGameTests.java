package io.github.greytaiwolf.botplayer.gametest;

import io.github.greytaiwolf.botplayer.BotPlayer;
import io.github.greytaiwolf.botplayer.gametest.P2GameTestSupport.TestBot;
import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
import io.github.greytaiwolf.botplayer.safety.HazardType;
import io.github.greytaiwolf.botplayer.safety.SafetyIncidentView;
import io.github.greytaiwolf.botplayer.safety.SafetyIntervention;
import io.github.greytaiwolf.botplayer.safety.SafetyState;
import io.github.greytaiwolf.botplayer.skill.core.SkillRunState;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillRunSubmission;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillRunView;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * P5A L0 hostile handoff 对通用 DAG 的暂停/恢复验收。
 *
 * <p>这里先让真实 bootstrap-iron DAG 进入一项已派发的节点，再由真实 hostile
 * Safety incident 暂停它。测试只在确认 PAUSED 后移除威胁实体，模拟目标自然离开；
 * 不替 Bot 改背包、完成节点或伪造回执。恢复必须等到 L0 clear-stability、自卫动作和
 * 原版控制面全部静止，随后以更高 revision 重新进入同一节点的观察/准备路径。</p>
 */
@GameTestHolder(BotPlayer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class P5SafetyPauseResumeGameTests {
    private static final String BATCH = "p5_safety_pause_resume";
    private static final int TIMEOUT_TICKS = 520;
    private static final int START_TIMEOUT_TICKS = 120;
    private static final int PAUSE_TIMEOUT_TICKS = 180;
    private static final int RESUME_TIMEOUT_TICKS = 180;
    private static final int CHECKPOINT_SCOPE_RADIUS = 8;

    private P5SafetyPauseResumeGameTests() {}

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void hostileHandoffPausesAndReobservesGenericDag(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        placeLocalBootstrapSources(helper);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(
                        helper, "safety_pause_resume_generic_dag");
        TestBot bot = fixture.spawn("pause");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        Difficulty previousDifficulty = helper.getLevel()
                .getServer()
                .getWorldData()
                .getDifficulty();
        cleanup.add(() -> helper.getLevel()
                .getServer()
                .setDifficulty(previousDifficulty, true));
        try {
            helper.getLevel().getServer().setDifficulty(Difficulty.NORMAL, true);
            prepareDefenderAndBootstrapInventory(bot);
            loadCheckpointScopeChunks(bot.player());
            SkillRunSubmission submission = bot.manager()
                    .startBootstrapIron(bot.name());
            P2GameTestSupport.require(
                    submission.status() == SkillRunSubmission.Status.ACCEPTED,
                    "P5A bootstrap run was rejected before the safety pause test: "
                            + submission.status() + "/"
                            + submission.safeSummary());
            UUID runId = submission.runId().orElseThrow();
            P2GameTestSupport.awaitCondition(
                    helper,
                    START_TIMEOUT_TICKS,
                    () -> runView(bot, runId)
                            .map(P5SafetyPauseResumeGameTests
                                    ::hasDispatchedNode)
                            .orElse(false),
                    "P5A bootstrap run never reached a dispatchable node before hostile handoff",
                    cleanup,
                    () -> triggerHostilePause(
                            helper, bot, runId, cleanup));
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    private static boolean hasDispatchedNode(SkillRunView view) {
        return !view.state().isTerminal()
                && (view.state() == SkillRunState.WAITING_ACTION
                        || view.state() == SkillRunState.WAITING_NAVIGATION
                        || view.state() == SkillRunState.WAITING_MENU
                        || view.state() == SkillRunState.WAITING_QUERY
                        || view.state() == SkillRunState.WAITING_TIMER);
    }

    private static void triggerHostilePause(
            GameTestHelper helper,
            TestBot bot,
            UUID runId,
            P2GameTestSupport.Cleanup cleanup) {
        Zombie zombie = Objects.requireNonNull(
                EntityType.ZOMBIE.create(helper.getLevel()),
                "P5A safety-pause zombie");
        cleanup.add(zombie::discard);
        BotServerPlayer player = bot.player();
        /* Keep the hostile on an empty floor cell, not inside the bootstrap source vein. */
        Vec3 position = helper.absoluteVec(new Vec3(1.5D, 1.0D, 4.5D));
        zombie.moveTo(position.x, position.y, position.z, 0.0F, 0.0F);
        zombie.setNoAi(true);
        zombie.setPersistenceRequired();
        zombie.setHealth(zombie.getMaxHealth());
        zombie.setTarget(player);
        P2GameTestSupport.require(
                helper.getLevel().addFreshEntity(zombie),
                "Hostile fixture could not enter the GameTest level");
        P2GameTestSupport.require(
                zombie.getTarget() == player,
                "Hostile fixture did not retain the real BotPlayer target");

        long[] pausedRevision = {-1L};
        P2GameTestSupport.awaitCondition(
                helper,
                PAUSE_TIMEOUT_TICKS,
                () -> {
                    SkillRunView view = runView(bot, runId).orElse(null);
                    SafetyIncidentView incident = bot.manager()
                            .safetyIncident(bot.name()).orElse(null);
                    boolean delegated = incident != null
                            && incident.hazardType()
                                    == HazardType.HOSTILE_TARGETING
                            && incident.state() == SafetyState.DELEGATED
                            && incident.currentIntervention()
                                    .filter(value -> value
                                            == SafetyIntervention
                                                    .DELEGATE_TO_SURVIVAL_SKILL)
                                    .isPresent();
                    if (view == null || view.state() != SkillRunState.PAUSED
                            || !delegated) {
                        return false;
                    }
                    pausedRevision[0] = view.stateRevision();
                    return true;
                },
                () -> "Hostile handoff did not pause the generic P5A run: "
                        + diagnostic(bot, runId),
                cleanup,
                () -> {
                    /* Threat disappearance is an external world change, not a Bot side effect. */
                    zombie.discard();
                    awaitReobservedResume(
                            helper, bot, runId, pausedRevision[0], cleanup);
                });
    }

    private static void awaitReobservedResume(
            GameTestHelper helper,
            TestBot bot,
            UUID runId,
            long pausedRevision,
            P2GameTestSupport.Cleanup cleanup) {
        P2GameTestSupport.require(pausedRevision >= 0L,
                "Safety-pause test did not capture the PAUSED revision");
        P2GameTestSupport.awaitCondition(
                helper,
                RESUME_TIMEOUT_TICKS,
                () -> {
                    SkillRunView view = runView(bot, runId).orElse(null);
                    if (view == null || view.state().isTerminal()
                            || view.state() == SkillRunState.PAUSED) {
                        return false;
                    }
                    return view.stateRevision() > pausedRevision;
                },
                () -> "Generic P5A run did not resume with a fresh observation after safety cleared: "
                        + diagnostic(bot, runId),
                cleanup,
                () -> {
                    try {
                        SkillRunView resumed = runView(bot, runId)
                                .orElseThrow(() -> new IllegalStateException(
                                        "resumed generic P5A run view disappeared"));
                        P2GameTestSupport.require(
                                !resumed.state().isTerminal()
                                        && resumed.state()
                                                != SkillRunState.PAUSED
                                        && resumed.stateRevision()
                                                > pausedRevision,
                                "Safety-cleared generic P5A run did not enter a fresh nonterminal "
                                        + "state: " + resumed);
                        P2GameTestSupport.require(
                                bot.manager().safetyIncident(bot.name())
                                        .isEmpty(),
                                "Generic P5A run resumed before L0 cleared the hostile incident");
                    } finally {
                        cleanup.run();
                    }
                    helper.succeed();
                });
    }

    private static Optional<SkillRunView> runView(TestBot bot, UUID runId) {
        return bot.manager().skillRun(bot.player().getUUID())
                .filter(view -> view.runId().equals(runId));
    }

    private static String diagnostic(TestBot bot, UUID runId) {
        return runView(bot, runId)
                .map(view -> "run=" + view.state()
                        + "/revision=" + view.stateRevision()
                        + "/summary=" + view.safeSummary())
                .orElse("run view missing")
                + ", safety=" + bot.manager().safetyIncident(bot.name());
    }

    private static void prepareDefenderAndBootstrapInventory(TestBot bot) {
        bot.player().getInventory().clearContent();
        bot.player().getInventory().selected = 0;
        bot.player().getInventory().setItem(0, new ItemStack(Items.IRON_SWORD));
        bot.player().inventoryMenu.setCarried(ItemStack.EMPTY);
        bot.player().inventoryMenu.broadcastChanges();
        bot.player().setHealth(bot.player().getMaxHealth());
        bot.player().invulnerableTime = 0;
    }

    private static void loadCheckpointScopeChunks(BotServerPlayer player) {
        BlockPos anchor = player.blockPosition();
        int minimumChunkX = Math.floorDiv(
                anchor.getX() - CHECKPOINT_SCOPE_RADIUS, 16);
        int maximumChunkX = Math.floorDiv(
                anchor.getX() + CHECKPOINT_SCOPE_RADIUS, 16);
        int minimumChunkZ = Math.floorDiv(
                anchor.getZ() - CHECKPOINT_SCOPE_RADIUS, 16);
        int maximumChunkZ = Math.floorDiv(
                anchor.getZ() + CHECKPOINT_SCOPE_RADIUS, 16);
        for (int chunkX = minimumChunkX;
                chunkX <= maximumChunkX;
                chunkX++) {
            for (int chunkZ = minimumChunkZ;
                    chunkZ <= maximumChunkZ;
                    chunkZ++) {
                player.serverLevel().getChunk(chunkX, chunkZ);
            }
        }
    }

    private static void placeLocalBootstrapSources(GameTestHelper helper) {
        for (SourceBlock source : sourceBlocks()) {
            helper.setBlock(source.position(), source.block());
        }
    }

    private static List<SourceBlock> sourceBlocks() {
        return List.of(
                new SourceBlock(new BlockPos(4, 1, 3), Blocks.OAK_LOG),
                new SourceBlock(new BlockPos(3, 1, 4), Blocks.OAK_LOG),
                new SourceBlock(new BlockPos(5, 1, 4), Blocks.OAK_LOG),
                new SourceBlock(new BlockPos(4, 1, 5), Blocks.OAK_LOG),
                new SourceBlock(new BlockPos(3, 1, 2), Blocks.COBBLESTONE),
                new SourceBlock(new BlockPos(4, 1, 2), Blocks.COBBLESTONE),
                new SourceBlock(new BlockPos(5, 1, 2), Blocks.COBBLESTONE),
                new SourceBlock(new BlockPos(2, 1, 3), Blocks.COBBLESTONE),
                new SourceBlock(new BlockPos(6, 1, 3), Blocks.COBBLESTONE),
                new SourceBlock(new BlockPos(2, 1, 4), Blocks.COBBLESTONE),
                new SourceBlock(new BlockPos(6, 1, 4), Blocks.COBBLESTONE),
                new SourceBlock(new BlockPos(2, 1, 5), Blocks.COBBLESTONE),
                new SourceBlock(new BlockPos(6, 1, 5), Blocks.COBBLESTONE),
                new SourceBlock(new BlockPos(3, 1, 6), Blocks.COBBLESTONE),
                new SourceBlock(new BlockPos(4, 1, 6), Blocks.COBBLESTONE),
                new SourceBlock(new BlockPos(2, 1, 2), Blocks.IRON_ORE),
                new SourceBlock(new BlockPos(6, 1, 2), Blocks.IRON_ORE),
                new SourceBlock(new BlockPos(2, 1, 6), Blocks.IRON_ORE),
                new SourceBlock(new BlockPos(6, 1, 6), Blocks.COAL_ORE));
    }

    private record SourceBlock(BlockPos position, Block block) {
        private SourceBlock {
            position = Objects.requireNonNull(position, "position");
            block = Objects.requireNonNull(block, "block");
        }
    }
}
