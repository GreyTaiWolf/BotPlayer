package io.github.greytaiwolf.botplayer.lifecycle;

import com.mojang.authlib.GameProfile;
import io.github.greytaiwolf.botplayer.BotPlayer;
import io.github.greytaiwolf.botplayer.action.ActionCancellationReason;
import io.github.greytaiwolf.botplayer.action.ActionEnvelope;
import io.github.greytaiwolf.botplayer.action.ActionMailbox;
import io.github.greytaiwolf.botplayer.action.ActionPriority;
import io.github.greytaiwolf.botplayer.action.ActionTransition;
import io.github.greytaiwolf.botplayer.action.BotActionRuntime;
import io.github.greytaiwolf.botplayer.action.input.PlayerInputController;
import io.github.greytaiwolf.botplayer.action.interaction.InventoryLayoutCleanupLease;
import io.github.greytaiwolf.botplayer.action.interaction.InventoryLayoutCleanupRequest;
import io.github.greytaiwolf.botplayer.action.interaction.InventoryLayoutCleanupResult;
import io.github.greytaiwolf.botplayer.action.minecraft.MinecraftActionBackend;
import io.github.greytaiwolf.botplayer.action.minecraft.MinecraftPlayerInputAdapter;
import io.github.greytaiwolf.botplayer.config.BotPlayerConfig;
import io.github.greytaiwolf.botplayer.inventory.BotInventoryMenu;
import io.github.greytaiwolf.botplayer.inventory.BotInventorySession;
import io.github.greytaiwolf.botplayer.inventory.BotInventorySessionManager;
import io.github.greytaiwolf.botplayer.inventory.InventoryCloseReason;
import io.github.greytaiwolf.botplayer.inventory.InventoryDistanceValidator;
import io.github.greytaiwolf.botplayer.inventory.InventoryLifecycleValidator;
import io.github.greytaiwolf.botplayer.inventory.InventorySessionToken;
import io.github.greytaiwolf.botplayer.inventory.InventorySessionState;
import io.github.greytaiwolf.botplayer.kernel.BotConnection;
import io.github.greytaiwolf.botplayer.kernel.BotGamePacketListener;
import io.github.greytaiwolf.botplayer.kernel.BotRuntimeHandle;
import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
import io.github.greytaiwolf.botplayer.network.payload.AgentBindingStatus;
import io.github.greytaiwolf.botplayer.network.payload.OpenCredentialScreenPayload;
import io.github.greytaiwolf.botplayer.navigation.GridPoint;
import io.github.greytaiwolf.botplayer.navigation.NavigationGoal;
import io.github.greytaiwolf.botplayer.navigation.NavigationPolicy;
import io.github.greytaiwolf.botplayer.navigation.NavigationRequest;
import io.github.greytaiwolf.botplayer.navigation.NavigationService;
import io.github.greytaiwolf.botplayer.navigation.NavigationSessionView;
import io.github.greytaiwolf.botplayer.navigation.NavigationSettings;
import io.github.greytaiwolf.botplayer.navigation.NavigationSubmission;
import io.github.greytaiwolf.botplayer.navigation.TerrainAssistSettings;
import io.github.greytaiwolf.botplayer.persistence.BotRosterSavedData;
import io.github.greytaiwolf.botplayer.perception.AuthorityEventCollector;
import io.github.greytaiwolf.botplayer.perception.ObservationSnapshot;
import io.github.greytaiwolf.botplayer.perception.PerceptionService;
import io.github.greytaiwolf.botplayer.perception.PerceptionSettings;
import io.github.greytaiwolf.botplayer.perception.SoundObservationCandidate;
import io.github.greytaiwolf.botplayer.profile.BotProfile;
import io.github.greytaiwolf.botplayer.safety.DamageCandidate;
import io.github.greytaiwolf.botplayer.safety.SafetyFrame;
import io.github.greytaiwolf.botplayer.safety.SafetyIncidentView;
import io.github.greytaiwolf.botplayer.safety.SafetyService;
import io.github.greytaiwolf.botplayer.safety.SafetySettings;
import io.github.greytaiwolf.botplayer.skill.core.SkillRegistry;
import io.github.greytaiwolf.botplayer.skill.runtime.SurvivalSkillRunView;
import io.github.greytaiwolf.botplayer.skill.runtime.SurvivalSkillService;
import io.github.greytaiwolf.botplayer.skill.runtime.SurvivalSkillSubmission;
import io.github.greytaiwolf.botplayer.worldmodel.WorldFact;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.jetbrains.annotations.Nullable;

/**
 * Owns online bot lifecycle. Every mutation is required to run on the Minecraft server thread.
 */
public final class BotLifecycleManager {
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final int LIFECYCLE_HISTORY_CAPACITY = 256;
    private static final int RESPAWN_FINALIZE_TIMEOUT_TICKS = 2;
    private static final int RESPAWN_RETRY_DELAY_TICKS = 1;
    private static final int MAX_RESPAWN_ATTEMPTS = 3;

    public enum ListenerDisconnectDecision {
        PROCEED,
        RETRY,
        ABORTED
    }

    private final MinecraftServer server;
    private final BotRosterSavedData roster;
    private final BotInventorySessionManager inventorySessions;
    private final PlayerInputController inputController;
    private final PerceptionService perceptionService;
    private final BotActionRuntime actionRuntime;
    private final NavigationService navigationService;
    private final SkillRegistry skillRegistry;
    private final SurvivalSkillService survivalSkillService;
    private final SafetyService safetyService;
    private final Map<UUID, RuntimeEntry> runtimes = new LinkedHashMap<>();
    private final Map<UUID, BotRuntimeHandle> handlesByBot = new LinkedHashMap<>();
    private final Map<UUID, UUID> activeAgentByBot = new LinkedHashMap<>();
    private final Map<UUID, UUID> botByActiveAgent = new LinkedHashMap<>();
    private final Deque<BotLifecycleTransition> lifecycleHistory =
            new ArrayDeque<>(LIFECYCLE_HISTORY_CAPACITY);
    private long serverTickStartedNanos = -1L;
    private boolean stopping;

    BotLifecycleManager(MinecraftServer server) {
        this.server = server;
        this.roster = BotRosterSavedData.get(server);
        this.inventorySessions = new BotInventorySessionManager(
                this::canWriteBotInventory,
                this::validateInventoryDistance,
                this::validateInventoryLifecycle);
        this.inputController =
                new PlayerInputController(BotPlayerConfig.MAX_BOTS.get());
        this.perceptionService = new PerceptionService(
                server,
                PerceptionSettings.fromConfig(),
                this::resolveActive);
        MinecraftActionBackend minecraftActionBackend =
                new MinecraftActionBackend(
                        this, inputController);
        this.actionRuntime = new BotActionRuntime(
                minecraftActionBackend,
                BotPlayerConfig.ACTION_MAILBOX_CAPACITY.get(),
                BotPlayerConfig.ACTION_LEDGER_CAPACITY.get(),
                BotPlayerConfig.ACTION_COMMANDS_PER_TICK.get(),
                BotPlayerConfig.ACTION_ACTIVE_CAPACITY.get(),
                BotPlayerConfig.ACTION_COMPLETION_CAPACITY.get(),
                perceptionService.actionOutcomeSink());
        this.navigationService = new NavigationService(
                NavigationSettings.fromConfig(),
                TerrainAssistSettings::fromConfig,
                this::resolveActive,
                this::submitAction,
                this::cancelAction);
        this.skillRegistry = new SkillRegistry();
        this.survivalSkillService = new SurvivalSkillService(
                skillRegistry,
                this::resolveActive,
                this::submitAction,
                (botId, actionId) ->
                        cancelAction(
                                botId,
                                actionId,
                                ActionCancellationReason
                                        .REQUESTED),
                new SurvivalSkillService
                        .GenerationLayoutCompensator() {
                    @Override
                    public boolean open(
                            UUID botId,
                            long generation,
                            InventoryLayoutCleanupLease
                                    layoutLease) {
                        return minecraftActionBackend
                                .openSkillInventoryLayout(
                                        botId,
                                        generation,
                                        layoutLease);
                    }

                    @Override
                    public InventoryLayoutCleanupResult cleanup(
                            UUID botId,
                            long generation,
                            InventoryLayoutCleanupRequest
                                    request) {
                        return minecraftActionBackend
                                .cleanupSkillInventoryLayout(
                                        botId,
                                        generation,
                                        request);
                    }

                    @Override
                    public void release(
                            UUID botId,
                            long generation,
                            UUID runId) {
                        minecraftActionBackend
                                .releaseSkillInventoryLayout(
                                        botId,
                                        generation,
                                        runId);
                    }

                    @Override
                    public void closeGeneration(
                            UUID botId,
                            long generation) {
                        minecraftActionBackend
                                .closeSkillInventoryGeneration(
                                        botId,
                                        generation);
                    }

                    @Override
                    public void closeAll() {
                        minecraftActionBackend
                                .closeSkillInventoryFences();
                    }
                },
                (botId, generation, currentTick) ->
                        actionRuntime
                                .quarantineBotGenerationNow(
                                        botId,
                                        generation,
                                        currentTick)
                                .containmentConfirmed());
        this.safetyService = new SafetyService(
                SafetySettings.fromConfig(),
                navigationService,
                this::submitAction,
                (botId, generation) ->
                        forceCloseInventory(
                                botId,
                                generation,
                                InventoryCloseReason.DANGER),
                survivalSkillService);
    }

    public void beginServerTick() {
        requireServerThread();
        serverTickStartedNanos = System.nanoTime();
    }

    public AuthorityEventCollector authorityEventCollector() {
        requireServerThread();
        return perceptionService.authorityCollector();
    }

    public void offerSoundObservation(
            SoundObservationCandidate candidate) {
        perceptionService.offerSound(candidate);
    }

    public ActionMailbox.Submission submitAction(
            ActionEnvelope envelope, ActionPriority priority) {
        return actionRuntime.submit(envelope, priority);
    }

    public ActionMailbox.Cancellation cancelAction(
            UUID botId, UUID actionId, ActionCancellationReason reason) {
        return actionRuntime.cancel(botId, actionId, reason);
    }

    public void recordSafetyDamage(
            BotServerPlayer player,
            DamageSource source,
            float finalDamage,
            long currentTick) {
        requireServerThread();
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(source, "source");
        RuntimeEntry runtime = runtimes.get(player.getUUID());
        if (runtime == null
                || runtime.state != BotLifecycleState.ACTIVE
                || runtime.handle.player().orElse(null) != player) {
            return;
        }
        String damageTypeId = source.typeHolder()
                .unwrapKey()
                .map(key -> key.location().toString())
                .orElse(source.getMsgId());
        safetyService.recordDamage(new DamageCandidate(
                player.getUUID(),
                runtime.handle.generation(),
                currentTick,
                damageTypeId,
                Optional.ofNullable(source.getDirectEntity())
                        .map(Entity::getUUID),
                Optional.ofNullable(source.getEntity())
                        .map(Entity::getUUID),
                finalDamage));
    }

    /**
     * The inventory session integration may strengthen this gate with a viewer lock. Lifecycle
     * authority is always the minimum requirement.
     */
    public boolean mayActionMutateInventory(
            UUID botId, long expectedGeneration) {
        requireServerThread();
        return inventorySessions
                .mutationGate()
                .mayMutate(botId, expectedGeneration);
    }

    /**
     * Cleanup may target an exact dead body, but it must never race a remote
     * inventory viewer.
     */
    public boolean mayCleanupMutateInventory(
            UUID botId, long expectedGeneration) {
        requireServerThread();
        if (resolveCleanupTarget(
                        botId, expectedGeneration)
                .isEmpty()) {
            return false;
        }
        return inventorySessions
                .sessionForBot(botId)
                .isEmpty();
    }

    public BotInventorySessionManager.ForceCloseStatus
            forceCloseInventory(
                    UUID botId,
                    long expectedGeneration,
                    InventoryCloseReason reason) {
        requireServerThread();
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(reason, "reason");
        BotInventorySessionManager.ForceCloseStatus status =
                inventorySessions.forceCloseBot(
                        botId, expectedGeneration, reason);
        if (status
                        == BotInventorySessionManager.ForceCloseStatus
                                .CLOSE_REQUESTED
                || status
                        == BotInventorySessionManager.ForceCloseStatus
                                .ALREADY_CLOSING) {
            inventorySessions
                    .sessionForBot(botId)
                    .filter(session ->
                            session.token().botGeneration()
                                    == expectedGeneration)
                    .ifPresent(session ->
                            closeInventorySession(
                                    session.token(), reason));
        }
        return status;
    }

