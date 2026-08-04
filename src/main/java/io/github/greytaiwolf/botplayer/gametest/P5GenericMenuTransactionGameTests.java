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
import io.github.greytaiwolf.botplayer.kernel.BotConnection;
import io.github.greytaiwolf.botplayer.kernel.BotGamePacketListener;
import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
import io.github.greytaiwolf.botplayer.lifecycle.BotLifecycleManager.ListenerDisconnectDecision;
import io.github.greytaiwolf.botplayer.mixin.PlayerListAccessor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.world.level.storage.LevelResource;
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
    private static final String DISCONNECT_BATCH =
            "p5_generic_menu_disconnect";
    private static final String LISTENER_BODY_DRIFT_BATCH =
            "p5_generic_menu_listener_body_drift";
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

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = DISCONNECT_BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void directDisconnectWaitsForCrossTickMenuCleanupBeforeSave(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        TestBot bot = P5GameTestSupport.spawnFixedBot(
                helper, "P5MenuLeave");
        P2GameTestSupport.Cleanup cleanup =
                P5GameTestSupport.cleanup(bot);
        try {
            UUID botId = bot.player().getUUID();
            long generation = bot.player()
                    .runtimeHandle()
                    .generation();
            P2GameTestSupport.require(
                    bot.player()
                                    .connection
                                    .getConnection()
                            instanceof BotConnection,
                    "Generic menu disconnect fixture has no BotConnection");
            BotConnection connection =
                    (BotConnection)
                            bot.player()
                                    .connection
                                    .getConnection();
            PlayerListAccessor playerList =
                    (PlayerListAccessor)
                            (Object) helper.getLevel()
                                    .getServer()
                                    .getPlayerList();
            Path playerData = helper.getLevel()
                    .getServer()
                    .getWorldPath(
                            LevelResource.PLAYER_DATA_DIR)
                    .resolve(botId + ".dat");

            InventoryMenuSwapPlan plan =
                    prepareFiveStepPlan(bot);
            playerList.botplayer$saveExactPlayer(
                    bot.player());
            PersistedInventoryLayout baseline =
                    savedInventoryLayout(playerData);
            byte[] baselineBytes = readAllBytes(playerData);
            int expectedExperienceLevel =
                    baseline.experienceLevel() + 7;
            PersistedInventoryLayout expectedPersistence =
                    new PersistedInventoryLayout(
                            baseline.selectedSlot(),
                            expectedExperienceLevel,
                            baseline.stacks());
            TrackedSubmission menu = submit(
                    bot,
                    new WorldInteractionAction(
                            new WorldInteractionActionSpec
                                    .InventoryMenuSwap(plan)),
                    ActionPriority.OWNER_TASK,
                    "direct-disconnect",
                    80);

            P2GameTestSupport.awaitCondition(
                    helper,
                    WAIT_TICKS,
                    () -> currentPrefix(bot, plan) == 2,
                    "Direct disconnect fixture never exposed prefix two",
                    cleanup,
                    () -> beginDirectDisconnect(
                            helper,
                            bot,
                            plan,
                            menu,
                            connection,
                            playerList,
                            playerData,
                            baseline,
                            expectedPersistence,
                            baselineBytes,
                            botId,
                            generation,
                            cleanup));
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = LISTENER_BODY_DRIFT_BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void listenerBodyDriftDuringDirectPendingFailsClosedWithoutSave(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        TestBot bot = P5GameTestSupport.spawnFixedBot(
                helper, "P5MenuRace");
        P2GameTestSupport.Cleanup cleanup =
                P5GameTestSupport.cleanup(bot);
        try {
            BotServerPlayer predecessor = bot.player();
            UUID botId = predecessor.getUUID();
            long generation = predecessor
                    .runtimeHandle()
                    .generation();
            P2GameTestSupport.require(
                    predecessor.connection
                                    instanceof BotGamePacketListener
                            && predecessor.connection
                                            .getConnection()
                                    instanceof BotConnection,
                    "Listener-body-drift fixture has no Bot listener stack");
            BotGamePacketListener listener =
                    (BotGamePacketListener)
                            predecessor.connection;
            BotConnection connection =
                    (BotConnection)
                            listener.getConnection();
            PlayerListAccessor playerList =
                    (PlayerListAccessor)
                            (Object) helper.getLevel()
                                    .getServer()
                                    .getPlayerList();
            Path playerData = helper.getLevel()
                    .getServer()
                    .getWorldPath(
                            LevelResource.PLAYER_DATA_DIR)
                    .resolve(botId + ".dat");

            InventoryMenuSwapPlan plan =
                    prepareFiveStepPlan(bot);
            playerList.botplayer$saveExactPlayer(
                    predecessor);
            PersistedInventoryLayout baseline =
                    savedInventoryLayout(playerData);
            byte[] baselineBytes = readAllBytes(playerData);
            TrackedSubmission menu = submit(
                    bot,
                    new WorldInteractionAction(
                            new WorldInteractionActionSpec
                                    .InventoryMenuSwap(plan)),
                    ActionPriority.OWNER_TASK,
                    "listener-body-drift",
                    80);

            P2GameTestSupport.awaitCondition(
                    helper,
                    WAIT_TICKS,
                    () -> currentPrefix(bot, plan) == 2,
                    "Listener-body-drift fixture never exposed prefix two",
                    cleanup,
                    () -> injectListenerBodyDriftDuringDirectPending(
                            helper,
                            bot,
                            plan,
                            menu,
                            listener,
                            connection,
                            playerData,
                            baseline,
                            baselineBytes,
                            botId,
                            generation,
                            cleanup));
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    private static void injectListenerBodyDriftDuringDirectPending(
            GameTestHelper helper,
            TestBot bot,
            InventoryMenuSwapPlan plan,
            TrackedSubmission menu,
            BotGamePacketListener listener,
            BotConnection connection,
            Path playerData,
            PersistedInventoryLayout baseline,
            byte[] baselineBytes,
            UUID botId,
            long generation,
            P2GameTestSupport.Cleanup cleanup) {
        try {
            BotServerPlayer predecessor = bot.player();
            listener.disconnect(Component.literal(
                    "P5 direct-pending listener body drift fixture"));
            P2GameTestSupport.require(
                    connection.snapshot().open()
                            && predecessor
                                    .hasDisconnectPreSaveFence()
                            && !menu.completion()
                                    .toCompletableFuture()
                                    .isDone()
                            && currentPrefix(bot, plan) == 1,
                    "Listener body drift did not begin from a cross-Tick direct PENDING state");
            predecessor.experienceLevel =
                    baseline.experienceLevel() + 13;

            BotServerPlayer replacement =
                    BotServerPlayer.recreateForRespawn(
                            helper.getLevel().getServer(),
                            predecessor.serverLevel(),
                            predecessor.getGameProfile(),
                            ClientInformation.createDefault(),
                            predecessor);
            replacement.connection = listener;
            listener.player = replacement;
            P2GameTestSupport.require(
                    replacement.runtimeHandle()
                                    == predecessor.runtimeHandle()
                            && replacement.getUUID()
                                    .equals(botId)
                            && replacement
                                    .hasDisconnectPreSaveFence()
                            && listener.disconnectRequested()
                            && !listener.acceptsRuntimeAuthority()
                            && connection.snapshot().open(),
                    "Listener replacement did not inherit the direct save fence and listener");
            ListenerDisconnectDecision decision =
                    bot.manager().onDisconnecting(
                            replacement,
                            listener,
                            connection);

            P2GameTestSupport.require(
                    decision == ListenerDisconnectDecision.ABORTED,
                    "Listener body drift did not fail the direct retirement closed");
            P2GameTestSupport.require(
                    !connection.snapshot().open()
                            && !listener.acceptsRuntimeAuthority()
                            && predecessor.runtimeHandle()
                                    .player()
                                    .isEmpty()
                            && predecessor.runtimeHandle()
                                            .generation()
                                    == generation + 1L
                            && bot.manager()
                                    .resolveActive(
                                            botId, generation)
                                    .isEmpty()
                            && helper.getLevel()
                                            .getServer()
                                            .getPlayerList()
                                            .getPlayer(botId)
                                    == null
                            && helper.getLevel()
                                            .getPlayerByUUID(botId)
                                    == null,
                    "Listener-body-drift fail-closed path retained body, listener, or runtime authority");
            for (var level : helper.getLevel()
                    .getServer()
                    .getAllLevels()) {
                P2GameTestSupport.require(
                        level.getPlayerByUUID(botId) == null
                                && level.getEntity(botId) == null
                                && level.players()
                                        .stream()
                                        .noneMatch(player ->
                                                player.getUUID()
                                                        .equals(botId)),
                        "Listener-body-drift fail-closed path retained a level identity");
            }
            P2GameTestSupport.require(
                    Arrays.equals(
                            baselineBytes,
                            readAllBytes(playerData))
                            && savedInventoryLayout(playerData)
                                    .equals(baseline),
                    "Listener-body-drift fail-closed path persisted the temporary layout or XP marker");
            P2GameTestSupport.require(
                    !predecessor.hasDisconnectPreSaveFence()
                            && !replacement
                                    .hasDisconnectPreSaveFence()
                            && !predecessor
                                    .hasDeathRetirementSaveFence()
                            && !replacement
                                    .hasDeathRetirementSaveFence(),
                    "Listener-body-drift fail-closed path retained a transient save fence after exact removal");
            predecessor.getInventory().clearContent();
            predecessor.getInventory().setItem(
                    0, new ItemStack(Items.DIAMOND));
            replacement.getInventory().clearContent();
            replacement.getInventory().setItem(
                    1, new ItemStack(Items.DIAMOND));
            PlayerListAccessor playerList =
                    (PlayerListAccessor)
                            (Object) helper.getLevel()
                                    .getServer()
                                    .getPlayerList();
            playerList.botplayer$saveExactPlayer(predecessor);
            playerList.botplayer$saveExactPlayer(replacement);
            P2GameTestSupport.require(
                    Arrays.equals(
                                    baselineBytes,
                                    readAllBytes(playerData))
                            && savedInventoryLayout(playerData)
                                    .equals(baseline),
                    "A retired listener-drift body overwrote playerdata after transient fences cleared");

            P2GameTestSupport.awaitOutcome(
                    helper,
                    menu.completion(),
                    WAIT_TICKS,
                    cleanup,
                    outcome -> {
                        P2GameTestSupport.require(
                                outcome.state()
                                                == ActionState
                                                        .FAILED
                                        && outcome.failureCode()
                                                == ActionFailureCode
                                                        .UNSAFE_CONTROL_STATE,
                                "Listener-body-drift no-save retirement did not quarantine the orphaned cleanup: "
                                        + outcome);
                        cleanup.run();
                        helper.succeed();
                    });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            helper.fail(
                    exception.getMessage() == null
                            ? exception.toString()
                            : exception.getMessage());
        }
    }

    private static void beginDirectDisconnect(
            GameTestHelper helper,
            TestBot bot,
            InventoryMenuSwapPlan plan,
            TrackedSubmission menu,
            BotConnection connection,
            PlayerListAccessor playerList,
            Path playerData,
            PersistedInventoryLayout baseline,
            PersistedInventoryLayout expectedPersistence,
            byte[] baselineBytes,
            UUID botId,
            long generation,
            P2GameTestSupport.Cleanup cleanup) {
        try {
            long disconnectTick = currentTick(bot);
            bot.player()
                    .connection
                    .disconnect(Component.literal(
                            "P5 generic menu pre-save cleanup fixture"));
            P2GameTestSupport.require(
                    connection.snapshot().open()
                            && bot.player()
                                    .hasDisconnectPreSaveFence()
                            && !menu.completion()
                                    .toCompletableFuture()
                                    .isDone()
                            && currentPrefix(bot, plan) == 1,
                    "Direct listener disconnect did not consume exactly one cleanup step and remain pending");
            bot.player().experienceLevel =
                    expectedPersistence.experienceLevel();

            playerList.botplayer$saveExactPlayer(
                    bot.player());
            P2GameTestSupport.require(
                    Arrays.equals(
                            baselineBytes,
                            readAllBytes(playerData)),
                    "First exact save crossed the pending disconnect pre-save fence");
            playerList.botplayer$saveExactPlayer(
                    bot.player());
            P2GameTestSupport.require(
                    Arrays.equals(
                            baselineBytes,
                            readAllBytes(playerData))
                            && savedInventoryLayout(
                                            playerData)
                                    .equals(baseline),
                    "Second exact save changed the persisted baseline while cleanup was pending");

            P2GameTestSupport.awaitCondition(
                    helper,
                    WAIT_TICKS,
                    () -> !connection.snapshot().open()
                            && helper.getLevel()
                                            .getServer()
                                            .getPlayerList()
                                            .getPlayer(botId)
                                    == null
                            && helper.getLevel()
                                            .getPlayerByUUID(botId)
                                    == null
                            && Files.isRegularFile(playerData),
                    "Direct listener disconnect did not finish vanilla save and removal",
                    cleanup,
                    () -> verifyDirectDisconnect(
                            helper,
                            bot,
                            plan,
                            menu,
                            playerData,
                            expectedPersistence,
                            botId,
                            generation,
                            disconnectTick,
                            cleanup));
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            helper.fail(
                    exception.getMessage() == null
                            ? exception.toString()
                            : exception.getMessage());
        }
    }

    private static void verifyDirectDisconnect(
            GameTestHelper helper,
            TestBot bot,
            InventoryMenuSwapPlan plan,
            TrackedSubmission menu,
            Path playerData,
            PersistedInventoryLayout expectedPersistence,
            UUID botId,
            long generation,
            long disconnectTick,
            P2GameTestSupport.Cleanup cleanup) {
        try {
            InventoryMenuSnapshot actual =
                    MinecraftActionSnapshot.inventoryMenu(
                            bot.player());
            requireSafeControlPlane(
                    bot, plan.initialSnapshot(), actual);
            P2GameTestSupport.require(
                    plan.initialSnapshot()
                                    .layoutEqualsIgnoringState(
                                            actual)
                            && savedInventoryLayout(playerData)
                                    .equals(expectedPersistence)
                            && currentTick(bot) > disconnectTick
                            && bot.manager()
                                    .resolveActive(
                                            botId, generation)
                                    .isEmpty()
                            && !bot.player()
                                    .hasDisconnectPreSaveFence(),
                    "Direct disconnect did not restore and persist the exact initial menu layout");
            P2GameTestSupport.awaitOutcome(
                    helper,
                    menu.completion(),
                    WAIT_TICKS,
                    cleanup,
                    outcome -> {
                        P2GameTestSupport.require(
                                outcome.state()
                                                == ActionState
                                                        .CANCELLED
                                        && outcome.failureCode()
                                                == ActionFailureCode
                                                        .CANCELLED,
                                "Lifecycle retirement did not preserve the menu action cancellation outcome: "
                                        + outcome);
                        cleanup.run();
                        helper.succeed();
                    });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            helper.fail(
                    exception.getMessage() == null
                            ? exception.toString()
                            : exception.getMessage());
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

    private static byte[] readAllBytes(Path playerData) {
        try {
            return Files.readAllBytes(playerData);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not read persisted generic-menu BotPlayer data",
                    exception);
        }
    }

    private static PersistedInventoryLayout
            savedInventoryLayout(Path playerData) {
        try {
            CompoundTag saved = NbtIo.readCompressed(
                    playerData,
                    NbtAccounter.unlimitedHeap());
            ListTag inventory = saved.getList(
                    "Inventory", Tag.TAG_COMPOUND);
            List<CompoundTag> stacks =
                    new ArrayList<>(inventory.size());
            for (int index = 0;
                    index < inventory.size();
                    index++) {
                stacks.add(
                        inventory.getCompound(index).copy());
            }
            stacks.sort(Comparator.comparingInt(
                    stack -> Byte.toUnsignedInt(
                            stack.getByte("Slot"))));
            return new PersistedInventoryLayout(
                    saved.getInt("SelectedItemSlot"),
                    saved.getInt("XpLevel"),
                    List.copyOf(stacks));
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not parse persisted generic-menu BotPlayer data",
                    exception);
        }
    }

    private record TrackedSubmission(
            UUID actionId,
            CompletionStage<ActionOutcome> completion) {}

    private record PersistedInventoryLayout(
            int selectedSlot,
            int experienceLevel,
            List<CompoundTag> stacks) {}

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
