package io.github.greytaiwolf.botplayer.gametest;

import io.github.greytaiwolf.botplayer.BotPlayer;
import io.github.greytaiwolf.botplayer.action.ActionCancellationReason;
import io.github.greytaiwolf.botplayer.action.ActionEnvelope;
import io.github.greytaiwolf.botplayer.action.ActionFailureCode;
import io.github.greytaiwolf.botplayer.action.ActionMailbox;
import io.github.greytaiwolf.botplayer.action.ActionOrigin;
import io.github.greytaiwolf.botplayer.action.ActionOutcome;
import io.github.greytaiwolf.botplayer.action.ActionPriority;
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
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.SmokerMenu;
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
    private static final String SECONDARY_BATCH =
            "p5_recipe_menu_secondary";
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
                                                    "menu.clicks", "9"),
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
     * P5B 后续工作站会复用同一 transaction cleanup：取消发生在真实原版 cursor 非空时，
     * 不能留下半开的 CraftingMenu、carried stack 或丢失输入。这个场景刻意不等待配方完成，
     * 只接受原版 close 归还后的精确物品守恒。
     */
    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = CRAFTING_TIMEOUT_TICKS)
    public static void cancellingCraftingTableRecipeReturnsCursorAndConservesInputs(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        BlockPos relativeTable = new BlockPos(4, 1, 5);
        BlockPos table = helper.absolutePos(relativeTable);
        helper.setBlock(relativeTable, Blocks.CRAFTING_TABLE);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper,
                        "recipe_crafting_table_cancel_cursor");
        TestBot bot = fixture.spawn("cncl");
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

            TrackedRecipeAction recipe = submitTracked(
                    bot,
                    new WorldInteractionAction(
                            new WorldInteractionActionSpec.WorldMenuRecipe(
                                    WorldInteractionActionSpec.Hand.MAIN_HAND,
                                    Optional.of(blockHit(bot, table)),
                                    ItemStackFingerprint.empty(),
                                    P5ARecipe.WOODEN_PICKAXE,
                                    1,
                                    MenuTransactionLimits.defaults())),
                    120,
                    "crafting-table-cancel");
            P2GameTestSupport.awaitCondition(
                    helper,
                    80,
                    () -> bot.player().containerMenu instanceof CraftingMenu
                            && !bot.player().containerMenu.getCarried()
                                    .isEmpty(),
                    "Crafting-table recipe never reached a real non-empty cursor",
                    cleanup,
                    () -> requestCraftingCancellation(
                            helper, bot, table, recipe, cleanup));
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    private static void requestCraftingCancellation(
            GameTestHelper helper,
            TestBot bot,
            BlockPos table,
            TrackedRecipeAction recipe,
            P2GameTestSupport.Cleanup cleanup) {
        try {
            ActionMailbox.Cancellation cancellation = bot.manager()
                    .cancelAction(
                            bot.player().getUUID(),
                            recipe.actionId(),
                            ActionCancellationReason.REQUESTED);
            P2GameTestSupport.require(
                    cancellation.status()
                            == ActionMailbox.CancellationStatus.ENQUEUED,
                    "Crafting-table cancellation was not enqueued: "
                            + cancellation.status());
            P2GameTestSupport.awaitOutcome(
                    helper,
                    recipe.completion(),
                    80,
                    cleanup,
                    outcome -> {
                        try {
                            P2GameTestSupport.require(
                                    outcome.state() == ActionState.CANCELLED
                                            && outcome.failureCode()
                                                    == ActionFailureCode.CANCELLED,
                                    "Crafting-table action did not report cancellation: "
                                            + outcome);
                            P2GameTestSupport.require(
                                    bot.player().containerMenu
                                                    == bot.player()
                                                            .inventoryMenu
                                            && bot.player().inventoryMenu
                                                    .getCarried().isEmpty(),
                                    "Cancelled crafting-table recipe leaked a menu or cursor");
                            P2GameTestSupport.require(
                                    inventoryCount(bot, Items.OAK_PLANKS)
                                                    == 3
                                            && inventoryCount(bot, Items.STICK)
                                                    == 2
                                            && inventoryCount(bot,
                                                    Items.WOODEN_PICKAXE)
                                                    == 0,
                                    "Cancelled crafting-table recipe did not conserve its inputs");
                            P2GameTestSupport.require(
                                    bot.player().serverLevel().getBlockState(
                                                    table)
                                            .is(Blocks.CRAFTING_TABLE),
                                    "Cancelled crafting-table recipe changed its frozen workstation");
                        } finally {
                            cleanup.run();
                        }
                        helper.succeed();
                    });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            helper.fail(exception.getMessage() == null
                    ? exception.toString()
                    : exception.getMessage());
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
     * 高炉虽然复用 39 槽布局，仍必须走精确 {@code BlastFurnaceMenu}、blasting 配方查询和
     * 分离的投入/轮询/领取事务；不能借用普通熔炉的 menu identity。
     */
    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = 620)
    public static void blastFurnaceRecipeUsesExactMenuAndBlastingContract(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        BlockPos relativeFurnace = new BlockPos(4, 1, 5);
        BlockPos furnace = helper.absolutePos(relativeFurnace);
        helper.setBlock(relativeFurnace, Blocks.BLAST_FURNACE);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper,
                        "recipe_blast_furnace_native");
        TestBot bot = fixture.spawn("blast");
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

            CompletionStage<ActionOutcome> completion =
                    P2GameTestSupport.submit(
                            bot,
                            new WorldInteractionAction(furnaceRecipe(
                                    bot,
                                    furnace,
                                    P5ARecipe
                                            .RAW_IRON_TO_IRON_INGOTS_BLASTING,
                                    new MenuTransactionLimits(16, 500))),
                            560);
            P2GameTestSupport.awaitOutcome(
                    helper,
                    completion,
                    580,
                    cleanup,
                    outcome -> {
                        try {
                            P2GameTestSupport.require(
                                    outcome.state() == ActionState.SUCCEEDED
                                            && outcome.failureCode()
                                                    == ActionFailureCode.NONE,
                                    "Blast-furnace action did not succeed: "
                                            + outcome);
                            P2GameTestSupport.require(
                                    bot.player().containerMenu
                                                    == bot.player().inventoryMenu
                                            && bot.player().inventoryMenu
                                                    .getCarried().isEmpty(),
                                    "Blast-furnace action leaked a menu or cursor");
                            P2GameTestSupport.require(
                                    inventoryCount(bot, Items.RAW_IRON) == 0
                                            && inventoryCount(bot, Items.COAL)
                                                    == 0
                                            && inventoryCount(bot,
                                                    Items.IRON_INGOT) == 3,
                                    "Blast-furnace action did not verify its exact input/fuel/output delta");
                            P2GameTestSupport.require(
                                    hasEvidence(outcome, "menu.family",
                                            MenuFamily.FURNACE.stableId())
                                            && hasEvidence(outcome,
                                                    "menu.closed", "true")
                                            && hasEvidence(outcome,
                                                    "furnace.kind",
                                                    "blast_furnace")
                                            && hasEvidence(outcome,
                                                    "furnace.recipe_type",
                                                    "minecraft:blasting")
                                            && bot.player().serverLevel()
                                                    .getBlockState(furnace)
                                                    .is(Blocks.BLAST_FURNACE),
                                    "Blast-furnace action did not retain its frozen vanilla workstation");
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
     * 烟熏炉同样需要 smoking 合同，且其较短的最小时限不能误放宽普通熔炉的 700 Tick 下限。
     */
    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = 340)
    public static void smokerRecipeUsesExactMenuAndSmokingContract(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        BlockPos relativeSmoker = new BlockPos(4, 1, 5);
        BlockPos smoker = helper.absolutePos(relativeSmoker);
        helper.setBlock(relativeSmoker, Blocks.SMOKER);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper,
                        "recipe_smoker_native");
        TestBot bot = fixture.spawn("smokr");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            bot.player().getInventory().clearContent();
            bot.player().getInventory().selected = 0;
            bot.player().getInventory().setItem(9,
                    new ItemStack(Items.CHICKEN));
            bot.player().getInventory().setItem(10,
                    new ItemStack(Items.COAL));
            bot.player().inventoryMenu.setCarried(ItemStack.EMPTY);
            bot.player().inventoryMenu.broadcastChanges();

            CompletionStage<ActionOutcome> completion =
                    P2GameTestSupport.submit(
                            bot,
                            new WorldInteractionAction(furnaceRecipe(
                                    bot,
                                    smoker,
                                    P5ARecipe
                                            .RAW_CHICKEN_TO_COOKED_CHICKEN_SMOKING,
                                    new MenuTransactionLimits(16, 240))),
                            280);
            P2GameTestSupport.awaitOutcome(
                    helper,
                    completion,
                    300,
                    cleanup,
                    outcome -> {
                        try {
                            P2GameTestSupport.require(
                                    outcome.state() == ActionState.SUCCEEDED
                                            && outcome.failureCode()
                                                    == ActionFailureCode.NONE,
                                    "Smoker action did not succeed: " + outcome);
                            P2GameTestSupport.require(
                                    bot.player().containerMenu
                                                    == bot.player().inventoryMenu
                                            && bot.player().inventoryMenu
                                                    .getCarried().isEmpty()
                                            && inventoryCount(bot,
                                                    Items.CHICKEN) == 0
                                            && inventoryCount(bot, Items.COAL)
                                                    == 0
                                            && inventoryCount(bot,
                                                    Items.COOKED_CHICKEN) == 1,
                                    "Smoker action did not preserve its exact item and cursor contract");
                            P2GameTestSupport.require(
                                    hasEvidence(outcome, "furnace.kind",
                                            "smoker")
                                            && hasEvidence(outcome,
                                                    "furnace.recipe_type",
                                                    "minecraft:smoking")
                                            && bot.player().serverLevel()
                                                    .getBlockState(smoker)
                                                    .is(Blocks.SMOKER),
                                    "Smoker action changed its frozen workstation");
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
     * 高炉合同不能把同样 39 槽的普通熔炉当作兼容目标。拒绝必须发生在任何点击前，且不留下
     * 打开的 menu、cursor 或背包账本变化。
     */
    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = CRAFTING_TIMEOUT_TICKS)
    public static void blastFurnaceRecipeRejectsNormalFurnaceTargetAndCloses(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        BlockPos relativeFurnace = new BlockPos(4, 1, 5);
        BlockPos furnace = helper.absolutePos(relativeFurnace);
        helper.setBlock(relativeFurnace, Blocks.FURNACE);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper,
                        "recipe_blast_target_mismatch");
        TestBot bot = fixture.spawn("mmism");
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

            CompletionStage<ActionOutcome> completion =
                    P2GameTestSupport.submit(
                            bot,
                            new WorldInteractionAction(furnaceRecipe(
                                    bot,
                                    furnace,
                                    P5ARecipe
                                            .RAW_IRON_TO_IRON_INGOTS_BLASTING,
                                    new MenuTransactionLimits(16, 400))),
                            440);
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
                                    "Mismatched furnace target was not rejected: "
                                            + outcome);
                            P2GameTestSupport.require(
                                    bot.player().containerMenu
                                                    == bot.player().inventoryMenu
                                            && bot.player().inventoryMenu
                                                    .getCarried().isEmpty()
                                            && inventoryCount(bot,
                                                    Items.RAW_IRON) == 3
                                            && inventoryCount(bot, Items.COAL)
                                                    == 1
                                            && inventoryCount(bot,
                                                    Items.IRON_INGOT) == 0
                                            && bot.player().serverLevel()
                                                    .getBlockState(furnace)
                                                    .is(Blocks.FURNACE),
                                    "Rejected mismatch changed the player ledger, menu or target block");
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
     * 取消发生在烟熏炉真实 cursor 非空时仍必须关闭窗口并归还 cursor；这确保严格炉型检查
     * 不会牺牲原有的 cleanup/fuel 输入守恒边界。
     */
    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = CRAFTING_TIMEOUT_TICKS)
    public static void cancellingSmokerRecipeReturnsCursorAndConservesInputs(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        BlockPos relativeSmoker = new BlockPos(4, 1, 5);
        BlockPos smoker = helper.absolutePos(relativeSmoker);
        helper.setBlock(relativeSmoker, Blocks.SMOKER);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper,
                        "recipe_smoker_cancel_cursor");
        TestBot bot = fixture.spawn("smcan");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            bot.player().getInventory().clearContent();
            bot.player().getInventory().selected = 0;
            bot.player().getInventory().setItem(9,
                    new ItemStack(Items.CHICKEN));
            bot.player().getInventory().setItem(10,
                    new ItemStack(Items.COAL));
            bot.player().inventoryMenu.setCarried(ItemStack.EMPTY);
            bot.player().inventoryMenu.broadcastChanges();

            TrackedRecipeAction recipe = submitTracked(
                    bot,
                    new WorldInteractionAction(furnaceRecipe(
                            bot,
                            smoker,
                            P5ARecipe
                                    .RAW_CHICKEN_TO_COOKED_CHICKEN_SMOKING,
                            new MenuTransactionLimits(16, 240))),
                    280,
                    "smoker-cancel");
            P2GameTestSupport.awaitCondition(
                    helper,
                    80,
                    () -> bot.player().containerMenu instanceof SmokerMenu
                            && !bot.player().containerMenu.getCarried()
                                    .isEmpty(),
                    "Smoker recipe never reached a real non-empty cursor",
                    cleanup,
                    () -> requestSmokerCancellation(
                            helper, bot, smoker, recipe, cleanup));
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    /**
     * 窗口已关闭、炉子正在烧制时，外部把高炉替换成普通熔炉必须在下一次轮询前失败关闭，
     * 不能继续把旧 session 的 collection 点击发给新菜单。
     */
    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = SECONDARY_BATCH,
            timeoutTicks = CRAFTING_TIMEOUT_TICKS)
    public static void blastFurnaceReplacementDuringWaitFailsClosed(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        BlockPos relativeFurnace = new BlockPos(4, 1, 5);
        BlockPos furnace = helper.absolutePos(relativeFurnace);
        helper.setBlock(relativeFurnace, Blocks.BLAST_FURNACE);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper,
                        "recipe_blast_replacement_wait");
        TestBot bot = fixture.spawn("blchg");
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

            CompletionStage<ActionOutcome> completion =
                    P2GameTestSupport.submit(
                            bot,
                            new WorldInteractionAction(furnaceRecipe(
                                    bot,
                                    furnace,
                                    P5ARecipe
                                            .RAW_IRON_TO_IRON_INGOTS_BLASTING,
                                    new MenuTransactionLimits(16, 500))),
                            540);
            P2GameTestSupport.awaitCondition(
                    helper,
                    100,
                    () -> bot.player().containerMenu == bot.player()
                            .inventoryMenu
                            && inventoryCount(bot, Items.RAW_IRON) < 3,
                    "Blast-furnace recipe never completed its closed deposit phase",
                    cleanup,
                    () -> replaceBlastFurnaceDuringWait(
                            helper,
                            relativeFurnace,
                            bot,
                            completion,
                            cleanup));
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
            batch = SECONDARY_BATCH,
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
     * 指定数量取出必须逐次走原版右键，而不是把完整堆叠移动后由服务端直接改回余量。
     */
    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = SECONDARY_BATCH,
            timeoutTicks = CRAFTING_TIMEOUT_TICKS)
    public static void singleChestTransferWithdrawsExactPartialAmount(
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
                        "single_chest_transfer_partial_withdraw");
        TestBot bot = fixture.spawn("pwith");
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
                                            5,
                                            new MenuTransactionLimits(7, 200))),
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
                                    "Partial chest withdrawal did not succeed: "
                                            + outcome);
                            P2GameTestSupport.require(
                                    hasEvidence(outcome, "menu.family",
                                            MenuFamily.CHEST_3X9.stableId())
                                            && hasEvidence(outcome,
                                                    "menu.closed", "true")
                                            && hasEvidence(outcome,
                                                    "menu.clicks", "7"),
                                    "Partial chest withdrawal did not execute its seven strict clicks: "
                                            + outcome.evidence());
                            P2GameTestSupport.require(
                                    chestEntity.getItem(0).getCount() == 11
                                            && inventoryCount(bot,
                                                    Items.OAK_LOG) == 5,
                                    "Partial chest withdrawal did not conserve 16 logs as 11 chest + 5 inventory");
                            P2GameTestSupport.require(
                                    bot.player().containerMenu
                                            == bot.player().inventoryMenu
                                            && bot.player().inventoryMenu
                                                    .getCarried().isEmpty(),
                                    "Partial chest withdrawal leaked an open menu or cursor");
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
     * 指定数量存入使用同一份逐右键模板，并验证箱子与 Bot 背包两侧的守恒。
     */
    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = SECONDARY_BATCH,
            timeoutTicks = CRAFTING_TIMEOUT_TICKS)
    public static void singleChestTransferDepositsExactPartialAmount(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        BlockPos relativeChest = new BlockPos(4, 1, 5);
        BlockPos chest = helper.absolutePos(relativeChest);
        helper.setBlock(relativeChest, Blocks.CHEST);
        ChestBlockEntity chestEntity = chestEntity(helper, chest);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper,
                        "single_chest_transfer_partial_deposit");
        TestBot bot = fixture.spawn("pdepo");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            bot.player().getInventory().clearContent();
            bot.player().getInventory().setItem(9,
                    new ItemStack(Items.OAK_LOG, 16));
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
                                            27,
                                            0,
                                            5,
                                            new MenuTransactionLimits(7, 200))),
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
                                    "Partial chest deposit did not succeed: "
                                            + outcome);
                            P2GameTestSupport.require(
                                    hasEvidence(outcome, "menu.family",
                                            MenuFamily.CHEST_3X9.stableId())
                                            && hasEvidence(outcome,
                                                    "menu.closed", "true")
                                            && hasEvidence(outcome,
                                                    "menu.clicks", "7"),
                                    "Partial chest deposit did not execute its seven strict clicks: "
                                            + outcome.evidence());
                            P2GameTestSupport.require(
                                    chestEntity.getItem(0).getCount() == 5
                                            && inventoryCount(bot,
                                                    Items.OAK_LOG) == 11,
                                    "Partial chest deposit did not conserve 16 logs as 5 chest + 11 inventory");
                            P2GameTestSupport.require(
                                    bot.player().containerMenu
                                            == bot.player().inventoryMenu
                                            && bot.player().inventoryMenu
                                                    .getCarried().isEmpty(),
                                    "Partial chest deposit leaked an open menu or cursor");
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
            batch = SECONDARY_BATCH,
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

    private static WorldInteractionActionSpec.WorldMenuRecipe furnaceRecipe(
            TestBot bot,
            BlockPos furnace,
            P5ARecipe recipe,
            MenuTransactionLimits limits) {
        return new WorldInteractionActionSpec.WorldMenuRecipe(
                WorldInteractionActionSpec.Hand.MAIN_HAND,
                Optional.of(blockHit(bot, furnace)),
                ItemStackFingerprint.empty(),
                recipe,
                1,
                limits);
    }

    private static void requestSmokerCancellation(
            GameTestHelper helper,
            TestBot bot,
            BlockPos smoker,
            TrackedRecipeAction recipe,
            P2GameTestSupport.Cleanup cleanup) {
        try {
            ActionMailbox.Cancellation cancellation = bot.manager()
                    .cancelAction(
                            bot.player().getUUID(),
                            recipe.actionId(),
                            ActionCancellationReason.REQUESTED);
            P2GameTestSupport.require(
                    cancellation.status()
                            == ActionMailbox.CancellationStatus.ENQUEUED,
                    "Smoker cancellation was not enqueued: "
                            + cancellation.status());
            P2GameTestSupport.awaitOutcome(
                    helper,
                    recipe.completion(),
                    80,
                    cleanup,
                    outcome -> {
                        try {
                            P2GameTestSupport.require(
                                    outcome.state() == ActionState.CANCELLED
                                            && outcome.failureCode()
                                                    == ActionFailureCode.CANCELLED,
                                    "Smoker action did not report cancellation: "
                                            + outcome);
                            P2GameTestSupport.require(
                                    bot.player().containerMenu
                                                    == bot.player().inventoryMenu
                                            && bot.player().inventoryMenu
                                                    .getCarried().isEmpty()
                                            && inventoryCount(bot,
                                                    Items.CHICKEN) == 1
                                            && inventoryCount(bot, Items.COAL)
                                                    == 1
                                            && inventoryCount(bot,
                                                    Items.COOKED_CHICKEN) == 0
                                            && bot.player().serverLevel()
                                                    .getBlockState(smoker)
                                                    .is(Blocks.SMOKER),
                                    "Cancelled smoker recipe did not return its cursor or conserve inputs");
                        } finally {
                            cleanup.run();
                        }
                        helper.succeed();
                    });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            helper.fail(exception.getMessage() == null
                    ? exception.toString()
                    : exception.getMessage());
        }
    }

    private static void replaceBlastFurnaceDuringWait(
            GameTestHelper helper,
            BlockPos relativeFurnace,
            TestBot bot,
            CompletionStage<ActionOutcome> completion,
            P2GameTestSupport.Cleanup cleanup) {
        try {
            helper.setBlock(relativeFurnace, Blocks.FURNACE);
            P2GameTestSupport.awaitOutcome(
                    helper,
                    completion,
                    100,
                    cleanup,
                    outcome -> {
                        try {
                            P2GameTestSupport.require(
                                    outcome.state() == ActionState.FAILED
                                            && outcome.failureCode()
                                                    == ActionFailureCode
                                                            .PRECONDITION_FAILED,
                                    "Changed blast-furnace target was not failed closed: "
                                            + outcome);
                            P2GameTestSupport.require(
                                    bot.player().containerMenu
                                                    == bot.player().inventoryMenu
                                            && bot.player().inventoryMenu
                                                    .getCarried().isEmpty()
                                            && bot.player().serverLevel()
                                                    .getBlockState(helper
                                                            .absolutePos(
                                                                    relativeFurnace))
                                                    .is(Blocks.FURNACE),
                                    "Changed blast-furnace target leaked a menu or cursor");
                        } finally {
                            cleanup.run();
                        }
                        helper.succeed();
                    });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            helper.fail(exception.getMessage() == null
                    ? exception.toString()
                    : exception.getMessage());
        }
    }

    private static TrackedRecipeAction submitTracked(
            TestBot bot,
            WorldInteractionAction action,
            int maximumTicks,
            String phase) {
        long currentTick = bot.player().serverLevel().getServer()
                .getTickCount();
        UUID actionId = UUID.randomUUID();
        ActionMailbox.Submission submission = bot.manager().submitAction(
                new ActionEnvelope(
                        actionId,
                        bot.player().getUUID(),
                        bot.player().runtimeHandle().generation(),
                        "gametest/p5/workstation/" + phase + "/" + actionId,
                        currentTick + maximumTicks + 40L,
                        maximumTicks,
                        action,
                        ActionOrigin.none()),
                ActionPriority.OWNER_TASK);
        P2GameTestSupport.require(
                submission.status() == ActionMailbox.SubmissionStatus.ENQUEUED,
                "Tracked recipe action was rejected: " + submission.status());
        return new TrackedRecipeAction(
                actionId, submission.completion().orElseThrow());
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

    private record TrackedRecipeAction(
            UUID actionId, CompletionStage<ActionOutcome> completion) {
    }
}
