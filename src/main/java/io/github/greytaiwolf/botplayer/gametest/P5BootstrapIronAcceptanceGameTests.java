package io.github.greytaiwolf.botplayer.gametest;

import io.github.greytaiwolf.botplayer.BotPlayer;
import io.github.greytaiwolf.botplayer.gametest.P2GameTestSupport.TestBot;
import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
import io.github.greytaiwolf.botplayer.skill.core.SkillRunState;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillRunSubmission;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillRunView;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * P5A bootstrap iron 的真实通用运行时纵切验收。
 *
 * <p>测试只在 {@link io.github.greytaiwolf.botplayer.lifecycle.BotLifecycleManager#startBootstrapIron(String)}
 * 之前放置有限的原版资源；提交之后不会给
 * Bot 注入物品、直接改库存，或替它破坏/放置方块。四类资源都在当前局部观察范围且距身体不远，
 * 因此它证明的是编译器插入的有界近场导航门、通用 DAG runtime、真实 {@code BREAK_BLOCK}、
 * 原版菜单配方、工作站放置与最终铁镐装备闭环，而不是尚未实现的远距离资源发现。
 *
 * <p>资源都放在同一层、5×5 的近场格内。四原木先占出生点正交相邻格，故它们的逐方块导航
 * 门立即满足半径一目标；破完后留下 3×3 空场，供外环其余 fragment 的有界导航使用。每次
 * 原版掉落都落在完整石地面上，能在生产 handler 的有限拾取回读窗口内自然进入
 * Bot 背包。资源数量恰好是 canonical 木头到铁镐模板所需的 4 原木、11 圆石、3 铁矿和
 * 1 煤矿。
 */
@GameTestHolder(BotPlayer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class P5BootstrapIronAcceptanceGameTests {
    private static final String BATCH = "p5a_bootstrap_iron";
    private static final int TIMEOUT_TICKS = 1_900;
    private static final int CHECKPOINT_SCOPE_RADIUS = 8;

    private P5BootstrapIronAcceptanceGameTests() {
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void emptyBackpackReachesIronPickThroughGenericRuntime(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        placeLocalSourceVein(helper);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper,
                        "bootstrap_iron_generic_runtime");
        /* "iron" is a valid P5 scoped role: lowercase base36 and at most five chars. */
        TestBot bot = fixture.spawn("iron");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            prepareEmptyBackpack(bot);
            loadCheckpointScopeChunks(bot.player());
            requireEmptyBootstrapInventory(bot);

            SkillRunSubmission submission = bot.manager()
                    .startBootstrapIron(bot.name());
            P2GameTestSupport.require(
                    submission.status() == SkillRunSubmission.Status.ACCEPTED,
                    "P5A bootstrap iron submission was rejected: "
                            + submission.status() + "/"
                            + submission.safeSummary());
            UUID runId = submission.runId().orElseThrow();

            P2GameTestSupport.awaitCondition(
                    helper,
                    TIMEOUT_TICKS - 20,
                    () -> terminalView(bot, runId).isPresent(),
                    "P5A bootstrap iron never reached a terminal runtime state",
                    cleanup,
                    () -> verifySuccessfulBootstrap(helper, bot, runId,
                            cleanup));
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    private static void prepareEmptyBackpack(TestBot bot) {
        bot.player().getInventory().clearContent();
        bot.player().getInventory().selected = 0;
        bot.player().inventoryMenu.setCarried(ItemStack.EMPTY);
        bot.player().inventoryMenu.broadcastChanges();
    }

    private static void requireEmptyBootstrapInventory(TestBot bot) {
        for (Item item : bootstrapMaterials()) {
            P2GameTestSupport.require(
                    count(bot, item) == 0,
                    "Bootstrap fixture started with a forbidden injected "
                            + item);
        }
        P2GameTestSupport.require(
                bot.player().getMainHandItem().isEmpty()
                        && bot.player().containerMenu
                                == bot.player().inventoryMenu
                        && bot.player().inventoryMenu.getCarried().isEmpty(),
                "Bootstrap fixture did not begin with an empty native inventory state");
    }

    private static void verifySuccessfulBootstrap(
            GameTestHelper helper,
            TestBot bot,
            UUID runId,
            P2GameTestSupport.Cleanup cleanup) {
        try {
            SkillRunView view = terminalView(bot, runId).orElseThrow();
            P2GameTestSupport.require(
                    view.state() == SkillRunState.SUCCEEDED,
                    "P5A bootstrap iron ended as " + view.state() + "/"
                            + view.failureCode() + "/" + view.safeSummary());
            P2GameTestSupport.require(
                    view.completedNodes() == view.totalNodes(),
                    "Successful bootstrap run left nodes incomplete: "
                            + view.completedNodes() + "/" + view.totalNodes());
            P2GameTestSupport.require(
                    bot.player().containerMenu == bot.player().inventoryMenu
                            && bot.player().inventoryMenu.getCarried().isEmpty(),
                    "Bootstrap did not close to native inventory with an empty cursor");
            P2GameTestSupport.require(
                    count(bot, Items.IRON_PICKAXE) == 1
                            && bot.player().getMainHandItem()
                                    .is(Items.IRON_PICKAXE),
                    "Bootstrap did not end with exactly one real iron pickaxe in main hand");
            P2GameTestSupport.require(
                    count(bot, Items.WOODEN_PICKAXE) == 1
                            && count(bot, Items.STONE_PICKAXE) == 1
                            && count(bot, Items.STICK) == 2,
                    "Bootstrap did not retain the exact canonical tool and stick remainder");
            for (Item consumed : List.of(
                    Items.OAK_LOG,
                    Items.OAK_PLANKS,
                    Items.CRAFTING_TABLE,
                    Items.COBBLESTONE,
                    Items.FURNACE,
                    Items.RAW_IRON,
                    Items.COAL,
                    Items.IRON_INGOT)) {
                P2GameTestSupport.require(
                        count(bot, consumed) == 0,
                        "Bootstrap left an unexpected canonical input/output remainder: "
                                + consumed);
            }
            for (SourceBlock source : sourceBlocks()) {
                Block block = helper.getLevel().getBlockState(
                                helper.absolutePos(source.position()))
                        .getBlock();
                P2GameTestSupport.require(
                        block != source.block(),
                        "Bootstrap did not destroy its exact local source block "
                                + source.position());
                P2GameTestSupport.require(
                        block == Blocks.AIR || block == Blocks.CRAFTING_TABLE
                                || block == Blocks.FURNACE,
                        "Bootstrap replaced a source with an unexpected block "
                                + block + " at " + source.position());
            }
            P2GameTestSupport.require(
                    countBlocks(helper, Blocks.CRAFTING_TABLE) == 1
                            && countBlocks(helper, Blocks.FURNACE) == 1,
                    "Bootstrap did not place and retain exactly one of each real workstation");
        } finally {
            cleanup.run();
        }
        helper.succeed();
    }

    private static java.util.Optional<SkillRunView> terminalView(
            TestBot bot, UUID runId) {
        return bot.manager().skillRun(bot.player().getUUID())
                .filter(view -> view.runId().equals(runId)
                        && view.state().isTerminal());
    }

    /**
     * Loads exactly the bounded scope that the P5A checkpoint observer reads at each safe
     * runtime boundary. This is test-world setup only; it neither writes state nor expands the
     * production TaskSensor scope.
     */
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

    /**
     * Each source position is in the existing radius-eight TaskSensor cube. The first four logs
     * are orthogonally adjacent to the spawn cell; after they are broken, the open inner plaza
     * lets each later radius-one goal stop beside its selected outer-ring source without assuming
     * an unbounded path or retaining any selected coordinate.
     */
    private static void placeLocalSourceVein(GameTestHelper helper) {
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

    private static List<Item> bootstrapMaterials() {
        return List.of(
                Items.OAK_LOG,
                Items.OAK_PLANKS,
                Items.STICK,
                Items.CRAFTING_TABLE,
                Items.WOODEN_PICKAXE,
                Items.COBBLESTONE,
                Items.FURNACE,
                Items.STONE_PICKAXE,
                Items.RAW_IRON,
                Items.COAL,
                Items.IRON_INGOT,
                Items.IRON_PICKAXE);
    }

    private static int count(TestBot bot, Item item) {
        int total = 0;
        for (int slot = 0;
                slot < bot.player().getInventory().getContainerSize();
                slot++) {
            ItemStack stack = bot.player().getInventory().getItem(slot);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static int countBlocks(GameTestHelper helper, Block expected) {
        int total = 0;
        for (int x = 0; x < 9; x++) {
            for (int y = 1; y < 5; y++) {
                for (int z = 0; z < 9; z++) {
                    if (helper.getLevel().getBlockState(
                            helper.absolutePos(new BlockPos(x, y, z)))
                            .is(expected)) {
                        total++;
                    }
                }
            }
        }
        return total;
    }

    private record SourceBlock(BlockPos position, Block block) {
        private SourceBlock {
            position = java.util.Objects.requireNonNull(position, "position");
            block = java.util.Objects.requireNonNull(block, "block");
        }
    }
}
