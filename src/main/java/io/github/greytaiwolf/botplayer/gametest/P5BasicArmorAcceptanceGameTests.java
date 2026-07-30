package io.github.greytaiwolf.botplayer.gametest;

import io.github.greytaiwolf.botplayer.BotPlayer;
import io.github.greytaiwolf.botplayer.gametest.P2GameTestSupport.TestBot;
import io.github.greytaiwolf.botplayer.inventory.BotInventoryMenu;
import io.github.greytaiwolf.botplayer.inventory.BotInventorySessionManager;
import io.github.greytaiwolf.botplayer.skill.core.SkillRunState;
import io.github.greytaiwolf.botplayer.skill.runtime.SurvivalSkillKind;
import io.github.greytaiwolf.botplayer.skill.runtime.SurvivalSkillRunView;
import io.github.greytaiwolf.botplayer.skill.runtime.SurvivalSkillSubmission;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * P5 基础盔甲纵切验收：只通过公开技能入口启动，并验证原生背包菜单的真实交换结果。
 */
@GameTestHolder(BotPlayer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class P5BasicArmorAcceptanceGameTests {
    private static final String BATCH = "p5_basic_armor";
    private static final int TIMEOUT_TICKS = 240;
    private static final int TERMINAL_WAIT_TICKS = 160;

    private P5BasicArmorAcceptanceGameTests() {}

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void hotbarUpgradeSwapsOldArmorThroughNativeMenu(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        TestBot bot = P5GameTestSupport.spawnFixedBot(
                helper, "P5ArmorSwap");
        P2GameTestSupport.Cleanup cleanup =
                P5GameTestSupport.cleanup(bot);
        try {
            prepareHealthy(bot);
            bot.player().getInventory().selected = 5;
            bot.player().getInventory().setItem(
                    5, new ItemStack(Items.STONE));
            bot.player().getInventory().setItem(
                    2, new ItemStack(Items.DIAMOND_HELMET));
            bot.player().getInventory().setItem(
                    39, new ItemStack(Items.IRON_HELMET));

            SurvivalSkillSubmission submission =
                    bot.manager().startBasicArmor(bot.name());
            P2GameTestSupport.require(
                    submission.accepted(),
                    "Armor upgrade was rejected: "
                            + submission.status());

            awaitTerminalRun(
                    helper,
                    bot,
                    submission.runId().orElseThrow(),
                    cleanup,
                    "Hotbar armor upgrade did not reach a terminal state",
                    view -> {
                        requireSuccessfulArmorRun(view, 1);
                        P2GameTestSupport.require(
                                bot.player()
                                                .getInventory()
                                                .getItem(39)
                                                .is(
                                                        Items
                                                                .DIAMOND_HELMET)
                                        && bot.player()
                                                .getInventory()
                                                .getItem(2)
                                                .is(
                                                        Items
                                                                .IRON_HELMET),
                                "Native menu did not swap the old helmet back into the source hotbar slot");
                        P2GameTestSupport.require(
                                bot.player()
                                                        .getInventory()
                                                        .selected
                                                == 5
                                        && bot.player()
                                                .getInventory()
                                                .getItem(5)
                                                .is(Items.STONE)
                                        && bot.player()
                                                .inventoryMenu
                                                .getCarried()
                                                .isEmpty(),
                                "Armor swap changed selection, sentinel item, or cursor");
                        P2GameTestSupport.require(
                                count(bot, Items.DIAMOND_HELMET)
                                                == 1
                                        && count(
                                                        bot,
                                                        Items
                                                                .IRON_HELMET)
                                                == 1
                                        && count(bot, Items.STONE)
                                                == 1,
                                "Armor swap violated inventory item conservation");
                    });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void bindingTargetRejectsUpgradeWithoutMutation(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        TestBot bot = P5GameTestSupport.spawnFixedBot(
                helper, "P5ArmorBound");
        P2GameTestSupport.Cleanup cleanup =
                P5GameTestSupport.cleanup(bot);
        try {
            prepareHealthy(bot);
            ItemStack boundTarget = withBindingCurse(
                    helper,
                    new ItemStack(Items.LEATHER_HELMET));
            ItemStack expectedBoundTarget =
                    boundTarget.copy();
            bot.player().getInventory().setItem(
                    0, new ItemStack(Items.DIAMOND_HELMET));
            bot.player().getInventory().setItem(
                    39, boundTarget);

            Optional<SurvivalSkillRunView> baselineView =
                    bot.manager()
                            .survivalSkillRun(bot.name());
            SurvivalSkillSubmission submission =
                    bot.manager().startBasicArmor(bot.name());
            requireRejectedWithoutRun(
                    bot,
                    submission,
                    SurvivalSkillSubmission.Status.NO_UPGRADE,
                    baselineView);
            P2GameTestSupport.require(
                    bot.player()
                                            .getInventory()
                                            .getItem(0)
                                            .is(
                                                    Items
                                                            .DIAMOND_HELMET)
                            && bot.player()
                                    .getInventory()
                                    .getItem(39)
                                    .is(Items.LEATHER_HELMET)
                            && ItemStack.matches(
                                    expectedBoundTarget,
                                    bot.player()
                                            .getInventory()
                                            .getItem(39))
                            && count(bot, Items.DIAMOND_HELMET)
                                    == 1
                            && count(bot, Items.LEATHER_HELMET)
                                    == 1,
                    "Binding target rejection changed the inventory");
            cleanup.run();
            helper.succeed();
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void bindingCandidateRejectsUpgradeWithoutMutation(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        TestBot bot = P5GameTestSupport.spawnFixedBot(
                helper, "P5ArmorCand");
        P2GameTestSupport.Cleanup cleanup =
                P5GameTestSupport.cleanup(bot);
        try {
            prepareHealthy(bot);
            ItemStack boundCandidate = withBindingCurse(
                    helper,
                    new ItemStack(Items.DIAMOND_HELMET));
            ItemStack expectedBoundCandidate =
                    boundCandidate.copy();
            bot.player().getInventory().setItem(
                    3, boundCandidate);

            Optional<SurvivalSkillRunView> baselineView =
                    bot.manager()
                            .survivalSkillRun(bot.name());
            SurvivalSkillSubmission submission =
                    bot.manager().startBasicArmor(bot.name());
            requireRejectedWithoutRun(
                    bot,
                    submission,
                    SurvivalSkillSubmission.Status.NO_UPGRADE,
                    baselineView);
            P2GameTestSupport.require(
                    bot.player()
                                            .getInventory()
                                            .getItem(3)
                                            .is(
                                                    Items
                                                            .DIAMOND_HELMET)
                            && bot.player()
                                    .getInventory()
                                    .getItem(39)
                                    .isEmpty()
                            && ItemStack.matches(
                                    expectedBoundCandidate,
                                    bot.player()
                                            .getInventory()
                                            .getItem(3))
                            && count(bot, Items.DIAMOND_HELMET)
                                    == 1,
                    "Binding candidate rejection changed the inventory");
            cleanup.run();
            helper.succeed();
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void mainInventoryOnlyCandidateDoesNotStart(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        TestBot bot = P5GameTestSupport.spawnFixedBot(
                helper, "P5ArmorMain");
        P2GameTestSupport.Cleanup cleanup =
                P5GameTestSupport.cleanup(bot);
        try {
            prepareHealthy(bot);
            bot.player().getInventory().setItem(
                    9, new ItemStack(Items.DIAMOND_HELMET));

            Optional<SurvivalSkillRunView> baselineView =
                    bot.manager()
                            .survivalSkillRun(bot.name());
            SurvivalSkillSubmission submission =
                    bot.manager().startBasicArmor(bot.name());
            requireRejectedWithoutRun(
                    bot,
                    submission,
                    SurvivalSkillSubmission.Status.NO_UPGRADE,
                    baselineView);
            P2GameTestSupport.require(
                    bot.player()
                                            .getInventory()
                                            .getItem(9)
                                            .is(
                                                    Items
                                                            .DIAMOND_HELMET)
                            && bot.player()
                                    .getInventory()
                                    .getItem(39)
                                    .isEmpty()
                            && count(bot, Items.DIAMOND_HELMET)
                                    == 1,
                    "Main-inventory-only rejection changed the inventory");
            cleanup.run();
            helper.succeed();
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void viewerLockPreventsNativeMenuMutation(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        TestBot bot = P5GameTestSupport.spawnFixedBot(
                helper, "P5ArmorView");
        P2GameTestSupport.Cleanup cleanup =
                P5GameTestSupport.cleanup(bot);
        try {
            prepareHealthy(bot);
            bot.player().getInventory().setItem(
                    1, new ItemStack(Items.DIAMOND_HELMET));
            ServerPlayer viewer = P2GameTestSupport.spawnViewer(
                    helper, new Vec3(4.5D, 1.0D, 3.4D));
            cleanup.add(
                    () -> P2GameTestSupport.disconnectViewer(
                            viewer));
            helper.getLevel()
                    .getServer()
                    .getPlayerList()
                    .op(viewer.getGameProfile());
            cleanup.add(() -> helper.getLevel()
                    .getServer()
                    .getPlayerList()
                    .deop(viewer.getGameProfile()));

            BotInventorySessionManager.OpenStatus openStatus =
                    bot.manager().openInventory(
                            viewer, bot.player());
            P2GameTestSupport.require(
                    openStatus.accepted()
                            && viewer.containerMenu
                                    instanceof BotInventoryMenu,
                    "Viewer could not acquire the native inventory write lock: "
                            + openStatus);

            SurvivalSkillSubmission submission =
                    bot.manager().startBasicArmor(bot.name());
            P2GameTestSupport.require(
                    submission.accepted(),
                    "Viewer-lock scenario did not enqueue the guarded skill: "
                            + submission.status());

            awaitTerminalRun(
                    helper,
                    bot,
                    submission.runId().orElseThrow(),
                    cleanup,
                    "Viewer-locked armor run did not reach a terminal state",
                    view -> {
                        P2GameTestSupport.require(
                                view.kind()
                                                        == SurvivalSkillKind
                                                                .EQUIP_BASIC_ARMOR
                                                && view.state()
                                                        == SkillRunState
                                                                .FAILED,
                                "Viewer-locked armor run was not failed closed");
                        P2GameTestSupport.require(
                                bot.player()
                                                .getInventory()
                                                .getItem(1)
                                                .is(
                                                        Items
                                                                .DIAMOND_HELMET)
                                        && bot.player()
                                                .getInventory()
                                                .getItem(39)
                                                .isEmpty()
                                        && count(
                                                        bot,
                                                        Items
                                                                .DIAMOND_HELMET)
                                                == 1,
                                "Viewer-locked native menu path changed the armor layout");
                        P2GameTestSupport.require(
                                viewer.containerMenu
                                        instanceof BotInventoryMenu,
                                "Failed skill stole or closed the viewer's inventory session");
                    });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void multipleHotbarPiecesUpgradeInFixedSlotOrder(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        TestBot bot = P5GameTestSupport.spawnFixedBot(
                helper, "P5ArmorSeq");
        P2GameTestSupport.Cleanup cleanup =
                P5GameTestSupport.cleanup(bot);
        try {
            prepareHealthy(bot);
            bot.player().getInventory().selected = 8;
            bot.player().getInventory().setItem(
                    8, new ItemStack(Items.STONE));
            bot.player().getInventory().setItem(
                    0, new ItemStack(Items.DIAMOND_HELMET));
            bot.player().getInventory().setItem(
                    1, new ItemStack(Items.IRON_CHESTPLATE));

            SurvivalSkillSubmission submission =
                    bot.manager().startBasicArmor(bot.name());
            P2GameTestSupport.require(
                    submission.accepted(),
                    "Sequential armor upgrade was rejected: "
                            + submission.status());

            awaitTerminalRun(
                    helper,
                    bot,
                    submission.runId().orElseThrow(),
                    cleanup,
                    "Sequential armor upgrades did not reach a terminal state",
                    view -> {
                        requireSuccessfulArmorRun(view, 2);
                        P2GameTestSupport.require(
                                bot.player()
                                                .getInventory()
                                                .getItem(39)
                                                .is(
                                                        Items
                                                                .DIAMOND_HELMET)
                                        && bot.player()
                                                .getInventory()
                                                .getItem(38)
                                                .is(
                                                        Items
                                                                .IRON_CHESTPLATE)
                                        && bot.player()
                                                .getInventory()
                                                .getItem(0)
                                                .isEmpty()
                                        && bot.player()
                                                .getInventory()
                                                .getItem(1)
                                                .isEmpty(),
                                "Sequential native menu swaps did not equip both armor pieces");
                        P2GameTestSupport.require(
                                bot.player()
                                                        .getInventory()
                                                        .selected
                                                == 8
                                        && bot.player()
                                                .getInventory()
                                                .getItem(8)
                                                .is(Items.STONE)
                                        && count(
                                                        bot,
                                                        Items
                                                                .DIAMOND_HELMET)
                                                == 1
                                        && count(
                                                        bot,
                                                        Items
                                                                .IRON_CHESTPLATE)
                                                == 1
                                        && count(bot, Items.STONE)
                                                == 1,
                                "Sequential armor upgrades changed selection or item counts");
                    });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    private static void awaitTerminalRun(
            GameTestHelper helper,
            TestBot bot,
            UUID runId,
            P2GameTestSupport.Cleanup cleanup,
            String failureMessage,
            Consumer<SurvivalSkillRunView> verifier) {
        P2GameTestSupport.awaitCondition(
                helper,
                TERMINAL_WAIT_TICKS,
                () -> bot.manager()
                        .survivalSkillRun(bot.name())
                        .filter(view ->
                                view.runId().equals(runId)
                                        && view.state()
                                                .isTerminal())
                        .isPresent(),
                failureMessage,
                cleanup,
                () -> {
                    SurvivalSkillRunView view =
                            bot.manager()
                                    .survivalSkillRun(bot.name())
                                    .filter(candidate ->
                                            candidate.runId()
                                                    .equals(runId))
                                    .orElseThrow();
                    verifier.accept(view);
                    cleanup.run();
                    helper.succeed();
                });
    }

    private static void requireSuccessfulArmorRun(
            SurvivalSkillRunView view, int expectedOperations) {
        P2GameTestSupport.require(
                view.kind()
                                == SurvivalSkillKind
                                        .EQUIP_BASIC_ARMOR
                        && view.state()
                                == SkillRunState.SUCCEEDED
                        && view.failureCode().isEmpty()
                        && view.operationSequence()
                                == expectedOperations,
                "Armor run did not succeed with the expected native-menu operation count: "
                        + view);
    }

    private static void requireRejectedWithoutRun(
            TestBot bot,
            SurvivalSkillSubmission submission,
            SurvivalSkillSubmission.Status expectedStatus,
            Optional<SurvivalSkillRunView> baselineView) {
        P2GameTestSupport.require(
                submission.status() == expectedStatus
                        && !submission.accepted()
                        && submission.runId().isEmpty(),
                "Armor request had an unexpected rejection status: "
                        + submission);
        P2GameTestSupport.require(
                baselineView.equals(
                        bot.manager()
                                .survivalSkillRun(bot.name())),
                "Rejected armor request created or changed a skill run");
    }

    private static ItemStack withBindingCurse(
            GameTestHelper helper, ItemStack stack) {
        stack.enchant(
                helper.getLevel()
                        .registryAccess()
                        .lookupOrThrow(
                                Registries.ENCHANTMENT)
                        .getOrThrow(
                                Enchantments.BINDING_CURSE),
                1);
        return stack;
    }

    private static void prepareHealthy(TestBot bot) {
        bot.player().setHealth(
                bot.player().getMaxHealth());
        bot.player().getFoodData().setFoodLevel(20);
        bot.player().getFoodData().setSaturation(5.0F);
        bot.player().getFoodData().setExhaustion(0.0F);
        bot.player().removeAllEffects();
        bot.player().stopUsingItem();
    }

    private static int count(TestBot bot, Item item) {
        int count = 0;
        int size = bot.player()
                .getInventory()
                .getContainerSize();
        for (int slot = 0; slot < size; slot++) {
            ItemStack stack =
                    bot.player()
                            .getInventory()
                            .getItem(slot);
            if (stack.is(item)) {
                count = Math.addExact(
                        count, stack.getCount());
            }
        }
        return count;
    }
}