    /**
     * Opens the bot's real inventory through a generation-bound single-viewer session.
     */
    public BotInventorySessionManager.OpenStatus openInventory(
            ServerPlayer viewer, BotServerPlayer bot) {
        requireServerThread();
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(bot, "bot");
        if (viewer instanceof BotServerPlayer) {
            return BotInventorySessionManager.OpenStatus.PERMISSION_DENIED;
        }

        long generation = bot.runtimeHandle().generation();
        BotActionTarget target =
                inspectActionTarget(bot.getUUID(), generation);
        if (target.status() != BotActionTargetStatus.ACTIVE
                || target.player().orElse(null) != bot) {
            return BotInventorySessionManager.OpenStatus.BOT_NOT_ACTIVE;
        }

        BotInventorySessionManager.OpenResult result =
                inventorySessions.open(
                        bot.getUUID(),
                        generation,
                        viewer.getUUID());
        if (result.status()
                == BotInventorySessionManager.OpenStatus.EXISTING_SESSION) {
            InventorySessionToken token =
                    result.session().orElseThrow().token();
            if (viewer.containerMenu instanceof BotInventoryMenu menu
                    && menu.sessionToken()
                            .filter(token::equals)
                            .isPresent()) {
                return result.status();
            }
            closeInventorySession(
                    token, InventoryCloseReason.MENU_REPLACED);
            return BotInventorySessionManager.OpenStatus.SESSION_CLOSING;
        }
        if (result.status()
                != BotInventorySessionManager.OpenStatus.OPENING) {
            return result.status();
        }

        InventorySessionToken token =
                result.session().orElseThrow().token();
        BotActionRuntime.GenerationCancellationResult cancellation;
        try {
            cancellation = actionRuntime.cancelBotGenerationNow(
                    bot.getUUID(),
                    generation,
                    ActionCancellationReason.LIFECYCLE,
                    server.getTickCount());
        } catch (RuntimeException exception) {
            failInventoryOpen(token, viewer);
            BotPlayer.LOGGER.error(
                    "Refused BotPlayer inventory menu for bot {} generation {} because "
                            + "the exclusive action drain failed",
                    bot.getUUID(),
                    generation,
                    exception);
            return BotInventorySessionManager.OpenStatus.SESSION_CLOSING;
        }
        if (!cancellation.safeForExclusiveMutation()) {
            failInventoryOpen(token, viewer);
            BotPlayer.LOGGER.error(
                    "Refused BotPlayer inventory menu for bot {} generation {} after unsafe "
                            + "action drain: cleanupFailures={}, quarantined={}, "
                            + "ticketRemaining={}, leaseRemaining={}",
                    bot.getUUID(),
                    generation,
                    cancellation.cleanupFailures(),
                    cancellation.quarantined(),
                    cancellation.ticketRemaining(),
                    cancellation.leaseRemaining());
            return BotInventorySessionManager.OpenStatus.SESSION_CLOSING;
        }

        BotActionTarget refreshedTarget =
                inspectActionTarget(bot.getUUID(), generation);
        BotInventorySession pendingSession =
                inventorySessions
                        .sessionForBot(bot.getUUID())
                        .filter(session ->
                                session.token().equals(token))
                        .orElse(null);
        if (refreshedTarget.status()
                        != BotActionTargetStatus.ACTIVE
                || refreshedTarget.player().orElse(null) != bot
                || pendingSession == null
                || pendingSession.state()
                        != InventorySessionState.OPENING
                || !inventorySessions
                        .revalidate(token)
                        .valid()) {
            failInventoryOpen(token, viewer);
            return BotInventorySessionManager.OpenStatus.SESSION_CLOSING;
        }

        OptionalInt menuId;
        try {
            menuId = viewer.openMenu(
                    new SimpleMenuProvider(
                            (containerId, viewerInventory, player) ->
                                    new BotInventoryMenu(
                                            containerId,
                                            viewerInventory,
                                            bot,
                                            token,
                                            inventorySessions),
                            Component.translatable(
                                    "container.botplayer.inventory")),
                    extraData -> extraData.writeVarInt(bot.getId()));
        } catch (RuntimeException exception) {
            failInventoryOpen(token, viewer);
            BotPlayer.LOGGER.error(
                    "Failed to open BotPlayer inventory menu for bot {} generation {}",
                    bot.getUUID(),
                    generation,
                    exception);
            return BotInventorySessionManager.OpenStatus.SESSION_CLOSING;
        }
        if (menuId.isEmpty()) {
            failInventoryOpen(token, viewer);
            return BotInventorySessionManager.OpenStatus.SESSION_CLOSING;
        }

        BotInventorySessionManager.OpenConfirmationStatus confirmation =
                inventorySessions.markOpened(token);
        if (confirmation
                        != BotInventorySessionManager
                                .OpenConfirmationStatus.OPENED
                && confirmation
                        != BotInventorySessionManager
                                .OpenConfirmationStatus.ALREADY_OPEN) {
            failInventoryOpen(token, viewer);
            return BotInventorySessionManager.OpenStatus.SESSION_CLOSING;
        }
        return result.status();
    }

    public BotServerPlayer spawn(CommandSourceStack source, String requestedName) {
        requireServerThread();
        if (stopping) {
            throw new IllegalStateException("The server is stopping");
        }
        if (!VALID_NAME.matcher(requestedName).matches()) {
            throw new IllegalArgumentException(
                    "Bot name must contain 1-16 ASCII letters, digits, or underscores");
        }
        if (runtimes.size() >= BotPlayerConfig.MAX_BOTS.get()) {
            throw new IllegalStateException("The configured bot limit has been reached");
        }
        if (isNameInUse(requestedName)) {
            throw new IllegalArgumentException("A player or bot with that name is already online");
        }

        @Nullable UUID proposedOwnerId =
                source.getEntity() instanceof ServerPlayer owner
                                && !(owner instanceof BotServerPlayer)
                        ? owner.getUUID()
                        : null;
        BotProfile profile = roster.getOrCreate(requestedName, proposedOwnerId);
        UUID botId = profile.botId();
        String canonicalName = profile.name();
        if (server.getPlayerList().getPlayer(botId) != null) {
            throw new IllegalArgumentException("That BotPlayer identity is already online");
        }
        BotRuntimeHandle handle = handlesByBot.computeIfAbsent(
                botId,
                ignored -> new BotRuntimeHandle(
                        botId,
                        canonicalName,
                        profile.ownerId().orElse(null)));
        if (!handle.name().equals(canonicalName)
                || !handle.ownerId().equals(profile.ownerId())) {
            throw new IllegalStateException(
                    "The retained runtime handle does not match the persistent bot profile");
        }
        if (handle.player().isPresent()) {
            throw new IllegalStateException(
                    "The retained runtime handle is still attached to a player");
        }
        RuntimeEntry runtime = new RuntimeEntry(handle, BotLifecycleState.SPAWNING);
        runtimes.put(botId, runtime);

        ServerLevel level = source.getLevel();
        Vec3 position = source.getPosition();
        Vec2 rotation = source.getRotation();
        GameProfile gameProfile = new GameProfile(botId, canonicalName);
        ClientInformation clientInformation = ClientInformation.createDefault();
        BotServerPlayer player =
                new BotServerPlayer(server, level, gameProfile, clientInformation, handle);
        BotConnection connection = new BotConnection();
        boolean hasExistingPlayerData = Files.isRegularFile(server
                .getWorldPath(LevelResource.PLAYER_DATA_DIR)
                .resolve(botId + ".dat"));

        try {
            CommonListenerCookie cookie = new CommonListenerCookie(
                    gameProfile,
                    0,
                    clientInformation,
                    false,
                    ConnectionType.OTHER);
            server.getPlayerList().placeNewPlayer(connection, player, cookie);
            if (!hasExistingPlayerData) {
                player.teleportTo(
                        level,
                        position.x,
                        position.y,
                        position.z,
                        Set.of(),
                        rotation.y,
                        rotation.x);
            }
            handle.attach(player);
            if (player.isDeadOrDying()) {
                onDeath(player);
            } else {
                transition(runtime, BotLifecycleState.ACTIVE);
                perceptionService.activate(
                        botId, handle.generation());
            }
            BotPlayer.LOGGER.info(
                    "Spawned BotPlayer {} ({}) in {}",
                    canonicalName,
                    botId,
                    level.dimension().location());
            return player;
        } catch (RuntimeException exception) {
            rollbackFailedSpawn(player, connection, runtime);
            throw exception;
        }
    }

    public boolean removeByName(String name, Component reason) {
        requireServerThread();
        RuntimeEntry runtime = findByName(name);
        if (runtime == null) {
            return false;
        }
        disconnect(runtime, reason);
        return true;
    }

