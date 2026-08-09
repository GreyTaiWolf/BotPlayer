package io.github.greytaiwolf.botplayer.gametest;

import io.github.greytaiwolf.botplayer.BotPlayer;
import io.github.greytaiwolf.botplayer.action.ActionFailureCode;
import io.github.greytaiwolf.botplayer.action.ActionOutcome;
import io.github.greytaiwolf.botplayer.action.ActionState;
import io.github.greytaiwolf.botplayer.action.WorldInteractionAction;
import io.github.greytaiwolf.botplayer.action.interaction.BlockHitTarget;
import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.WorldInteractionActionSpec;
import io.github.greytaiwolf.botplayer.action.interaction.menu.P5ARecipe;
import io.github.greytaiwolf.botplayer.action.minecraft.MinecraftActionSnapshot;
import io.github.greytaiwolf.botplayer.gametest.P2GameTestSupport.TestBot;
import io.github.greytaiwolf.botplayer.skill.menu.MenuFamily;
import io.github.greytaiwolf.botplayer.skill.menu.MenuTransactionLimits;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * P5A 白名单配方的实机原版菜单验收。
 *
 * <p>此处刻意不使用 recipe packet 或直接改 Inventory：四批 2×2 crafting 从 native
 * InventoryMenu 开始，逐 Tick {@code clicked()} 完成，最终必须关闭回 native menu 且只有
 * 已审核的账本 delta 可见。
 */
