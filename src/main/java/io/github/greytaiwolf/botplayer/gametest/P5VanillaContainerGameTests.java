package io.github.greytaiwolf.botplayer.gametest;

import io.github.greytaiwolf.botplayer.BotPlayer;
import io.github.greytaiwolf.botplayer.action.ActionFailureCode;
import io.github.greytaiwolf.botplayer.action.ActionOutcome;
import io.github.greytaiwolf.botplayer.action.ActionState;
import io.github.greytaiwolf.botplayer.action.WorldInteractionAction;
import io.github.greytaiwolf.botplayer.action.interaction.BlockHitTarget;
import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.WorldInteractionActionSpec;
import io.github.greytaiwolf.botplayer.action.minecraft.MinecraftActionSnapshot;
import io.github.greytaiwolf.botplayer.gametest.P2GameTestSupport.TestBot;
import io.github.greytaiwolf.botplayer.skill.menu.MenuFamily;
import io.github.greytaiwolf.botplayer.skill.menu.MenuTransactionLimits;
import java.util.concurrent.CompletionStage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * P5B 原版通用容器的真实菜单回归矩阵。
 *
 * <p>所有 case 都只通过 {@link WorldInteractionActionSpec.WorldMenuTransfer} 打开原版菜单，
 * 后续逐 tick 由 adapter 调用原生 {@code clicked()}；fixture 直接写入方块实体仅用于准备
 * 输入账本，绝不用于实现 transfer。每个容器至少覆盖一个方向的全量移动和另一个方向的
 * 指定数量移动，并核对 menu family、严格 click 数、守恒以及关闭后的空 cursor。
 */
