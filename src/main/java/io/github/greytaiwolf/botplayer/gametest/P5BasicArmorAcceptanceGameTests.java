package io.github.greytaiwolf.botplayer.gametest;

import io.github.greytaiwolf.botplayer.BotPlayer;
import io.github.greytaiwolf.botplayer.action.ActionEnvelope;
import io.github.greytaiwolf.botplayer.action.ActionMailbox;
import io.github.greytaiwolf.botplayer.action.ActionOrigin;
import io.github.greytaiwolf.botplayer.action.ActionPriority;
import io.github.greytaiwolf.botplayer.action.StopAction;
import io.github.greytaiwolf.botplayer.gametest.P2GameTestSupport.TestBot;
import io.github.greytaiwolf.botplayer.inventory.BotInventoryMenu;
import io.github.greytaiwolf.botplayer.inventory.BotInventorySessionManager;
import io.github.greytaiwolf.botplayer.skill.core.SkillFailureCode;
import io.github.greytaiwolf.botplayer.skill.core.SkillRunState;
import io.github.greytaiwolf.botplayer.skill.runtime.SurvivalSkillKind;
import io.github.greytaiwolf.botplayer.skill.runtime.SurvivalSkillRunView;
import io.github.greytaiwolf.botplayer.skill.runtime.SurvivalSkillSubmission;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private static final String REJECTION_BATCH =
            "p5_basic_armor_rejection";
    private static final String COMPLETION_DELAY_BATCH =
            "p5_basic_armor_completion_delay";
    private static final int TIMEOUT_TICKS = 240;
    private static final int TERMINAL_WAIT_TICKS = 200;

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
            batch = REJECTION_BATCH,
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
            batch = REJECTION_BATCH,
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
    public static void mainInventoryCandidateUsesEmptyTemporaryHotbar(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        TestBot bot = P5GameTestSupport.spawnFixedBot(
                helper, "P5ArmorMain");
        P2GameTestSupport.Cleanup cleanup =
                P5GameTestSupport.cleanup(bot);
        try {
            prepareHealthy(bot);
            bot.player().getInventory().selected = 5;
            bot.player().getInventory().setItem(
                    5, new ItemStack(Items.STONE));
            bot.player().getInventory().setItem(
                    9, new ItemStack(Items.DIAMOND_HELMET));

            SurvivalSkillSubmission submission =
                    bot.manager().startBasicArmor(bot.name());
            P2GameTestSupport.require(
                    submission.accepted(),
                    "Two-click main-inventory armor upgrade was rejected: "
                            + submission.status());

            awaitTerminalRun(
                    helper,
                    bot,
                    submission.runId().orElseThrow(),
                    cleanup,
                    "Two-click main-inventory armor upgrade did not reach a terminal state",
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
                                                .getItem(9)
                                                .isEmpty()
                                        && bot.player()
                                                .getInventory()
                                                .getItem(0)
                                                .isEmpty(),
                                "Two-click plan did not restore the empty temporary hotbar slot");
                        for (int slot = 0; slot <= 8; slot++) {
                            ItemStack stack =
                                    bot.player()
                                            .getInventory()
                                            .getItem(slot);
                            P2GameTestSupport.require(
                                    slot == 5
                                            ? stack.is(Items.STONE)
                                            : stack.isEmpty(),
                                    "Two-click plan changed hotbar slot "
                                            + slot);
                        }
                        P2GameTestSupport.require(
                                bot.player()
                                                        .getInventory()
                                                        .selected
                                                == 5
                                        && bot.player()
                                                .inventoryMenu
                                                .getCarried()
                                                .isEmpty()
                                        && count(
                                                        bot,
                                                        Items
                                                                .DIAMOND_HELMET)
                                                == 1
                                        && count(bot, Items.STONE)
                                                == 1,
                                "Two-click plan changed selection, cursor, or item counts");
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
    public static void mainInventoryUpgradeCyclesOldArmorThroughFullHotbar(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        TestBot bot = P5GameTestSupport.spawnFixedBot(
                helper, "P5ArmorFull");
        P2GameTestSupport.Cleanup cleanup =
                P5GameTestSupport.cleanup(bot);
        try {
            ItemStack[] expectedHotbar =
                    prepareThreeClickArmorLayout(bot);

            SurvivalSkillSubmission submission =
                    bot.manager().startBasicArmor(bot.name());
            P2GameTestSupport.require(
                    submission.accepted(),
                    "Three-click main-inventory armor upgrade was rejected: "
                            + submission.status());

            awaitTerminalRun(
                    helper,
                    bot,
                    submission.runId().orElseThrow(),
                    cleanup,
                    "Three-click main-inventory armor upgrade did not reach a terminal state",
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
                                                .getItem(9)
                                                .is(
                                                        Items
                                                                .IRON_HELMET),
                                "Three-click plan did not return the old helmet to the source slot");
                        for (int slot = 0;
                                slot < expectedHotbar.length;
                                slot++) {
                            P2GameTestSupport.require(
                                    ItemStack.matches(
                                            expectedHotbar[slot],
                                            bot.player()
                                                    .getInventory()
                                                    .getItem(slot))
                                            && count(
                                                            bot,
                                                            expectedHotbar[
                                                                    slot]
                                                                    .getItem())
                                                    == 1,
                                    "Three-click plan changed hotbar slot "
                                            + slot);
                        }
                        P2GameTestSupport.require(
                                bot.player()
                                                        .getInventory()
                                                        .selected
                                                == 8
                                        && bot.player()
                                                .inventoryMenu
                                                .getCarried()
                                                .isEmpty()
                                        && count(
                                                        bot,
                                                        Items
                                                                .DIAMOND_HELMET)
                                                == 1
                                        && count(
                                                        bot,
                                                        Items
                                                                .IRON_HELMET)
                                                == 1,
                                "Three-click plan changed selection, cursor, or armor counts");
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
    public static void sameTickEmergencyQueuePreemptsAfterPrefixOne(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        TestBot bot = P5GameTestSupport.spawnFixedBot(
                helper, "P5ArmorPre1");
        P2GameTestSupport.Cleanup cleanup =
                P5GameTestSupport.cleanup(bot);
        try {
            ItemStack[] expectedHotbar =
                    prepareThreeClickArmorLayout(bot);
            SurvivalSkillSubmission submission =
                    bot.manager().startBasicArmor(bot.name());
            P2GameTestSupport.require(
                    submission.accepted(),
                    "Prefix-one preemption armor upgrade was rejected: "
                            + submission.status());

            /*
             * 两条命令按 mailbox FIFO 入队；runtime 先 start 菜单 ticket
             * 并完成第 1 击，再让后入队的 EMERGENCY Stop acquire 通道。
             */
            submitEmergencyStop(bot);
            awaitTerminalRun(
                    helper,
                    bot,
                    submission.runId().orElseThrow(),
                    cleanup,
                    "Same-tick emergency queue did not preempt the armor run",
                    view -> requirePreemptedArmorEndpoint(
                            bot,
                            expectedHotbar,
                            view,
                            false));
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void emergencyQueuedAtPrefixOnePreemptsAfterPrefixTwo(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        TestBot bot = P5GameTestSupport.spawnFixedBot(
                helper, "P5ArmorPre2");
        P2GameTestSupport.Cleanup cleanup =
                P5GameTestSupport.cleanup(bot);
        try {
            ItemStack[] expectedHotbar =
                    prepareThreeClickArmorLayout(bot);
            SurvivalSkillSubmission submission =
                    bot.manager().startBasicArmor(bot.name());
            P2GameTestSupport.require(
                    submission.accepted(),
                    "Prefix-two preemption armor upgrade was rejected: "
                            + submission.status());
            UUID runId = submission.runId().orElseThrow();

            P2GameTestSupport.awaitCondition(
                    helper,
                    TERMINAL_WAIT_TICKS,
                    () -> matchesThreeClickPrefixOne(
                            bot, expectedHotbar),
                    "Armor run never exposed the exact first menu prefix",
                    cleanup,
                    () -> {
                        /*
                         * 旧菜单 ticket 在下一 Tick 先执行第 2 击；随后
                         * Stop ticket acquire，但同 Tick 物理栅栏会把最终
                         * 收口点击延后到下一 Tick。
                         */
                        submitEmergencyStop(bot);
                        awaitTerminalRun(
                                helper,
                                bot,
                                runId,
                                cleanup,
                                "Emergency queued at prefix one did not preempt after prefix two",
                                view ->
                                        requirePreemptedArmorEndpoint(
                                                bot,
                                                expectedHotbar,
                                                view,
                                                true));
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
    public static void viewerOpenAfterPrefixOneSettlesBeforeTakingLock(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        TestBot bot = P5GameTestSupport.spawnFixedBot(
                helper, "P5ArmorViewMid");
        P2GameTestSupport.Cleanup cleanup =
                P5GameTestSupport.cleanup(bot);
        try {
            ItemStack[] expectedHotbar =
                    prepareThreeClickArmorLayout(bot);
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

            SurvivalSkillSubmission submission =
                    bot.manager().startBasicArmor(bot.name());
            P2GameTestSupport.require(
                    submission.accepted(),
                    "Mid-transaction viewer scenario rejected the armor skill: "
                            + submission.status());
            UUID runId = submission.runId().orElseThrow();

            P2GameTestSupport.awaitCondition(
                    helper,
                    TERMINAL_WAIT_TICKS,
                    () -> matchesThreeClickPrefixOne(
                            bot, expectedHotbar),
                    "Armor run never exposed a prefix for the viewer hand-off",
                    cleanup,
                    () -> {
                        BotInventorySessionManager.OpenStatus openStatus =
                                bot.manager().openInventory(
                                        viewer, bot.player());
                        P2GameTestSupport.require(
                                openStatus
                                                == BotInventorySessionManager
                                                        .OpenStatus
                                                        .OPENING
                                        && viewer.containerMenu
                                                instanceof BotInventoryMenu,
                                "Viewer did not acquire the lock after action settlement: "
                                        + openStatus);
                        awaitTerminalRun(
                                helper,
                                bot,
                                runId,
                                cleanup,
                                "Viewer hand-off did not terminate the armor run",
                                view -> {
                                    P2GameTestSupport.require(
                                            view.kind()
                                                                    == SurvivalSkillKind
                                                                            .EQUIP_BASIC_ARMOR
                                                            && view.state()
                                                                    == SkillRunState
                                                                            .CANCELLED
                                                            && view.operationSequence()
                                                                    == 1
                                                            && !view.safeSummary()
                                                                    .contains(
                                                                            "已安全提交 1 个盔甲升级"),
                                            "Viewer hand-off exposed an invalid terminal run: "
                                                    + view);
                                    requireInitialArmorEndpoint(
                                            bot, expectedHotbar);
                                    P2GameTestSupport.require(
                                            viewer.containerMenu
                                                    instanceof BotInventoryMenu,
                                            "Settled skill stole or closed the new viewer lock");
                                });
                    });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = COMPLETION_DELAY_BATCH,
            timeoutTicks = 300)
    public static void hardDeadlineRecordsPhysicalFinalBeforeDelayedCompletion(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        TestBot bot = P5GameTestSupport.spawnFixedBot(
                helper, "P5ArmorLateAck");
        P2GameTestSupport.Cleanup cleanup =
                P5GameTestSupport.cleanup(bot);
        CountDownLatch completionGate = new CountDownLatch(1);
        AtomicBoolean dispatcherBlocked = new AtomicBoolean();
        cleanup.add(completionGate::countDown);
        try {
            long currentTick = helper.getLevel()
                    .getServer()
                    .getTickCount();
            UUID blockerId = UUID.randomUUID();
            ActionEnvelope blocker = new ActionEnvelope(
                    blockerId,
                    bot.player().getUUID(),
                    bot.player().runtimeHandle().generation(),
                    "gametest/p5/armor-completion-gate/"
                            + blockerId,
                    currentTick + 40L,
                    5,
                    new StopAction(),
                    ActionOrigin.none());
            ActionMailbox.Submission gateSubmission =
                    bot.manager().submitAction(
                            blocker,
                            ActionPriority.OWNER_TASK);
            P2GameTestSupport.require(
                    gateSubmission.status()
                            == ActionMailbox.SubmissionStatus
                                    .ENQUEUED,
                    "Completion-gate action was rejected");
            gateSubmission.completion()
                    .orElseThrow()
                    .whenComplete((outcome, throwable) -> {
                        dispatcherBlocked.set(true);
                        try {
                            completionGate.await(
                                    20L,
                                    TimeUnit.SECONDS);
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                        }
                    });

            P2GameTestSupport.awaitCondition(
                    helper,
                    40,
                    dispatcherBlocked::get,
                    "Completion dispatcher did not enter the deterministic gate",
                    cleanup,
                    () -> {
                        ItemStack[] expectedHotbar =
                                prepareThreeClickArmorLayout(bot);
                        SurvivalSkillSubmission submission =
                                bot.manager().startBasicArmor(
                                        bot.name());
                        P2GameTestSupport.require(
                                submission.accepted(),
                                "Delayed-completion armor skill was rejected: "
                                        + submission.status());
                        awaitTerminalRun(
                                helper,
                                bot,
                                submission.runId().orElseThrow(),
                                cleanup,
                                "Hard deadline did not close the delayed-completion armor run",
                                view -> {
                                    P2GameTestSupport.require(
                                            view.kind()
                                                                    == SurvivalSkillKind
                                                                            .EQUIP_BASIC_ARMOR
                                                            && view.state()
                                                                    == SkillRunState
                                                                            .FAILED
                                                            && view.failureCode()
                                                                    .filter(code ->
                                                                            code
                                                                                    == SkillFailureCode
                                                                                            .TIMEOUT)
                                                                    .isPresent()
                                                            && view.operationSequence()
                                                                    == 1
                                                            && view.safeSummary()
                                                                    .contains(
                                                                            "已安全提交 1 个盔甲升级"),
                                            "Hard timeout hid or overwrote the physical armor result: "
                                                    + view);
                                    requireFinalArmorEndpoint(
                                            bot, expectedHotbar);
                                });
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

    private static ItemStack[] prepareThreeClickArmorLayout(
            TestBot bot) {
        prepareHealthy(bot);
        Item[] hotbarItems = {
            Items.STONE,
            Items.DIRT,
            Items.COBBLESTONE,
            Items.OAK_LOG,
            Items.STICK,
            Items.TORCH,
            Items.APPLE,
            Items.BREAD,
            Items.FLINT
        };
        ItemStack[] expectedHotbar =
                new ItemStack[hotbarItems.length];
        for (int slot = 0;
                slot < hotbarItems.length;
                slot++) {
            expectedHotbar[slot] =
                    new ItemStack(hotbarItems[slot]);
            bot.player().getInventory().setItem(
                    slot, expectedHotbar[slot].copy());
        }
        bot.player().getInventory().selected = 8;
        bot.player().getInventory().setItem(
                9, new ItemStack(Items.DIAMOND_HELMET));
        bot.player().getInventory().setItem(
                39, new ItemStack(Items.IRON_HELMET));
        return expectedHotbar;
    }

    private static boolean matchesThreeClickPrefixOne(
            TestBot bot, ItemStack[] expectedHotbar) {
        if (!bot.player()
                        .getInventory()
                        .getItem(0)
                        .is(Items.DIAMOND_HELMET)
                || !ItemStack.matches(
                        expectedHotbar[0],
                        bot.player()
                                .getInventory()
                                .getItem(9))
                || !bot.player()
                        .getInventory()
                        .getItem(39)
                        .is(Items.IRON_HELMET)
                || bot.player()
                                .getInventory()
                                .selected
                        != 8
                || !bot.player()
                        .inventoryMenu
                        .getCarried()
                        .isEmpty()) {
            return false;
        }
        for (int slot = 1;
                slot < expectedHotbar.length;
                slot++) {
            if (!ItemStack.matches(
                    expectedHotbar[slot],
                    bot.player()
                            .getInventory()
                            .getItem(slot))) {
                return false;
            }
        }
        return true;
    }

    private static void requirePreemptedArmorEndpoint(
            TestBot bot,
            ItemStack[] expectedHotbar,
            SurvivalSkillRunView view,
            boolean finalEndpoint) {
        P2GameTestSupport.require(
                view.kind()
                                        == SurvivalSkillKind
                                                .EQUIP_BASIC_ARMOR
                                && view.state()
                                        == SkillRunState
                                                .PREEMPTED
                                && view.failureCode().isEmpty()
                                && view.operationSequence() == 1,
                "Preempted armor run exposed an invalid terminal view: "
                        + view);
        boolean summaryCommitted =
                view.safeSummary().contains(
                        "已安全提交 1 个盔甲升级");
        P2GameTestSupport.require(
                summaryCommitted == finalEndpoint,
                finalEndpoint
                        ? "Final endpoint was not recorded in the preempted skill summary"
                        : "Initial endpoint was falsely recorded as an armor upgrade");
        for (int slot = 0;
                slot < expectedHotbar.length;
                slot++) {
            P2GameTestSupport.require(
                    ItemStack.matches(
                                    expectedHotbar[slot],
                                    bot.player()
                                            .getInventory()
                                            .getItem(slot))
                            && count(
                                            bot,
                                            expectedHotbar[slot]
                                                    .getItem())
                                    == 1,
                    "Settled armor transaction changed hotbar slot "
                            + slot);
        }
        P2GameTestSupport.require(
                bot.player()
                                .getInventory()
                                .getItem(9)
                                .is(
                                        finalEndpoint
                                                ? Items.IRON_HELMET
                                                : Items.DIAMOND_HELMET)
                        && bot.player()
                                .getInventory()
                                .getItem(39)
                                .is(
                                        finalEndpoint
                                                ? Items.DIAMOND_HELMET
                                                : Items.IRON_HELMET)
                        && bot.player()
                                        .getInventory()
                                        .selected
                                == 8
                        && bot.player()
                                .inventoryMenu
                                .getCarried()
                                .isEmpty()
                        && count(
                                        bot,
                                        Items.DIAMOND_HELMET)
                                == 1
                        && count(
                                        bot,
                                        Items.IRON_HELMET)
                                == 1,
                finalEndpoint
                        ? "Prefix-two preemption did not settle to the exact final endpoint"
                        : "Prefix-one preemption did not settle to the exact initial endpoint");
    }

    private static void requireInitialArmorEndpoint(
            TestBot bot, ItemStack[] expectedHotbar) {
        for (int slot = 0;
                slot < expectedHotbar.length;
                slot++) {
            P2GameTestSupport.require(
                    ItemStack.matches(
                                    expectedHotbar[slot],
                                    bot.player()
                                            .getInventory()
                                            .getItem(slot))
                            && count(
                                            bot,
                                            expectedHotbar[slot]
                                                    .getItem())
                                    == 1,
                    "Viewer hand-off changed hotbar slot "
                            + slot);
        }
        P2GameTestSupport.require(
                bot.player()
                                .getInventory()
                                .getItem(9)
                                .is(Items.DIAMOND_HELMET)
                        && bot.player()
                                .getInventory()
                                .getItem(39)
                                .is(Items.IRON_HELMET)
                        && bot.player()
                                        .getInventory()
                                        .selected
                                == 8
                        && bot.player()
                                .inventoryMenu
                                .getCarried()
                                .isEmpty()
                        && count(bot, Items.DIAMOND_HELMET)
                                == 1
                        && count(bot, Items.IRON_HELMET)
                                == 1,
                "Viewer hand-off did not settle the menu prefix to the exact initial endpoint");
    }

    private static void requireFinalArmorEndpoint(
            TestBot bot, ItemStack[] expectedHotbar) {
        for (int slot = 0;
                slot < expectedHotbar.length;
                slot++) {
            P2GameTestSupport.require(
                    ItemStack.matches(
                                    expectedHotbar[slot],
                                    bot.player()
                                            .getInventory()
                                            .getItem(slot))
                            && count(
                                            bot,
                                            expectedHotbar[slot]
                                                    .getItem())
                                    == 1,
                    "Delayed completion changed hotbar slot "
                            + slot);
        }
        P2GameTestSupport.require(
                bot.player()
                                .getInventory()
                                .getItem(9)
                                .is(Items.IRON_HELMET)
                        && bot.player()
                                .getInventory()
                                .getItem(39)
                                .is(Items.DIAMOND_HELMET)
                        && bot.player()
                                        .getInventory()
                                        .selected
                                == 8
                        && bot.player()
                                .inventoryMenu
                                .getCarried()
                                .isEmpty()
                        && count(bot, Items.DIAMOND_HELMET)
                                == 1
                        && count(bot, Items.IRON_HELMET)
                                == 1,
                "Hard timeout did not preserve the exact final armor endpoint");
    }

    private static void submitEmergencyStop(TestBot bot) {
        long currentTick = bot.player()
                .serverLevel()
                .getServer()
                .getTickCount();
        UUID actionId = UUID.randomUUID();
        ActionEnvelope envelope = new ActionEnvelope(
                actionId,
                bot.player().getUUID(),
                bot.player().runtimeHandle().generation(),
                "gametest/p5/armor-preempt/" + actionId,
                currentTick + 40L,
                5,
                new StopAction(),
                ActionOrigin.none());
        ActionMailbox.Submission submission =
                bot.manager().submitAction(
                        envelope,
                        ActionPriority.EMERGENCY);
        P2GameTestSupport.require(
                submission.status()
                        == ActionMailbox.SubmissionStatus
                                .ENQUEUED,
                "Emergency armor preemption was rejected");
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
