package io.github.greytaiwolf.botplayer.gametest;

import io.github.greytaiwolf.botplayer.BotPlayer;
import io.github.greytaiwolf.botplayer.action.ActionFailureCode;
import io.github.greytaiwolf.botplayer.action.ActionOutcome;
import io.github.greytaiwolf.botplayer.action.ActionState;
import io.github.greytaiwolf.botplayer.action.WorldInteractionAction;
import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.WorldInteractionActionSpec;
import io.github.greytaiwolf.botplayer.action.interaction.menu.P5ARecipe;
import io.github.greytaiwolf.botplayer.action.minecraft.MinecraftActionSnapshot;
import io.github.greytaiwolf.botplayer.gametest.P2GameTestSupport.TestBot;
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
        TestBot bot = fixture.spawn("crafter");
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
        TestBot bot = fixture.spawn("smelter");
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