@GameTestHolder(BotPlayer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class P5VanillaContainerGameTests {
    private static final String BATCH = "p5_vanilla_containers";
    private static final int TIMEOUT_TICKS = 220;
    private static final BlockPos RELATIVE_CONTAINER = new BlockPos(4, 1, 5);
    private static final int STACK_SIZE = 16;
    private static final int PARTIAL_AMOUNT = 5;

    private P5VanillaContainerGameTests() {
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void doubleChestWithdrawsPartialAmountThroughSixByNineMenu(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        DoubleChestFixture doubleChest = doubleChest(helper, RELATIVE_CONTAINER);
        doubleChest.first().setItem(0, new ItemStack(Items.OAK_LOG, STACK_SIZE));
        doubleChest.second().setItem(0, new ItemStack(Items.OAK_LOG, STACK_SIZE));
        doubleChest.first().setChanged();
        doubleChest.second().setChanged();
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper,
                        "double_chest_partial_withdraw");
        TestBot bot = fixture.spawn("partial_withdraw");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            clearBotInventory(bot);
            submitAndVerify(
                    helper,
                    bot,
                    cleanup,
                    doubleChest.target(),
                    MenuFamily.CHEST_6X9,
                    0,
                    54,
                    PARTIAL_AMOUNT,
                    PARTIAL_AMOUNT + 2,
                    "Double chest partial withdrawal",
                    () -> P2GameTestSupport.require(
                            itemCount(doubleChest.first(), Items.OAK_LOG)
                                    + itemCount(doubleChest.second(), Items.OAK_LOG)
                                    == STACK_SIZE * 2 - PARTIAL_AMOUNT
                                    && itemCount(bot, Items.OAK_LOG)
                                            == PARTIAL_AMOUNT,
                            "Double chest partial withdrawal did not conserve both halves and player inventory"));
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void doubleChestDepositsWholeStackThroughSixByNineMenu(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        DoubleChestFixture doubleChest = doubleChest(helper, RELATIVE_CONTAINER);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper,
                        "double_chest_whole_deposit");
        TestBot bot = fixture.spawn("whole_deposit");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            givePlayerMainInventory(bot, STACK_SIZE);
            submitAndVerify(
                    helper,
                    bot,
                    cleanup,
                    doubleChest.target(),
                    MenuFamily.CHEST_6X9,
                    54,
                    0,
                    0,
                    2,
                    "Double chest whole deposit",
                    () -> P2GameTestSupport.require(
                            itemCount(doubleChest.first(), Items.OAK_LOG)
                                    + itemCount(doubleChest.second(), Items.OAK_LOG)
                                    == STACK_SIZE
                                    && itemCount(bot, Items.OAK_LOG) == 0,
                            "Double chest whole deposit did not conserve the complete stack"));
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void barrelWithdrawsWholeStackThroughThreeByNineMenu(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        BlockPos barrelPos = helper.absolutePos(RELATIVE_CONTAINER);
        helper.setBlock(RELATIVE_CONTAINER, Blocks.BARREL);
        BarrelBlockEntity barrel = barrelEntity(helper, barrelPos);
        barrel.setItem(0, new ItemStack(Items.OAK_LOG, STACK_SIZE));
        barrel.setChanged();
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper,
                        "barrel_whole_withdraw");
        TestBot bot = fixture.spawn("whole_withdraw");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            clearBotInventory(bot);
            submitAndVerify(
                    helper,
                    bot,
                    cleanup,
                    barrelPos,
                    MenuFamily.CHEST_3X9,
                    0,
                    27,
                    0,
                    2,
                    "Barrel whole withdrawal",
                    () -> P2GameTestSupport.require(
                            barrel.getItem(0).isEmpty()
                                    && itemCount(bot, Items.OAK_LOG)
                                            == STACK_SIZE,
                            "Barrel whole withdrawal did not move the exact stack"));
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void barrelDepositsPartialAmountThroughThreeByNineMenu(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        BlockPos barrelPos = helper.absolutePos(RELATIVE_CONTAINER);
        helper.setBlock(RELATIVE_CONTAINER, Blocks.BARREL);
        BarrelBlockEntity barrel = barrelEntity(helper, barrelPos);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper,
                        "barrel_partial_deposit");
        TestBot bot = fixture.spawn("partial_deposit");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            givePlayerMainInventory(bot, STACK_SIZE);
            submitAndVerify(
                    helper,
                    bot,
                    cleanup,
                    barrelPos,
                    MenuFamily.CHEST_3X9,
                    27,
                    0,
                    PARTIAL_AMOUNT,
                    PARTIAL_AMOUNT + 2,
                    "Barrel partial deposit",
                    () -> P2GameTestSupport.require(
                            barrel.getItem(0).getCount() == PARTIAL_AMOUNT
                                    && itemCount(bot, Items.OAK_LOG)
                                            == STACK_SIZE - PARTIAL_AMOUNT,
                            "Barrel partial deposit did not conserve the requested amount"));
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void shulkerWithdrawsPartialAmountThroughThreeByNineMenu(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        BlockPos shulkerPos = helper.absolutePos(RELATIVE_CONTAINER);
        helper.setBlock(RELATIVE_CONTAINER, Blocks.PURPLE_SHULKER_BOX);
        ShulkerBoxBlockEntity shulker = shulkerEntity(helper, shulkerPos);
        shulker.setItem(0, new ItemStack(Items.OAK_LOG, STACK_SIZE));
        shulker.setChanged();
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper,
                        "shulker_partial_withdraw");
        TestBot bot = fixture.spawn("partial_withdraw");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            clearBotInventory(bot);
            submitAndVerify(
                    helper,
                    bot,
                    cleanup,
                    shulkerPos,
                    MenuFamily.CHEST_3X9,
                    0,
                    27,
                    PARTIAL_AMOUNT,
                    PARTIAL_AMOUNT + 2,
                    "Shulker partial withdrawal",
                    () -> P2GameTestSupport.require(
                            shulker.getItem(0).getCount()
                                    == STACK_SIZE - PARTIAL_AMOUNT
                                    && itemCount(bot, Items.OAK_LOG)
                                            == PARTIAL_AMOUNT,
                            "Shulker partial withdrawal did not conserve the requested amount"));
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void shulkerDepositsWholeStackThroughThreeByNineMenu(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        BlockPos shulkerPos = helper.absolutePos(RELATIVE_CONTAINER);
        helper.setBlock(RELATIVE_CONTAINER, Blocks.PURPLE_SHULKER_BOX);
        ShulkerBoxBlockEntity shulker = shulkerEntity(helper, shulkerPos);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper,
                        "shulker_whole_deposit");
        TestBot bot = fixture.spawn("whole_deposit");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            givePlayerMainInventory(bot, STACK_SIZE);
            submitAndVerify(
                    helper,
                    bot,
                    cleanup,
                    shulkerPos,
                    MenuFamily.CHEST_3X9,
                    27,
                    0,
                    0,
                    2,
                    "Shulker whole deposit",
                    () -> P2GameTestSupport.require(
                            shulker.getItem(0).getCount() == STACK_SIZE
                                    && itemCount(bot, Items.OAK_LOG) == 0,
                            "Shulker whole deposit did not move the exact stack"));
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    private static void submitAndVerify(
            GameTestHelper helper,
            TestBot bot,
            P2GameTestSupport.Cleanup cleanup,
            BlockPos target,
            MenuFamily family,
            int sourceSlot,
            int targetSlot,
            int amount,
            int expectedClicks,
            String label,
            Runnable ledgerVerifier) {
        CompletionStage<ActionOutcome> completion = P2GameTestSupport.submit(
                bot,
                new WorldInteractionAction(
                        new WorldInteractionActionSpec.WorldMenuTransfer(
                                WorldInteractionActionSpec.Hand.MAIN_HAND,
                                blockHit(bot, target),
                                ItemStackFingerprint.empty(),
                                family,
                                sourceSlot,
                                targetSlot,
                                amount,
                                new MenuTransactionLimits(expectedClicks, 200))),
                100);
        P2GameTestSupport.awaitOutcome(
                helper,
                completion,
                160,
                cleanup,
                outcome -> {
                    try {
                        P2GameTestSupport.require(
                                outcome.state() == ActionState.SUCCEEDED
                                        && outcome.failureCode()
                                                == ActionFailureCode.NONE,
                                label + " did not succeed: " + outcome);
                        P2GameTestSupport.require(
                                hasEvidence(outcome, "menu.family",
                                        family.stableId())
                                        && hasEvidence(outcome, "menu.closed",
                                                "true")
                                        && hasEvidence(outcome, "menu.clicks",
                                                Integer.toString(expectedClicks)),
                                label + " did not verify its exact native menu transaction: "
                                        + outcome.evidence());
                        P2GameTestSupport.require(
                                bot.player().containerMenu
                                        == bot.player().inventoryMenu
                                        && bot.player().inventoryMenu
                                                .getCarried().isEmpty(),
                                label + " leaked an open menu or cursor");
                        ledgerVerifier.run();
                    } finally {
                        cleanup.run();
                    }
                    helper.succeed();
                });
    }

    private static DoubleChestFixture doubleChest(
            GameTestHelper helper, BlockPos relativeFirst) {
        BlockPos first = helper.absolutePos(relativeFirst);
        BlockPos second = first.east();
        helper.setBlock(relativeFirst, Blocks.CHEST);
        helper.setBlock(relativeFirst.east(), Blocks.CHEST);
        ChestType firstType = helper.getLevel().getBlockState(first)
                .getValue(ChestBlock.TYPE);
        ChestType secondType = helper.getLevel().getBlockState(second)
                .getValue(ChestBlock.TYPE);
        P2GameTestSupport.require(
                firstType != ChestType.SINGLE
                        && secondType != ChestType.SINGLE
                        && firstType != secondType,
                "Double-chest fixture did not form two complementary chest halves");
        return new DoubleChestFixture(
                first, chestEntity(helper, first), chestEntity(helper, second));
    }

    private static void clearBotInventory(TestBot bot) {
        bot.player().getInventory().clearContent();
        bot.player().getInventory().selected = 0;
        bot.player().inventoryMenu.setCarried(ItemStack.EMPTY);
        bot.player().inventoryMenu.broadcastChanges();
        P2GameTestSupport.require(
                bot.player().containerMenu == bot.player().inventoryMenu,
                "Container fixture did not start in the native InventoryMenu");
    }

    private static void givePlayerMainInventory(TestBot bot, int count) {
        clearBotInventory(bot);
        // 玩家 inventory index 9 在 3×9/6×9 菜单中分别固定映射为 slot 27/54。
        bot.player().getInventory().setItem(9,
                new ItemStack(Items.OAK_LOG, count));
        bot.player().inventoryMenu.broadcastChanges();
    }

    private static ChestBlockEntity chestEntity(
            GameTestHelper helper, BlockPos position) {
        if (helper.getLevel().getBlockEntity(position)
                instanceof ChestBlockEntity chest) {
            return chest;
        }
        throw new IllegalStateException(
                "Double-chest fixture did not create a chest block entity");
    }

    private static BarrelBlockEntity barrelEntity(
            GameTestHelper helper, BlockPos position) {
        if (helper.getLevel().getBlockEntity(position)
                instanceof BarrelBlockEntity barrel) {
            return barrel;
        }
        throw new IllegalStateException(
                "Barrel fixture did not create a barrel block entity");
    }

    private static ShulkerBoxBlockEntity shulkerEntity(
            GameTestHelper helper, BlockPos position) {
        if (helper.getLevel().getBlockEntity(position)
                instanceof ShulkerBoxBlockEntity shulker) {
            return shulker;
        }
        throw new IllegalStateException(
                "Shulker fixture did not create a shulker block entity");
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

    private static int itemCount(Container container, Item item) {
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static int itemCount(TestBot bot, Item item) {
        return itemCount(bot.player().getInventory(), item);
    }

    private record DoubleChestFixture(
            BlockPos target,
            ChestBlockEntity first,
            ChestBlockEntity second) {
    }
}
