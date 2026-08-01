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
import io.github.greytaiwolf.botplayer.config.BotPlayerConfig;
import io.github.greytaiwolf.botplayer.gametest.P2GameTestSupport.TestBot;
import io.github.greytaiwolf.botplayer.kernel.BotConnection;
import io.github.greytaiwolf.botplayer.lifecycle.BotActionTargetStatus;
import io.github.greytaiwolf.botplayer.lifecycle.BotLifecycleState;
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
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 死亡只在 keepInventory=true 的当前纵切中等待跨 Tick 菜单补偿后释放保存。
 */
@GameTestHolder(BotPlayer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class P5DeathRetirementGameTests {
    private static final String BATCH =
            "p5_death_retirement_keep_inventory";
    private static final String UNSAFE_BATCH =
            "p5_death_retirement_unsafe";
    private static final int TIMEOUT_TICKS = 260;
    private static final int WAIT_TICKS = 180;

    private P5DeathRetirementGameTests() {}

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void deathWaitsForCrossTickCleanupBeforeReleasingSaveFence(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        boolean previousAutoRespawn =
                BotPlayerConfig.AUTO_RESPAWN.get();
        boolean previousKeepInventory = helper.getLevel()
                .getGameRules()
                .getBoolean(GameRules.RULE_KEEPINVENTORY);
        P2GameTestSupport.Cleanup cleanup =
                P5GameTestSupport.cleanup();
        cleanup.add(() -> BotPlayerConfig.AUTO_RESPAWN.set(
                previousAutoRespawn));
        cleanup.add(() -> helper.getLevel()
                .getGameRules()
                .getRule(GameRules.RULE_KEEPINVENTORY)
                .set(
                        previousKeepInventory,
                        helper.getLevel().getServer()));
        try {
            BotPlayerConfig.AUTO_RESPAWN.set(false);
            helper.getLevel()
                    .getGameRules()
                    .getRule(GameRules.RULE_KEEPINVENTORY)
                    .set(true, helper.getLevel().getServer());
            TestBot bot = P5GameTestSupport.spawnFixedBot(
                    helper, "P5DeathPending");
            UUID botId = bot.player().getUUID();
            long generation = bot.player()
                    .runtimeHandle()
                    .generation();
            P2GameTestSupport.require(
                    bot.player()
                                    .connection
                                    .getConnection()
                            instanceof BotConnection,
                    "Death-retirement fixture has no BotConnection");
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
            byte[] baselineBytes =
                    readAllBytes(playerData);
            cleanup.add(() -> restorePlayerData(
                    playerData, baselineBytes));
            P5GameTestSupport.trackBot(cleanup, bot);
            PersistedInventoryLayout expectedPersistence =
                    new PersistedInventoryLayout(
                            baseline.selectedSlot(),
                            baseline.experienceLevel() + 17,
                            baseline.stacks());
            TrackedSubmission menu = submit(
                    bot,
                    new WorldInteractionAction(
                            new WorldInteractionActionSpec
                                    .InventoryMenuSwap(plan)),
                    "death-retirement");

            P2GameTestSupport.awaitCondition(
                    helper,
                    WAIT_TICKS,
                    () -> currentPrefix(bot, plan) == 2,
                    "Death-retirement fixture never exposed prefix two",
                    cleanup,
                    () -> beginDeathRetirement(
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
            batch = UNSAFE_BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void unsafeDeathCleanupRemovesTheBodyWithoutSaving(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        boolean previousAutoRespawn =
                BotPlayerConfig.AUTO_RESPAWN.get();
        boolean previousKeepInventory = helper.getLevel()
                .getGameRules()
                .getBoolean(GameRules.RULE_KEEPINVENTORY);
        P2GameTestSupport.Cleanup cleanup =
                P5GameTestSupport.cleanup();
        cleanup.add(() -> BotPlayerConfig.AUTO_RESPAWN.set(
                previousAutoRespawn));
        cleanup.add(() -> helper.getLevel()
                .getGameRules()
                .getRule(GameRules.RULE_KEEPINVENTORY)
                .set(
                        previousKeepInventory,
                        helper.getLevel().getServer()));
        try {
            BotPlayerConfig.AUTO_RESPAWN.set(false);
            helper.getLevel()
                    .getGameRules()
                    .getRule(GameRules.RULE_KEEPINVENTORY)
                    .set(true, helper.getLevel().getServer());
            TestBot bot = P5GameTestSupport.spawnFixedBot(
                    helper, "P5DeathUnsafe");
            UUID botId = bot.player().getUUID();
            long generation = bot.player()
                    .runtimeHandle()
                    .generation();
            P2GameTestSupport.require(
                    bot.player()
                                    .connection
                                    .getConnection()
                            instanceof BotConnection,
                    "Unsafe-death fixture has no BotConnection");
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
            byte[] baselineBytes =
                    readAllBytes(playerData);
            cleanup.add(() -> restorePlayerData(
                    playerData, baselineBytes));
            P5GameTestSupport.trackBot(cleanup, bot);
            TrackedSubmission menu = submit(
                    bot,
                    new WorldInteractionAction(
                            new WorldInteractionActionSpec
                                    .InventoryMenuSwap(plan)),
                    "unsafe-death-retirement");

            P2GameTestSupport.awaitCondition(
                    helper,
                    WAIT_TICKS,
                    () -> currentPrefix(bot, plan) == 2,
                    "Unsafe-death fixture never exposed prefix two",
                    cleanup,
                    () -> beginUnsafeDeathRetirement(
                            helper,
                            bot,
                            plan,
                            menu,
                            connection,
                            playerList,
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

    private static void beginUnsafeDeathRetirement(
            GameTestHelper helper,
            TestBot bot,
            InventoryMenuSwapPlan plan,
            TrackedSubmission menu,
            BotConnection connection,
            PlayerListAccessor playerList,
            Path playerData,
            PersistedInventoryLayout baseline,
            byte[] baselineBytes,
            UUID botId,
            long generation,
            P2GameTestSupport.Cleanup cleanup) {
        try {
            bot.player().kill();
            P2GameTestSupport.require(
                    bot.player()
                                    .hasDeathRetirementSaveFence()
                            && currentPrefix(bot, plan) == 1
                            && !menu.completion()
                                    .toCompletableFuture()
                                    .isDone(),
                    "Unsafe death did not begin from one exact pending cleanup step");
            bot.player().experienceLevel =
                    baseline.experienceLevel() + 23;
            bot.player().getInventory().setItem(
                    11, new ItemStack(Items.DIAMOND));

            playerList.botplayer$saveExactPlayer(
                    bot.player());
            playerList.botplayer$saveExactPlayer(
                    bot.player());
            P2GameTestSupport.require(
                    Arrays.equals(
                            baselineBytes,
                            readAllBytes(playerData))
                            && savedInventoryLayout(
                                            playerData)
                                    .equals(baseline),
                    "Unsafe pending death crossed its pre-save fence");

            P2GameTestSupport.awaitCondition(
                    helper,
                    WAIT_TICKS,
                    () -> !connection.snapshot().open()
                            && menu.completion()
                                    .toCompletableFuture()
                                    .isDone()
                            && bot.player()
                                    .runtimeHandle()
                                    .player()
                                    .isEmpty()
                            && helper.getLevel()
                                            .getServer()
                                            .getPlayerList()
                                            .getPlayer(botId)
                                    == null
                            && helper.getLevel()
                                            .getPlayerByUUID(botId)
                                    == null,
                    "Unsafe death retirement did not remove its exact body",
                    cleanup,
                    () -> verifyUnsafeDeathRetirement(
                            helper,
                            bot,
                            menu,
                            playerData,
                            baseline,
                            botId,
                            generation,
                            cleanup));
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            helper.fail(safeMessage(exception));
        }
    }

    private static void verifyUnsafeDeathRetirement(
            GameTestHelper helper,
            TestBot bot,
            TrackedSubmission menu,
            Path playerData,
            PersistedInventoryLayout baseline,
            UUID botId,
            long generation,
            P2GameTestSupport.Cleanup cleanup) {
        try {
            P2GameTestSupport.require(
                    bot.player()
                                            .runtimeHandle()
                                            .generation()
                                    == generation + 1L
                            && bot.manager()
                                    .resolveActive(
                                            botId, generation)
                                    .isEmpty()
                            && !bot.player()
                                    .hasDeathRetirementSaveFence()
                            && savedInventoryLayout(playerData)
                                    .equals(baseline),
                    "Unsafe death retirement retained authority, fence, or persisted its temporary layout");
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
                        "Unsafe death retirement retained a level identity");
            }
            P2GameTestSupport.awaitOutcome(
                    helper,
                    menu.completion(),
                    WAIT_TICKS,
                    cleanup,
                    outcome -> {
                        P2GameTestSupport.require(
                                outcome.state()
                                                == ActionState.FAILED
                                        && outcome.failureCode()
                                                == ActionFailureCode
                                                        .UNSAFE_CONTROL_STATE,
                                "Unsafe death did not quarantine the menu action: "
                                        + outcome);
                        cleanup.run();
                        helper.succeed();
                    });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            helper.fail(safeMessage(exception));
        }
    }

    private static void beginDeathRetirement(
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
            AtomicInteger deathEvents = new AtomicInteger();
            AtomicReference<byte[]> preTailSaveBytes =
                    new AtomicReference<>();
            Consumer<LivingDeathEvent> listener = event -> {
                if (event.getEntity() != bot.player()) {
                    return;
                }
                deathEvents.incrementAndGet();
                playerList.botplayer$saveExactPlayer(
                        bot.player());
                preTailSaveBytes.set(
                        readAllBytes(playerData));
            };
            NeoForge.EVENT_BUS.addListener(listener);
            cleanup.add(() ->
                    NeoForge.EVENT_BUS.unregister(listener));
            long deathTick = currentTick(bot);
            bot.player().kill();
            P2GameTestSupport.require(
                    deathEvents.get() == 1
                            && Arrays.equals(
                                    baselineBytes,
                                    preTailSaveBytes.get())
                            && bot.player().isDeadOrDying()
                            && bot.player()
                                    .hasDeathRetirementSaveFence()
                            && connection.snapshot().open()
                            && !menu.completion()
                                    .toCompletableFuture()
                                    .isDone()
                            && currentPrefix(bot, plan) == 1,
                    "Death did not consume exactly one cleanup step and retain its save fence");
            bot.player().experienceLevel =
                    expectedPersistence.experienceLevel();

            playerList.botplayer$saveExactPlayer(
                    bot.player());
            P2GameTestSupport.require(
                    Arrays.equals(
                            baselineBytes,
                            readAllBytes(playerData)),
                    "First exact save crossed the pending death fence");
            playerList.botplayer$saveExactPlayer(
                    bot.player());
            P2GameTestSupport.require(
                    Arrays.equals(
                            baselineBytes,
                            readAllBytes(playerData))
                            && savedInventoryLayout(
                                            playerData)
                                    .equals(baseline),
                    "Second exact save changed player data while death cleanup was pending");

            P2GameTestSupport.awaitCondition(
                    helper,
                    WAIT_TICKS,
                    () -> menu.completion()
                                    .toCompletableFuture()
                                    .isDone()
                            && !bot.player()
                                    .hasDeathRetirementSaveFence()
                            && currentPrefix(bot, plan) == 0,
                    "Death retirement did not reach its exact initial endpoint",
                    cleanup,
                    () -> verifyCompletedDeathRetirement(
                            helper,
                            bot,
                            plan,
                            menu,
                            connection,
                            playerList,
                            playerData,
                            expectedPersistence,
                            botId,
                            generation,
                            deathTick,
                            cleanup));
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            helper.fail(safeMessage(exception));
        }
    }

    private static void verifyCompletedDeathRetirement(
            GameTestHelper helper,
            TestBot bot,
            InventoryMenuSwapPlan plan,
            TrackedSubmission menu,
            BotConnection connection,
            PlayerListAccessor playerList,
            Path playerData,
            PersistedInventoryLayout expectedPersistence,
            UUID botId,
            long generation,
            long deathTick,
            P2GameTestSupport.Cleanup cleanup) {
        try {
            InventoryMenuSnapshot actual =
                    MinecraftActionSnapshot.inventoryMenu(
                            bot.player());
            long exactSnapshots = bot.manager()
                    .snapshots()
                    .stream()
                    .filter(snapshot -> snapshot.botId().equals(botId))
                    .count();
            var retainedSnapshot = bot.manager()
                    .snapshots()
                    .stream()
                    .filter(snapshot -> snapshot.botId().equals(botId))
                    .findFirst()
                    .orElse(null);
            P2GameTestSupport.require(
                    plan.initialSnapshot()
                                    .layoutEqualsIgnoringState(
                                            actual)
                            && actual.cursor().isEmpty()
                            && actual.inventoryMultisetEquals(
                                    plan.initialSnapshot())
                            && currentTick(bot) > deathTick
                            && bot.player()
                                            .runtimeHandle()
                                            .generation()
                                    == generation
                            && bot.player()
                                            .runtimeHandle()
                                            .player()
                                            .orElse(null)
                                    == bot.player()
                            && bot.manager()
                                    .resolveActive(
                                            botId, generation)
                                    .isEmpty()
                            && exactSnapshots == 1L
                            && retainedSnapshot != null
                            && retainedSnapshot.generation()
                                    == generation
                            && retainedSnapshot.state()
                                    == BotLifecycleState.DEAD
                            && bot.manager()
                                            .inspectActionTarget(
                                                    botId,
                                                    generation)
                                            .status()
                                    == BotActionTargetStatus.NOT_ACTIVE
                            && helper.getLevel()
                                            .getServer()
                                            .getPlayerList()
                                            .getPlayer(botId)
                                    == bot.player()
                            && helper.getLevel()
                                            .getPlayerByUUID(botId)
                                    == bot.player()
                            && connection.snapshot().open(),
                    "Completed death retirement changed authority, generation, or menu layout");

            UUID rejectedActionId = UUID.randomUUID();
            ActionMailbox.Submission rejected =
                    bot.manager().submitAction(
                            new ActionEnvelope(
                                    rejectedActionId,
                                    botId,
                                    generation,
                                    "gametest/p5/death-retirement/closed/"
                                            + rejectedActionId,
                                    currentTick(bot) + 20L,
                                    5,
                                    new StopAction(),
                                    ActionOrigin.none()),
                            ActionPriority.OWNER_TASK);
            P2GameTestSupport.require(
                    rejected.status()
                                            == ActionMailbox
                                                    .SubmissionStatus
                                                    .BOT_GENERATION_CLOSED
                            && rejected.completion().isEmpty(),
                    "Completed death retirement reopened old-generation action ingress");

            playerList.botplayer$saveExactPlayer(
                    bot.player());
            P2GameTestSupport.require(
                    savedInventoryLayout(playerData)
                            .equals(expectedPersistence),
                    "Completed death retirement did not release its own fence for a real save");
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
                                "Death retirement changed the action cancellation outcome: "
                                        + outcome);
                        cleanup.run();
                        helper.succeed();
                    });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            helper.fail(safeMessage(exception));
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
        bot.player().inventoryMenu.setCarried(
                ItemStack.EMPTY);
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

    private static int currentPrefix(
            TestBot bot, InventoryMenuSwapPlan plan) {
        InventoryMenuSnapshot actual =
                MinecraftActionSnapshot.inventoryMenu(
                        bot.player());
        OptionalInt prefix =
                plan.matchingPrefixIgnoringState(actual);
        return prefix.orElseThrow(() ->
                new IllegalStateException(
                        "Death retirement left its exact menu prefixes"));
    }

    private static TrackedSubmission submit(
            TestBot bot,
            io.github.greytaiwolf.botplayer.action.ActionRequest action,
            String phase) {
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
                                "gametest/p5/death-retirement/"
                                        + phase + "/" + actionId,
                                tick + 120L,
                                80,
                                action,
                                ActionOrigin.none()),
                        ActionPriority.OWNER_TASK);
        P2GameTestSupport.require(
                submission.status()
                        == ActionMailbox.SubmissionStatus.ENQUEUED,
                "Death-retirement action was rejected: "
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
                    "Could not read death-retirement player data",
                    exception);
        }
    }

    private static void restorePlayerData(
            Path playerData, byte[] baselineBytes) {
        try {
            Files.write(playerData, baselineBytes);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not restore death-retirement player data",
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
                    "Could not parse death-retirement player data",
                    exception);
        }
    }

    private static String safeMessage(Throwable throwable) {
        return throwable.getMessage() == null
                ? throwable.toString()
                : throwable.getMessage();
    }

    private record TrackedSubmission(
            UUID actionId,
            CompletionStage<ActionOutcome> completion) {}

    private record PersistedInventoryLayout(
            int selectedSlot,
            int experienceLevel,
            List<CompoundTag> stacks) {}
}
