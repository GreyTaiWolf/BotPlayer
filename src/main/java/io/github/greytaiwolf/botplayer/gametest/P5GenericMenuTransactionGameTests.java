package io.github.greytaiwolf.botplayer.gametest;

import io.github.greytaiwolf.botplayer.BotPlayer;
import io.github.greytaiwolf.botplayer.action.ActionEnvelope;
import io.github.greytaiwolf.botplayer.action.ActionFailureCode;
import io.github.greytaiwolf.botplayer.action.ActionMailbox;
import io.github.greytaiwolf.botplayer.action.ActionOrigin;
import io.github.greytaiwolf.botplayer.action.ActionOutcome;
import io.github.greytaiwolf.botplayer.action.ActionPriority;
import io.github.greytaiwolf.botplayer.action.ActionState;
import io.github.greytaiwolf.botplayer.action.StopAction;
import io.github.greytaiwolf.botplayer.action.WorldInteractionAction;
import io.github.greytaiwolf.botplayer.action.interaction.WorldInteractionActionSpec;
import io.github.greytaiwolf.botplayer.action.interaction.menu.InventoryMenuSnapshot;
import io.github.greytaiwolf.botplayer.action.interaction.menu.InventoryMenuSwapInstruction;
import io.github.greytaiwolf.botplayer.action.interaction.menu.InventoryMenuSwapPlan;
import io.github.greytaiwolf.botplayer.action.interaction.menu.InventoryMenuSwapPlanBuilder;
import io.github.greytaiwolf.botplayer.action.minecraft.MinecraftActionSnapshot;
import io.github.greytaiwolf.botplayer.gametest.P2GameTestSupport.TestBot;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 通用原生背包 SWAP 的真实逐 Tick 事务验收。
 */
