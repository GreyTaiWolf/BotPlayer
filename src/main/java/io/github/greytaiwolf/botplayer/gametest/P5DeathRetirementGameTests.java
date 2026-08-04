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
import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
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
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 死亡退役分别验收保留布局的跨 Tick 补偿，以及原版已消费布局的耐久交接。
 */
@GameTestHolder(BotPlayer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class P5DeathRetirementGameTests {
    private static final String BATCH =
            "p5_death_retirement_keep_inventory";
    private static final String UNSAFE_BATCH =
            "p5_death_retirement_unsafe";
    private static final String CONSUMED_BATCH =
            "p5_death_retirement_vanilla_consumed";
    private static final String SPECTATOR_BATCH =
            "p5_death_retirement_spectator_preserved";
    private static final String REENTRY_BATCH =
            "p5_death_retirement_reentry";
    private static final int TIMEOUT_TICKS = 420;
    private static final int WAIT_TICKS = 180;
    private static final int WAL_TIMEOUT_TICKS = 540;
    private static final int WAL_WAIT_TICKS = 300;
    private static final String DEATH_HANDOFF_TAG =
            "BotPlayerDeathHandoffV2";

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

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = CONSUMED_BATCH,
            timeoutTicks = WAL_TIMEOUT_TICKS)
    public static void vanillaConsumedDeathCommitsEmptySuccessor(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        helper.killAllEntitiesOfClass(ItemEntity.class);
        boolean previousAutoRespawn =
                BotPlayerConfig.AUTO_RESPAWN.get();
        int previousRespawnDelay =
                BotPlayerConfig.RESPAWN_DELAY_TICKS.get();
        boolean previousKeepInventory = helper.getLevel()
                .getGameRules()
                .getBoolean(GameRules.RULE_KEEPINVENTORY);
        P2GameTestSupport.Cleanup cleanup =
                P5GameTestSupport.cleanup();
        cleanup.add(() -> BotPlayerConfig.AUTO_RESPAWN.set(
                previousAutoRespawn));
        cleanup.add(() -> BotPlayerConfig.RESPAWN_DELAY_TICKS.set(
                previousRespawnDelay));
        cleanup.add(() -> helper.getLevel()
                .getGameRules()
                .getRule(GameRules.RULE_KEEPINVENTORY)
                .set(
                        previousKeepInventory,
                        helper.getLevel().getServer()));
        try {
            BotPlayerConfig.AUTO_RESPAWN.set(true);
            BotPlayerConfig.RESPAWN_DELAY_TICKS.set(0);
            helper.getLevel()
                    .getGameRules()
                    .getRule(GameRules.RULE_KEEPINVENTORY)
                    .set(false, helper.getLevel().getServer());
            TestBot bot = P5GameTestSupport.spawnFixedBot(
                    helper, "P5WalDrop");
            BotServerPlayer predecessor = bot.player();
            UUID botId = predecessor.getUUID();
            long generation = predecessor
                    .runtimeHandle()
                    .generation();
            Path playerDataDirectory = helper.getLevel()
                    .getServer()
                    .getWorldPath(LevelResource.PLAYER_DATA_DIR);
            Path primary = playerDataDirectory.resolve(
                    botId + ".dat");
            Path backup = playerDataDirectory.resolve(
                    botId + ".dat_old");
            Path marker = playerDataDirectory
                    .resolve("botplayer-death-tombstones")
                    .resolve(botId + ".death-v2");
            PlayerDataFiles originalFiles =
                    snapshotPlayerData(primary, backup);
            cleanup.add(() -> restorePlayerData(
                    primary, backup, originalFiles));
            cleanup.add(() -> deleteIfPresent(marker));
            P5GameTestSupport.trackBot(cleanup, bot);
            cleanup.add(() -> helper.killAllEntitiesOfClass(
                    ItemEntity.class));

            InventoryMenuSwapPlan plan =
                    prepareFiveStepPlan(
                            bot,
                            new ItemStack(Items.DIAMOND));
            predecessor.experienceLevel = 7;
            predecessor.totalExperience = 123;
            predecessor.experienceProgress = 0.375F;
            TrackedSubmission menu = submit(
                    bot,
                    new WorldInteractionAction(
                            new WorldInteractionActionSpec
                                    .InventoryMenuSwap(plan)),
                    "vanilla-consumed");

            P2GameTestSupport.awaitCondition(
                    helper,
                    WAIT_TICKS,
                    () -> currentPrefix(bot, plan) == 2,
                    "Vanilla-consumed fixture never exposed prefix two",
                    cleanup,
                    () -> beginVanillaConsumedDeath(
                            helper,
                            bot,
                            predecessor,
                            menu,
                            primary,
                            backup,
                            marker,
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
            batch = SPECTATOR_BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void spectatorDeathUsesPreservedCleanup(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        helper.killAllEntitiesOfClass(ItemEntity.class);
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
                    .set(false, helper.getLevel().getServer());
            TestBot bot = P5GameTestSupport.spawnFixedBot(
                    helper, "P5SpecKeep");
            BotServerPlayer player = bot.player();
            UUID botId = player.getUUID();
            long generation = player.runtimeHandle().generation();
            Path playerDataDirectory = helper.getLevel()
                    .getServer()
                    .getWorldPath(LevelResource.PLAYER_DATA_DIR);
            Path primary = playerDataDirectory.resolve(
                    botId + ".dat");
            Path backup = playerDataDirectory.resolve(
                    botId + ".dat_old");
            Path marker = playerDataDirectory
                    .resolve("botplayer-death-tombstones")
                    .resolve(botId + ".death-v2");
            PlayerDataFiles originalFiles =
                    snapshotPlayerData(primary, backup);
            cleanup.add(() -> restorePlayerData(
                    primary, backup, originalFiles));
            cleanup.add(() -> deleteIfPresent(marker));
            P5GameTestSupport.trackBot(cleanup, bot);
            cleanup.add(() -> helper.killAllEntitiesOfClass(
                    ItemEntity.class));

            InventoryMenuSwapPlan plan =
                    prepareFiveStepPlan(bot);
            PlayerListAccessor playerList =
                    (PlayerListAccessor)
                            (Object) helper.getLevel()
                                    .getServer()
                                    .getPlayerList();
            playerList.botplayer$saveExactPlayer(player);
            PersistedInventoryLayout baseline =
                    savedInventoryLayout(primary);
            TrackedSubmission menu = submit(
                    bot,
                    new WorldInteractionAction(
                            new WorldInteractionActionSpec
                                    .InventoryMenuSwap(plan)),
                    "spectator-preserved");

            P2GameTestSupport.awaitCondition(
                    helper,
                    WAIT_TICKS,
                    () -> currentPrefix(bot, plan) == 2,
                    "Spectator-preserved fixture never exposed prefix two",
                    cleanup,
                    () -> beginSpectatorPreservedDeath(
                            helper,
                            bot,
                            plan,
                            menu,
                            playerList,
                            primary,
                            backup,
                            marker,
                            baseline,
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
            batch = REENTRY_BATCH,
            timeoutTicks = WAL_TIMEOUT_TICKS)
    public static void latePredecessorDeathCannotDisturbActiveSuccessor(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        helper.killAllEntitiesOfClass(ItemEntity.class);
        helper.killAllEntitiesOfClass(ExperienceOrb.class);
        boolean previousAutoRespawn =
                BotPlayerConfig.AUTO_RESPAWN.get();
        int previousRespawnDelay =
                BotPlayerConfig.RESPAWN_DELAY_TICKS.get();
        boolean previousKeepInventory = helper.getLevel()
                .getGameRules()
                .getBoolean(GameRules.RULE_KEEPINVENTORY);
        P2GameTestSupport.Cleanup cleanup =
                P5GameTestSupport.cleanup();
        cleanup.add(() -> BotPlayerConfig.AUTO_RESPAWN.set(
                previousAutoRespawn));
        cleanup.add(() -> BotPlayerConfig.RESPAWN_DELAY_TICKS.set(
                previousRespawnDelay));
        cleanup.add(() -> helper.getLevel()
                .getGameRules()
                .getRule(GameRules.RULE_KEEPINVENTORY)
                .set(
                        previousKeepInventory,
                        helper.getLevel().getServer()));
        try {
            BotPlayerConfig.AUTO_RESPAWN.set(true);
            BotPlayerConfig.RESPAWN_DELAY_TICKS.set(0);
            helper.getLevel()
                    .getGameRules()
                    .getRule(GameRules.RULE_KEEPINVENTORY)
                    .set(false, helper.getLevel().getServer());
            TestBot bot = P5GameTestSupport.spawnFixedBot(
                    helper, "P5LateOldDie");
            BotServerPlayer predecessor = bot.player();
            UUID botId = predecessor.getUUID();
            long generation = predecessor
                    .runtimeHandle()
                    .generation();
            Path playerDataDirectory = helper.getLevel()
                    .getServer()
                    .getWorldPath(LevelResource.PLAYER_DATA_DIR);
            Path primary = playerDataDirectory.resolve(
                    botId + ".dat");
            Path backup = playerDataDirectory.resolve(
                    botId + ".dat_old");
            Path marker = playerDataDirectory
                    .resolve("botplayer-death-tombstones")
                    .resolve(botId + ".death-v2");
            PlayerDataFiles originalFiles =
                    snapshotPlayerData(primary, backup);
            cleanup.add(() -> restorePlayerData(
                    primary, backup, originalFiles));
            cleanup.add(() -> deleteIfPresent(marker));
            P5GameTestSupport.trackBot(cleanup, bot);
            cleanup.add(() -> helper.killAllEntitiesOfClass(
                    ItemEntity.class));
            cleanup.add(() -> helper.killAllEntitiesOfClass(
                    ExperienceOrb.class));

            predecessor.getInventory().clearContent();
            predecessor.inventoryMenu.setCarried(ItemStack.EMPTY);
            predecessor.inventoryMenu.broadcastChanges();
            predecessor.kill();

            P2GameTestSupport.awaitCondition(
                    helper,
                    WAL_WAIT_TICKS,
                    () -> bot.manager()
                                    .resolveActive(
                                            botId,
                                            generation + 1L)
                                    .filter(player ->
                                            player != predecessor)
                                    .isPresent()
                            && Files.isRegularFile(primary)
                            && Files.isRegularFile(backup)
                            && !Files.exists(marker),
                    "Initial vanilla-consumed death did not activate its successor",
                    cleanup,
                    () -> beginLatePredecessorDeath(
                            helper,
                            bot,
                            predecessor,
                            primary,
                            backup,
                            marker,
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
            batch = REENTRY_BATCH,
            timeoutTicks = WAL_TIMEOUT_TICKS)
    public static void itemDropCallbackReentrantDeathConsumesOnlyOnce(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        helper.killAllEntitiesOfClass(ItemEntity.class);
        helper.killAllEntitiesOfClass(ExperienceOrb.class);
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
                    .set(false, helper.getLevel().getServer());
            TestBot bot = P5GameTestSupport.spawnFixedBot(
                    helper, "P5DropReentry");
            BotServerPlayer predecessor = bot.player();
            UUID botId = predecessor.getUUID();
            long generation = predecessor
                    .runtimeHandle()
                    .generation();
            Path playerDataDirectory = helper.getLevel()
                    .getServer()
                    .getWorldPath(LevelResource.PLAYER_DATA_DIR);
            Path primary = playerDataDirectory.resolve(
                    botId + ".dat");
            Path backup = playerDataDirectory.resolve(
                    botId + ".dat_old");
            Path marker = playerDataDirectory
                    .resolve("botplayer-death-tombstones")
                    .resolve(botId + ".death-v2");
            PlayerDataFiles originalFiles =
                    snapshotPlayerData(primary, backup);
            cleanup.add(() -> restorePlayerData(
                    primary, backup, originalFiles));
            cleanup.add(() -> deleteIfPresent(marker));
            P5GameTestSupport.trackBot(cleanup, bot);
            cleanup.add(() -> helper.killAllEntitiesOfClass(
                    ItemEntity.class));
            cleanup.add(() -> helper.killAllEntitiesOfClass(
                    ExperienceOrb.class));

            predecessor.getInventory().clearContent();
            predecessor.getInventory().setItem(
                    0, new ItemStack(Items.DIAMOND));
            predecessor.getInventory().setItem(
                    1, new ItemStack(Items.EMERALD));
            predecessor.inventoryMenu.setCarried(ItemStack.EMPTY);
            predecessor.inventoryMenu.broadcastChanges();
            predecessor.experienceLevel = 1;
            predecessor.totalExperience = 7;
            predecessor.experienceProgress = 0.0F;

            AtomicInteger reentrantDeaths = new AtomicInteger();
            Consumer<EntityJoinLevelEvent> listener = event -> {
                if (event.getLevel() != predecessor.serverLevel()
                        || !(event.getEntity()
                                instanceof ItemEntity item)
                        || item.distanceToSqr(predecessor) > 64.0D
                        || (!item.getItem().is(Items.DIAMOND)
                                && !item.getItem()
                                        .is(Items.EMERALD))
                        || !reentrantDeaths.compareAndSet(0, 1)) {
                    return;
                }
                predecessor.die(
                        predecessor.damageSources().generic());
            };
            NeoForge.EVENT_BUS.addListener(listener);
            cleanup.add(() ->
                    NeoForge.EVENT_BUS.unregister(listener));

            predecessor.die(
                    predecessor.damageSources().generic());
            P2GameTestSupport.require(
                    reentrantDeaths.get() == 1
                            && exactNearbyItemCount(
                                            predecessor,
                                            Items.DIAMOND)
                                    == 1
                            && exactNearbyItemCount(
                                            predecessor,
                                            Items.EMERALD)
                                    == 1
                            && nearbyExperienceTotal(predecessor)
                                    == 7,
                    "Synchronous item-join death reentry duplicated or lost item/XP drops");

            P2GameTestSupport.awaitCondition(
                    helper,
                    WAL_WAIT_TICKS,
                    () -> bot.manager()
                                    .snapshots()
                                    .stream()
                                    .anyMatch(snapshot ->
                                            snapshot.botId()
                                                            .equals(botId)
                                                    && snapshot.generation()
                                                            == generation
                                                    && snapshot.state()
                                                            == BotLifecycleState.DEAD)
                            && !predecessor
                                    .hasDeathRetirementSaveFence()
                            && Files.isRegularFile(primary)
                            && Files.isRegularFile(backup)
                            && !Files.exists(marker),
                    "Reentrant vanilla-consumed death did not settle its durable retirement",
                    cleanup,
                    () -> verifyReentrantDeathDrops(
                            helper,
                            bot,
                            predecessor,
                            marker,
                            botId,
                            generation,
                            reentrantDeaths,
                            cleanup));
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    private static void beginVanillaConsumedDeath(
            GameTestHelper helper,
            TestBot bot,
            BotServerPlayer predecessor,
            TrackedSubmission menu,
            Path primary,
            Path backup,
            Path marker,
            UUID botId,
            long generation,
            P2GameTestSupport.Cleanup cleanup) {
        try {
            predecessor.kill();
            int diamondDrops = predecessor.serverLevel()
                    .getEntitiesOfClass(
                            ItemEntity.class,
                            predecessor.getBoundingBox()
                                    .inflate(8.0D),
                            item -> item.getItem()
                                    .is(Items.DIAMOND))
                    .stream()
                    .mapToInt(item -> item.getItem().getCount())
                    .sum();
            P2GameTestSupport.require(
                    diamondDrops == 1
                            && predecessor
                                    .getInventory()
                                    .isEmpty(),
                    "Vanilla death did not consume and drop the exact diamond inventory");

            P2GameTestSupport.awaitCondition(
                    helper,
                    WAL_WAIT_TICKS,
                    () -> bot.manager()
                                    .resolveActive(
                                            botId,
                                            generation + 1L)
                                    .isPresent()
                            && menu.completion()
                                    .toCompletableFuture()
                                    .isDone()
                            && Files.isRegularFile(primary)
                            && Files.isRegularFile(backup)
                            && !Files.exists(marker),
                    "Vanilla-consumed death did not commit and activate its successor",
                    cleanup,
                    () -> verifyVanillaConsumedDeath(
                            helper,
                            bot,
                            predecessor,
                            menu,
                            primary,
                            backup,
                            marker,
                            botId,
                            generation,
                            cleanup));
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            helper.fail(safeMessage(exception));
        }
    }

    private static void verifyVanillaConsumedDeath(
            GameTestHelper helper,
            TestBot bot,
            BotServerPlayer predecessor,
            TrackedSubmission menu,
            Path primary,
            Path backup,
            Path marker,
            UUID botId,
            long generation,
            P2GameTestSupport.Cleanup cleanup) {
        try {
            BotServerPlayer successor = bot.manager()
                    .resolveActive(botId, generation + 1L)
                    .orElseThrow();
            CompoundTag primaryData = readPlayerData(primary);
            CompoundTag backupData = readPlayerData(backup);
            P2GameTestSupport.require(
                    successor != predecessor
                            && successor.runtimeHandle()
                                    == predecessor.runtimeHandle()
                            && successor.runtimeHandle()
                                    .generation()
                                    == generation + 1L
                            && successor.getInventory().isEmpty()
                            && successor.experienceLevel == 0
                            && successor.totalExperience == 0
                            && Float.floatToRawIntBits(
                                            successor.experienceProgress)
                                    == Float.floatToRawIntBits(0.0F)
                            && helper.getLevel()
                                            .getServer()
                                            .getPlayerList()
                                            .getPlayer(botId)
                                    == successor
                            && helper.getLevel()
                                            .getPlayerByUUID(botId)
                                    == successor,
                    "Vanilla-consumed successor did not become the empty ACTIVE next generation");
            P2GameTestSupport.require(
                    isCanonicalEmptyAlive(primaryData)
                            && isCanonicalEmptyAlive(backupData)
                            && !Files.exists(marker),
                    "Vanilla-consumed successor playerdata retained inventory, XP, handoff, or tombstone");
            P2GameTestSupport.awaitOutcome(
                    helper,
                    menu.completion(),
                    WAIT_TICKS,
                    cleanup,
                    outcome -> {
                        P2GameTestSupport.require(
                                outcome.state()
                                                == ActionState.CANCELLED
                                        && outcome.failureCode()
                                                == ActionFailureCode
                                                        .CANCELLED
                                        && outcome.safeSummary()
                                                .contains(
                                                        "vanilla death consumed"),
                                "Active menu did not close through VANILLA_DEATH_CONSUMED: "
                                        + outcome);
                        cleanup.run();
                        helper.succeed();
                    });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            helper.fail(safeMessage(exception));
        }
    }

    private static void beginSpectatorPreservedDeath(
            GameTestHelper helper,
            TestBot bot,
            InventoryMenuSwapPlan plan,
            TrackedSubmission menu,
            PlayerListAccessor playerList,
            Path primary,
            Path backup,
            Path marker,
            PersistedInventoryLayout baseline,
            UUID botId,
            long generation,
            P2GameTestSupport.Cleanup cleanup) {
        try {
            bot.player().setGameMode(GameType.SPECTATOR);
            long deathTick = currentTick(bot);
            bot.player().kill();
            P2GameTestSupport.require(
                    bot.player().isDeadOrDying()
                            && bot.player()
                                    .hasDeathRetirementSaveFence()
                            && currentPrefix(bot, plan) == 1
                            && !menu.completion()
                                    .toCompletableFuture()
                                    .isDone()
                            && !Files.exists(marker)
                            && nearbyItemDrops(bot.player()).isEmpty(),
                    "Pre-death spectator did not enter the PRESERVED cross-tick cleanup path");

            P2GameTestSupport.awaitCondition(
                    helper,
                    WAIT_TICKS,
                    () -> currentPrefix(bot, plan) == 0
                            && menu.completion()
                                    .toCompletableFuture()
                                    .isDone()
                            && !bot.player()
                                    .hasDeathRetirementSaveFence(),
                    "Spectator PRESERVED cleanup did not roll prefix one back to zero",
                    cleanup,
                    () -> verifySpectatorPreservedDeath(
                            helper,
                            bot,
                            plan,
                            menu,
                            playerList,
                            primary,
                            backup,
                            marker,
                            baseline,
                            botId,
                            generation,
                            deathTick,
                            cleanup));
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            helper.fail(safeMessage(exception));
        }
    }

    private static void verifySpectatorPreservedDeath(
            GameTestHelper helper,
            TestBot bot,
            InventoryMenuSwapPlan plan,
            TrackedSubmission menu,
            PlayerListAccessor playerList,
            Path primary,
            Path backup,
            Path marker,
            PersistedInventoryLayout baseline,
            UUID botId,
            long generation,
            long deathTick,
            P2GameTestSupport.Cleanup cleanup) {
        try {
            InventoryMenuSnapshot actual =
                    MinecraftActionSnapshot.inventoryMenu(
                            bot.player());
            P2GameTestSupport.require(
                    plan.initialSnapshot()
                                    .layoutEqualsIgnoringState(actual)
                            && currentTick(bot) > deathTick
                            && bot.player()
                                    .runtimeHandle()
                                    .generation()
                                    == generation
                            && bot.manager()
                                    .inspectActionTarget(
                                            botId, generation)
                                    .status()
                                    == BotActionTargetStatus.NOT_ACTIVE
                            && nearbyItemDrops(bot.player()).isEmpty()
                            && !Files.exists(marker),
                    "Spectator PRESERVED cleanup changed generation, layout, or produced drops");
            playerList.botplayer$saveExactPlayer(bot.player());
            playerList.botplayer$saveExactPlayer(bot.player());
            P2GameTestSupport.require(
                    savedInventoryLayout(primary).equals(baseline)
                            && savedInventoryLayout(backup)
                                    .equals(baseline)
                            && !readPlayerData(primary)
                                    .contains(DEATH_HANDOFF_TAG)
                            && !readPlayerData(backup)
                                    .contains(DEATH_HANDOFF_TAG),
                    "Spectator PRESERVED cleanup did not persist its restored inventory without a handoff");
            P2GameTestSupport.awaitOutcome(
                    helper,
                    menu.completion(),
                    WAIT_TICKS,
                    cleanup,
                    outcome -> {
                        P2GameTestSupport.require(
                                outcome.state()
                                                == ActionState.CANCELLED
                                        && outcome.failureCode()
                                                == ActionFailureCode
                                                        .CANCELLED
                                        && !outcome.safeSummary()
                                                .contains(
                                                        "vanilla death consumed"),
                                "Spectator PRESERVED action used the consumed-death terminal path: "
                                        + outcome);
                        cleanup.run();
                        helper.succeed();
                    });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            helper.fail(safeMessage(exception));
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
        return prepareFiveStepPlan(
                bot, new ItemStack(Items.COAL));
    }

    private static InventoryMenuSwapPlan prepareFiveStepPlan(
            TestBot bot, ItemStack fifthStack) {
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
                11, fifthStack.copy());
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

    private static List<ItemEntity> nearbyItemDrops(
            BotServerPlayer player) {
        return player.serverLevel().getEntitiesOfClass(
                ItemEntity.class,
                player.getBoundingBox().inflate(8.0D));
    }

    private static boolean isCanonicalEmptyAlive(
            CompoundTag playerData) {
        return playerData.getList(
                                "Inventory",
                                Tag.TAG_COMPOUND)
                        .isEmpty()
                && Float.isFinite(playerData.getFloat("Health"))
                && playerData.getFloat("Health") > 0.0F
                && playerData.getInt("XpLevel") == 0
                && playerData.getInt("XpTotal") == 0
                && Float.floatToRawIntBits(
                                playerData.getFloat("XpP"))
                        == Float.floatToRawIntBits(0.0F)
                && !playerData.contains(DEATH_HANDOFF_TAG);
    }

    private static CompoundTag readPlayerData(Path playerData) {
        try {
            return NbtIo.readCompressed(
                    playerData,
                    NbtAccounter.create(4L * 1024L * 1024L));
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not parse death-retirement player data",
                    exception);
        }
    }

    private static PlayerDataFiles snapshotPlayerData(
            Path primary, Path backup) {
        return new PlayerDataFiles(
                snapshotFile(primary), snapshotFile(backup));
    }

    private static FileSnapshot snapshotFile(Path file) {
        try {
            return Files.exists(file)
                    ? new FileSnapshot(
                            true, Files.readAllBytes(file))
                    : new FileSnapshot(false, new byte[0]);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not snapshot death-retirement player data",
                    exception);
        }
    }

    private static void restorePlayerData(
            Path primary,
            Path backup,
            PlayerDataFiles originalFiles) {
        restoreFile(primary, originalFiles.primary());
        restoreFile(backup, originalFiles.backup());
    }

    private static void restoreFile(
            Path file, FileSnapshot snapshot) {
        try {
            if (snapshot.existed()) {
                Files.write(file, snapshot.bytes());
            } else {
                Files.deleteIfExists(file);
            }
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not restore death-retirement player data",
                    exception);
        }
    }

    private static void deleteIfPresent(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not remove death-retirement tombstone",
                    exception);
        }
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

    private record PlayerDataFiles(
            FileSnapshot primary, FileSnapshot backup) {}

    private record FileSnapshot(boolean existed, byte[] bytes) {
        private FileSnapshot {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }
}
