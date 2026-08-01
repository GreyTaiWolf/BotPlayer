package io.github.greytaiwolf.botplayer.gametest;

import io.github.greytaiwolf.botplayer.BotPlayer;
import io.github.greytaiwolf.botplayer.action.ActionEnvelope;
import io.github.greytaiwolf.botplayer.action.ActionMailbox;
import io.github.greytaiwolf.botplayer.action.ActionOrigin;
import io.github.greytaiwolf.botplayer.action.ActionPriority;
import io.github.greytaiwolf.botplayer.action.ActionState;
import io.github.greytaiwolf.botplayer.action.StopAction;
import io.github.greytaiwolf.botplayer.config.BotPlayerConfig;
import io.github.greytaiwolf.botplayer.gametest.P2GameTestSupport.TestBot;
import io.github.greytaiwolf.botplayer.kernel.BotConnection;
import io.github.greytaiwolf.botplayer.kernel.BotGamePacketListener;
import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
import io.github.greytaiwolf.botplayer.lifecycle.BotActionTargetStatus;
import io.github.greytaiwolf.botplayer.lifecycle.BotLifecycleManager.ListenerDisconnectDecision;
import io.github.greytaiwolf.botplayer.safety.HazardType;
import io.github.greytaiwolf.botplayer.safety.SafetyIntervention;
import io.github.greytaiwolf.botplayer.safety.SafetyState;
import io.github.greytaiwolf.botplayer.skill.core.SkillFailureCode;
import io.github.greytaiwolf.botplayer.skill.core.SkillRunState;
import io.github.greytaiwolf.botplayer.skill.runtime.SurvivalSkillKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * P5A 主动进食验收：安全面只负责移交，恢复动作必须走真实玩家交互路径。
 */