@GameTestHolder(BotPlayer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class P5GenericMenuTransactionGameTests {
    private static final String BATCH =
            "p5_generic_menu_transaction";
    private static final int TIMEOUT_TICKS = 240;
    private static final int WAIT_TICKS = 180;

    private P5GenericMenuTransactionGameTests() {}

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void fiveStepPreemptionSettlesAcrossTicks(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        TestBot bot = P5GameTestSupport.spawnFixedBot(
                helper, "P5MenuFive");
        P2GameTestSupport.Cleanup cleanup =
                P5GameTestSupport.cleanup(bot);
        try {
            InventoryMenuSwapPlan plan =
                    prepareFiveStepPlan(bot);
            long generation = bot.player()
                    .runtimeHandle()
                    .generation();
            TrackedSubmission menu = submit(
                    bot,
                    new WorldInteractionAction(
                            new WorldInteractionActionSpec
                                    .InventoryMenuSwap(plan)),
                    ActionPriority.OWNER_TASK,
                    "five-step",
                    80);

            P2GameTestSupport.awaitCondition(
                    helper,
                    WAIT_TICKS,
                    () -> currentPrefix(bot, plan) == 1,
                    "Five-step menu action never exposed stable prefix one",
                    cleanup,
                    () -> {
                        long submittedTick = currentTick(bot);
                        TrackedSubmission stop = submit(
                                bot,
                                new StopAction(),
                                ActionPriority.EMERGENCY,
                                "preempt",
                                40);
                        awaitPrefixTwoAfterPreemptionQueued(
                                helper,
                                bot,
                                plan,
                                menu,
                                stop,
                                generation,
                                submittedTick,
                                cleanup);
                    });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    private static void awaitPrefixTwoAfterPreemptionQueued(
            GameTestHelper helper,
            TestBot bot,
            InventoryMenuSwapPlan plan,
            TrackedSubmission menu,
            TrackedSubmission stop,
            long generation,
            long stopSubmittedTick,
            P2GameTestSupport.Cleanup cleanup) {
        P2GameTestSupport.awaitCondition(
                helper,
                WAIT_TICKS,
                () -> currentPrefix(bot, plan) == 2,
                "Queued preemption never exposed the exact second prefix",
                cleanup,
                () -> {
                    long settlementStartedTick = currentTick(bot);
                    P2GameTestSupport.require(
                            settlementStartedTick
                                    > stopSubmittedTick,
                            "Old menu owner did not advance on a later Tick");
                    observeSettlement(
                            helper,
                            bot,
                            plan,
                            menu,
                            stop,
                            generation,
                            new SettlementObservation(
                                    settlementStartedTick),
                            WAIT_TICKS,
                            cleanup);
                });
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void genericSequenceRejectsEquipmentSlots(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        TestBot bot = P5GameTestSupport.spawnFixedBot(
                helper, "P5MenuEquip");
        P2GameTestSupport.Cleanup cleanup =
                P5GameTestSupport.cleanup(bot);
        try {
            bot.player().getInventory().clearContent();
            bot.player().getInventory().selected = 8;
            bot.player().getInventory().setItem(
                    0, new ItemStack(Items.DIAMOND_HELMET));
            bot.player().getInventory().setItem(
                    39, new ItemStack(Items.IRON_HELMET));
            bot.player().inventoryMenu.setCarried(
                    ItemStack.EMPTY);
            bot.player().inventoryMenu.broadcastChanges();
            InventoryMenuSnapshot initial =
                    MinecraftActionSnapshot.inventoryMenu(
                            bot.player());
            InventoryMenuSwapPlan plan =
                    InventoryMenuSwapPlanBuilder.swapSequence(
                            initial,
                            List.of(
                                    new InventoryMenuSwapInstruction(
                                            39, 0)));
            TrackedSubmission submission = submit(
                    bot,
                    new WorldInteractionAction(
                            new WorldInteractionActionSpec
                                    .InventoryMenuSwap(plan)),
                    ActionPriority.OWNER_TASK,
                    "equipment-reject",
                    40);

            P2GameTestSupport.awaitOutcome(
                    helper,
                    submission.completion(),
                    WAIT_TICKS,
                    cleanup,
                    outcome -> {
                        P2GameTestSupport.require(
                                outcome.state()
                                                == ActionState.FAILED
                                        && outcome.failureCode()
                                                == ActionFailureCode
                                                        .UNSUPPORTED,
                                "Generic equipment plan was not rejected as unsupported: "
                                        + outcome);
                        InventoryMenuSnapshot actual =
                                MinecraftActionSnapshot.inventoryMenu(
                                        bot.player());
                        requireSafeControlPlane(
                                bot, initial, actual);
                        P2GameTestSupport.require(
                                initial.layoutEqualsIgnoringState(
                                        actual),
                                "Rejected generic equipment plan changed the menu snapshot");
                        cleanup.run();
                        helper.succeed();
                    });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    private static InventoryMenuSwapPlan prepareFiveStepPlan(
            TestBot bot) {
        bot.player().getInventory().clearContent();
        bot.player().getInventory().selected = 8;
        bot.player().getInventory().setItem(
                0, new ItemStack(Items.STONE));
        bot.player().getInventory().setItem(
                1, new ItemStack(Items.DIRT));
        bot.player().getInventory().setItem(
                9, new ItemStack(Items.TORCH));
        bot.player().getInventory().setItem(
                10, new ItemStack(Items.STICK));
        bot.player().getInventory().setItem(
                11, new ItemStack(Items.COAL));
        bot.player().inventoryMenu.setCarried(ItemStack.EMPTY);
        bot.player().inventoryMenu.broadcastChanges();
        InventoryMenuSnapshot initial =
                MinecraftActionSnapshot.inventoryMenu(
                        bot.player());
        return InventoryMenuSwapPlanBuilder.swapSequence(
                initial,
                List.of(
                        new InventoryMenuSwapInstruction(9, 0),
                        new InventoryMenuSwapInstruction(10, 0),
                        new InventoryMenuSwapInstruction(11, 1),
                        new InventoryMenuSwapInstruction(9, 1),
                        new InventoryMenuSwapInstruction(10, 1)));
    }

    private static void observeSettlement(
            GameTestHelper helper,
            TestBot bot,
            InventoryMenuSwapPlan plan,
            TrackedSubmission menu,
            TrackedSubmission stop,
            long generation,
            SettlementObservation observation,
            int remainingTicks,
            P2GameTestSupport.Cleanup cleanup) {
        try {
            InventoryMenuSnapshot actual =
                    MinecraftActionSnapshot.inventoryMenu(
                            bot.player());
            requireSafeControlPlane(
                    bot, plan.initialSnapshot(), actual);
            int prefix = plan.matchingPrefixIgnoringState(
                            actual)
                    .orElseThrow(() ->
                            new IllegalStateException(
                                    "Menu cleanup left the five-step plan prefixes"));
            observation.observe(
                    prefix,
                    currentTick(bot),
                    plan.orderedSteps().size());
            CompletableFuture<ActionOutcome> stopFuture =
                    stop.completion()
                            .toCompletableFuture();
            CompletableFuture<ActionOutcome> menuFuture =
                    menu.completion()
                            .toCompletableFuture();
            int endpoint = observation.endpointPrefix();
            if (endpoint < 0 || prefix != endpoint) {
                P2GameTestSupport.require(
                        !stopFuture.isDone()
                                && !menuFuture.isDone(),
                        "Menu owner or emergency claimant completed before settlement reached its endpoint");
                if (remainingTicks <= 0) {
                    throw new IllegalStateException(
                            "Five-step menu cleanup did not reach a bounded endpoint");
                }
                helper.runAfterDelay(
                        1L,
                        () -> observeSettlement(
                                helper,
                                bot,
                                plan,
                                menu,
                                stop,
                                generation,
                                observation,
                                remainingTicks - 1,
                                cleanup));
                return;
            }

            P2GameTestSupport.require(
                    observation.sawIntermediate()
                            && observation.endpointTick()
                                    - observation.settlementStartedTick()
                                    >= 2L,
                    "Menu cleanup did not remain pending across distinct Ticks");
            InventoryMenuSnapshot expected =
                    plan.snapshotAtPrefix(0);
            P2GameTestSupport.require(
                    expected.layoutEqualsIgnoringState(actual),
                    "Menu cleanup reached the wrong frozen endpoint");
            awaitTerminalOutcomes(
                    helper,
                    bot,
                    menu,
                    stop,
                    generation,
                    cleanup);
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            helper.fail(
                    exception.getMessage() == null
                            ? exception.toString()
                            : exception.getMessage());
        }
    }

    private static void awaitTerminalOutcomes(
            GameTestHelper helper,
            TestBot bot,
            TrackedSubmission menu,
            TrackedSubmission stop,
            long generation,
            P2GameTestSupport.Cleanup cleanup) {
        P2GameTestSupport.awaitOutcome(
                helper,
                menu.completion(),
                WAIT_TICKS,
                cleanup,
                menuOutcome -> {
                    P2GameTestSupport.require(
                            menuOutcome.state()
                                            == ActionState.PREEMPTED
                                    && menuOutcome.failureCode()
                                            == ActionFailureCode
                                                    .PREEMPTED,
                            "Five-step menu action did not preserve its PREEMPTED business outcome: "
                                    + menuOutcome);
                    P2GameTestSupport.awaitOutcome(
                            helper,
                            stop.completion(),
                            WAIT_TICKS,
                            cleanup,
                            stopOutcome -> {
                                P2GameTestSupport.require(
                                        stopOutcome.state()
                                                        == ActionState
                                                                .SUCCEEDED
                                                && bot.player()
                                                        .runtimeHandle()
                                                        .generation()
                                                        == generation,
                                        "Emergency claimant failed or changed generation: "
                                                + stopOutcome);
                                TrackedSubmission probe = submit(
                                        bot,
                                        new StopAction(),
                                        ActionPriority.OWNER_CONTROL,
                                        "probe",
                                        40);
                                P2GameTestSupport.awaitOutcome(
                                        helper,
                                        probe.completion(),
                                        40,
                                        cleanup,
                                        probeOutcome -> {
                                            P2GameTestSupport.require(
                                                    probeOutcome.state()
                                                                    == ActionState
                                                                            .SUCCEEDED
                                                            && bot.player()
                                                                    .runtimeHandle()
                                                                    .generation()
                                                                    == generation,
                                                    "Same-generation probe failed after generic menu cleanup");
                                            cleanup.run();
                                            helper.succeed();
                                        });
                            });
                });
    }

    private static int currentPrefix(
            TestBot bot, InventoryMenuSwapPlan plan) {
        InventoryMenuSnapshot actual =
                MinecraftActionSnapshot.inventoryMenu(
                        bot.player());
        requireSafeControlPlane(
                bot, plan.initialSnapshot(), actual);
        OptionalInt prefix =
                plan.matchingPrefixIgnoringState(actual);
        return prefix.orElseThrow(() ->
                new IllegalStateException(
                        "Five-step action left its exact plan prefixes"));
    }

    private static void requireSafeControlPlane(
            TestBot bot,
            InventoryMenuSnapshot initial,
            InventoryMenuSnapshot actual) {
        P2GameTestSupport.require(
                bot.player().containerMenu
                                == bot.player().inventoryMenu
                        && actual.containerId()
                                == initial.containerId()
                        && actual.selectedHotbar()
                                == initial.selectedHotbar()
                        && actual.cursor().isEmpty()
                        && actual.inventoryMultisetEquals(initial),
                "Menu transaction changed container, cursor, selection, or the 41-slot multiset");
        for (int menuSlot = 0; menuSlot <= 4; menuSlot++) {
            P2GameTestSupport.require(
                    bot.player()
                            .inventoryMenu
                            .getSlot(menuSlot)
                            .getItem()
                            .isEmpty(),
                    "Menu transaction changed a read-only crafting slot");
        }
    }

    private static TrackedSubmission submit(
            TestBot bot,
            io.github.greytaiwolf.botplayer.action.ActionRequest action,
            ActionPriority priority,
            String phase,
            int maxTicks) {
        long tick = currentTick(bot);
        UUID actionId = UUID.randomUUID();
        ActionMailbox.Submission submission =
                bot.manager().submitAction(
                        new ActionEnvelope(
                                actionId,
                                bot.player().getUUID(),
                                bot.player()
                                        .runtimeHandle()
                                        .generation(),
                                "gametest/p5/generic-menu/"
                                        + phase + "/" + actionId,
                                tick + maxTicks + 40L,
                                maxTicks,
                                action,
                                ActionOrigin.none()),
                        priority);
        P2GameTestSupport.require(
                submission.status()
                        == ActionMailbox.SubmissionStatus
                                .ENQUEUED,
                "Generic menu " + phase
                        + " submission was rejected: "
                        + submission.status());
        return new TrackedSubmission(
                actionId,
                submission.completion().orElseThrow());
    }

    private static long currentTick(TestBot bot) {
        return bot.player()
                .serverLevel()
                .getServer()
                .getTickCount();
    }

    private record TrackedSubmission(
            UUID actionId,
            CompletionStage<ActionOutcome> completion) {}

    private static final class SettlementObservation {
        private final long settlementStartedTick;
        private int lastPrefix = 2;
        private int direction;
        private boolean sawIntermediate;
        private long endpointTick = -1L;
        private long lastChangeTick = -1L;

        private SettlementObservation(
                long settlementStartedTick) {
            this.settlementStartedTick =
                    settlementStartedTick;
        }

        private void observe(
                int prefix, long tick, int finalPrefix) {
            if (prefix == lastPrefix) {
                return;
            }
            int delta = prefix - lastPrefix;
            if (direction == 0) {
                P2GameTestSupport.require(
                        prefix >= 0
                                && prefix <= finalPrefix
                                && delta == -1,
                        "Five-step cleanup did not choose the nearest INITIAL endpoint");
                direction = Integer.signum(delta);
            } else {
                P2GameTestSupport.require(
                        delta == -1,
                        "Menu cleanup changed direction or applied more than one adjacent click in a Tick");
            }
            P2GameTestSupport.require(
                    tick > lastChangeTick,
                    "Menu cleanup exposed two prefix changes in one Tick");
            lastChangeTick = tick;
            int endpoint = endpointPrefix();
            if (prefix == endpoint) {
                endpointTick = tick;
            } else {
                sawIntermediate = true;
            }
            lastPrefix = prefix;
        }

        private int endpointPrefix() {
            return direction == 0 ? -1 : 0;
        }

        private boolean sawIntermediate() {
            return sawIntermediate;
        }

        private long settlementStartedTick() {
            return settlementStartedTick;
        }

        private long endpointTick() {
            return endpointTick;
        }
    }
}