@GameTestHolder(BotPlayer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class P5RecipeMenuGameTests {
    private static final String BATCH = "p5_recipe_menu";
    private static final int CRAFTING_TIMEOUT_TICKS = 220;
    private static final int FURNACE_TIMEOUT_TICKS = 920;

    private P5RecipeMenuGameTests() {
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = CRAFTING_TIMEOUT_TICKS)
    public static void fourBatchInventoryCraftingUsesRealMenuClicksAndCloses(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper,
                        "recipe_inventory_four_batch");
        TestBot bot = fixture.spawn("craft");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            bot.player().getInventory().clearContent();
            bot.player().getInventory().selected = 0;
            // InventoryMenu slot 9 is player inventory index 9, which keeps the selected hand empty.
            bot.player().getInventory().setItem(9,
                    new ItemStack(Items.OAK_LOG, 4));
            bot.player().inventoryMenu.setCarried(ItemStack.EMPTY);
            bot.player().inventoryMenu.broadcastChanges();
            P2GameTestSupport.require(
                    bot.player().containerMenu == bot.player().inventoryMenu,
                    "Recipe fixture did not start in native InventoryMenu");

            CompletionStage<ActionOutcome> completion =
                    P2GameTestSupport.submit(
                            bot,
                            new WorldInteractionAction(
                                    new WorldInteractionActionSpec
                                            .WorldMenuRecipe(
                                            WorldInteractionActionSpec.Hand
                                                    .MAIN_HAND,
                                            Optional.empty(),
                                            ItemStackFingerprint.empty(),
                                            P5ARecipe.OAK_LOG_TO_PLANKS,
                                            4,
                                            MenuTransactionLimits.defaults())),
                            120);
            P2GameTestSupport.awaitOutcome(
                    helper,
                    completion,
                    180,
                    cleanup,
                    outcome -> {
                        try {
                            P2GameTestSupport.require(
                                    outcome.state() == ActionState.SUCCEEDED
                                            && outcome.failureCode()
                                                    == ActionFailureCode.NONE,
                                    "Four-batch recipe action did not succeed: "
                                            + outcome);
                            P2GameTestSupport.require(
                                    bot.player().containerMenu
                                            == bot.player().inventoryMenu
                                            && bot.player().inventoryMenu
                                                    .getCarried().isEmpty(),
                                    "Recipe action did not close to native menu with empty cursor");
                            P2GameTestSupport.require(
                                    inventoryCount(bot, Items.OAK_LOG) == 0
                                            && inventoryCount(bot,
                                                    Items.OAK_PLANKS) == 16,
                                    "Recipe action did not produce exactly sixteen planks from four logs");
                            for (int slot = 1; slot <= 4; slot++) {
                                P2GameTestSupport.require(
                                        bot.player().inventoryMenu
                                                .getSlot(slot).getItem()
                                                .isEmpty(),
                                        "Recipe action left an inventory crafting input behind");
                            }
                        } finally {
                            cleanup.run();
                        }
                        helper.succeed();
                    });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    /**
     * 工作台配方必须真的打开原版 {@code CraftingMenu}，而不是在 2×2 背包格或直接改库存
     * 中伪造 3×3 的木镐产物。
     */
    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = CRAFTING_TIMEOUT_TICKS)
    public static void craftingTableRecipeUsesNativeThreeByThreeMenuAndCloses(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        BlockPos relativeTable = new BlockPos(4, 1, 5);
        BlockPos table = helper.absolutePos(relativeTable);
        helper.setBlock(relativeTable, Blocks.CRAFTING_TABLE);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper,
                        "recipe_crafting_table_native");
        TestBot bot = fixture.spawn("craft");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            bot.player().getInventory().clearContent();
            bot.player().getInventory().selected = 0;
            bot.player().getInventory().setItem(9,
                    new ItemStack(Items.OAK_PLANKS, 3));
            bot.player().getInventory().setItem(10,
                    new ItemStack(Items.STICK, 2));
            bot.player().inventoryMenu.setCarried(ItemStack.EMPTY);
            bot.player().inventoryMenu.broadcastChanges();
            P2GameTestSupport.require(
                    bot.player().containerMenu == bot.player().inventoryMenu,
                    "Crafting-table fixture did not start in native InventoryMenu");

            CompletionStage<ActionOutcome> completion =
                    P2GameTestSupport.submit(
                            bot,
                            new WorldInteractionAction(
                                    new WorldInteractionActionSpec
                                            .WorldMenuRecipe(
                                            WorldInteractionActionSpec.Hand
                                                    .MAIN_HAND,
                                            Optional.of(blockHit(bot, table)),
                                            ItemStackFingerprint.empty(),
                                            P5ARecipe.WOODEN_PICKAXE,
                                            1,
                                            MenuTransactionLimits.defaults())),
                            120);
            P2GameTestSupport.awaitOutcome(
                    helper,
                    completion,
                    180,
                    cleanup,
                    outcome -> {
                        try {
                            P2GameTestSupport.require(
                                    outcome.state() == ActionState.SUCCEEDED
                                            && outcome.failureCode()
                                                    == ActionFailureCode.NONE,
                                    "Crafting-table recipe action did not succeed: "
                                            + outcome);
                            P2GameTestSupport.require(
                                    hasEvidence(outcome, "menu.family",
                                            MenuFamily.CRAFTING_3X3
                                                    .stableId())
                                            && hasEvidence(outcome,
                                                    "menu.closed", "true")
                                            && hasEvidence(outcome,
                                                    "menu.clicks", "10"),
                                    "Crafting-table recipe did not verify a closed native 3x3 menu: "
                                            + outcome.evidence());
                            P2GameTestSupport.require(
                                    bot.player().containerMenu
                                            == bot.player().inventoryMenu
                                            && bot.player().inventoryMenu
                                                    .getCarried().isEmpty(),
                                    "Crafting-table recipe did not close to native menu with empty cursor");
                            P2GameTestSupport.require(
                                    inventoryCount(bot, Items.OAK_PLANKS) == 0
                                            && inventoryCount(bot, Items.STICK)
                                                    == 0
                                            && inventoryCount(bot,
                                                    Items.WOODEN_PICKAXE) == 1,
                                    "Crafting-table recipe did not consume the exact inputs and produce one wooden pickaxe");
                            P2GameTestSupport.require(
                                    bot.player().serverLevel().getBlockState(
                                                    table)
                                            .is(Blocks.CRAFTING_TABLE),
                                    "Crafting-table recipe changed its opened vanilla block");
                        } finally {
                            cleanup.run();
                        }
                        helper.succeed();
                    });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    /**
     * 三块 raw iron 的原版熔炼至少需要 600 游戏 Tick；该场景证明 action 会关闭投入菜单、
     * 多次重新右键观察同一炉子，再经第二份 {@code clicked()} 计划领取，而不是直接改炉子
     * 或库存。
     */
    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = FURNACE_TIMEOUT_TICKS)
    public static void furnaceRecipeDepositsPollsAndCollectsThroughNativeMenus(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        BlockPos relativeFurnace = new BlockPos(4, 1, 5);
        BlockPos furnace = helper.absolutePos(relativeFurnace);
        helper.setBlock(relativeFurnace, Blocks.FURNACE);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper,
                        "recipe_furnace_native");
        TestBot bot = fixture.spawn("smelt");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            bot.player().getInventory().clearContent();
            bot.player().getInventory().selected = 0;
            bot.player().getInventory().setItem(9,
                    new ItemStack(Items.RAW_IRON, 3));
            bot.player().getInventory().setItem(10,
                    new ItemStack(Items.COAL));
            bot.player().inventoryMenu.setCarried(ItemStack.EMPTY);
            bot.player().inventoryMenu.broadcastChanges();
            WorldInteractionActionSpec.WorldMenuRecipe recipe =
                    new WorldInteractionActionSpec.WorldMenuRecipe(
                            WorldInteractionActionSpec.Hand.MAIN_HAND,
                            Optional.of(MinecraftActionSnapshot.blockHit(
                                    bot.player(),
                                    new BlockHitResult(
                                            new Vec3(
                                                    furnace.getX() + 0.5D,
                                                    furnace.getY() + 0.5D,
                                                    furnace.getZ()),
                                            Direction.NORTH,
                                            furnace,
                                            false))),
                            ItemStackFingerprint.empty(),
                            P5ARecipe.RAW_IRON_TO_IRON_INGOTS,
                            1,
                            new MenuTransactionLimits(16, 800));

            CompletionStage<ActionOutcome> completion =
                    P2GameTestSupport.submit(
                            bot, new WorldInteractionAction(recipe), 860);
            P2GameTestSupport.awaitOutcome(
                    helper,
                    completion,
                    880,
                    cleanup,
                    outcome -> {
                        try {
                            P2GameTestSupport.require(
                                    outcome.state() == ActionState.SUCCEEDED
                                            && outcome.failureCode()
                                                    == ActionFailureCode.NONE,
                                    "Furnace recipe action did not succeed: "
                                            + outcome);
                            P2GameTestSupport.require(
                                    bot.player().containerMenu
                                            == bot.player().inventoryMenu
                                            && bot.player().inventoryMenu
                                                    .getCarried().isEmpty(),
                                    "Furnace action did not close to native menu with empty cursor");
                            P2GameTestSupport.require(
                                    inventoryCount(bot, Items.RAW_IRON) == 0
                                            && inventoryCount(bot, Items.COAL)
                                                    == 0
                                            && inventoryCount(bot,
                                                    Items.IRON_INGOT) == 3,
                                    "Furnace action did not produce exactly three iron ingots");
                            P2GameTestSupport.require(
                                    bot.player().serverLevel().getBlockState(
                                                    furnace)
                                            .is(Blocks.FURNACE),
                                    "Furnace recipe changed its opened vanilla block");
                        } finally {
                            cleanup.run();
                        }
                        helper.succeed();
                    });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    /**
     * 单箱 transfer 只能在真实 3×9 原版菜单打开后绑定 source/target。这里验证完整堆叠
     * 会经 {@code clicked()} 转到玩家主背包，并以空 cursor 关闭。
     */
    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = CRAFTING_TIMEOUT_TICKS)
    public static void singleChestTransferUsesNativeThreeByNineMenuAndCloses(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        BlockPos relativeChest = new BlockPos(4, 1, 5);
        BlockPos chest = helper.absolutePos(relativeChest);
        helper.setBlock(relativeChest, Blocks.CHEST);
        ChestBlockEntity chestEntity = chestEntity(helper, chest);
        chestEntity.setItem(0, new ItemStack(Items.OAK_LOG, 16));
        chestEntity.setChanged();
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper,
                        "single_chest_transfer_native");
        TestBot bot = fixture.spawn("chest");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            bot.player().getInventory().clearContent();
            bot.player().getInventory().selected = 0;
            bot.player().inventoryMenu.setCarried(ItemStack.EMPTY);
            bot.player().inventoryMenu.broadcastChanges();

            CompletionStage<ActionOutcome> completion =
                    P2GameTestSupport.submit(
                            bot,
                            new WorldInteractionAction(
                                    new WorldInteractionActionSpec
                                            .WorldMenuTransfer(
                                            WorldInteractionActionSpec.Hand
                                                    .MAIN_HAND,
                                            blockHit(bot, chest),
                                            ItemStackFingerprint.empty(),
                                            MenuFamily.CHEST_3X9,
                                            0,
                                            27,
                                            MenuTransactionLimits.defaults())),
                            80);
            P2GameTestSupport.awaitOutcome(
                    helper,
                    completion,
                    140,
                    cleanup,
                    outcome -> {
                        try {
                            P2GameTestSupport.require(
                                    outcome.state() == ActionState.SUCCEEDED
                                            && outcome.failureCode()
                                                    == ActionFailureCode.NONE,
                                    "Single-chest transfer action did not succeed: "
                                            + outcome);
                            P2GameTestSupport.require(
                                    hasEvidence(outcome, "menu.family",
                                            MenuFamily.CHEST_3X9.stableId())
                                            && hasEvidence(outcome,
                                                    "menu.closed", "true")
                                            && hasEvidence(outcome,
                                                    "menu.clicks", "2"),
                                    "Single-chest transfer did not verify a closed native 3x9 menu: "
                                            + outcome.evidence());
                            P2GameTestSupport.require(
                                    chestEntity.getItem(0).isEmpty()
                                            && inventoryCount(bot,
                                                    Items.OAK_LOG) == 16,
                                    "Single-chest transfer did not move the exact source stack to player inventory");
                            P2GameTestSupport.require(
                                    bot.player().containerMenu
                                            == bot.player().inventoryMenu
                                            && bot.player().inventoryMenu
                                                    .getCarried().isEmpty(),
                                    "Single-chest transfer did not close to native menu with empty cursor");
                        } finally {
                            cleanup.run();
                        }
                        helper.succeed();
                    });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    /**
     * 空 source 不能被 adapter 猜测成其它箱格、快捷移动或直接写库存。即使右键已经打开
     * 原版箱子，失败路径也必须恢复 native InventoryMenu。
     */
    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = CRAFTING_TIMEOUT_TICKS)
    public static void singleChestTransferRejectsEmptySourceAndClosesMenu(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        BlockPos relativeChest = new BlockPos(4, 1, 5);
        BlockPos chest = helper.absolutePos(relativeChest);
        helper.setBlock(relativeChest, Blocks.CHEST);
        ChestBlockEntity chestEntity = chestEntity(helper, chest);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper,
                        "single_chest_transfer_empty_source");
        TestBot bot = fixture.spawn("chest");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            bot.player().getInventory().clearContent();
            bot.player().getInventory().selected = 0;
            bot.player().inventoryMenu.setCarried(ItemStack.EMPTY);
            bot.player().inventoryMenu.broadcastChanges();

            CompletionStage<ActionOutcome> completion =
                    P2GameTestSupport.submit(
                            bot,
                            new WorldInteractionAction(
                                    new WorldInteractionActionSpec
                                            .WorldMenuTransfer(
                                            WorldInteractionActionSpec.Hand
                                                    .MAIN_HAND,
                                            blockHit(bot, chest),
                                            ItemStackFingerprint.empty(),
                                            MenuFamily.CHEST_3X9,
                                            0,
                                            27,
                                            MenuTransactionLimits.defaults())),
                            80);
            P2GameTestSupport.awaitOutcome(
                    helper,
                    completion,
                    80,
                    cleanup,
                    outcome -> {
                        try {
                            P2GameTestSupport.require(
                                    outcome.state() == ActionState.FAILED
                                            && outcome.failureCode()
                                                    == ActionFailureCode
                                                            .PRECONDITION_FAILED,
                                    "Empty single-chest source was not rejected as a precondition failure: "
                                            + outcome);
                            P2GameTestSupport.require(
                                    chestEntity.getItem(0).isEmpty()
                                            && inventoryCount(bot,
                                                    Items.OAK_LOG) == 0,
                                    "Rejected empty-source transfer changed a chest or player ledger");
                            P2GameTestSupport.require(
                                    bot.player().containerMenu
                                            == bot.player().inventoryMenu
                                            && bot.player().inventoryMenu
                                                    .getCarried().isEmpty(),
                                    "Rejected empty-source transfer leaked an open menu or cursor");
                        } finally {
                            cleanup.run();
                        }
                        helper.succeed();
                    });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    private static ChestBlockEntity chestEntity(
            GameTestHelper helper, BlockPos chest) {
        if (helper.getLevel().getBlockEntity(chest)
                instanceof ChestBlockEntity chestEntity) {
            return chestEntity;
        }
        throw new IllegalStateException(
                "Single-chest GameTest fixture did not create a chest block entity");
    }

    private static BlockHitTarget blockHit(TestBot bot, BlockPos target) {
        return MinecraftActionSnapshot.blockHit(
                bot.player(),
                new BlockHitResult(
                        new Vec3(target.getX() + 0.5D,
                                target.getY() + 0.5D,
                                target.getZ()),
                        Direction.NORTH,
                        target,
                        false));
    }

    private static boolean hasEvidence(
            ActionOutcome outcome, String key, String value) {
        return outcome.evidence().stream().anyMatch(evidence ->
                evidence.key().equals(key) && evidence.value().equals(value));
    }

    private static int inventoryCount(TestBot bot,
            net.minecraft.world.item.Item item) {
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
}