@GameTestHolder(BotPlayer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class P5SurvivalSkillAcceptanceGameTests {
    private static final String BATCH = "p5_survival_skills";
    private static final String CONFIG_MUTATION_BATCH =
            "p5_survival_skills_config_mutation";
    private static final int TIMEOUT_TICKS = 360;
    /*
     * SurvivalSkillService 当前未公开该生产常量；验收固定 40，
     * 使生产退避被意外缩短时直接失败。
     */
    private static final int PRODUCTION_RETRY_BACKOFF_TICKS = 40;

    private P5SurvivalSkillAcceptanceGameTests() {}

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void criticalFoodUsesSafeMainInventoryFoodAndRestoresSelection(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        TestBot bot =
                P5GameTestSupport.spawnFixedBot(helper, "P5Eat");
        P2GameTestSupport.Cleanup cleanup =
                P5GameTestSupport.cleanup(bot);
        try {
            bot.player().getInventory().selected = 0;
            bot.player().getInventory().setItem(
                    0, new ItemStack(Items.STONE));
            bot.player().getInventory().setItem(
                    9, new ItemStack(Items.MELON_SLICE, 2));
            bot.player().getInventory().setItem(
                    10, new ItemStack(Items.ROTTEN_FLESH));
            bot.player().getFoodData().setFoodLevel(4);
            bot.player().getFoodData().setSaturation(0.0F);
            bot.player().getFoodData().setExhaustion(0.0F);

            boolean[] observedDelegation = {false};
            UUID[] incidentId = {null};
            UUID[] runId = {null};
            P2GameTestSupport.awaitCondition(
                    helper,
                    220,
                    () -> {
                        bot.manager()
                                .safetyIncident(bot.name())
                                .filter(incident ->
                                        incident.hazardType()
                                                == HazardType
                                                        .FOOD_CRITICAL)
                                .ifPresent(incident -> {
                                    if (incidentId[0] == null) {
                                        incidentId[0] =
                                                incident.incidentId();
                                    }
                                    P2GameTestSupport.require(
                                            incidentId[0].equals(
                                                    incident.incidentId()),
                                            "Eating changed safety incident identity");
                                    observedDelegation[0] |=
                                            incident.state()
                                                            == SafetyState
                                                                    .DELEGATED
                                                    && incident
                                                            .currentIntervention()
                                                            .filter(value ->
                                                                    value
                                                                            == SafetyIntervention
                                                                                    .DELEGATE_TO_SURVIVAL_SKILL)
                                                            .isPresent();
                                });
                        var view = bot.manager()
                                .survivalSkillRun(bot.name())
                                .orElse(null);
                        if (view != null) {
                            if (runId[0] == null) {
                                runId[0] = view.runId();
                            }
                            P2GameTestSupport.require(
                                    runId[0].equals(view.runId()),
                                    "Eating replaced the active skill run");
                        }
                        return observedDelegation[0]
                                && view != null
                                && view.kind()
                                        == SurvivalSkillKind.EAT_FOOD
                                && view.state()
                                        == SkillRunState.SUCCEEDED
                                && bot.player()
                                                .getFoodData()
                                                .getFoodLevel()
                                        > 4;
                    },
                    "P5 did not complete delegated real-item eating",
                    cleanup,
                    () -> {
                        P2GameTestSupport.require(
                                runId[0] != null
                                        && incidentId[0] != null,
                                "Eating identities were not observed");
                        P2GameTestSupport.require(
                                count(bot, Items.MELON_SLICE) == 1,
                                "Eating consumed an unexpected food count");
                        P2GameTestSupport.require(
                                count(bot, Items.ROTTEN_FLESH) == 1,
                                "Harmful food was selected or lost");
                        P2GameTestSupport.require(
                                bot.player()
                                                        .getInventory()
                                                        .getItem(0)
                                                        .is(Items.STONE)
                                                && bot.player()
                                                                .getInventory()
                                                                .getItem(0)
                                                                .getCount()
                                                        == 1
                                                && count(bot, Items.STONE)
                                                        == 1,
                                "Inventory swap did not preserve the selected stone stack");
                        P2GameTestSupport.require(
                                bot.player()
                                                .getInventory()
                                                .getItem(9)
                                                .is(Items.MELON_SLICE)
                                        && bot.player()
                                                        .getInventory()
                                                        .getItem(9)
                                                        .getCount()
                                                == 1
                                        && bot.player()
                                                .getInventory()
                                                .getItem(1)
                                                .isEmpty(),
                                "Eating did not restore the temporary swap layout");
                        P2GameTestSupport.require(
                                bot.player().getInventory().selected == 0,
                                "Eating did not restore the previous hotbar selection");
                        P2GameTestSupport.require(
                                !bot.player().isUsingItem(),
                                "Eating left the Bot in an active item-use state");
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
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void selectedHotbarFoodSkipsNoopSelectionAndEats(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        TestBot bot = P5GameTestSupport.spawnFixedBot(
                helper, "P5EatHeld");
        P2GameTestSupport.Cleanup cleanup =
                P5GameTestSupport.cleanup(bot);
        try {
            int selectedSlot = 4;
            long generation =
                    bot.player().runtimeHandle().generation();
            bot.player().getInventory().selected =
                    selectedSlot;
            bot.player().getInventory().setItem(
                    selectedSlot,
                    new ItemStack(Items.MELON_SLICE, 2));
            bot.player().getFoodData().setFoodLevel(4);
            bot.player().getFoodData().setSaturation(0.0F);
            bot.player().getFoodData().setExhaustion(0.0F);

            P2GameTestSupport.awaitCondition(
                    helper,
                    180,
                    () -> bot.manager()
                            .survivalSkillRun(bot.name())
                            .filter(view ->
                                    view.kind()
                                                    == SurvivalSkillKind
                                                            .EAT_FOOD
                                            && view.state()
                                                    == SkillRunState
                                                            .SUCCEEDED)
                            .isPresent(),
                    "Selected hotbar food did not complete without a no-op selection",
                    cleanup,
                    () -> {
                        P2GameTestSupport.require(
                                bot.player()
                                                        .getFoodData()
                                                        .getFoodLevel()
                                                > 4
                                        && bot.player()
                                                        .getInventory()
                                                        .getItem(
                                                                selectedSlot)
                                                        .is(
                                                                Items
                                                                        .MELON_SLICE)
                                        && bot.player()
                                                        .getInventory()
                                                        .getItem(
                                                                selectedSlot)
                                                        .getCount()
                                                == 1,
                                "Selected food was not consumed exactly once");
                        P2GameTestSupport.require(
                                bot.player()
                                                        .getInventory()
                                                        .selected
                                                == selectedSlot
                                        && bot.player()
                                                        .runtimeHandle()
                                                        .generation()
                                                == generation
                                        && !bot.player()
                                                .isUsingItem(),
                                "Selected-food path changed selection, generation, or use control");
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
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void unsafeLayoutReceiptPreventsDimensionGenerationActivation(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        TestBot bot = P5GameTestSupport.spawnFixedBot(
                helper, "P5DimFence");
        P2GameTestSupport.Cleanup cleanup =
                P5GameTestSupport.cleanup(bot);
        try {
            long generation =
                    bot.player().runtimeHandle().generation();
            UUID botId = bot.player().getUUID();
            P2GameTestSupport.require(
                    bot.player()
                                    .connection
                                    .getConnection()
                            instanceof BotConnection,
                    "P5 dimension fixture has no BotConnection");
            BotConnection connection =
                    (BotConnection)
                            bot.player()
                                    .connection
                                    .getConnection();
            P2GameTestSupport.require(
                    bot.player().connection
                            instanceof BotGamePacketListener,
                    "P5 dimension fixture has no BotGamePacketListener");
            BotGamePacketListener listener =
                    (BotGamePacketListener)
                            bot.player().connection;
            bot.player().getInventory().selected = 0;
            bot.player().getInventory().setItem(
                    0, new ItemStack(Items.STONE));
            bot.player().getInventory().setItem(
                    9, new ItemStack(Items.MELON_SLICE, 2));
            bot.player().getFoodData().setFoodLevel(4);
            bot.player().getFoodData().setSaturation(0.0F);
            bot.player().getFoodData().setExhaustion(0.0F);

            boolean[] closeRequested = {false};
            UUID[] runId = {null};
            P2GameTestSupport.awaitCondition(
                    helper,
                    180,
                    () -> {
                        if (!closeRequested[0]
                                && bot.player().isUsingItem()) {
                            var activeView = bot.manager()
                                    .survivalSkillRun(botId)
                                    .orElseThrow(() ->
                                            new IllegalStateException(
                                                    "Eating body had no active skill view before no-save closure"));
                            P2GameTestSupport.require(
                                    activeView.botId().equals(botId)
                                            && activeView.botGeneration()
                                                    == generation
                                            && activeView.kind()
                                                    == SurvivalSkillKind
                                                            .EAT_FOOD
                                            && !activeView.state()
                                                    .isTerminal(),
                                    "No-save fixture captured the wrong active run");
                            runId[0] = activeView.runId();
                            bot.player()
                                    .getInventory()
                                    .setItem(
                                            10,
                                            new ItemStack(
                                                    Items
                                                            .DIAMOND));
                            closeRequested[0] = true;
                            bot.manager()
                                    .onChangedDimension(
                                            bot.player());
                        }
                        return closeRequested[0]
                                && !connection.snapshot()
                                        .open();
                    },
                    "Unsafe lifecycle layout receipt did not contain the bot",
                    cleanup,
                    () -> {
                        P2GameTestSupport.require(
                                bot.manager()
                                        .resolveActive(
                                                botId,
                                                generation)
                                        .isEmpty(),
                                "Unsafe old generation remained active after dimension closure");
                        P2GameTestSupport.require(
                                bot.player()
                                                .runtimeHandle()
                                                .player()
                                                .isEmpty()
                                        && bot.player()
                                                        .runtimeHandle()
                                                        .generation()
                                                == generation + 1L,
                                "No-save closure retained a body or advanced an unexpected generation");
                        P2GameTestSupport.require(
                                bot.player()
                                                .getInventory()
                                                .getItem(10)
                                                .is(
                                                        Items
                                                                .DIAMOND)
                                        && count(
                                                        bot,
                                                        Items
                                                                .DIAMOND)
                                                == 1,
                                "Fail-closed dimension containment mutated the external item");
                        var terminalView = bot.manager()
                                .survivalSkillRun(botId)
                                .orElseThrow(() ->
                                        new IllegalStateException(
                                                "No-save dimension containment lost the skill receipt"));
                        P2GameTestSupport.require(
                                runId[0] != null
                                        && terminalView.runId()
                                                .equals(runId[0])
                                        && terminalView.botId()
                                                .equals(botId)
                                        && terminalView
                                                        .botGeneration()
                                                == generation
                                        && terminalView.state()
                                                == SkillRunState.FAILED
                                        && terminalView
                                                .failureCode()
                                                .filter(code ->
                                                        code
                                                                == SkillFailureCode
                                                                        .ITEM_CONSERVATION_VIOLATION)
                                                .isPresent()
                                        && terminalView.safeSummary()
                                                .contains(
                                                        "无保存隔离移除"),
                                "No-save dimension containment rewrote or left the exact skill run active");
                        var server = helper.getLevel().getServer();
                        P2GameTestSupport.require(
                                server.getPlayerList()
                                                        .getPlayer(botId)
                                                == null
                                        && server.getPlayerList()
                                                .getPlayers()
                                                .stream()
                                                .noneMatch(player ->
                                                        player.getUUID()
                                                                .equals(botId)),
                                "No-save closure retained the Bot in PlayerList indexes");
                        for (var level : server.getAllLevels()) {
                            P2GameTestSupport.require(
                                    level.getPlayerByUUID(botId)
                                                    == null
                                            && level.getEntity(botId)
                                                    == null
                                            && level.players()
                                                    .stream()
                                                    .noneMatch(player ->
                                                            player.getUUID()
                                                                    .equals(botId)),
                                    "No-save closure retained the Bot in a level index");
                        }
                        P2GameTestSupport.require(
                                !connection.snapshot().open()
                                        && listener
                                                .disconnectRequested()
                                        && !listener
                                                .acceptsRuntimeAuthority(),
                                "No-save closure retained physical connection or listener authority");
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
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void sharedListenerNoSaveClosureRevokesBothBodies(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        TestBot bot = P5GameTestSupport.spawnFixedBot(
                helper, "P5ShareFence");
        P2GameTestSupport.Cleanup cleanup =
                P5GameTestSupport.cleanup(bot);
        try {
            BotServerPlayer predecessor = bot.player();
            UUID botId = predecessor.getUUID();
            var server = helper.getLevel().getServer();
            long generation = predecessor
                    .runtimeHandle()
                    .generation();
            P2GameTestSupport.require(
                    predecessor.connection
                            instanceof BotGamePacketListener
                                    && predecessor.connection
                                                    .getConnection()
                                            instanceof BotConnection,
                    "Shared-listener fixture has no Bot connection stack");
            BotGamePacketListener listener =
                    (BotGamePacketListener)
                            predecessor.connection;
            BotConnection connection =
                    (BotConnection)
                            listener.getConnection();
            Path playerData = server
                    .getWorldPath(
                            LevelResource.PLAYER_DATA_DIR)
                    .resolve(botId + ".dat");
            predecessor.getInventory().clearContent();
            predecessor.getInventory().selected = 7;
            predecessor.getInventory().setItem(
                    7, new ItemStack(Items.TORCH));
            server.getPlayerList().save(predecessor);
            P2GameTestSupport.require(
                    Files.isRegularFile(playerData),
                    "Shared-listener fixture could not persist its baseline layout");
            PersistedLayout persistedBaseline =
                    savedLayout(playerData);
            P2GameTestSupport.require(
                    persistedBaseline.selectedSlot() == 7
                            && persistedBaseline
                                    .occupiedSlots()
                                    .equals(Set.of(7)),
                    "Shared-listener persisted baseline was not deterministic");
            BotServerPlayer replacement =
                    BotServerPlayer.recreateForRespawn(
                            server,
                            predecessor.serverLevel(),
                            predecessor.getGameProfile(),
                            ClientInformation.createDefault(),
                            predecessor);
            replacement.connection = listener;
            P2GameTestSupport.require(
                    replacement.runtimeHandle()
                                    == predecessor.runtimeHandle()
                            && replacement.getUUID()
                                    .equals(botId)
                            && server.getPlayerList()
                                            .getPlayer(botId)
                                    == predecessor
                            && predecessor.serverLevel()
                                            .getPlayerByUUID(botId)
                                    == predecessor
                            && server.getPlayerList()
                                    .getPlayers()
                                    .stream()
                                    .noneMatch(player ->
                                            player == replacement),
                    "Shared-listener replacement was registered or changed identity");
            predecessor.getInventory().clearContent();
            predecessor.getInventory().selected = 8;
            predecessor.getInventory().setItem(
                    10, new ItemStack(Items.DIAMOND));
            P2GameTestSupport.require(
                    listener.player == predecessor
                            && listener
                                    .acceptsRuntimeAuthority()
                            && connection.snapshot().open(),
                    "Shared-listener fixture did not begin with one authoritative open listener");

            ListenerDisconnectDecision decision;
            listener.player = replacement;
            try {
                P2GameTestSupport.require(
                        predecessor.connection == listener
                                && replacement.connection
                                        == listener
                                && listener.player
                                        == replacement
                                && listener
                                        .acceptsRuntimeAuthority(),
                        "Shared-listener fixture did not expose the unstable body graph");
                decision = bot.manager().onDisconnecting(
                        predecessor,
                        listener,
                        connection);
            } finally {
                if (listener.acceptsRuntimeAuthority()
                        && listener.player == replacement) {
                    listener.player = predecessor;
                }
            }

            P2GameTestSupport.require(
                    decision
                            == ListenerDisconnectDecision.ABORTED,
                    "Shared-listener mismatch did not choose fail-closed removal");
            P2GameTestSupport.require(
                    predecessor.runtimeHandle()
                                    .player()
                                    .isEmpty()
                            && predecessor.runtimeHandle()
                                            .generation()
                                    == generation + 1L,
                    "Shared-listener closure retained an attached body or wrong generation");
            P2GameTestSupport.require(
                    bot.manager()
                                            .inspectActionTarget(
                                                    botId,
                                                    generation)
                                            .status()
                                    == BotActionTargetStatus
                                            .STALE_GENERATION
                            && bot.manager()
                                    .resolveActive(
                                            botId,
                                            generation)
                                    .isEmpty()
                            && bot.manager()
                                    .resolveCleanupTarget(
                                            botId,
                                            generation)
                                    .isEmpty(),
                    "Shared-listener closure retained old-generation action or cleanup authority");
            P2GameTestSupport.require(
                    predecessor.connection == listener
                            && replacement.connection
                                    == listener
                            && listener.player == replacement
                            && listener.disconnectRequested()
                            && !listener
                                    .acceptsRuntimeAuthority()
                            && !connection.snapshot().open(),
                    "Shared listener or one of its exact bodies retained authority");
            P2GameTestSupport.require(
                    server.getPlayerList().getPlayer(botId)
                                    == null
                            && server.getPlayerList()
                                    .getPlayers()
                                    .stream()
                                    .noneMatch(player ->
                                            player.getUUID()
                                                    .equals(botId)),
                    "Shared-listener closure retained a PlayerList identity");
            for (var level : server.getAllLevels()) {
                P2GameTestSupport.require(
                        level.getPlayerByUUID(botId) == null
                                && level.getEntity(botId) == null
                                && level.players()
                                        .stream()
                                        .noneMatch(player ->
                                                player == predecessor
                                                        || player
                                                                == replacement
                                                        || player.getUUID()
                                                                .equals(botId)),
                        "Shared-listener closure retained an entity index");
            }
            P2GameTestSupport.require(
                    savedLayout(playerData)
                            .equals(persistedBaseline),
                    "Shared-listener no-save closure persisted the temporary layout");
            P2GameTestSupport.require(
                    predecessor.getInventory()
                                    .getItem(10)
                                    .is(Items.DIAMOND)
                            && predecessor.getInventory()
                                            .getItem(10)
                                            .getCount()
                                    == 1,
                    "Shared-listener fail-closed path mutated the external item");
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
    public static void disconnectPersistsRestoredEatingSelectionBeforeRemoval(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        TestBot bot = P5GameTestSupport.spawnFixedBot(
                helper, "P5EatLeave");
        P2GameTestSupport.Cleanup cleanup =
                P5GameTestSupport.cleanup(bot);
        try {
            UUID botId = bot.player().getUUID();
            long generation =
                    bot.player()
                            .runtimeHandle()
                            .generation();
            P2GameTestSupport.require(
                    bot.player()
                                    .connection
                                    .getConnection()
                            instanceof BotConnection,
                    "P5 disconnect fixture has no BotConnection");
            BotConnection connection =
                    (BotConnection)
                            bot.player()
                                    .connection
                                    .getConnection();
            Path playerData = helper
                    .getLevel()
                    .getServer()
                    .getWorldPath(
                            LevelResource.PLAYER_DATA_DIR)
                    .resolve(botId + ".dat");
            P2GameTestSupport.require(
                    !Files.isRegularFile(playerData),
                    "P5 disconnect fixture reused stale player data");
            bot.player().getInventory().selected = 0;
            bot.player().getInventory().setItem(
                    0, new ItemStack(Items.STONE));
            bot.player().getInventory().setItem(
                    9,
                    new ItemStack(
                            Items.MELON_SLICE, 2));
            bot.player().getFoodData().setFoodLevel(4);
            bot.player().getFoodData().setSaturation(0.0F);
            bot.player().getFoodData().setExhaustion(0.0F);

            boolean[] disconnectRequested = {false};
            P2GameTestSupport.awaitCondition(
                    helper,
                    180,
                    () -> {
                        if (!disconnectRequested[0]
                                && bot.player()
                                        .isUsingItem()) {
                            P2GameTestSupport.require(
                                    bot.player()
                                                    .getInventory()
                                                    .selected
                                            == 1
                                            && bot.player()
                                                    .getInventory()
                                                    .getItem(1)
                                                    .is(
                                                            Items
                                                                    .MELON_SLICE),
                                    "Disconnect fixture never reached the temporary eating layout");
                            disconnectRequested[0] =
                                    true;
                            bot.player()
                                    .connection
                                    .disconnect(
                                            Component.literal(
                                                    "P5 pre-save cleanup fixture"));
                        }
                        return disconnectRequested[0]
                                && !connection.snapshot()
                                        .open()
                                && Files.isRegularFile(
                                        playerData);
                    },
                    "Disconnect did not save and remove the eating bot",
                    cleanup,
                    () -> {
                        PersistedLayout saved =
                                savedLayout(playerData);
                        P2GameTestSupport.require(
                                saved.selectedSlot() == 0,
                                "Vanilla persisted the temporary eating selection before compensation");
                        P2GameTestSupport.require(
                                saved.occupiedSlots()
                                                .contains(0)
                                        && !saved.occupiedSlots()
                                                .contains(1)
                                        && saved.occupiedSlots()
                                                .contains(9),
                                "Vanilla persisted the temporary inventory swap before compensation");
                        P2GameTestSupport.require(
                                helper.getLevel()
                                                .getServer()
                                                .getPlayerList()
                                                .getPlayer(
                                                        botId)
                                        == null
                                        && bot.manager()
                                                .resolveActive(
                                                        botId,
                                                        generation)
                                                .isEmpty(),
                                "Disconnected eating bot retained lifecycle authority");
                        P2GameTestSupport.require(
                                bot.player()
                                                .getInventory()
                                                .selected
                                        == 0
                                        && bot.player()
                                                .getInventory()
                                                .getItem(0)
                                                .is(Items.STONE)
                                        && bot.player()
                                                .getInventory()
                                                .getItem(1)
                                                .isEmpty()
                                        && bot.player()
                                                .getInventory()
                                                .getItem(9)
                                                .is(
                                                        Items
                                                                .MELON_SLICE)
                                        && bot.player()
                                                        .getInventory()
                                                        .getItem(9)
                                                        .getCount()
                                                == 2
                                        && count(
                                                        bot,
                                                        Items
                                                                .MELON_SLICE)
                                                == 2
                                        && !bot.player()
                                                .isUsingItem(),
                                "Pre-save disconnect cleanup did not restore the exact eating layout");
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
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void fullHotbarRefusesMainInventoryFoodWithoutMovingItems(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        TestBot bot = P5GameTestSupport.spawnFixedBot(
                helper, "P5EatFull");
        P2GameTestSupport.Cleanup cleanup =
                P5GameTestSupport.cleanup(bot);
        try {
            bot.player().getInventory().selected = 0;
            for (int slot = 0; slot < 9; slot++) {
                bot.player().getInventory().setItem(
                        slot,
                        new ItemStack(Items.STONE, slot + 1));
            }
            bot.player().getInventory().setItem(
                    9, new ItemStack(Items.MELON_SLICE));
            bot.player().getFoodData().setFoodLevel(4);
            bot.player().getFoodData().setSaturation(0.0F);
            bot.player().getFoodData().setExhaustion(0.0F);

            UUID[] incidentId = {null};
            UUID[] runId = {null};
            long[] firstBlockedTick = {-1L};
            boolean[] backoffWindowObserved = {false};
            P2GameTestSupport.awaitCondition(
                    helper,
                    240,
                    () -> {
                        long currentTick = helper
                                .getLevel()
                                .getServer()
                                .getTickCount();
                        var incident = bot.manager()
                                .safetyIncident(bot.name())
                                .orElse(null);
                        if (incident == null
                                || incident.hazardType()
                                        != HazardType.FOOD_CRITICAL) {
                            return false;
                        }
                        if (incidentId[0] == null) {
                            incidentId[0] =
                                    incident.incidentId();
                        }
                        P2GameTestSupport.require(
                                incidentId[0].equals(
                                        incident.incidentId()),
                                "Full-hotbar retry changed incident identity");
                        if (firstBlockedTick[0] < 0L) {
                            if (incident.state()
                                            != SafetyState.BLOCKED
                                    || incident
                                            .currentIntervention()
                                            .filter(value ->
                                                    value
                                                            == SafetyIntervention
                                                                    .HOLD_POSITION)
                                            .isEmpty()) {
                                return false;
                            }
                            firstBlockedTick[0] =
                                    incident.lastObservedTick();
                            P2GameTestSupport.require(
                                    currentTick >= firstBlockedTick[0],
                                    "Full-hotbar incident reported a future tick");
                            requireHotbarLayout(bot, true);
                            P2GameTestSupport.require(
                                    bot.manager()
                                            .survivalSkillRun(bot.name())
                                            .isEmpty(),
                                    "Full hotbar unexpectedly started eating");
                            P2GameTestSupport.require(
                                    bot.player()
                                                            .getInventory()
                                                            .getItem(9)
                                                            .is(Items.MELON_SLICE)
                                                    && bot.player()
                                                                    .getInventory()
                                                                    .getItem(9)
                                                                    .getCount()
                                                            == 1
                                                    && bot.player()
                                                            .getInventory()
                                                            .getItem(20)
                                                            .isEmpty()
                                                    && count(
                                                                    bot,
                                                                    Items.STONE)
                                                            == 45
                                                    && count(
                                                                    bot,
                                                                    Items.MELON_SLICE)
                                                            == 1,
                                    "Full-hotbar refusal mutated inventory before the fixture opened a slot");
                            ItemStack moved =
                                    bot.player()
                                            .getInventory()
                                            .getItem(1)
                                            .copy();
                            bot.player().getInventory().setItem(
                                    20, moved);
                            bot.player().getInventory().setItem(
                                    1, ItemStack.EMPTY);
                            return false;
                        }

                        var view = bot.manager()
                                .survivalSkillRun(bot.name())
                                .orElse(null);
                        long nextEligibleTick = Math.addExact(
                                firstBlockedTick[0],
                                PRODUCTION_RETRY_BACKOFF_TICKS);
                        if (currentTick < nextEligibleTick) {
                            P2GameTestSupport.require(
                                    view == null,
                                    "Incident retry started before the production 40-tick backoff elapsed");
                            P2GameTestSupport.require(
                                    bot.player()
                                                    .getFoodData()
                                                    .getFoodLevel()
                                            == 4
                                            && count(
                                                            bot,
                                                            Items
                                                                    .MELON_SLICE)
                                                    == 1
                                            && !bot.player()
                                                    .isUsingItem(),
                                    "Backoff window mutated the eating state");
                            return false;
                        }
                        backoffWindowObserved[0] = true;
                        if (view == null) {
                            return false;
                        }
                        if (runId[0] == null) {
                            runId[0] = view.runId();
                        }
                        P2GameTestSupport.require(
                                view.startedTick() >= nextEligibleTick,
                                "Eating retry started before the production 40-tick backoff elapsed");
                        P2GameTestSupport.require(
                                runId[0].equals(view.runId()),
                                "Retry replaced the eating skill run");
                        return view.kind()
                                                == SurvivalSkillKind
                                                        .EAT_FOOD
                                && view.state()
                                        == SkillRunState.SUCCEEDED
                                && bot.player()
                                                .getFoodData()
                                                .getFoodLevel()
                                        > 4;
                    },
                    "P5 did not enforce backoff then resume after a hotbar slot opened",
                    cleanup,
                    () -> {
                        P2GameTestSupport.require(
                                backoffWindowObserved[0]
                                        && incidentId[0] != null
                                        && runId[0] != null,
                                "Full-hotbar retry evidence was incomplete");
                        P2GameTestSupport.require(
                                count(bot, Items.MELON_SLICE) == 0,
                                "Recovered eating did not consume exactly one food");
                        P2GameTestSupport.require(
                                count(bot, Items.STONE) == 45,
                                "Hotbar opening or eating lost an item");
                        P2GameTestSupport.require(
                                bot.player()
                                                .getInventory()
                                                .getItem(20)
                                                .is(Items.STONE)
                                        && bot.player()
                                                        .getInventory()
                                                        .getItem(20)
                                                        .getCount()
                                                == 2,
                                "The externally opened hotbar stack was not preserved");
                        requireHotbarLayout(bot, false);
                        P2GameTestSupport.require(
                                !bot.player().isUsingItem(),
                                "Recovered eating left item use active");
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
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void unsupportedFoodRemainsBlockedWithoutStartingEating(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        TestBot bot = P5GameTestSupport.spawnFixedBot(
                helper, "P5EatHarm");
        P2GameTestSupport.Cleanup cleanup =
                P5GameTestSupport.cleanup(bot);
        try {
            bot.player().getInventory().selected = 0;
            bot.player().getInventory().setItem(
                    9, new ItemStack(Items.ROTTEN_FLESH));
            bot.player().getInventory().setItem(
                    10, new ItemStack(Items.CHORUS_FRUIT));
            bot.player().getInventory().setItem(
                    11, new ItemStack(Items.HONEY_BOTTLE));
            bot.player().getFoodData().setFoodLevel(4);
            bot.player().getFoodData().setSaturation(0.0F);
            bot.player().getFoodData().setExhaustion(0.0F);

            UUID[] incidentId = {null};
            long[] blockedTick = {-1L};
            P2GameTestSupport.awaitCondition(
                    helper,
                    140,
                    () -> {
                        long currentTick = helper
                                .getLevel()
                                .getServer()
                                .getTickCount();
                        var incident = bot.manager()
                                .safetyIncident(bot.name())
                                .orElse(null);
                        if (incident == null
                                || incident.hazardType()
                                        != HazardType.FOOD_CRITICAL
                                || incident.state()
                                        != SafetyState.BLOCKED
                                || incident
                                        .currentIntervention()
                                        .filter(value ->
                                                value
                                                        == SafetyIntervention
                                                                .HOLD_POSITION)
                                        .isEmpty()) {
                            return false;
                        }
                        if (incidentId[0] == null) {
                            incidentId[0] =
                                    incident.incidentId();
                            blockedTick[0] = currentTick;
                        }
                        P2GameTestSupport.require(
                                incidentId[0].equals(
                                        incident.incidentId()),
                                "Unsupported-food refusal changed incident identity");
                        P2GameTestSupport.require(
                                bot.manager()
                                        .survivalSkillRun(bot.name())
                                        .isEmpty(),
                                "Unsupported-food inventory started eating");
                        return currentTick >= blockedTick[0] + 50L;
                    },
                    "P5 did not keep unsupported food blocked",
                    cleanup,
                    () -> {
                        P2GameTestSupport.require(
                                bot.player()
                                                        .getInventory()
                                                        .getItem(9)
                                                        .is(Items.ROTTEN_FLESH)
                                                && bot.player()
                                                                .getInventory()
                                                                .getItem(9)
                                                                .getCount()
                                                        == 1
                                                && bot.player()
                                                        .getInventory()
                                                        .getItem(10)
                                                        .is(Items.CHORUS_FRUIT)
                                                && bot.player()
                                                                .getInventory()
                                                                .getItem(10)
                                                                .getCount()
                                                        == 1
                                                && bot.player()
                                                        .getInventory()
                                                        .getItem(11)
                                                        .is(Items.HONEY_BOTTLE)
                                                && bot.player()
                                                                .getInventory()
                                                                .getItem(11)
                                                                .getCount()
                                                        == 1,
                                "Unsupported-food refusal changed exact inventory slots");
                        P2GameTestSupport.require(
                                count(bot, Items.ROTTEN_FLESH) == 1,
                                "Harmful food was moved or consumed");
                        P2GameTestSupport.require(
                                count(bot, Items.CHORUS_FRUIT) == 1,
                                "Custom-consumption food was moved or consumed");
                        P2GameTestSupport.require(
                                count(bot, Items.HONEY_BOTTLE) == 1,
                                "Remainder-producing food was moved or consumed");
                        P2GameTestSupport.require(
                                bot.player()
                                                .getFoodData()
                                                .getFoodLevel()
                                        == 4,
                                "Unsupported-food refusal changed food level");
                        P2GameTestSupport.require(
                                bot.player().getInventory().selected == 0
                                        && !bot.player().isUsingItem(),
                                "Unsupported-food refusal changed held-item state");
                        P2GameTestSupport.require(
                                !bot.player()
                                        .hasEffect(MobEffects.HUNGER),
                                "Unsupported-food refusal applied a food effect");
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
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void emergencyPreemptionRestoresTemporaryEatingLayout(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        TestBot bot = P5GameTestSupport.spawnFixedBot(
                helper, "P5EatPreempt");
        P2GameTestSupport.Cleanup cleanup =
                P5GameTestSupport.cleanup(bot);
        try {
            long botGeneration =
                    bot.player().runtimeHandle().generation();
            bot.player().getInventory().selected = 0;
            bot.player().getInventory().setItem(
                    0, new ItemStack(Items.STONE));
            bot.player().getInventory().setItem(
                    9, new ItemStack(Items.MELON_SLICE, 2));
            bot.player().getFoodData().setFoodLevel(4);
            bot.player().getFoodData().setSaturation(0.0F);
            bot.player().getFoodData().setExhaustion(0.0F);

            boolean[] emergencySubmitted = {false};
            UUID[] runId = {null};
            P2GameTestSupport.awaitCondition(
                    helper,
                    220,
                    () -> {
                        var view = bot.manager()
                                .survivalSkillRun(bot.name())
                                .orElse(null);
                        if (view != null && runId[0] == null) {
                            runId[0] = view.runId();
                        }
                        if (!emergencySubmitted[0]
                                && bot.player().isUsingItem()) {
                            bot.player()
                                    .getFoodData()
                                    .setFoodLevel(20);
                            submitEmergencyStop(bot);
                            emergencySubmitted[0] = true;
                            return false;
                        }
                        if (!emergencySubmitted[0]
                                || view == null) {
                            return false;
                        }
                        P2GameTestSupport.require(
                                runId[0].equals(view.runId()),
                                "Preemption replaced the eating skill run");
                        return view.state()
                                == SkillRunState.PREEMPTED;
                    },
                    "P5 did not compensate a preempted eating action",
                    cleanup,
                    () -> {
                        P2GameTestSupport.require(
                                emergencySubmitted[0]
                                        && runId[0] != null,
                                "Preemption evidence was incomplete");
                        P2GameTestSupport.require(
                                bot.player()
                                                .getInventory()
                                                .getItem(9)
                                                .is(Items.MELON_SLICE)
                                        && bot.player()
                                                        .getInventory()
                                                        .getItem(9)
                                                        .getCount()
                                                == 2
                                        && bot.player()
                                                .getInventory()
                                                .getItem(1)
                                                .isEmpty(),
                                "Preemption did not restore the temporary food swap");
                        P2GameTestSupport.require(
                                count(bot, Items.MELON_SLICE) == 2
                                        && count(bot, Items.STONE) == 1,
                                "Preemption violated item conservation");
                        P2GameTestSupport.require(
                                bot.player().getInventory().selected == 0
                                        && !bot.player().isUsingItem(),
                                "Preemption did not restore the held-item state");
                        P2GameTestSupport.require(
                                bot.player()
                                                .runtimeHandle()
                                                .generation()
                                        == botGeneration,
                                "Clean preemption changed the Bot generation");
                        ActionMailbox.Submission probe =
                                submitProbeStop(bot);
                        P2GameTestSupport.require(
                                probe.status()
                                        == ActionMailbox
                                                .SubmissionStatus
                                                .ENQUEUED,
                                "Clean preemption quarantined the current generation");
                        P2GameTestSupport.awaitOutcome(
                                helper,
                                probe.completion()
                                        .orElseThrow(),
                                40,
                                cleanup,
                                outcome -> {
                                    P2GameTestSupport.require(
                                            outcome.state()
                                                    == ActionState
                                                            .SUCCEEDED,
                                            "Same-generation probe did not complete after clean preemption");
                                    P2GameTestSupport.require(
                                            bot.player()
                                                            .runtimeHandle()
                                                            .generation()
                                                    == botGeneration,
                                            "Same-generation probe completed on a replacement generation");
                                    cleanup.run();
                                    helper.succeed();
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
    public static void offhandEatingPreservesExternallyChangedSelection(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        TestBot bot = P5GameTestSupport.spawnFixedBot(
                helper, "P5EatOffhand");
        P2GameTestSupport.Cleanup cleanup =
                P5GameTestSupport.cleanup(bot);
        try {
            int initialFoodLevel = 4;
            int externalSelection = 3;
            long botGeneration =
                    bot.player().runtimeHandle().generation();
            bot.player().getInventory().selected = 0;
            bot.player().getInventory().setItem(
                    0, new ItemStack(Items.STONE));
            bot.player().getInventory().setItem(
                    externalSelection,
                    new ItemStack(Items.DIRT));
            bot.player().getInventory().setItem(
                    40, new ItemStack(Items.MELON_SLICE, 2));
            bot.player()
                    .getFoodData()
                    .setFoodLevel(initialFoodLevel);
            bot.player().getFoodData().setSaturation(0.0F);
            bot.player().getFoodData().setExhaustion(0.0F);

            boolean[] selectionChangedExternally = {false};
            UUID[] runId = {null};
            P2GameTestSupport.awaitCondition(
                    helper,
                    220,
                    () -> {
                        var view = bot.manager()
                                .survivalSkillRun(bot.name())
                                .orElse(null);
                        if (view != null && runId[0] == null) {
                            runId[0] = view.runId();
                        }
                        if (!selectionChangedExternally[0]
                                && bot.player().isUsingItem()) {
                            P2GameTestSupport.require(
                                    view != null
                                            && view.kind()
                                                    == SurvivalSkillKind
                                                            .EAT_FOOD,
                                    "Offhand item use was not owned by the eating skill");
                            P2GameTestSupport.require(
                                    bot.player()
                                                            .getOffhandItem()
                                                            .is(Items.MELON_SLICE)
                                                    && bot.player()
                                                                    .getOffhandItem()
                                                                    .getCount()
                                                            == 2
                                                    && count(
                                                                    bot,
                                                                    Items.MELON_SLICE)
                                                            == 2,
                                    "Offhand food changed before external selection");
                            P2GameTestSupport.require(
                                    bot.player()
                                                            .getFoodData()
                                                            .getFoodLevel()
                                                    == initialFoodLevel
                                            && bot.player()
                                                            .getInventory()
                                                            .selected
                                                    == 0,
                                    "Offhand food completed before external selection");
                            bot.player().getInventory().selected =
                                    externalSelection;
                            P2GameTestSupport.require(
                                    bot.player()
                                                    .getInventory()
                                                    .selected
                                            == externalSelection,
                                    "Fixture could not change the selected hotbar slot");
                            selectionChangedExternally[0] = true;
                            return false;
                        }
                        if (!selectionChangedExternally[0]
                                || view == null) {
                            return false;
                        }
                        P2GameTestSupport.require(
                                runId[0].equals(view.runId()),
                                "Offhand eating replaced the active skill run");
                        return view.kind()
                                                == SurvivalSkillKind
                                                        .EAT_FOOD
                                && view.state()
                                        == SkillRunState.SUCCEEDED
                                && bot.player()
                                                .getFoodData()
                                                .getFoodLevel()
                                        > initialFoodLevel;
                    },
                    "P5 did not complete offhand eating after external selection changed",
                    cleanup,
                    () -> {
                        P2GameTestSupport.require(
                                selectionChangedExternally[0]
                                        && runId[0] != null,
                                "Offhand selection-change evidence was incomplete");
                        P2GameTestSupport.require(
                                bot.player()
                                                        .getOffhandItem()
                                                        .is(Items.MELON_SLICE)
                                                && bot.player()
                                                                .getOffhandItem()
                                                                .getCount()
                                                        == 1
                                                && count(
                                                                bot,
                                                                Items.MELON_SLICE)
                                                        == 1,
                                "Offhand eating did not consume exactly one food");
                        P2GameTestSupport.require(
                                bot.player()
                                                        .getInventory()
                                                        .getItem(0)
                                                        .is(Items.STONE)
                                                && bot.player()
                                                                .getInventory()
                                                                .getItem(0)
                                                                .getCount()
                                                        == 1
                                                && bot.player()
                                                        .getInventory()
                                                        .getItem(
                                                                externalSelection)
                                                        .is(Items.DIRT)
                                                && bot.player()
                                                                .getInventory()
                                                                .getItem(
                                                                        externalSelection)
                                                                .getCount()
                                                        == 1,
                                "Offhand eating mutated unrelated hotbar stacks");
                        P2GameTestSupport.require(
                                bot.player()
                                                        .getInventory()
                                                        .selected
                                                == externalSelection
                                        && !bot.player()
                                                .isUsingItem(),
                                "Offhand eating overwrote the external selected slot");
                        P2GameTestSupport.require(
                                bot.player()
                                                .runtimeHandle()
                                                .generation()
                                        == botGeneration,
                                "Offhand eating changed the Bot generation");
                        ActionMailbox.Submission probe =
                                submitProbeStop(bot);
                        P2GameTestSupport.require(
                                probe.status()
                                        == ActionMailbox
                                                .SubmissionStatus
                                                .ENQUEUED,
                                "Offhand eating quarantined the current generation");
                        P2GameTestSupport.awaitOutcome(
                                helper,
                                probe.completion()
                                        .orElseThrow(),
                                40,
                                cleanup,
                                outcome -> {
                                    P2GameTestSupport.require(
                                            outcome.state()
                                                    == ActionState
                                                            .SUCCEEDED,
                                            "Same-generation probe did not complete after offhand eating");
                                    P2GameTestSupport.require(
                                            bot.player()
                                                                    .runtimeHandle()
                                                                    .generation()
                                                            == botGeneration
                                                    && bot.player()
                                                                    .getInventory()
                                                                    .selected
                                                            == externalSelection,
                                            "Probe replaced the generation or external selected slot");
                                    cleanup.run();
                                    helper.succeed();
                                });
                    });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = CONFIG_MUTATION_BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void deathCancelsInFlightEatingAfterConfirmedGenerationClose(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        boolean previousAutoRespawn =
                BotPlayerConfig.AUTO_RESPAWN.get();
        boolean previousKeepInventory = helper
                .getLevel()
                .getGameRules()
                .getBoolean(
                        GameRules.RULE_KEEPINVENTORY);
        P2GameTestSupport.Cleanup cleanup =
                P5GameTestSupport.cleanup();
        cleanup.add(() -> BotPlayerConfig.AUTO_RESPAWN.set(
                previousAutoRespawn));
        cleanup.add(() -> helper
                .getLevel()
                .getGameRules()
                .getRule(GameRules.RULE_KEEPINVENTORY)
                .set(
                        previousKeepInventory,
                        helper.getLevel().getServer()));
        try {
            BotPlayerConfig.AUTO_RESPAWN.set(false);
            helper.getLevel()
                    .getGameRules()
                    .getRule(
                            GameRules.RULE_KEEPINVENTORY)
                    .set(
                            true,
                            helper.getLevel().getServer());
            TestBot bot = P5GameTestSupport.spawnFixedBot(
                    helper, "P5EatDeath");
            P5GameTestSupport.trackBot(cleanup, bot);
            long generation =
                    bot.player().runtimeHandle().generation();
            bot.player().getInventory().selected = 0;
            bot.player().getInventory().setItem(
                    0, new ItemStack(Items.STONE));
            bot.player().getInventory().setItem(
                    9, new ItemStack(Items.MELON_SLICE, 2));
            bot.player().getFoodData().setFoodLevel(4);
            bot.player().getFoodData().setSaturation(0.0F);
            bot.player().getFoodData().setExhaustion(0.0F);

            boolean[] killedDuringUse = {false};
            UUID[] runId = {null};
            P2GameTestSupport.awaitCondition(
                    helper,
                    160,
                    () -> {
                        var view = bot.manager()
                                .survivalSkillRun(bot.name())
                                .orElse(null);
                        if (!killedDuringUse[0]
                                && bot.player().isUsingItem()) {
                            P2GameTestSupport.require(
                                    view != null
                                            && view.kind()
                                                    == SurvivalSkillKind
                                                            .EAT_FOOD
                                            && !view.state().isTerminal()
                                            && view.botGeneration()
                                                    == generation,
                                    "Death fixture did not observe the active eating run");
                            runId[0] = view.runId();
                            bot.player().kill();
                            killedDuringUse[0] = true;
                            return false;
                        }
                        return killedDuringUse[0]
                                && view != null
                                && runId[0].equals(view.runId())
                                && view.botGeneration() == generation
                                && view.state()
                                        == SkillRunState.CANCELLED;
                    },
                    "Death did not cancel the in-flight eating skill",
                    cleanup,
                    () -> {
                        var view = bot.manager()
                                .survivalSkillRun(bot.name())
                                .orElseThrow();
                        P2GameTestSupport.require(
                                killedDuringUse[0]
                                        && runId[0] != null
                                        && runId[0].equals(view.runId())
                                        && view.botGeneration()
                                                == generation,
                                "Lifecycle cancellation lost the original run identity");
                        P2GameTestSupport.require(
                                view.state()
                                                == SkillRunState
                                                        .CANCELLED
                                        && view.failureCode().isEmpty(),
                                "Confirmed generation closure was misclassified as a skill failure");
                        P2GameTestSupport.require(
                                !bot.player().isUsingItem(),
                                "Lifecycle cancellation left item-use control active");
                        P2GameTestSupport.require(
                                bot.player()
                                                        .getInventory()
                                                        .getItem(0)
                                                        .is(Items.STONE)
                                                && bot.player()
                                                                .getInventory()
                                                                .getItem(0)
                                                                .getCount()
                                                        == 1
                                        && bot.player()
                                                .getInventory()
                                                .getItem(1)
                                                .isEmpty()
                                        && bot.player()
                                                .getInventory()
                                                .getItem(9)
                                                .is(Items.MELON_SLICE)
                                        && bot.player()
                                                        .getInventory()
                                                        .getItem(9)
                                                        .getCount()
                                                == 2
                                        && bot.player()
                                                        .getInventory()
                                                        .selected
                                                == 0,
                                "Lifecycle cancellation did not restore the main-inventory food and held slot");
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
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void occupiedRestoreSourceFailsClosedWithoutQuarantiningGeneration(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        TestBot bot = P5GameTestSupport.spawnFixedBot(
                helper, "P5EatUnsafe");
        P2GameTestSupport.Cleanup cleanup =
                P5GameTestSupport.cleanup(bot);
        try {
            bot.player().getInventory().selected = 0;
            bot.player().getInventory().setItem(
                    0, new ItemStack(Items.COBBLESTONE));
            bot.player().getInventory().setItem(
                    9, new ItemStack(Items.MELON_SLICE, 2));
            bot.player().getInventory().setItem(
                    10, new ItemStack(Items.DIAMOND));
            bot.player().getFoodData().setFoodLevel(4);
            bot.player().getFoodData().setSaturation(0.0F);
            bot.player().getFoodData().setExhaustion(0.0F);

            boolean[] collisionInjected = {false};
            UUID[] runId = {null};
            P2GameTestSupport.awaitCondition(
                    helper,
                    220,
                    () -> {
                        var view = bot.manager()
                                .survivalSkillRun(bot.name())
                                .orElse(null);
                        if (view != null && runId[0] == null) {
                            runId[0] = view.runId();
                        }
                        if (!collisionInjected[0]
                                && bot.player().isUsingItem()) {
                            bot.player()
                                    .getFoodData()
                                    .setFoodLevel(20);
                            bot.player().getInventory().setItem(
                                    10, ItemStack.EMPTY);
                            bot.player().getInventory().setItem(
                                    9,
                                    new ItemStack(
                                            Items.DIAMOND));
                            submitEmergencyStop(bot);
                            collisionInjected[0] = true;
                            return false;
                        }
                        if (!collisionInjected[0]
                                || view == null) {
                            return false;
                        }
                        P2GameTestSupport.require(
                                runId[0].equals(view.runId()),
                                "Unsafe recovery replaced the eating skill run");
                        return view.state()
                                                == SkillRunState
                                                        .FAILED
                                && view.failureCode()
                                        .filter(code ->
                                                code
                                                        == SkillFailureCode
                                                                .WORLD_CHANGED)
                                        .isPresent();
                    },
                    "P5 did not report an occupied restore source",
                    cleanup,
                    () -> {
                        P2GameTestSupport.require(
                                collisionInjected[0]
                                        && runId[0] != null,
                                "Unsafe recovery evidence was incomplete");
                        P2GameTestSupport.require(
                                bot.player()
                                                .getInventory()
                                                .getItem(9)
                                                .is(Items.DIAMOND)
                                        && bot.player()
                                                        .getInventory()
                                                        .getItem(9)
                                                .getCount()
                                                == 1,
                                "Recovery moved or replaced the unknown source item");
                        P2GameTestSupport.require(
                                bot.player()
                                        .getInventory()
                                        .getItem(10)
                                        .isEmpty(),
                                "Recovery duplicated the externally moved source item");
                        P2GameTestSupport.require(
                                bot.player()
                                                .getInventory()
                                                .getItem(1)
                                                .is(Items.MELON_SLICE)
                                        && bot.player()
                                                        .getInventory()
                                                        .getItem(1)
                                                        .getCount()
                                                == 2,
                                "Unsafe recovery mutated the temporary food stack");
                        P2GameTestSupport.require(
                                count(bot, Items.MELON_SLICE) == 2
                                        && count(bot, Items.DIAMOND)
                                                == 1
                                        && count(
                                                        bot,
                                                        Items
                                                                .COBBLESTONE)
                                                == 1,
                                "Unsafe recovery violated item conservation");
                        P2GameTestSupport.require(
                                bot.player().getInventory().selected == 0
                                        && !bot.player().isUsingItem(),
                                "Layout-drift recovery left held-item control active");
                        ActionMailbox.Submission probe =
                                submitProbeStop(bot);
                        P2GameTestSupport.require(
                                probe.status()
                                        == ActionMailbox
                                                .SubmissionStatus
                                                .ENQUEUED,
                                "Explainable layout drift quarantined the generation");
                        P2GameTestSupport.awaitOutcome(
                                helper,
                                probe.completion()
                                        .orElseThrow(),
                                40,
                                cleanup,
                                outcome -> {
                                    P2GameTestSupport.require(
                                            outcome.state()
                                                    == ActionState
                                                            .SUCCEEDED,
                                            "Same-generation probe did not complete after layout drift");
                                    cleanup.run();
                                    helper.succeed();
                                });
                    });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
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
                "gametest/p5/preempt/" + actionId,
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
                        == ActionMailbox.SubmissionStatus.ENQUEUED,
                "Emergency preemption was rejected");
    }

    private static ActionMailbox.Submission submitProbeStop(
            TestBot bot) {
        long currentTick = bot.player()
                .serverLevel()
                .getServer()
                .getTickCount();
        UUID actionId = UUID.randomUUID();
        return bot.manager().submitAction(
                new ActionEnvelope(
                        actionId,
                        bot.player().getUUID(),
                        bot.player()
                                .runtimeHandle()
                                .generation(),
                        "gametest/p5/quarantine-probe/"
                                + actionId,
                        currentTick + 40L,
                        5,
                        new StopAction(),
                        ActionOrigin.none()),
                ActionPriority.OWNER_CONTROL);
    }

    private static void requireHotbarLayout(
            TestBot bot, boolean full) {
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack =
                    bot.player().getInventory().getItem(slot);
            if (!full && slot == 1) {
                P2GameTestSupport.require(
                        stack.isEmpty(),
                        "Temporary hotbar slot was not restored");
                continue;
            }
            P2GameTestSupport.require(
                    stack.is(Items.STONE)
                            && stack.getCount() == slot + 1,
                    "Hotbar stack changed at slot " + slot);
        }
        P2GameTestSupport.require(
                bot.player().getInventory().selected == 0,
                "Hotbar selection changed");
    }

    private static int count(TestBot bot, Item item) {
        int count = 0;
        int size = bot.player().getInventory().getContainerSize();
        for (int slot = 0; slot < size; slot++) {
            ItemStack stack =
                    bot.player().getInventory().getItem(slot);
            if (stack.is(item)) {
                count = Math.addExact(count, stack.getCount());
            }
        }
        return count;
    }

    private static PersistedLayout savedLayout(
            Path playerData) {
        try {
            CompoundTag saved =
                    NbtIo.readCompressed(
                            playerData,
                            NbtAccounter.unlimitedHeap());
            ListTag inventory = saved.getList(
                    "Inventory", Tag.TAG_COMPOUND);
            Set<Integer> occupiedSlots =
                    new LinkedHashSet<>();
            for (int index = 0;
                    index < inventory.size();
                    index++) {
                occupiedSlots.add(
                        Byte.toUnsignedInt(
                                inventory
                                        .getCompound(index)
                                        .getByte("Slot")));
            }
            return new PersistedLayout(
                    saved.getInt("SelectedItemSlot"),
                    Set.copyOf(occupiedSlots));
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not read persisted BotPlayer data",
                    exception);
        }
    }

    private record PersistedLayout(
            int selectedSlot,
            Set<Integer> occupiedSlots) {}

}