    public List<BotSnapshot> snapshots() {
        requireServerThread();
        return runtimes.values().stream()
                .map(runtime -> new BotSnapshot(
                        runtime.handle.botId(),
                        runtime.handle.name(),
                        runtime.state,
                        runtime.handle.generation()))
                .sorted(Comparator.comparing(BotSnapshot::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public NavigationSubmission startNavigation(
            String name, GridPoint target) {
        return startNavigation(
                name, target, NavigationPolicy.safeDefault());
    }

    public NavigationSubmission startNavigation(
            String name,
            GridPoint target,
            NavigationPolicy policy) {
        requireServerThread();
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(policy, "policy");
        RuntimeEntry runtime = findByName(name);
        if (runtime == null
                || runtime.state != BotLifecycleState.ACTIVE) {
            return NavigationSubmission.rejected(
                    NavigationSubmission.Status.BOT_NOT_ACTIVE,
                    "没有活动 BotPlayer：" + name);
        }
        BotServerPlayer player =
                runtime.handle.player().orElseThrow();
        long currentTick = server.getTickCount();
        UUID navigationId = UUID.randomUUID();
        NavigationRequest request = new NavigationRequest(
                navigationId,
                runtime.handle.botId(),
                runtime.handle.generation(),
                new NavigationGoal.ExactPosition(
                        player.serverLevel()
                                .dimension()
                                .location()
                                .toString(),
                        target,
                        0,
                        1),
                policy,
                currentTick + 12_000L,
                12_000,
                "command:" + navigationId);
        return navigationService.submit(request, currentTick);
    }

    public boolean stopNavigation(String name) {
        requireServerThread();
        RuntimeEntry runtime = findByName(name);
        if (runtime == null) {
            return false;
        }
        NavigationSessionView view = navigationService
                .inspect(runtime.handle.botId())
                .orElse(null);
        return view != null
                && navigationService.cancel(
                        view.navigationId(),
                        server.getTickCount(),
                        "由管理命令取消");
    }

    public Optional<NavigationSessionView> navigationSession(
            String name) {
        requireServerThread();
        RuntimeEntry runtime = findByName(name);
        return runtime == null
                ? Optional.empty()
                : navigationService.inspect(runtime.handle.botId());
    }

    public Optional<SafetyIncidentView> safetyIncident(
            String name) {
        requireServerThread();
        RuntimeEntry runtime = findByName(name);
        return runtime == null
                ? Optional.empty()
                : safetyService.inspect(runtime.handle.botId());
    }

    public Optional<SafetyFrame> latestSafetyFrame(String name) {
        requireServerThread();
        RuntimeEntry runtime = findByName(name);
        return runtime == null
                ? Optional.empty()
                : safetyService.latestFrame(runtime.handle.botId());
    }

    public Optional<SurvivalSkillRunView> survivalSkillRun(
            String name) {
        requireServerThread();
        RuntimeEntry runtime = findByName(name);
        return runtime == null
                ? Optional.empty()
                : survivalSkillService.inspect(
                        runtime.handle.botId());
    }

    public SurvivalSkillSubmission startBasicArmor(
            String name) {
        requireServerThread();
        RuntimeEntry runtime = findByName(name);
        if (runtime == null
                || runtime.state
                        != BotLifecycleState.ACTIVE) {
            return SurvivalSkillSubmission.rejected(
                    SurvivalSkillSubmission.Status.BOT_NOT_ACTIVE,
                    "没有活动 BotPlayer：" + name);
        }
        BotServerPlayer player =
                runtime.handle.player().orElse(null);
        if (player == null
                || player.runtimeHandle()
                        != runtime.handle
                || runtime.handle.generation() <= 0L) {
            return SurvivalSkillSubmission.rejected(
                    SurvivalSkillSubmission.Status.BOT_NOT_ACTIVE,
                    "BotPlayer 活动代际尚未就绪");
        }
        return survivalSkillService.startBasicArmor(
                player, server.getTickCount());
    }

    public Optional<ObservationSnapshot> latestPerception(
            String name) {
        requireServerThread();
        RuntimeEntry runtime = findByName(name);
        if (runtime == null
                || runtime.state != BotLifecycleState.ACTIVE) {
            return Optional.empty();
        }
        return perceptionService.latest(
                runtime.handle.botId(),
                runtime.handle.generation());
    }

    public Optional<ObservationSnapshot> latestPerception(
            UUID botId, long expectedGeneration) {
        requireServerThread();
        return perceptionService.latest(
                botId, expectedGeneration);
    }

    public List<WorldFact> recentPerceptionFacts(
            String name, int limit) {
        requireServerThread();
        RuntimeEntry runtime = findByName(name);
        if (runtime == null
                || runtime.state != BotLifecycleState.ACTIVE) {
            return List.of();
        }
        return perceptionService.recentFacts(
                runtime.handle.botId(),
                runtime.handle.generation(),
                limit);
    }

    public long perceptionCoverageGaps(String name) {
        requireServerThread();
        RuntimeEntry runtime = findByName(name);
        if (runtime == null
                || runtime.state != BotLifecycleState.ACTIVE) {
            return 0L;
        }
        return perceptionService.coverageGapCount(
                runtime.handle.botId(),
                runtime.handle.generation());
    }

    /**
     * 仅供服务器诊断与 GameTest 使用的权威事件内部水位。
     */
    public long perceptionAuthoritySequence() {
        requireServerThread();
        return perceptionService.eventBus()
                .currentAuthoritySeq();
    }

    public void correctPerceivedActivity(
            CommandSourceStack source,
            String botName,
            String actorName,
            String correctedActivity) {
        requireServerThread();
        Objects.requireNonNull(source, "source");
        if (!source.hasPermission(2)) {
            throw new IllegalArgumentException(
                    "P3 activity correction requires operator permission");
        }
        RuntimeEntry runtime = findByName(botName);
        if (runtime == null
                || runtime.state != BotLifecycleState.ACTIVE) {
            throw new IllegalArgumentException(
                    "No active BotPlayer named " + botName);
        }
        ServerPlayer actor = server.getPlayerList()
                .getPlayers()
                .stream()
                .filter(player -> player.getScoreboardName()
                        .equalsIgnoreCase(actorName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No online player named " + actorName));
        ServerPlayer corrector = source.getEntity()
                        instanceof ServerPlayer sourcePlayer
                ? sourcePlayer
                : null;
        UUID correctorId = corrector == null
                ? UUID.nameUUIDFromBytes(
                        ("botplayer:command_source:"
                                        + source.getTextName())
                                .getBytes(StandardCharsets.UTF_8))
                : corrector.getUUID();
        String correctorName = corrector == null
                ? source.getTextName()
                : corrector.getScoreboardName();
        perceptionService.recordActivityCorrection(
                runtime.handle.botId(),
                runtime.handle.generation(),
                actor.getUUID(),
                actor.getScoreboardName(),
                correctorId,
                correctorName,
                correctedActivity,
                server.getTickCount());
    }

    public long droppedSoundObservations() {
        requireServerThread();
        return perceptionService.droppedSounds();
    }

    public List<ActionTransition> actionTransitionHistory(int limit) {
        requireServerThread();
        return actionRuntime.transitionHistory(limit);
    }

    public List<BotLifecycleTransition> lifecycleTransitionHistory(int limit) {
        requireServerThread();
        if (limit < 0 || limit > LIFECYCLE_HISTORY_CAPACITY) {
            throw new IllegalArgumentException(
                    "limit must be between 0 and "
                            + LIFECYCLE_HISTORY_CAPACITY);
        }
        if (limit == 0 || lifecycleHistory.isEmpty()) {
            return List.of();
        }
        int skip = Math.max(0, lifecycleHistory.size() - limit);
        return lifecycleHistory.stream().skip(skip).toList();
    }

    /**
     * Applies one generation-owned movement intent immediately before Player#doTick.
     */
    public void applyPlayerInput(BotServerPlayer player) {
        requireServerThread();
        Objects.requireNonNull(player, "player");
        long generation = player.runtimeHandle().generation();
        if (generation <= 0) {
            MinecraftPlayerInputAdapter.clear(player);
            return;
        }
        BotActionTarget target =
                inspectActionTarget(player.getUUID(), generation);
        if (target.status() != BotActionTargetStatus.ACTIVE
                || target.player().orElse(null) != player) {
            MinecraftPlayerInputAdapter.clear(player);
            return;
        }
        inputController.applyOnce(
                player.getUUID(),
                generation,
                server.getTickCount(),
                input -> MinecraftPlayerInputAdapter.apply(player, input));
    }

    /**
     * Publishes the position produced by vanilla physics to listener and chunk tracking state.
     */
    public void syncPlayerInputAfterPhysics(BotServerPlayer player) {
        requireServerThread();
        Objects.requireNonNull(player, "player");
        long generation = player.runtimeHandle().generation();
        if (generation <= 0) {
            return;
        }
        BotActionTarget target =
                inspectActionTarget(player.getUUID(), generation);
        if (target.status() == BotActionTargetStatus.ACTIVE
                && target.player().orElse(null) == player) {
            MinecraftPlayerInputAdapter.syncAfterPhysics(player);
        }
    }

    /**
     * Cancels world-local work and rotates generation after a completed dimension transition.
     */
    public void onChangedDimension(BotServerPlayer player) {
        requireServerThread();
        Objects.requireNonNull(player, "player");
        RuntimeEntry runtime = runtimes.get(player.getUUID());
        if (runtime == null || runtime.state != BotLifecycleState.ACTIVE) {
            return;
        }
        long oldGeneration = runtime.handle.generation();
        if (oldGeneration <= 0
                || resolveCleanupTarget(
                                        player.getUUID(),
                                        oldGeneration)
                                .orElse(null)
                        != player) {
            return;
        }
        GenerationRetirement retirement =
                retireGenerationBestEffort(
                        runtime,
                        oldGeneration,
                        InventoryCloseReason
                                .BOT_DIMENSION_CHANGE);
        if (runtimes.get(
                                runtime.handle.botId())
                        != runtime
                || runtime.disconnectingPlayer
                        != null
                || hasRequestedListenerDisconnect(
                        runtime)) {
            return;
        }
        if (!retirement.safelyClosed()) {
            BotPlayer.LOGGER.error(
                    "Refusing to rotate BotPlayer {} ({}) after dimension change because generation {} did not produce safe action and inventory-layout receipts",
                    runtime.handle.name(),
                    runtime.handle.botId(),
                    oldGeneration,
                    retirement.failure());
            prepareListenerDisconnect(
                    runtime,
                    player,
                    oldGeneration,
                    retirement);
            disconnect(
                    runtime,
                    Component.literal(
                            "Bot generation could not be safely closed after dimension change"));
            return;
        }
        if (runtime.state
                        != BotLifecycleState.ACTIVE
                || runtime.handle.generation()
                        != oldGeneration
                || resolveCleanupTarget(
                                        player.getUUID(),
                                        oldGeneration)
                                .orElse(null)
                        != player) {
            disconnect(
                    runtime,
                    Component.literal(
                            "Bot authority changed during dimension generation closure"));
            return;
        }
        runtime.handle.rotateGeneration(player);
        perceptionService.activate(
                runtime.handle.botId(),
                runtime.handle.generation());
    }

    /**
     * Resolves the current authoritative player only for the requested ACTIVE generation.
     */
    public Optional<BotServerPlayer> resolveActive(
            UUID botId, long expectedGeneration) {
        return resolveActionTarget(botId, expectedGeneration);
    }

    /**
     * Resolves the authoritative ACTIVE body for a generation-bound action.
     */
    public Optional<BotServerPlayer> resolveActionTarget(
            UUID botId, long expectedGeneration) {
        return inspectActionTarget(botId, expectedGeneration).player();
    }

    /**
     * Resolves action authority without ever treating PlayerList membership alone as sufficient.
     *
     * <p>The packet listener must already point at the same body. During vanilla respawn the new
     * body enters PlayerList before ServerGamePacketListenerImpl swaps its authoritative player
     * reference; that interim body is intentionally rejected.
     */
    public BotActionTarget inspectActionTarget(
            UUID botId, long expectedGeneration) {
        requireServerThread();
        requireActionIdentity(botId, expectedGeneration);
        BotRuntimeHandle retainedHandle = handlesByBot.get(botId);
        if (stopping) {
            return target(
                    BotActionTargetStatus.SERVER_STOPPING,
                    retainedHandle,
                    null);
        }

        RuntimeEntry runtime = runtimes.get(botId);
        if (runtime == null) {
            if (retainedHandle == null) {
                return new BotActionTarget(
                        BotActionTargetStatus.UNKNOWN_BOT,
                        0,
                        Optional.empty());
            }
            return target(
                    retainedHandle.generation() == expectedGeneration
                            ? BotActionTargetStatus.NOT_ACTIVE
                            : BotActionTargetStatus.STALE_GENERATION,
                    retainedHandle,
                    null);
        }
        if (retainedHandle != runtime.handle) {
            return target(
                    BotActionTargetStatus.INVALID_INSTANCE,
                    runtime.handle,
                    null);
        }
        if (runtime.handle.generation() != expectedGeneration) {
            return target(
                    BotActionTargetStatus.STALE_GENERATION,
                    runtime.handle,
                    null);
        }
        if (runtime.disconnectingPlayer != null) {
            return target(
                    BotActionTargetStatus.NOT_ACTIVE,
                    runtime.handle,
                    null);
        }
        if (runtime.state != BotLifecycleState.ACTIVE) {
            return target(
                    BotActionTargetStatus.NOT_ACTIVE,
                    runtime.handle,
                    null);
        }
        if (runtime.replacementHandoffInProgress) {
            return target(
                    BotActionTargetStatus.NOT_ACTIVE,
                    runtime.handle,
                    null);
        }

        BotServerPlayer player = runtime.handle.player().orElse(null);
        if (!isAuthoritativeInstance(runtime, player, true)) {
            return target(
                    BotActionTargetStatus.INVALID_INSTANCE,
                    runtime.handle,
                    null);
        }
        return target(
                BotActionTargetStatus.ACTIVE,
                runtime.handle,
                player);
    }

    /**
     * Resolves the exact body for cleanup, including a dead body before
     * listener replacement or a synchronously staged same-connection
     * replacement inheritor for an already armed old-generation cleanup.
     */
    public Optional<BotServerPlayer> resolveCleanupTarget(
            UUID botId, long expectedGeneration) {
        requireServerThread();
        requireActionIdentity(botId, expectedGeneration);
        RuntimeEntry runtime = runtimes.get(botId);
        if (runtime == null
                || handlesByBot.get(botId) != runtime.handle
                || runtime.handle.generation() != expectedGeneration) {
            return Optional.empty();
        }
        BotServerPlayer stagedPlayer =
                runtime.stagedCleanupPlayer;
        if (runtime.stagedCleanupGeneration
                                == expectedGeneration
                && isStagedCleanupAuthority(
                        runtime, stagedPlayer)) {
            return Optional.of(stagedPlayer);
        }
        BotServerPlayer player = runtime.handle.player().orElse(null);
        return isBoundListenerDisconnectTarget(
                                runtime, player)
                        || isRequestedRetirementCleanupTarget(
                                runtime, player)
                        || isAuthoritativeInstance(
                                runtime, player, false)
                ? Optional.of(player)
                : Optional.empty();
    }

    /**
     * Reports whether an exact cleanup target is a replacement body that
     * inherited only body-local state from the requested old generation.
     */
    public boolean isStagedReplacementCleanupTarget(
            UUID botId,
            long expectedGeneration,
            BotServerPlayer player) {
        requireServerThread();
        requireActionIdentity(
                botId, expectedGeneration);
        Objects.requireNonNull(player, "player");
        RuntimeEntry runtime = runtimes.get(botId);
        return runtime != null
                && handlesByBot.get(botId)
                        == runtime.handle
                && runtime.handle.generation()
                        == expectedGeneration
                && runtime.stagedCleanupGeneration
                        == expectedGeneration
                && runtime.stagedCleanupPlayer
                        == player
                && isStagedCleanupAuthority(
                        runtime, player);
    }

    /**
     * Verifies exact persistent ownership and opens the client-local credential screen.
     */
    public void openCredentialScreen(ServerPlayer requester, String name) {
        requireServerThread();
        if (stopping) {
            throw new IllegalStateException("The server is stopping");
        }
        if (requester instanceof BotServerPlayer) {
            throw new IllegalArgumentException("Only a real player can configure credentials");
        }

        RuntimeEntry runtime = findByName(name);
        if (runtime == null || runtime.state != BotLifecycleState.ACTIVE) {
            throw new IllegalArgumentException("No active BotPlayer named " + name);
        }
        if (!isExactOwner(runtime, requester)) {
            throw new IllegalArgumentException("You do not own that BotPlayer");
        }

        PacketDistributor.sendToPlayer(
                requester,
                new OpenCredentialScreenPayload(
                        roster.serverInstanceId(),
                        runtime.handle.botId(),
                        runtime.handle.name(),
                        Optional.ofNullable(activeAgentByBot.get(runtime.handle.botId()))));
    }

    /**
     * Applies a client-agent binding request after authenticating the sending real player.
     */
    public AgentBindingStatus updateAgentBinding(
            ServerPlayer requester, UUID botId, UUID agentId, boolean active) {
        requireServerThread();
        if (stopping) {
            return AgentBindingStatus.BOT_NOT_ACTIVE;
        }
        RuntimeEntry runtime = runtimes.get(botId);
        if (runtime == null || runtime.state != BotLifecycleState.ACTIVE) {
            return AgentBindingStatus.BOT_NOT_ACTIVE;
        }
        if (requester instanceof BotServerPlayer || !isExactOwner(runtime, requester)) {
            return AgentBindingStatus.NOT_OWNER;
        }

        UUID currentAgentId = activeAgentByBot.get(botId);
        if (!active) {
            if (currentAgentId == null) {
                return AgentBindingStatus.ALREADY_UNBOUND;
            }
            if (!currentAgentId.equals(agentId)) {
                return AgentBindingStatus.STALE_AGENT_ID;
            }
            clearAgentBinding(botId);
            return AgentBindingStatus.UNBOUND;
        }

        UUID boundBotId = botByActiveAgent.get(agentId);
        if (boundBotId != null && !boundBotId.equals(botId)) {
            return AgentBindingStatus.AGENT_ID_IN_USE;
        }
        if (agentId.equals(currentAgentId)) {
            return AgentBindingStatus.BOUND;
        }

        AgentBindingStatus status =
                currentAgentId == null
                        ? AgentBindingStatus.BOUND
                        : AgentBindingStatus.REPLACED;
        clearAgentBinding(botId);
        activeAgentByBot.put(botId, agentId);
        botByActiveAgent.put(agentId, botId);
        return status;
    }

    /**
     * Clears transient bindings sponsored by a real player when that player disconnects.
     */
    public void onRealPlayerLogout(ServerPlayer player) {
        requireServerThread();
        if (player instanceof BotServerPlayer) {
            return;
        }

        closeViewerInventory(
                player.getUUID(),
                InventoryCloseReason.VIEWER_DISCONNECTED);
        UUID ownerId = player.getUUID();
        List<UUID> ownedBotIds = activeAgentByBot.keySet().stream()
                .filter(botId -> {
                    RuntimeEntry runtime = runtimes.get(botId);
                    return runtime != null
                            && roster.findById(botId)
                                    .flatMap(BotProfile::ownerId)
                                    .filter(ownerId::equals)
                                    .isPresent();
                })
                .toList();
        ownedBotIds.forEach(this::clearAgentBinding);
    }

    public void onRealPlayerChangedDimension(ServerPlayer player) {
        requireServerThread();
        if (!(player instanceof BotServerPlayer)) {
            closeViewerInventory(
                    player.getUUID(),
                    InventoryCloseReason.DIMENSION_CHANGED);
        }
    }

    public void onDeath(BotServerPlayer player) {
        requireServerThread();
        Objects.requireNonNull(player, "player");
        RuntimeEntry runtime = runtimes.get(player.getUUID());
        if (runtime == null
                || resolveCleanupTarget(
                                        player.getUUID(),
                                        runtime.handle.generation())
                                .orElse(null)
                        != player) {
            return;
        }
        if (runtime.state == BotLifecycleState.DEAD
                || runtime.state == BotLifecycleState.RESPAWNING
                || runtime.state == BotLifecycleState.DESPAWNING) {
            return;
        }

        long generation = runtime.handle.generation();
        GenerationRetirement retirement =
                retireGenerationBestEffort(
                        runtime,
                        generation,
                        InventoryCloseReason.BOT_DEATH);
        boolean generationSafelyClosed =
                retirement.safelyClosed();
        if (runtimes.get(
                                runtime.handle.botId())
                        != runtime
                || runtime.disconnectingPlayer
                        != null
                || hasRequestedListenerDisconnect(
                        runtime)) {
            return;
        }
        transition(runtime, BotLifecycleState.DEAD);
        runtime.respawnCandidate = null;
        runtime.respawnFinalizeDeadlineTick = -1;
        runtime.respawnAttempts = 0;
        runtime.respawnSuppressed =
                !generationSafelyClosed;
        if (generationSafelyClosed
                && BotPlayerConfig.AUTO_RESPAWN.get()) {
            runtime.respawnAtTick =
                    server.getTickCount() + BotPlayerConfig.RESPAWN_DELAY_TICKS.get();
        } else {
            runtime.respawnAtTick = -1;
        }
        if (!generationSafelyClosed) {
            BotPlayer.LOGGER.error(
                    "Suppressing respawn for BotPlayer {} ({}) because generation {} did not produce safe action and inventory-layout receipts",
                    runtime.handle.name(),
                    runtime.handle.botId(),
                    generation,
                    retirement.failure());
        }
    }

    /**
     * Records NeoForge's respawn event without granting authority early.
     *
     * <p>PlayerList fires this event before ServerGamePacketListenerImpl updates its {@code player}
     * field. Final attachment therefore happens only after the initiating packet handler returns.
     */
    public void onRespawnCandidate(BotServerPlayer player) {
        requireServerThread();
        Objects.requireNonNull(player, "player");
        RuntimeEntry runtime = runtimes.get(player.getUUID());
        if (runtime == null
                || runtime.state != BotLifecycleState.RESPAWNING
                || hasRequestedListenerDisconnect(
                        runtime)
                || handlesByBot.get(player.getUUID()) != runtime.handle
                || player.runtimeHandle() != runtime.handle
                || runtime.handle.player().orElse(null) == player
                || server.getPlayerList().getPlayer(player.getUUID()) != player
                || player.serverLevel().getPlayerByUUID(player.getUUID())
                        != player) {
            return;
        }
        runtime.respawnCandidate = player;
    }

    /**
     * Handles a ServerPlayer replacement that completed outside the delayed death-respawn loop.
     */
    public void onConnectionPlayerReplaced(
            BotServerPlayer oldPlayer, BotServerPlayer replacement) {
        requireServerThread();
        Objects.requireNonNull(oldPlayer, "oldPlayer");
        Objects.requireNonNull(replacement, "replacement");
        RuntimeEntry runtime = runtimes.get(oldPlayer.getUUID());
        if (runtime == null
                || runtime.state != BotLifecycleState.ACTIVE
                || runtime.handle.player().orElse(null) != oldPlayer
                || runtime.replacementHandoffInProgress
                || runtime.handoffAborted
                || runtime.stagedCleanupPlayer != null
                || runtime.stagedCleanupGeneration >= 0L
                || runtime.disconnectingPlayer != null
                || !oldPlayer.getUUID()
                        .equals(replacement.getUUID())
                || oldPlayer.connection == null
                || replacement.runtimeHandle() != runtime.handle
                || replacement.connection == null
                || replacement.connection
                        != oldPlayer.connection
                || replacement.connection.player != replacement
                || !isListenerAuthority(replacement)
                || !isBotConnectionOpen(replacement)
                || server.getPlayerList().getPlayer(replacement.getUUID())
                        != replacement
                || replacement.serverLevel()
                                .getPlayerByUUID(replacement.getUUID())
                        != replacement
                || isPresentInAnyLevel(oldPlayer)) {
            return;
        }

        long oldGeneration = runtime.handle.generation();
        runtime.handoffAborted = false;
        runtime.replacementHandoffInProgress =
                true;
        runtime.stagedCleanupGeneration =
                oldGeneration;
        runtime.stagedCleanupPredecessor =
                oldPlayer;
        runtime.stagedCleanupPlayer =
                replacement;
        GenerationRetirement retirement =
                retireGenerationBestEffort(
                        runtime,
                        oldGeneration,
                        InventoryCloseReason.BOT_RESPAWN);
        boolean mayCommit =
                canCommitReplacementHandoff(
                        runtime,
                        oldPlayer,
                        replacement,
                        oldGeneration);
        if (!retirement.safelyClosed()
                || !mayCommit) {
            GenerationRetirement abortReceipt =
                    retirement;
            if (retirement.safelyClosed()
                    && !mayCommit
                    && !hasRequestedListenerDisconnect(
                            runtime)) {
                abortReceipt =
                        new GenerationRetirement(
                                false,
                                new IllegalStateException(
                                        "Replacement authority changed during generation closure"));
            }
            abortStagedReplacementHandoff(
                    runtime,
                    replacement,
                    oldGeneration,
                    abortReceipt,
                    retirement.safelyClosed()
                            ? "Bot replacement authority changed during generation closure"
                            : "Bot replacement inherited an unsafe generation");
            return;
        }

        runtime.stagedCleanupGeneration = -1L;
        runtime.stagedCleanupPredecessor = null;
        runtime.stagedCleanupPlayer = null;
        try {
            runtime.handle.attach(replacement);
            MinecraftPlayerInputAdapter.clear(
                    replacement);
            if (!canActivateReplacement(
                    runtime, replacement)) {
                throw new IllegalStateException(
                        "Replacement authority changed before activation");
            }
            perceptionService.activate(
                    runtime.handle.botId(),
                    runtime.handle.generation());
            if (!canActivateReplacement(
                    runtime, replacement)) {
                throw new IllegalStateException(
                        "Replacement authority changed during activation");
            }
            runtime.replacementHandoffInProgress =
                    false;
        } catch (RuntimeException exception) {
            abortReplacementHandoff(
                    runtime,
                    replacement,
                    exception,
                    "Bot replacement activation failed");
        }
    }

    /**
     * Closes generation-owned physical state while vanilla still owns the
     * exact player body.
     *
     * <p>{@link
     * net.minecraft.server.network.ServerGamePacketListenerImpl#onDisconnect}
     * saves and removes the player. The packet listener must therefore call
     * this hook first; post-disconnect cleanup alone can restore the detached
     * Java object while leaving a temporary skill inventory layout persisted
     * on disk.
     *
     * @return whether vanilla may proceed, must retry after the owning stack,
     *     or must stop because an inconsistent body was failed closed
     */
    public ListenerDisconnectDecision onDisconnecting(
            BotServerPlayer player,
            ServerGamePacketListenerImpl listener,
            Connection connection) {
        requireServerThread();
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(listener, "listener");
        Objects.requireNonNull(connection, "connection");
        RuntimeEntry runtime =
                runtimes.get(player.getUUID());
        if (player.connection != listener
                || listener.player != player) {
            if (runtime == null
                    || player.runtimeHandle()
                            != runtime.handle) {
                return ListenerDisconnectDecision
                        .PROCEED;
            }
            if (runtime
                    .generationRetirementInProgress) {
                return ListenerDisconnectDecision
                        .RETRY;
            }
            abortUnstableListenerDisconnect(
                    runtime,
                    player,
                    server.getPlayerList()
                            .getPlayer(
                                    player.getUUID()),
                    "Disconnect listener and BotPlayer body no longer shared the same connection");
            return ListenerDisconnectDecision.ABORTED;
        }
        if (runtime == null) {
            return ListenerDisconnectDecision.PROCEED;
        }

        BotServerPlayer current =
                runtime.handle.player().orElse(null);
        boolean stagedReplacement =
                isPendingReplacementDisconnect(
                        runtime, player);
        boolean unstagedReplacement =
                isUnstagedReplacementDisconnect(
                        runtime, player);
        if (current != player
                && !stagedReplacement
                && !unstagedReplacement) {
            return ListenerDisconnectDecision.PROCEED;
        }

        /*
         * PlayerRespawnEvent is fired after PlayerList has installed the
         * replacement but before this listener changes its player field.
         * A synchronous event subscriber may request disconnect in that
         * window. Never let vanilla save/remove the predecessor: the listener
         * will retry from a queued TickTask after PlayerList#respawn unwinds.
         */
        ServerPlayer listedPlayer =
                server.getPlayerList()
                        .getPlayer(player.getUUID());
        ServerPlayer levelPlayer =
                player.serverLevel()
                        .getPlayerByUUID(
                                player.getUUID());
        if (listedPlayer != player
                || levelPlayer != player) {
            runtime.handoffAborted = true;
            runtime.respawnSuppressed = true;
            if (isRespawnDisconnectTransition(
                            runtime,
                            player,
                            listener,
                            listedPlayer)
                    || runtime
                            .generationRetirementInProgress) {
                return ListenerDisconnectDecision.RETRY;
            }
            abortUnstableListenerDisconnect(
                    runtime,
                    player,
                    listedPlayer,
                    "Disconnect body diverged from PlayerList or level identity");
            return ListenerDisconnectDecision.ABORTED;
        }

        long generation =
                stagedReplacement
                        ? runtime.stagedCleanupGeneration
                        : runtime.handle.generation();
        if (isPreparedListenerDisconnect(
                runtime,
                player,
                listener,
                connection,
                generation)) {
            transition(
                    runtime,
                    BotLifecycleState.DESPAWNING);
            return ListenerDisconnectDecision.PROCEED;
        }

        if (!bindListenerDisconnect(
                runtime,
                player,
                listener,
                        connection,
                        generation)) {
            runtime.handoffAborted = true;
            transition(
                    runtime,
                    BotLifecycleState.DESPAWNING);
            if (runtime
                    .generationRetirementInProgress) {
                return ListenerDisconnectDecision.RETRY;
            }
            abortUnstableListenerDisconnect(
                    runtime,
                    player,
                    listedPlayer,
                    "Disconnect generation ticket conflicted with an existing listener binding");
            return ListenerDisconnectDecision.ABORTED;
        }
        runtime.handoffAborted |=
                runtime.replacementHandoffInProgress
                        || stagedReplacement
                        || unstagedReplacement;
        transition(
                runtime,
                BotLifecycleState.DESPAWNING);
        if (runtime.generationRetirementInProgress) {
            return ListenerDisconnectDecision.RETRY;
        }
        RuntimeException failure = null;
        boolean safelyClosed = false;
        try {
            if (unstagedReplacement
                    && !stageDisconnectCleanupTarget(
                            runtime,
                            player,
                            generation)) {
                failure = appendFailure(
                        failure,
                        new IllegalStateException(
                                "Disconnecting replacement lost exact cleanup authority"));
            }
            GenerationRetirement retirement =
                    retireGenerationBestEffort(
                            runtime,
                            generation,
                            InventoryCloseReason
                                    .BOT_UNLOADED);
            safelyClosed =
                    retirement.safelyClosed();
            failure = appendFailure(
                    failure,
                    retirement.failure());
        } catch (RuntimeException exception) {
            failure = appendFailure(
                    failure, exception);
        }

        recordDisconnectPreparation(
                runtime,
                generation,
                new GenerationRetirement(
                        safelyClosed && failure == null,
                        failure));
        if (!safelyClosed || failure != null) {
            BotPlayer.LOGGER.error(
                    "BotPlayer {} ({}) is disconnecting after an unsafe pre-save generation closure",
                    runtime.handle.name(),
                    runtime.handle.botId(),
                    failure);
        }
        return ListenerDisconnectDecision.PROCEED;
    }

    /**
     * Converts a deferred disconnect that did not converge after its one
     * queued retry into a no-save fail-closed removal. A still-running
     * retirement keeps ownership and asks the listener to retry once more.
     */
    public boolean onDisconnectRetryExhausted(
            BotServerPlayer player,
            ServerGamePacketListenerImpl listener,
            Connection connection) {
        requireServerThread();
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(listener, "listener");
        Objects.requireNonNull(connection, "connection");
        RuntimeEntry runtime =
                runtimes.get(player.getUUID());
        if (runtime == null
                || player.runtimeHandle()
                        != runtime.handle) {
            return true;
        }
        if (runtime.generationRetirementInProgress) {
            return false;
        }
        abortUnstableListenerDisconnect(
                runtime,
                player,
                server.getPlayerList()
                        .getPlayer(player.getUUID()),
                "Deferred disconnect did not converge to one authoritative body");
        return true;
    }

    public void onDisconnected(
            BotServerPlayer player,
            ServerGamePacketListenerImpl listener,
            Connection connection) {
        requireServerThread();
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(listener, "listener");
        Objects.requireNonNull(connection, "connection");
        RuntimeEntry runtime = runtimes.get(player.getUUID());
        if (runtime == null) {
            return;
        }
        BotServerPlayer current = runtime.handle.player().orElse(null);
        boolean stagedReplacement =
                isPendingReplacementDisconnect(
                        runtime, player);
        boolean unstagedReplacement =
                isUnstagedReplacementDisconnect(
                        runtime, player);
        if (current != player
                && !stagedReplacement
                && !unstagedReplacement) {
            return;
        }

        long generation =
                stagedReplacement
                        ? runtime.stagedCleanupGeneration
                        : runtime.handle.generation();
        boolean preparedListener =
                runtime.disconnectingPlayer == player
                        && runtime.disconnectingListener
                                == listener
                        && runtime.disconnectingConnection
                                == connection
                        && runtime.disconnectingGeneration
                                == generation;
        if (runtime.disconnectingPlayer != null
                && !preparedListener) {
            RuntimeException failure =
                    new IllegalStateException(
                            "Post-disconnect generation did not match the bound pre-save transaction");
            failure = appendFailure(
                    failure,
                    finalizeRuntimeTeardown(
                            runtime, player));
            BotPlayer.LOGGER.error(
                    "BotPlayer {} ({}) disconnected outside its exact generation ticket",
                    runtime.handle.name(),
                    player.getUUID(),
                    failure);
            return;
        }
        if (!preparedListener
                        && (player.connection != listener
                                || listener.player
                                        != player)) {
            return;
        }
        runtime.handoffAborted |=
                stagedReplacement
                        || unstagedReplacement;
        transition(
                runtime,
                BotLifecycleState.DESPAWNING);
        GenerationRetirement retirement;
        if (runtime.disconnectPreparationComplete
                && runtime.disconnectingGeneration
                        == generation) {
            retirement = new GenerationRetirement(
                    runtime
                            .disconnectPreparationSafelyClosed,
                    runtime.disconnectPreparationFailure);
        } else {
            retirement = new GenerationRetirement(
                    false,
                    new IllegalStateException(
                            "Missing exact pre-save disconnect preparation receipt for generation "
                                    + generation));
        }
        RuntimeException failure =
                retirement.failure();
        failure = appendFailure(
                failure,
                finalizeRuntimeTeardown(
                        runtime, player));
        if (failure != null) {
            BotPlayer.LOGGER.error(
                    "BotPlayer {} ({}) disconnected with cleanup failures",
                    runtime.handle.name(),
                    player.getUUID(),
                    failure);
        } else if (!retirement.safelyClosed()) {
            BotPlayer.LOGGER.error(
                    "BotPlayer {} ({}) disconnected without a safe pre-save generation receipt",
                    runtime.handle.name(),
                    player.getUUID());
        }
        BotPlayer.LOGGER.info(
                "Unloaded BotPlayer {} ({})",
                runtime.handle.name(),
                player.getUUID());
    }

    public void tick() {
        requireServerThread();
        if (stopping) {
            return;
        }

        long tickStartedNanos = serverTickStartedNanos;
        serverTickStartedNanos = -1L;
        int currentTick = server.getTickCount();
        for (RuntimeEntry runtime : List.copyOf(runtimes.values())) {
            if (hasRequestedListenerDisconnect(
                    runtime)) {
                continue;
            }
            if (runtime.state == BotLifecycleState.RESPAWNING) {
                if (finalizeRespawnIfAuthoritative(
                        runtime, currentTick)) {
                    continue;
                }
                if (runtime.respawnFinalizeDeadlineTick >= 0
                        && currentTick
                                >= runtime.respawnFinalizeDeadlineTick) {
                    failRespawnAttempt(
                            runtime,
                            currentTick,
                            "listener authority did not converge");
                }
                continue;
            }

            BotServerPlayer attachedPlayer =
                    runtime.handle.player().orElse(null);
            if (attachedPlayer != null
                    && isListenerAuthority(attachedPlayer)) {
                attachedPlayer.tickClientlessConnectionPhase();
            }
            if (runtime.state == BotLifecycleState.DEAD
                    && runtime.respawnAtTick < 0
                    && !runtime.respawnSuppressed
                    && BotPlayerConfig.AUTO_RESPAWN.get()) {
                runtime.respawnAtTick =
                        currentTick + BotPlayerConfig.RESPAWN_DELAY_TICKS.get();
            }
            if (runtime.state != BotLifecycleState.DEAD
                    || runtime.respawnAtTick < 0
                    || currentTick < runtime.respawnAtTick) {
                continue;
            }

            BotServerPlayer player = runtime.handle.player().orElse(null);
            if (player == null || !player.isDeadOrDying() || player.connection == null) {
                continue;
            }

            beginRespawnAttempt(runtime, currentTick);
            try {
                player.connection.handleClientCommand(
                        new ServerboundClientCommandPacket(
                                ServerboundClientCommandPacket.Action
                                        .PERFORM_RESPAWN));
            } catch (RuntimeException exception) {
                failRespawnAttempt(
                        runtime,
                        currentTick,
                        "respawn handler threw "
                                + exception.getClass().getSimpleName());
                continue;
            }
            if (!finalizeRespawnIfAuthoritative(
                            runtime, currentTick)
                    && runtime.respawnFinalizeDeadlineTick
                            == currentTick) {
                failRespawnAttempt(
                        runtime,
                        currentTick,
                        "respawn handler returned without listener authority");
            }
        }
        revalidateInventorySessions();
        for (RuntimeEntry runtime :
                List.copyOf(runtimes.values())) {
            if (runtime.state != BotLifecycleState.ACTIVE) {
                continue;
            }
            BotServerPlayer player =
                    runtime.handle.player().orElse(null);
            if (player != null
                    && isListenerAuthority(player)) {
                safetyService.tickBot(
                        player,
                        runtime.handle.generation(),
                        currentTick);
            }
        }
        survivalSkillService.tick(currentTick);
        navigationService.tick(currentTick);
        actionRuntime.tick(currentTick);
        perceptionService.tick(currentTick);
        if (tickStartedNanos >= 0L) {
            perceptionService.recordTickDurationNanos(
                    Math.max(
                            0L,
                            System.nanoTime() - tickStartedNanos));
        }
    }

    public void shutdown() {
        requireServerThread();
        if (stopping) {
            return;
        }
        closeAllInventories(InventoryCloseReason.SERVER_STOPPING);
        safetyService.shutdown();
        navigationService.close();
        actionRuntime.shutdown(server.getTickCount());
        survivalSkillService.shutdown(
                server.getTickCount(),
                actionRuntime::isGenerationSafe);
        stopping = true;
        try {
            for (RuntimeEntry runtime : new ArrayList<>(runtimes.values())) {
                try {
                    disconnect(runtime, Component.literal("Server stopping"));
                } catch (RuntimeException exception) {
                    BotPlayer.LOGGER.error(
                            "Failed to cleanly unload BotPlayer {} ({}) during server stop",
                            runtime.handle.name(),
                            runtime.handle.botId(),
                            exception);
                    closeFailedShutdownRuntime(runtime);
                }
            }
        } finally {
            perceptionService.shutdown();
            runtimes.clear();
            activeAgentByBot.clear();
            botByActiveAgent.clear();
        }
    }

    private void disconnect(RuntimeEntry runtime, Component reason) {
        if (runtime.disconnectingPlayer != null
                || hasRequestedListenerDisconnect(
                        runtime)) {
            runtime.respawnAtTick = -1;
            runtime.respawnFinalizeDeadlineTick = -1;
            runtime.respawnCandidate = null;
            runtime.respawnSuppressed = true;
            runtime.handoffAborted |=
                    runtime.replacementHandoffInProgress;
            transition(
                    runtime,
                    BotLifecycleState.DESPAWNING);
            if (runtime.disconnectingPlayer != null
                    && !listenerDisconnectRequested(
                            runtime.disconnectingPlayer)
                    && runtime.disconnectingListener
                            instanceof BotGamePacketListener
                                    botListener) {
                botListener.disconnect(reason);
            }
            return;
        }
        runtime.respawnAtTick = -1;
        runtime.respawnFinalizeDeadlineTick = -1;
        runtime.respawnCandidate = null;
        runtime.respawnSuppressed = true;
        BotServerPlayer attached =
                runtime.handle.player().orElse(null);
        BotServerPlayer disconnectBody = attached;
        if (attached != null
                && attached.connection != null
                && attached.connection.player
                        instanceof BotServerPlayer
                                listenerPlayer
                && listenerPlayer.runtimeHandle()
                        == runtime.handle
                && listenerPlayer.getUUID()
                        .equals(runtime.handle.botId())) {
            disconnectBody = listenerPlayer;
        }

        RuntimeException failure = null;
        if (attached != null
                && attached.connection != null) {
            try {
                attached.connection.disconnect(reason);
            } catch (RuntimeException exception) {
                failure = appendFailure(
                        failure, exception);
            }
        }
        if (runtime.disconnectingPlayer == null
                && !hasRequestedListenerDisconnect(
                        runtime)
                && runtimes.get(
                        runtime.handle.botId())
                == runtime) {
            transition(
                    runtime,
                    BotLifecycleState.DESPAWNING);
            GenerationRetirement retirement =
                    retireGenerationBestEffort(
                            runtime,
                            runtime.handle.generation(),
                            InventoryCloseReason
                                    .BOT_UNLOADED);
            failure = appendFailure(
                    failure,
                    retirement.failure());
            failure = appendFailure(
                    failure,
                    finalizeRuntimeTeardown(
                            runtime,
                            disconnectBody));
        }
        if (failure != null) {
            throw failure;
        }
    }

    private GenerationRetirement
            retireGenerationBestEffort(
                    RuntimeEntry runtime,
                    long generation,
                    InventoryCloseReason inventoryReason) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(
                inventoryReason, "inventoryReason");
        if (generation <= 0L) {
            return new GenerationRetirement(
                    true, null);
        }
        if (runtime.generationRetirementInProgress) {
            return new GenerationRetirement(
                    false,
                    new IllegalStateException(
                            "Generation retirement is already in progress"));
        }
        runtime.generationRetirementInProgress =
                true;
        GenerationRetirement retirement;
        try {
            retirement = retireGenerationStarted(
                    runtime,
                    generation,
                    inventoryReason);
        } finally {
            runtime.generationRetirementInProgress =
                    false;
        }
        retirement = rememberGenerationRetirement(
                runtime, generation, retirement);
        captureRequestedDisconnectRetirement(
                runtime, generation, retirement);
        recordDisconnectPreparation(
                runtime, generation, retirement);
        return retirement;
    }

    private static GenerationRetirement
            rememberGenerationRetirement(
                    RuntimeEntry runtime,
                    long generation,
                    GenerationRetirement retirement) {
        if (runtime.rememberedRetirementGeneration
                > generation) {
            return retirement;
        }
        if (runtime.rememberedRetirementGeneration
                < generation) {
            runtime.rememberedRetirementGeneration =
                    generation;
            runtime.rememberedRetirementSafelyClosed =
                    retirement.safelyClosed();
            runtime.rememberedRetirementFailure =
                    retirement.failure();
        } else {
            runtime.rememberedRetirementSafelyClosed &=
                    retirement.safelyClosed();
            runtime.rememberedRetirementFailure =
                    appendFailure(
                            runtime
                                    .rememberedRetirementFailure,
                            retirement.failure());
        }
        return new GenerationRetirement(
                runtime.rememberedRetirementSafelyClosed,
                runtime.rememberedRetirementFailure);
    }

    private GenerationRetirement
            retireGenerationStarted(
                    RuntimeEntry runtime,
                    long generation,
                    InventoryCloseReason inventoryReason) {
        UUID botId = runtime.handle.botId();
        RuntimeException failure = null;
        try {
            closeBotInventory(
                    runtime, inventoryReason);
        } catch (RuntimeException exception) {
            failure = appendFailure(
                    failure, exception);
        }
        if (runtimes.get(botId) != runtime) {
            return new GenerationRetirement(
                    false, failure);
        }
        try {
            cancelBotActions(
                    runtime,
                    generation,
                    ActionCancellationReason.LIFECYCLE);
        } catch (RuntimeException exception) {
            failure = appendFailure(
                    failure, exception);
        }
        if (runtimes.get(botId) != runtime) {
            return new GenerationRetirement(
                    false, failure);
        }
        try {
            inputController.forceClear(
                    botId, generation);
            runtime.handle
                    .player()
                    .ifPresent(
                            MinecraftPlayerInputAdapter
                                    ::clear);
            if (runtime.stagedCleanupPlayer
                    != null) {
                MinecraftPlayerInputAdapter.clear(
                        runtime.stagedCleanupPlayer);
            }
        } catch (RuntimeException exception) {
            failure = appendFailure(
                    failure, exception);
        }
        if (runtimes.get(botId) != runtime) {
            return new GenerationRetirement(
                    false, failure);
        }
        try {
            perceptionService.closeGeneration(
                    botId, generation);
        } catch (RuntimeException exception) {
            failure = appendFailure(
                    failure, exception);
        }
        if (runtimes.get(botId) != runtime) {
            return new GenerationRetirement(
                    false, failure);
        }

        boolean safelyClosed = false;
        try {
            safelyClosed =
                    closeP4Generation(
                            botId, generation);
        } catch (RuntimeException exception) {
            failure = appendFailure(
                    failure, exception);
            try {
                actionRuntime
                        .quarantineBotGenerationNow(
                                botId,
                                generation,
                                server.getTickCount());
            } catch (RuntimeException quarantineFailure) {
                failure = appendFailure(
                        failure,
                        quarantineFailure);
            }
            if (runtimes.get(botId)
                    == runtime) {
                try {
                    closeP4Generation(
                            botId, generation);
                } catch (RuntimeException retryFailure) {
                    failure = appendFailure(
                            failure,
                            retryFailure);
                }
            }
        }
        return new GenerationRetirement(
                safelyClosed && failure == null,
                failure);
    }

    @Nullable
    private RuntimeException finalizeRuntimeTeardown(
            RuntimeEntry runtime,
            @Nullable BotServerPlayer preferredPlayer) {
        return finalizeRuntimeTeardown(
                runtime, preferredPlayer, false);
    }

    @Nullable
    private RuntimeException finalizeRuntimeTeardown(
            RuntimeEntry runtime,
            @Nullable BotServerPlayer preferredPlayer,
            boolean suppressPlayerDataSave) {
        UUID botId = runtime.handle.botId();
        if (runtimes.get(botId) != runtime) {
            return null;
        }
        RuntimeException failure = null;
        try {
            transition(
                    runtime,
                    BotLifecycleState.DESPAWNING);
        } catch (RuntimeException exception) {
            failure = appendFailure(
                    failure, exception);
        }

        BotServerPlayer attached =
                runtime.handle.player().orElse(null);
        BotServerPlayer staged =
                runtime.stagedCleanupPlayer;
        long generation =
                runtime.handle.generation();
        runtime.handoffAborted = true;
        runtime.replacementHandoffInProgress =
                false;
        runtime.disconnectingGeneration = -1L;
        runtime.disconnectingPlayer = null;
        runtime.disconnectingListener = null;
        runtime.disconnectingConnection = null;
        runtime.disconnectPreparationComplete =
                false;
        runtime.disconnectPreparationSafelyClosed =
                false;
        runtime.disconnectPreparationFailure = null;
        runtime.rememberedRetirementGeneration =
                -1L;
        runtime.rememberedRetirementSafelyClosed =
                false;
        runtime.rememberedRetirementFailure = null;
        runtime.stagedCleanupGeneration = -1L;
        runtime.stagedCleanupPredecessor = null;
        runtime.stagedCleanupPlayer = null;
        runtime.respawnAtTick = -1;
        runtime.respawnFinalizeDeadlineTick = -1;
        runtime.respawnCandidate = null;
        runtime.respawnSuppressed = true;
        clearAgentBinding(botId);
        runtimes.remove(botId, runtime);
        try {
            if (generation > 0L) {
                inputController.forgetBot(
                        botId, generation);
            }
        } catch (RuntimeException exception) {
            failure = appendFailure(
                    failure, exception);
        }
        if (attached != null) {
            runtime.handle.detach(attached);
        }

        try {
            perceptionService.closeBot(botId);
        } catch (RuntimeException exception) {
            failure = appendFailure(
                    failure, exception);
        }
        failure = appendFailure(
                failure,
                removePlayerIdentity(
                        attached,
                        suppressPlayerDataSave));
        if (preferredPlayer != attached) {
            failure = appendFailure(
                    failure,
                    removePlayerIdentity(
                            preferredPlayer,
                            suppressPlayerDataSave));
        }
        if (staged != attached
                && staged != preferredPlayer) {
            failure = appendFailure(
                    failure,
                    removePlayerIdentity(
                            staged,
                            suppressPlayerDataSave));
        }
        return failure;
    }

    @Nullable
    private RuntimeException removePlayerIdentity(
            @Nullable BotServerPlayer player) {
        return removePlayerIdentity(player, false);
    }

    @Nullable
    private RuntimeException removePlayerIdentity(
            @Nullable BotServerPlayer player,
            boolean suppressPlayerDataSave) {
        if (player == null) {
            return null;
        }
        RuntimeException failure = null;
        try {
            if (server.getPlayerList()
                                    .getPlayer(
                                            player.getUUID())
                            == player
                    || isPresentInAnyLevel(player)) {
                if (suppressPlayerDataSave) {
                    player.suppressNextPlayerDataSave();
                }
                try {
                    server.getPlayerList()
                            .remove(player);
                } finally {
                    player.clearPlayerDataSaveSuppression();
                }
            }
        } catch (RuntimeException exception) {
            failure = appendFailure(
                    failure, exception);
        }
        try {
            MinecraftPlayerInputAdapter.clear(player);
            if (player.connection != null
                    && player.connection
                                    .getConnection()
                            instanceof BotConnection connection) {
                connection.markClosed();
            }
        } catch (RuntimeException exception) {
            failure = appendFailure(
                    failure, exception);
        }
        return failure;
    }

    private void rollbackFailedSpawn(
            BotServerPlayer player, BotConnection connection, RuntimeEntry runtime) {
        if (runtime.state != BotLifecycleState.DESPAWNING) {
            transition(runtime, BotLifecycleState.DESPAWNING);
        }
        boolean isRegistered = server.getPlayerList().getPlayer(player.getUUID()) == player;
        boolean isInLevel =
                player.serverLevel().getPlayerByUUID(player.getUUID()) == player;
        if ((isRegistered || isInLevel) && player.connection != null) {
            player.connection.disconnect(Component.literal("BotPlayer spawn failed"));
        } else if (isRegistered || isInLevel) {
            server.getPlayerList().remove(player);
        }
        connection.markClosed();
        perceptionService.closeBot(runtime.handle.botId());
        closeP4Generation(
                runtime.handle.botId(),
                runtime.handle.generation());
        clearPlayerInput(runtime, true);
        runtime.handle.detach(player);
        runtimes.remove(runtime.handle.botId());
    }

    private void closeFailedShutdownRuntime(RuntimeEntry runtime) {
        BotServerPlayer player = runtime.handle.player().orElse(null);
        if (player == null) {
            return;
        }
        try {
            if (server.getPlayerList().getPlayer(player.getUUID()) == player
                    || player.serverLevel().getPlayerByUUID(player.getUUID()) == player) {
                server.getPlayerList().remove(player);
            }
        } catch (RuntimeException fallbackFailure) {
            BotPlayer.LOGGER.error(
                    "Fallback removal also failed for BotPlayer {} ({})",
                    runtime.handle.name(),
                    runtime.handle.botId(),
                    fallbackFailure);
        } finally {
            perceptionService.closeBot(runtime.handle.botId());
            closeP4Generation(
                    runtime.handle.botId(),
                    runtime.handle.generation());
            clearPlayerInput(runtime, true);
            if (player.connection != null
                    && player.connection.getConnection() instanceof BotConnection botConnection) {
                botConnection.markClosed();
            }
            runtime.handle.detach(player);
        }
    }

    private void revalidateInventorySessions() {
        for (RuntimeEntry runtime :
                List.copyOf(runtimes.values())) {
            BotInventorySession session = inventorySessions
                    .sessionForBot(runtime.handle.botId())
                    .orElse(null);
            if (session == null) {
                continue;
            }
            InventorySessionToken token = session.token();
            if (!inventorySessions.revalidate(token).valid()) {
                closeInventorySession(
                        token, InventoryCloseReason.VIEWER_INVALID);
            }
        }
    }

    private void closeBotInventory(
            RuntimeEntry runtime, InventoryCloseReason reason) {
        BotInventorySession session = inventorySessions
                .sessionForBot(runtime.handle.botId())
                .orElse(null);
        if (session != null) {
            closeInventorySession(session.token(), reason);
        }
    }

    private void closeViewerInventory(
            UUID viewerId, InventoryCloseReason reason) {
        BotInventorySession session =
                inventorySessions
                        .sessionForViewer(viewerId)
                        .orElse(null);
        if (session != null) {
            closeInventorySession(session.token(), reason);
        }
    }

    private void closeAllInventories(
            InventoryCloseReason reason) {
        List<InventorySessionToken> requested =
                inventorySessions.forceCloseAll(reason);
        for (InventorySessionToken token : requested) {
            closeInventorySession(token, reason);
        }
        for (ServerPlayer player :
                List.copyOf(server.getPlayerList().getPlayers())) {
            BotInventorySession session = inventorySessions
                    .sessionForViewer(player.getUUID())
                    .orElse(null);
            if (session != null) {
                closeInventorySession(session.token(), reason);
            }
        }
    }

    private void closeInventorySession(
            InventorySessionToken token,
            InventoryCloseReason reason) {
        BotInventorySessionManager.ForceCloseStatus status =
                inventorySessions.forceClose(token, reason);
        if (status
                        == BotInventorySessionManager.ForceCloseStatus
                                .NOT_FOUND
                || status
                        == BotInventorySessionManager.ForceCloseStatus
                                .ALREADY_CLOSED) {
            return;
        }

        ServerPlayer viewer =
                server.getPlayerList().getPlayer(token.viewerId());
        if (viewer != null
                && viewer.containerMenu instanceof BotInventoryMenu menu
                && menu.sessionToken().filter(token::equals).isPresent()) {
            viewer.closeContainer();
        }
        inventorySessions.confirmClosed(token);
    }

    private void failInventoryOpen(
            InventorySessionToken token,
            ServerPlayer viewer) {
        inventorySessions.failOpen(token);
        if (viewer.containerMenu
                        instanceof BotInventoryMenu menu
                && menu.sessionToken()
                        .filter(token::equals)
                        .isPresent()) {
            viewer.closeContainer();
        }
    }

    private boolean canWriteBotInventory(
            UUID botId, UUID viewerId) {
        ServerPlayer viewer =
                server.getPlayerList().getPlayer(viewerId);
        if (viewer == null || viewer instanceof BotServerPlayer) {
            return false;
        }
        boolean owner = roster.findById(botId)
                .flatMap(BotProfile::ownerId)
                .filter(viewerId::equals)
                .isPresent();
        return owner
                || server.getPlayerList()
                        .isOp(viewer.getGameProfile());
    }

    private InventoryLifecycleValidator.LifecycleStatus
            validateInventoryLifecycle(
                    UUID botId, long expectedGeneration) {
        return switch (inspectActionTarget(
                        botId, expectedGeneration)
                .status()) {
            case ACTIVE ->
                    InventoryLifecycleValidator.LifecycleStatus.ACTIVE;
            case UNKNOWN_BOT ->
                    InventoryLifecycleValidator.LifecycleStatus.UNKNOWN_BOT;
            case NOT_ACTIVE ->
                    InventoryLifecycleValidator.LifecycleStatus.NOT_ACTIVE;
            case STALE_GENERATION ->
                    InventoryLifecycleValidator.LifecycleStatus
                            .STALE_GENERATION;
            case INVALID_INSTANCE ->
                    InventoryLifecycleValidator.LifecycleStatus
                            .INVALID_INSTANCE;
            case SERVER_STOPPING ->
                    InventoryLifecycleValidator.LifecycleStatus
                            .SERVER_STOPPING;
        };
    }

    private InventoryDistanceValidator.SpatialStatus
            validateInventoryDistance(UUID botId, UUID viewerId) {
        ServerPlayer viewer =
                server.getPlayerList().getPlayer(viewerId);
        if (viewer == null
                || viewer instanceof BotServerPlayer
                || !viewer.isAlive()
                || viewer.isDeadOrDying()) {
            return InventoryDistanceValidator.SpatialStatus
                    .VIEWER_NOT_ALIVE;
        }
        RuntimeEntry runtime = runtimes.get(botId);
        BotServerPlayer bot = runtime == null
                ? null
                : runtime.handle.player().orElse(null);
        if (runtime == null
                || runtime.state != BotLifecycleState.ACTIVE
                || bot == null
                || !bot.isAlive()
                || bot.isDeadOrDying()
                || !isListenerAuthority(bot)) {
            return InventoryDistanceValidator.SpatialStatus
                    .BOT_NOT_ALIVE;
        }
        if (viewer.serverLevel() != bot.serverLevel()) {
            return InventoryDistanceValidator.SpatialStatus
                    .DIFFERENT_DIMENSION;
        }
        double maximumDistance =
                BotPlayerConfig.INVENTORY_VIEW_DISTANCE.get();
        return viewer.distanceToSqr(bot)
                        <= maximumDistance * maximumDistance
                ? InventoryDistanceValidator.SpatialStatus.IN_RANGE
                : InventoryDistanceValidator.SpatialStatus.OUT_OF_RANGE;
    }

    private void beginRespawnAttempt(
            RuntimeEntry runtime, int currentTick) {
        transition(runtime, BotLifecycleState.RESPAWNING);
        runtime.respawnAtTick = -1;
        runtime.respawnCandidate = null;
        runtime.respawnAttempts++;
        runtime.respawnFinalizeDeadlineTick =
                currentTick + RESPAWN_FINALIZE_TIMEOUT_TICKS;
    }

    private boolean finalizeRespawnIfAuthoritative(
            RuntimeEntry runtime, int currentTick) {
        if (runtime.state != BotLifecycleState.RESPAWNING
                || hasRequestedListenerDisconnect(
                        runtime)) {
            return false;
        }
        BotServerPlayer oldPlayer =
                runtime.handle.player().orElse(null);
        if (oldPlayer == null || oldPlayer.connection == null) {
            return false;
        }
        ServerPlayer listenerPlayer = oldPlayer.connection.player;
        if (!(listenerPlayer instanceof BotServerPlayer replacement)
                || replacement == oldPlayer
                || replacement.runtimeHandle() != runtime.handle
                || replacement.connection != oldPlayer.connection
                || replacement.connection.player != replacement
                || server.getPlayerList().getPlayer(replacement.getUUID())
                        != replacement
                || replacement.serverLevel()
                                .getPlayerByUUID(replacement.getUUID())
                        != replacement
                || (runtime.respawnCandidate != null
                        && runtime.respawnCandidate != replacement)) {
            return false;
        }

        clearPlayerInput(runtime, false);
        runtime.handle.attach(replacement);
        MinecraftPlayerInputAdapter.clear(replacement);
        transition(runtime, BotLifecycleState.ACTIVE);
        perceptionService.activate(
                runtime.handle.botId(),
                runtime.handle.generation());
        runtime.respawnAtTick = -1;
        runtime.respawnFinalizeDeadlineTick = -1;
        runtime.respawnCandidate = null;
        runtime.respawnAttempts = 0;
        runtime.respawnSuppressed = false;
        return true;
    }

    private void failRespawnAttempt(
            RuntimeEntry runtime,
            int currentTick,
            String safeReason) {
        if (runtime.state != BotLifecycleState.RESPAWNING) {
            return;
        }
        BotPlayer.LOGGER.error(
                "BotPlayer respawn attempt {} failed for {} ({}): {}",
                runtime.respawnAttempts,
                runtime.handle.name(),
                runtime.handle.botId(),
                safeReason);
        runtime.respawnCandidate = null;
        runtime.respawnFinalizeDeadlineTick = -1;
        transition(runtime, BotLifecycleState.DEAD);

        BotServerPlayer oldPlayer =
                runtime.handle.player().orElse(null);
        boolean retryable =
                oldPlayer != null
                        && isAuthoritativeInstance(
                                runtime, oldPlayer, false)
                        && oldPlayer.isDeadOrDying()
                        && oldPlayer.connection != null;
        if (retryable
                && runtime.respawnAttempts < MAX_RESPAWN_ATTEMPTS
                && BotPlayerConfig.AUTO_RESPAWN.get()) {
            runtime.respawnAtTick =
                    currentTick + RESPAWN_RETRY_DELAY_TICKS;
            return;
        }
        runtime.respawnAtTick = -1;
        runtime.respawnSuppressed = true;
    }

    private void cancelBotActions(
            RuntimeEntry runtime,
            long throughGeneration,
            ActionCancellationReason reason) {
        if (throughGeneration <= 0) {
            return;
        }
        actionRuntime.cancelBotNow(
                runtime.handle.botId(),
                throughGeneration,
                reason,
                server.getTickCount());
    }

    private boolean closeP4Generation(
            UUID botId, long generation) {
        if (generation <= 0L) {
            return true;
        }
        long currentTick = server.getTickCount();
        boolean safelyClosed = false;
        RuntimeException failure = null;
        try {
            safelyClosed =
                    survivalSkillService.closeGeneration(
                            botId,
                            generation,
                            currentTick,
                            actionRuntime
                                    .isGenerationSafe(
                                            botId,
                                            generation));
        } catch (RuntimeException exception) {
            failure = appendFailure(
                    failure, exception);
        }
        try {
            navigationService.closeGeneration(
                    botId, generation, currentTick);
        } catch (RuntimeException exception) {
            failure = appendFailure(
                    failure, exception);
        }
        try {
            safetyService.closeGeneration(
                    botId, generation, currentTick);
        } catch (RuntimeException exception) {
            failure = appendFailure(
                    failure, exception);
        }
        if (failure != null) {
            throw failure;
        }
        return safelyClosed;
    }

    private void clearPlayerInput(
            RuntimeEntry runtime, boolean forget) {
        long generation = runtime.handle.generation();
        if (generation <= 0) {
            return;
        }
        inputController.forceClear(
                runtime.handle.botId(), generation);
        runtime.handle
                .player()
                .ifPresent(MinecraftPlayerInputAdapter::clear);
        if (forget) {
            inputController.forgetBot(
                    runtime.handle.botId(), generation);
        }
    }

    private boolean transition(
            RuntimeEntry runtime, BotLifecycleState next) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(next, "next");
        BotLifecycleState previous = runtime.state;
        if (previous == next) {
            return false;
        }
        previous.requireTransitionTo(next);
        runtime.state = next;
        long generation = runtime.handle.generation();
        if (generation > 0) {
            if (lifecycleHistory.size()
                    == LIFECYCLE_HISTORY_CAPACITY) {
                lifecycleHistory.removeFirst();
            }
            lifecycleHistory.addLast(new BotLifecycleTransition(
                    runtime.handle.botId(),
                    generation,
                    previous,
                    next,
                    server.getTickCount()));
        }
        return true;
    }

    private boolean isAuthoritativeInstance(
            RuntimeEntry runtime,
            @Nullable BotServerPlayer player,
            boolean requireAlive) {
        return player != null
                && handlesByBot.get(runtime.handle.botId())
                        == runtime.handle
                && player.runtimeHandle() == runtime.handle
                && (!requireAlive || !player.isDeadOrDying())
                && server.getPlayerList()
                                .getPlayer(runtime.handle.botId())
                        == player
                && player.serverLevel()
                                .getPlayerByUUID(runtime.handle.botId())
                        == player
                && isListenerAuthority(player);
    }

    private boolean isStagedCleanupAuthority(
            RuntimeEntry runtime,
            @Nullable BotServerPlayer player) {
        BotServerPlayer predecessor =
                runtime.stagedCleanupPredecessor;
        boolean boundDisconnectTarget =
                isBoundListenerDisconnectTarget(
                        runtime, player);
        boolean requestedRetirementTarget =
                isRequestedRetirementCleanupTarget(
                        runtime, player);
        return player != null
                && predecessor != null
                && (runtime.state
                                == BotLifecycleState.ACTIVE
                        || boundDisconnectTarget
                        || requestedRetirementTarget)
                && handlesByBot.get(
                                runtime.handle.botId())
                        == runtime.handle
                && player.runtimeHandle()
                        == runtime.handle
                && predecessor.runtimeHandle()
                        == runtime.handle
                && runtime.handle.player()
                                .orElse(null)
                        == predecessor
                && predecessor.getUUID()
                        .equals(
                                runtime.handle.botId())
                && player.getUUID()
                        .equals(
                                runtime.handle.botId())
                && predecessor.connection != null
                && predecessor.connection
                        == player.connection
                && predecessor.connection.player
                        == player
                && server.getPlayerList()
                                .getPlayer(
                                        runtime.handle
                                                .botId())
                        == player
                && player.serverLevel()
                                .getPlayerByUUID(
                                        runtime.handle
                                                .botId())
                        == player
                && !isPresentInAnyLevel(
                        predecessor)
                && (boundDisconnectTarget
                        || requestedRetirementTarget
                        || (isListenerAuthority(player)
                                && isBotConnectionOpen(
                                        player)));
    }

    private boolean stageDisconnectCleanupTarget(
            RuntimeEntry runtime,
            BotServerPlayer player,
            long generation) {
        BotServerPlayer predecessor =
                runtime.handle.player().orElse(null);
        if (generation <= 0L
                || predecessor == null
                || predecessor == player
                || handlesByBot.get(
                                runtime.handle.botId())
                        != runtime.handle
                || runtime.handle.generation()
                        != generation
                || player.runtimeHandle()
                        != runtime.handle
                || predecessor.runtimeHandle()
                        != runtime.handle
                || !player.getUUID()
                        .equals(runtime.handle.botId())
                || predecessor.connection == null
                || predecessor.connection
                        != player.connection
                || predecessor.connection.player
                        != player
                || server.getPlayerList()
                                .getPlayer(
                                        runtime.handle
                                                .botId())
                        != player
                || player.serverLevel()
                                .getPlayerByUUID(
                                        runtime.handle
                                                .botId())
                        != player
                || isPresentInAnyLevel(predecessor)
                || !isBoundListenerDisconnectTarget(
                        runtime, player)) {
            return false;
        }
        runtime.replacementHandoffInProgress =
                true;
        runtime.stagedCleanupGeneration = generation;
        runtime.stagedCleanupPredecessor =
                predecessor;
        runtime.stagedCleanupPlayer = player;
        return isStagedCleanupAuthority(
                runtime, player);
    }

    private static boolean bindListenerDisconnect(
            RuntimeEntry runtime,
            BotServerPlayer player,
            ServerGamePacketListenerImpl listener,
            Connection connection,
            long generation) {
        if (generation < 0L) {
            return false;
        }
        if (runtime.disconnectingPlayer == null) {
            runtime.disconnectingPlayer = player;
            runtime.disconnectingListener = listener;
            runtime.disconnectingConnection =
                    connection;
            runtime.disconnectingGeneration =
                    generation;
            return true;
        }
        return runtime.disconnectingPlayer == player
                && runtime.disconnectingListener
                        == listener
                && runtime.disconnectingConnection
                        == connection
                && runtime.disconnectingGeneration
                        == generation;
    }

    private static boolean
            isPreparedListenerDisconnect(
                    RuntimeEntry runtime,
                    BotServerPlayer player,
                    ServerGamePacketListenerImpl listener,
                    Connection connection,
                    long generation) {
        return runtime.disconnectPreparationComplete
                && runtime.disconnectingPlayer == player
                && runtime.disconnectingListener
                        == listener
                && runtime.disconnectingConnection
                        == connection
                && runtime.disconnectingGeneration
                        == generation;
    }

    private boolean prepareListenerDisconnect(
            RuntimeEntry runtime,
            BotServerPlayer player,
            long generation,
            GenerationRetirement retirement) {
        if (generation <= 0L
                || runtimes.get(
                                runtime.handle.botId())
                        != runtime
                || handlesByBot.get(
                                runtime.handle.botId())
                        != runtime.handle
                || player.runtimeHandle()
                        != runtime.handle
                || !player.getUUID()
                        .equals(runtime.handle.botId())
                || !(player.connection
                        instanceof BotGamePacketListener
                                listener)
                || listener.player != player
                || server.getPlayerList()
                                .getPlayer(
                                        runtime.handle
                                                .botId())
                        != player
                || player.serverLevel()
                                .getPlayerByUUID(
                                        runtime.handle
                                                .botId())
                        != player
                || !isExactDisconnectGenerationTarget(
                        runtime, player, generation)
                || !isBotConnectionOpen(player)
                || !bindListenerDisconnect(
                        runtime,
                        player,
                        listener,
                        listener.getConnection(),
                        generation)) {
            return false;
        }
        recordDisconnectPreparation(
                runtime, generation, retirement);
        return true;
    }

    private void captureRequestedDisconnectRetirement(
            RuntimeEntry runtime,
            long generation,
            GenerationRetirement retirement) {
        BotServerPlayer staged =
                runtime.stagedCleanupPlayer;
        if (listenerDisconnectRequested(staged)
                && prepareListenerDisconnect(
                        runtime,
                        staged,
                        generation,
                        retirement)) {
            return;
        }
        BotServerPlayer current =
                runtime.handle.player().orElse(null);
        if (listenerDisconnectRequested(current)) {
            prepareListenerDisconnect(
                    runtime,
                    current,
                    generation,
                    retirement);
        }
    }

    private boolean
            isExactDisconnectGenerationTarget(
                    RuntimeEntry runtime,
                    BotServerPlayer player,
                    long generation) {
        if (runtime.handle.generation()
                != generation) {
            return false;
        }
        BotServerPlayer current =
                runtime.handle.player().orElse(null);
        if (current == player) {
            return true;
        }
        return runtime
                                .replacementHandoffInProgress
                        && runtime.stagedCleanupGeneration
                                == generation
                        && runtime.stagedCleanupPlayer
                                == player
                        && runtime.stagedCleanupPredecessor
                                == current
                        && current != null
                        && current.connection
                                == player.connection
                        && current.runtimeHandle()
                                == runtime.handle
                        && !isPresentInAnyLevel(
                                current);
    }

    private boolean isRespawnDisconnectTransition(
            RuntimeEntry runtime,
            BotServerPlayer predecessor,
            ServerGamePacketListenerImpl listener,
            @Nullable ServerPlayer listedPlayer) {
        if (!(listedPlayer
                instanceof BotServerPlayer successor)) {
            return false;
        }
        return successor != predecessor
                && runtime.handle.player()
                                .orElse(null)
                        == predecessor
                && predecessor.runtimeHandle()
                        == runtime.handle
                && successor.runtimeHandle()
                        == runtime.handle
                && successor.getUUID()
                        .equals(runtime.handle.botId())
                && predecessor.connection
                        == listener
                && successor.connection
                        == listener
                && listener.player
                        == predecessor
                && successor.serverLevel()
                                .getPlayerByUUID(
                                        runtime.handle
                                                .botId())
                        == successor
                && !isPresentInAnyLevel(
                        predecessor);
    }

    private void abortUnstableListenerDisconnect(
            RuntimeEntry runtime,
            BotServerPlayer listenerPlayer,
            @Nullable ServerPlayer listedPlayer,
            String reason) {
        runtime.handoffAborted = true;
        runtime.respawnAtTick = -1;
        runtime.respawnFinalizeDeadlineTick = -1;
        runtime.respawnSuppressed = true;
        RuntimeException failure = null;
        try {
            transition(
                    runtime,
                    BotLifecycleState.DESPAWNING);
        } catch (RuntimeException exception) {
            failure = appendFailure(
                    failure, exception);
        }
        GenerationRetirement retirement =
                retireGenerationBestEffort(
                        runtime,
                        runtime.handle.generation(),
                        InventoryCloseReason
                                .BOT_UNLOADED);
        failure = appendFailure(
                failure,
                retirement.failure());
        failure = appendFailure(
                failure,
                finalizeRuntimeTeardown(
                        runtime,
                        listenerPlayer,
                        true));
        if (listedPlayer
                        instanceof BotServerPlayer listedBot
                && listedBot.runtimeHandle()
                        == runtime.handle
                && listedBot != listenerPlayer) {
            failure = appendFailure(
                    failure,
                    removePlayerIdentity(
                            listedBot,
                            true));
        }
        BotPlayer.LOGGER.error(
                "{}; BotPlayer {} ({}) was failed closed without invoking vanilla disconnect (generation safely closed: {})",
                reason,
                runtime.handle.name(),
                runtime.handle.botId(),
                retirement.safelyClosed(),
                failure);
    }

    private static void recordDisconnectPreparation(
            RuntimeEntry runtime,
            long generation,
            GenerationRetirement retirement) {
        if (runtime.disconnectingPlayer == null
                || runtime.disconnectingGeneration
                        != generation) {
            return;
        }
        if (runtime.disconnectPreparationComplete) {
            runtime.disconnectPreparationSafelyClosed &=
                    retirement.safelyClosed();
            runtime.disconnectPreparationFailure =
                    appendFailure(
                            runtime.disconnectPreparationFailure,
                            retirement.failure());
            return;
        }
        runtime.disconnectPreparationComplete =
                true;
        runtime.disconnectPreparationSafelyClosed =
                retirement.safelyClosed();
        runtime.disconnectPreparationFailure =
                retirement.failure();
    }

    private boolean
            isBoundListenerDisconnectTarget(
                    RuntimeEntry runtime,
                    @Nullable BotServerPlayer player) {
        return player != null
                && !runtime.disconnectPreparationComplete
                && handlesByBot.get(
                                runtime.handle.botId())
                        == runtime.handle
                && player.runtimeHandle()
                        == runtime.handle
                && player.getUUID()
                        .equals(runtime.handle.botId())
                && runtime.disconnectingPlayer == player
                && runtime.disconnectingListener
                        == player.connection
                && runtime.disconnectingConnection
                        == player.connection
                                .getConnection()
                && runtime.disconnectingGeneration
                        == runtime.handle.generation()
                && player.connection.player == player
                && server.getPlayerList()
                                .getPlayer(
                                        runtime.handle
                                                .botId())
                        == player
                && player.serverLevel()
                                .getPlayerByUUID(
                                        runtime.handle
                                                .botId())
                        == player
                && isBotConnectionOpen(player);
    }

    private boolean
            isRequestedRetirementCleanupTarget(
                    RuntimeEntry runtime,
                    @Nullable BotServerPlayer player) {
        return runtime.generationRetirementInProgress
                && player != null
                && player.runtimeHandle()
                        == runtime.handle
                && player.connection != null
                && player.connection.player == player
                && player.connection
                                instanceof BotGamePacketListener
                                        botListener
                && botListener.disconnectRequested()
                && server.getPlayerList()
                                .getPlayer(
                                        runtime.handle
                                                .botId())
                        == player
                && player.serverLevel()
                                .getPlayerByUUID(
                                        runtime.handle
                                                .botId())
                        == player
                && isBotConnectionOpen(player);
    }

    private static boolean
            hasRequestedListenerDisconnect(
                    RuntimeEntry runtime) {
        return listenerDisconnectRequested(
                        runtime.handle
                                .player()
                                .orElse(null))
                || listenerDisconnectRequested(
                        runtime.stagedCleanupPlayer)
                || listenerDisconnectRequested(
                        runtime.respawnCandidate);
    }

    private static boolean listenerDisconnectRequested(
            @Nullable BotServerPlayer player) {
        return player != null
                && player.connection
                                instanceof BotGamePacketListener
                                        botListener
                && botListener.disconnectRequested();
    }

    private boolean canCommitReplacementHandoff(
            RuntimeEntry runtime,
            BotServerPlayer predecessor,
            BotServerPlayer replacement,
            long oldGeneration) {
        return runtimes.get(
                                runtime.handle.botId())
                        == runtime
                && runtime.state
                        == BotLifecycleState.ACTIVE
                && runtime
                        .replacementHandoffInProgress
                && !runtime.handoffAborted
                && runtime.handle.generation()
                        == oldGeneration
                && runtime.handle.player()
                                .orElse(null)
                        == predecessor
                && runtime.stagedCleanupGeneration
                        == oldGeneration
                && runtime.stagedCleanupPredecessor
                        == predecessor
                && runtime.stagedCleanupPlayer
                        == replacement
                && isStagedCleanupAuthority(
                        runtime, replacement);
    }

    private boolean canActivateReplacement(
            RuntimeEntry runtime,
            BotServerPlayer replacement) {
        return runtimes.get(
                                runtime.handle.botId())
                        == runtime
                && runtime.state
                        == BotLifecycleState.ACTIVE
                && runtime
                        .replacementHandoffInProgress
                && !runtime.handoffAborted
                && runtime.handle.player()
                                .orElse(null)
                        == replacement
                && isAuthoritativeInstance(
                        runtime, replacement, true)
                && isBotConnectionOpen(replacement);
    }

    private void abortStagedReplacementHandoff(
            RuntimeEntry runtime,
            BotServerPlayer replacement,
            long oldGeneration,
            GenerationRetirement retirement,
            String reason) {
        if (runtimes.get(
                        runtime.handle.botId())
                != runtime) {
            return;
        }
        runtime.handoffAborted = true;
        runtime.respawnAtTick = -1;
        runtime.respawnFinalizeDeadlineTick = -1;
        runtime.respawnCandidate = null;
        runtime.respawnSuppressed = true;
        RuntimeException failure =
                retirement.failure();
        try {
            transition(
                    runtime,
                    BotLifecycleState.DESPAWNING);
        } catch (RuntimeException exception) {
            failure = appendFailure(
                    failure, exception);
        }

        GenerationRetirement stickyRetirement =
                new GenerationRetirement(
                        retirement.safelyClosed()
                                && failure == null,
                        failure);
        if (prepareListenerDisconnect(
                runtime,
                replacement,
                oldGeneration,
                stickyRetirement)) {
            try {
                MinecraftPlayerInputAdapter.clear(
                        replacement);
                replacement.connection.disconnect(
                        Component.literal(reason));
            } catch (RuntimeException exception) {
                RuntimeException disconnectFailure =
                        appendFailure(
                                failure, exception);
                recordDisconnectPreparation(
                        runtime,
                        oldGeneration,
                        new GenerationRetirement(
                                false,
                                disconnectFailure));
                if (!listenerDisconnectRequested(
                        replacement)) {
                    disconnectFailure =
                            appendFailure(
                                    disconnectFailure,
                                    finalizeRuntimeTeardown(
                                            runtime,
                                            replacement,
                                            true));
                }
                failure = disconnectFailure;
            }
        } else {
            failure = appendFailure(
                    failure,
                    new IllegalStateException(
                            "Could not bind the exact staged replacement to its old-generation disconnect receipt"));
            failure = appendFailure(
                    failure,
                    finalizeRuntimeTeardown(
                            runtime,
                            replacement,
                            true));
        }
        if (failure != null) {
            BotPlayer.LOGGER.error(
                    "{} for BotPlayer {} ({})",
                    reason,
                    runtime.handle.name(),
                    runtime.handle.botId(),
                    failure);
        } else if (!retirement.safelyClosed()) {
            BotPlayer.LOGGER.error(
                    "{} for BotPlayer {} ({}) without a safe old-generation receipt",
                    reason,
                    runtime.handle.name(),
                    runtime.handle.botId());
        }
    }

    private void abortReplacementHandoff(
            RuntimeEntry runtime,
            BotServerPlayer replacement,
            @Nullable RuntimeException priorFailure,
            String reason) {
        if (runtimes.get(
                        runtime.handle.botId())
                != runtime) {
            return;
        }
        RuntimeException failure = priorFailure;
        runtime.handoffAborted = true;
        try {
            transition(
                    runtime,
                    BotLifecycleState.DESPAWNING);
        } catch (RuntimeException exception) {
            failure = appendFailure(
                    failure, exception);
        }
        runtime.stagedCleanupGeneration = -1L;
        runtime.stagedCleanupPredecessor = null;
        runtime.stagedCleanupPlayer = null;
        try {
            if (replacement.runtimeHandle()
                            == runtime.handle
                    && replacement.getUUID()
                            .equals(
                                    runtime.handle.botId())
                    && runtime.handle.player()
                                    .orElse(null)
                            != replacement) {
                runtime.handle.attach(replacement);
            }
            MinecraftPlayerInputAdapter.clear(
                    replacement);
        } catch (RuntimeException exception) {
            failure = appendFailure(
                    failure, exception);
        }
        runtime.replacementHandoffInProgress =
                false;
        if (runtime.handle.player()
                        .orElse(null)
                == replacement) {
            try {
                disconnect(
                        runtime,
                        Component.literal(reason));
            } catch (RuntimeException exception) {
                failure = appendFailure(
                        failure, exception);
            }
        }
        if (runtime.disconnectingPlayer == null
                && !hasRequestedListenerDisconnect(
                        runtime)
                && runtimes.get(
                        runtime.handle.botId())
                == runtime) {
            failure = appendFailure(
                    failure,
                    finalizeRuntimeTeardown(
                            runtime, replacement));
        }
        if (failure != null) {
            BotPlayer.LOGGER.error(
                    "{} for BotPlayer {} ({})",
                    reason,
                    runtime.handle.name(),
                    runtime.handle.botId(),
                    failure);
        } else {
            BotPlayer.LOGGER.error(
                    "{} for BotPlayer {} ({})",
                    reason,
                    runtime.handle.name(),
                    runtime.handle.botId());
        }
    }

    private static boolean isBotConnectionOpen(
            BotServerPlayer player) {
        return player.connection != null
                && player.connection.getConnection()
                        instanceof BotConnection connection
                && connection.snapshot().open();
    }

    private static boolean
            isPendingReplacementDisconnect(
                    RuntimeEntry runtime,
                    BotServerPlayer player) {
        BotServerPlayer predecessor =
                runtime.stagedCleanupPredecessor;
        return runtime
                        .replacementHandoffInProgress
                && runtime.stagedCleanupGeneration
                        == runtime.handle.generation()
                && runtime.stagedCleanupPlayer
                        == player
                && predecessor != null
                && runtime.handle.player()
                                .orElse(null)
                        == predecessor
                && player.runtimeHandle()
                        == runtime.handle
                && predecessor.runtimeHandle()
                        == runtime.handle
                && player.getUUID()
                        .equals(
                                runtime.handle.botId())
                && predecessor.connection != null
                && predecessor.connection
                        == player.connection;
    }

    private static boolean
            isUnstagedReplacementDisconnect(
                    RuntimeEntry runtime,
                    BotServerPlayer player) {
        BotServerPlayer predecessor =
                runtime.handle.player().orElse(null);
        return !runtime
                        .replacementHandoffInProgress
                && predecessor != null
                && predecessor != player
                && player.runtimeHandle()
                        == runtime.handle
                && predecessor.runtimeHandle()
                        == runtime.handle
                && player.getUUID()
                        .equals(
                                runtime.handle.botId())
                && predecessor.connection != null
                && predecessor.connection
                        == player.connection
                && predecessor.connection.player
                        == player;
    }

    private boolean isPresentInAnyLevel(
            BotServerPlayer player) {
        for (ServerLevel level :
                server.getAllLevels()) {
            for (ServerPlayer candidate :
                    level.players()) {
                if (candidate == player) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isListenerAuthority(
            BotServerPlayer player) {
        return player.connection != null
                && player.connection.player == player
                && (!(player.connection
                                instanceof BotGamePacketListener
                                        botListener)
                        || botListener
                                .acceptsRuntimeAuthority());
    }

    private static BotActionTarget target(
            BotActionTargetStatus status,
            @Nullable BotRuntimeHandle handle,
            @Nullable BotServerPlayer player) {
        return new BotActionTarget(
                status,
                handle == null ? 0 : handle.generation(),
                Optional.ofNullable(player));
    }

    private static void requireActionIdentity(
            UUID botId, long expectedGeneration) {
        Objects.requireNonNull(botId, "botId");
        if (botId.equals(new UUID(0L, 0L))) {
            throw new IllegalArgumentException(
                    "botId must not be zero");
        }
        if (expectedGeneration <= 0) {
            throw new IllegalArgumentException(
                    "expectedGeneration must be positive");
        }
    }

    private static @Nullable RuntimeException appendFailure(
            @Nullable RuntimeException current,
            @Nullable RuntimeException next) {
        if (next == null) {
            return current;
        }
        if (current == null) {
            return next;
        }
        if (current != next) {
            current.addSuppressed(next);
        }
        return current;
    }

    @Nullable
    private RuntimeEntry findByName(String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        return runtimes.values().stream()
                .filter(runtime ->
                        runtime.handle.name().toLowerCase(Locale.ROOT).equals(normalized))
                .findFirst()
                .orElse(null);
    }

    private boolean isNameInUse(String requestedName) {
        return server.getPlayerList().getPlayers().stream()
                .anyMatch(player -> player.getGameProfile().getName().equalsIgnoreCase(requestedName));
    }

    private boolean isExactOwner(RuntimeEntry runtime, ServerPlayer requester) {
        return roster.findById(runtime.handle.botId())
                .flatMap(BotProfile::ownerId)
                .filter(requester.getUUID()::equals)
                .isPresent();
    }

    private void clearAgentBinding(UUID botId) {
        UUID agentId = activeAgentByBot.remove(botId);
        if (agentId != null) {
            botByActiveAgent.remove(agentId, botId);
        }
    }

    private void requireServerThread() {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Bot lifecycle mutation must run on the server thread");
        }
    }

    private static final class RuntimeEntry {
        private final BotRuntimeHandle handle;
        private BotLifecycleState state;
        private int respawnAtTick = -1;
        private int respawnFinalizeDeadlineTick = -1;
        private int respawnAttempts;
        private boolean respawnSuppressed;
        private boolean replacementHandoffInProgress;
        private boolean handoffAborted;
        private boolean generationRetirementInProgress;
        private boolean disconnectPreparationComplete;
        private boolean disconnectPreparationSafelyClosed;
        private long disconnectingGeneration = -1L;
        private long rememberedRetirementGeneration =
                -1L;
        private boolean rememberedRetirementSafelyClosed;
        private long stagedCleanupGeneration = -1L;
        @Nullable
        private BotServerPlayer disconnectingPlayer;
        @Nullable
        private ServerGamePacketListenerImpl
                disconnectingListener;
        @Nullable
        private Connection disconnectingConnection;
        @Nullable
        private RuntimeException
                disconnectPreparationFailure;
        @Nullable
        private RuntimeException
                rememberedRetirementFailure;
        @Nullable
        private BotServerPlayer respawnCandidate;
        @Nullable
        private BotServerPlayer stagedCleanupPredecessor;
        @Nullable
        private BotServerPlayer stagedCleanupPlayer;

        private RuntimeEntry(BotRuntimeHandle handle, BotLifecycleState state) {
            this.handle = handle;
            this.state = state;
        }
    }

    private record GenerationRetirement(
            boolean safelyClosed,
            @Nullable RuntimeException failure) {}
}
