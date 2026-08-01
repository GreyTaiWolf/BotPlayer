package io.github.greytaiwolf.botplayer.lifecycle;

import com.mojang.authlib.GameProfile;
import io.github.greytaiwolf.botplayer.BotPlayer;
import io.github.greytaiwolf.botplayer.action.ActionCancellationReason;
import io.github.greytaiwolf.botplayer.action.ActionEnvelope;
import io.github.greytaiwolf.botplayer.action.ActionMailbox;
import io.github.greytaiwolf.botplayer.action.ActionPriority;
import io.github.greytaiwolf.botplayer.action.ActionTransition;
import io.github.greytaiwolf.botplayer.action.BotActionRuntime;
import io.github.greytaiwolf.botplayer.action.GenerationDrainStatus;
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
import io.github.greytaiwolf.botplayer.lifecycle.retirement.GenerationRetirementContinuation;
import io.github.greytaiwolf.botplayer.lifecycle.retirement.GenerationRetirementFailure;
import io.github.greytaiwolf.botplayer.lifecycle.retirement.GenerationRetirementKey;
import io.github.greytaiwolf.botplayer.lifecycle.retirement.GenerationRetirementReceipt;
import io.github.greytaiwolf.botplayer.lifecycle.retirement.GenerationRetirementSession;
import io.github.greytaiwolf.botplayer.lifecycle.retirement.GenerationRetirementStatus;
import io.github.greytaiwolf.botplayer.lifecycle.retirement.GenerationRetirementTicket;
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
import java.util.LinkedHashSet;
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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameRules;
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

        BotInventorySessionManager.OpenStatus probe =
                inventorySessions.probeOpen(
                        bot.getUUID(),
                        generation,
                        viewer.getUUID());
        if (probe != BotInventorySessionManager.OpenStatus.OPENING) {
            if (probe
                    != BotInventorySessionManager
                            .OpenStatus.EXISTING_SESSION) {
                return probe;
            }
            BotInventorySession existingSession =
                    inventorySessions
                            .sessionForBot(bot.getUUID())
                            .orElse(null);
            return existingSession == null
                    ? BotInventorySessionManager.OpenStatus
                            .SESSION_CLOSING
                    : finishExistingInventoryOpen(
                            viewer, existingSession);
        }

        /*
         * 先让动作运行时把菜单事务收口，再创建查看者写锁。反过来会让
         * cleanup 自己被 mayCleanupMutateInventory 拒绝，留下不安全 prefix。
         */
        BotActionRuntime.GenerationCancellationResult cancellation;
        try {
            cancellation = actionRuntime.cancelBotGenerationNow(
                    bot.getUUID(),
                    generation,
                    ActionCancellationReason.LIFECYCLE,
                    server.getTickCount());
        } catch (RuntimeException exception) {
            BotPlayer.LOGGER.error(
                    "Refused BotPlayer inventory menu for bot {} generation {} because "
                            + "the exclusive action drain failed",
                    bot.getUUID(),
                    generation,
                    exception);
            return BotInventorySessionManager.OpenStatus.BOT_LOCKED;
        }
        if (!cancellation.safeForExclusiveMutation()) {
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
            return BotInventorySessionManager.OpenStatus.BOT_LOCKED;
        }

        BotActionTarget refreshedTarget =
                inspectActionTarget(bot.getUUID(), generation);
        if (refreshedTarget.status()
                        != BotActionTargetStatus.ACTIVE
                || refreshedTarget.player().orElse(null) != bot) {
            return BotInventorySessionManager.OpenStatus.BOT_NOT_ACTIVE;
        }

        BotInventorySessionManager.OpenResult result =
                inventorySessions.open(
                        bot.getUUID(),
                        generation,
                        viewer.getUUID());
        if (result.status()
                != BotInventorySessionManager.OpenStatus.OPENING) {
            return result.status()
                            == BotInventorySessionManager
                                    .OpenStatus.EXISTING_SESSION
                    ? finishExistingInventoryOpen(
                            viewer,
                            result.session().orElseThrow())
                    : result.status();
        }

        InventorySessionToken token =
                result.session().orElseThrow().token();
        BotInventorySession pendingSession =
                inventorySessions
                        .sessionForBot(bot.getUUID())
                        .filter(session ->
                                session.token().equals(token))
                        .orElse(null);
        if (pendingSession == null
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

    private BotInventorySessionManager.OpenStatus finishExistingInventoryOpen(
            ServerPlayer viewer,
            BotInventorySession session) {
        InventorySessionToken token =
                session.token();
        if (viewer.containerMenu instanceof BotInventoryMenu menu
                && menu.sessionToken().filter(token::equals).isPresent()) {
            return BotInventorySessionManager.OpenStatus.EXISTING_SESSION;
        }
        closeInventorySession(token, InventoryCloseReason.MENU_REPLACED);
        return BotInventorySessionManager.OpenStatus.SESSION_CLOSING;
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

    /**
     * 按稳定身份读取最近技能视图，供 body 已隔离移除后的诊断与验收使用。
     */
    public Optional<SurvivalSkillRunView> survivalSkillRun(
            UUID botId) {
        requireServerThread();
        Objects.requireNonNull(botId, "botId");
        return survivalSkillService.inspect(botId);
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
        if (runtime == null) {
            player.releaseCompletedDeathAttemptSaveFence();
            return;
        }
        long generation = runtime.handle.generation();
        BotServerPlayer exactTarget =
                resolveCleanupTarget(
                                player.getUUID(), generation)
                        .orElse(null);
        if (exactTarget != player) {
            if (!player.adoptCompletedDeathSaveFence()) {
                player.armDeathRetirementSaveFence();
            }
            BotServerPlayer successor =
                    knownDeathSuccessor(runtime, player);
            failDeathRetirementWithoutSave(
                    runtime,
                    player,
                    successor,
                    new IllegalStateException(
                            "Death was observed on a non-authoritative BotPlayer body"));
            return;
        }
        if (runtime.state == BotLifecycleState.DEAD) {
            PendingDeathRetirement pending =
                    runtime.deathRetirement;
            if (pending != null
                    && !isExactDeathRetirementAuthority(
                            runtime, pending)) {
                if (!player.adoptCompletedDeathSaveFence()) {
                    player.armDeathRetirementSaveFence();
                }
                failDeathRetirementWithoutSave(
                        runtime,
                        player,
                        knownDeathSuccessor(runtime, player),
                        new IllegalStateException(
                                "Repeated death found a non-authoritative pending retirement"));
                return;
            }
            player.releaseCompletedDeathAttemptSaveFence();
            return;
        }
        if (runtime.state == BotLifecycleState.RESPAWNING) {
            if (hasCompleteDeathRetirement(runtime)
                    && runtime.handle.player().orElse(null)
                            == player) {
                player.releaseCompletedDeathAttemptSaveFence();
                return;
            }
            if (!player.adoptCompletedDeathSaveFence()) {
                player.armDeathRetirementSaveFence();
            }
            failDeathRetirementWithoutSave(
                    runtime,
                    player,
                    knownDeathSuccessor(runtime, player),
                    new IllegalStateException(
                            "Death interrupted respawn without a complete retirement owner"));
            return;
        }
        if (runtime.state == BotLifecycleState.DESPAWNING) {
            boolean existingOwner =
                    runtime.pendingNoSaveTeardown != null
                            || runtime.directDisconnectRetirement
                                    != null
                            || runtime.disconnectingPlayer != null
                            || runtime.generationRetirementInProgress
                            || runtime.deathRetirementFailClosedInProgress;
            if (existingOwner) {
                player.releaseCompletedDeathAttemptSaveFence();
                return;
            }
            if (!player.adoptCompletedDeathSaveFence()) {
                player.armDeathRetirementSaveFence();
            }
            failDeathRetirementWithoutSave(
                    runtime,
                    player,
                    knownDeathSuccessor(runtime, player),
                    new IllegalStateException(
                            "Death entered DESPAWNING without a teardown owner"));
            return;
        }
        if (!player.adoptCompletedDeathSaveFence()) {
            /* Loaded-dead/manual observations have no enclosing die invocation. */
            player.armDeathRetirementSaveFence();
        }
        if (!player.serverLevel()
                .getGameRules()
                .getBoolean(GameRules.RULE_KEEPINVENTORY)) {
            /*
             * Vanilla has already dropped and cleared this inventory before
             * ServerPlayer.die's TAIL. Release only this death attempt's save
             * ownership and retain the legacy synchronous retirement path. A
             * failed layout receipt leaves the empty dead body saveable and
             * suppresses respawn; restoring a pre-death multiset here would
             * duplicate the drops already committed to the world.
             */
            boolean deathFenceFullyReleased =
                    player.releaseDeathRetirementSaveFence();
            finishVanillaConsumedDeath(
                    runtime,
                    deathFenceFullyReleased);
            return;
        }

        if (runtime.generationRetirementInProgress
                || runtime.replacementHandoffInProgress
                || runtime.stagedCleanupGeneration >= 0L
                || runtime.stagedCleanupPredecessor != null
                || runtime.stagedCleanupPlayer != null) {
            player.armDeathRetirementSaveFence();
            failDeathRetirementWithoutSave(
                    runtime,
                    player,
                    runtime.stagedCleanupPlayer,
                    new IllegalStateException(
                            "Death overlapped another generation retirement"));
            return;
        }
        transition(runtime, BotLifecycleState.DEAD);
        runtime.respawnCandidate = null;
        runtime.respawnFinalizeDeadlineTick = -1;
        runtime.respawnAttempts = 0;
        runtime.respawnSuppressed = true;
        runtime.respawnAtTick = -1;
        runtime.completedDeathRetirement = null;

        if (!(player.connection
                        instanceof BotGamePacketListener listener)
                || listener.player != player
                || !isBotConnectionOpen(player)) {
            player.armDeathRetirementSaveFence();
            RuntimeException failure =
                    new IllegalStateException(
                            "Death retirement could not bind the authoritative listener");
            failDeathRetirementWithoutSave(
                    runtime, player, null, failure);
            return;
        }

        Connection connection = listener.getConnection();
        long startedTick = server.getTickCount();
        GenerationRetirementKey key =
                new GenerationRetirementKey(
                        UUID.randomUUID(),
                        runtime.handle.botId(),
                        generation,
                        GenerationRetirementContinuation
                                .DEATH_RESPAWN);
        PendingDeathRetirement pending =
                new PendingDeathRetirement(
                        player,
                        listener,
                        connection,
                        generation,
                        GenerationRetirementSession.open(
                                key,
                                startedTick,
                                boundedRetirementDeadline(
                                        startedTick)));
        runtime.deathRetirement = pending;
        player.armDeathRetirementSaveFence();
        if (!isExactDeathRetirementAuthority(
                runtime, pending)) {
            pending.failure = appendFailure(
                    pending.failure,
                    new IllegalStateException(
                            "Death retirement could not freeze its authoritative body"));
            return;
        }
        advanceDeathRetirementAttempt(
                runtime, pending, startedTick);
    }

    @Nullable
    private BotServerPlayer knownDeathSuccessor(
            RuntimeEntry runtime,
            @Nullable BotServerPlayer predecessor) {
        UUID botId = runtime.handle.botId();
        ServerPlayer listed =
                server.getPlayerList().getPlayer(botId);
        if (listed instanceof BotServerPlayer successor
                && successor != predecessor) {
            return successor;
        }
        if (predecessor != null
                && predecessor.connection != null
                && predecessor.connection.player
                        instanceof BotServerPlayer successor
                && successor != predecessor
                && successor.getUUID().equals(botId)) {
            return successor;
        }
        BotServerPlayer[] retained = {
            runtime.respawnCandidate,
            runtime.stagedCleanupPlayer,
            runtime.stagedCleanupPredecessor,
            runtime.handle.player().orElse(null)
        };
        for (BotServerPlayer candidate : retained) {
            if (candidate != null
                    && candidate != predecessor
                    && candidate.getUUID().equals(botId)) {
                return candidate;
            }
        }
        for (ServerLevel level : server.getAllLevels()) {
            ServerPlayer levelPlayer =
                    level.getPlayerByUUID(botId);
            if (levelPlayer instanceof BotServerPlayer successor
                    && successor != predecessor) {
                return successor;
            }
            Entity entity = level.getEntity(botId);
            if (entity instanceof BotServerPlayer successor
                    && successor != predecessor) {
                return successor;
            }
        }
        return null;
    }

    /**
     * Transitional vanilla-consumed path for keepInventory=false. Vanilla has
     * already committed drops and cleared the inventory, so only an already-safe
     * synchronous generation close may authorize respawn. An unsafe active layout
     * remains on the dead body with normal saving enabled.
     */
    private void finishVanillaConsumedDeath(
            RuntimeEntry runtime,
            boolean deathFenceFullyReleased) {
        long generation = runtime.handle.generation();
        long startedTick = server.getTickCount();
        GenerationRetirement retirement =
                retireGenerationBestEffort(
                        runtime,
                        generation,
                        InventoryCloseReason.BOT_DEATH);
        boolean generationSafelyClosed =
                retirement.safelyClosed()
                        && deathFenceFullyReleased;
        if (runtimes.get(runtime.handle.botId())
                        != runtime
                || runtime.disconnectingPlayer != null
                || hasRequestedListenerDisconnect(runtime)) {
            return;
        }

        transition(runtime, BotLifecycleState.DEAD);
        runtime.deathRetirement = null;
        runtime.completedDeathRetirement =
                generationSafelyClosed
                        ? completedVanillaConsumedDeathReceipt(
                                runtime,
                                generation,
                                startedTick)
                        : null;
        runtime.respawnCandidate = null;
        runtime.respawnFinalizeDeadlineTick = -1;
        runtime.respawnAttempts = 0;
        runtime.respawnSuppressed =
                !generationSafelyClosed;
        runtime.respawnAtTick =
                generationSafelyClosed
                                && BotPlayerConfig.AUTO_RESPAWN.get()
                        ? server.getTickCount()
                                + BotPlayerConfig.RESPAWN_DELAY_TICKS.get()
                        : -1;
        if (!generationSafelyClosed) {
            BotPlayer.LOGGER.error(
                    "Suppressing respawn for BotPlayer {} ({}) because vanilla-consumed generation {} did not produce safe action and inventory-layout receipts",
                    runtime.handle.name(),
                    runtime.handle.botId(),
                    generation,
                    retirement.failure());
        }
    }

    private static GenerationRetirementReceipt
            completedVanillaConsumedDeathReceipt(
                    RuntimeEntry runtime,
                    long generation,
                    long startedTick) {
        GenerationRetirementKey key =
                new GenerationRetirementKey(
                        UUID.randomUUID(),
                        runtime.handle.botId(),
                        generation,
                        GenerationRetirementContinuation
                                .DEATH_RESPAWN);
        long deadlineTick =
                boundedRetirementDeadline(startedTick);
        GenerationRetirementSession session =
                GenerationRetirementSession.open(
                        key, startedTick, deadlineTick);
        GenerationRetirementTicket ticket =
                new GenerationRetirementTicket(
                        key,
                        startedTick,
                        deadlineTick,
                        startedTick,
                        1);
        return session.observe(
                        ticket,
                        GenerationRetirementStatus.COMPLETE,
                        1L,
                        -1L)
                .receipt();
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

        PendingDeathRetirement deathRetirement =
                runtime.deathRetirement;
        if (deathRetirement != null) {
            if (!deathRetirement.matchesBinding(
                            player,
                            listener,
                            connection,
                            runtime.handle.generation())
                    || !isExactDeathRetirementAuthority(
                            runtime, deathRetirement)) {
                deathRetirement.failure = appendFailure(
                        deathRetirement.failure,
                        new IllegalStateException(
                                "Disconnect changed authority during death retirement"));
                failDeathRetirementWithoutSave(
                        runtime,
                        deathRetirement.player,
                        player,
                        deathRetirement.failure);
                return ListenerDisconnectDecision.ABORTED;
            }
            if (deathRetirement.status()
                    == GenerationRetirementStatus.UNSAFE) {
                failDeathRetirementWithoutSave(
                        runtime,
                        deathRetirement.player,
                        null,
                        deathRetirement.failure);
                return ListenerDisconnectDecision.ABORTED;
            }
            return ListenerDisconnectDecision.RETRY;
        }

        PendingDirectDisconnectRetirement directRetirement =
                runtime.directDisconnectRetirement;
        if (directRetirement != null
                && !directRetirement.matchesBinding(
                        player,
                        listener,
                        connection,
                        runtime.handle.generation())) {
            abortUnstableListenerDisconnect(
                    runtime,
                    player,
                    server.getPlayerList()
                            .getPlayer(player.getUUID()),
                    "Direct disconnect retirement lost its exact body binding");
            return ListenerDisconnectDecision.ABORTED;
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
        Player levelPlayer =
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
            if (!runtime
                            .disconnectPreparationSafelyClosed
                    || runtime
                                    .disconnectPreparationFailure
                            != null) {
                abortUnstableListenerDisconnect(
                        runtime,
                        player,
                        listedPlayer,
                        "Prepared disconnect did not hold a safe pre-save generation receipt");
                return ListenerDisconnectDecision.ABORTED;
            }
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
        boolean directCurrentBody =
                current == player
                        && !stagedReplacement
                        && !unstagedReplacement;
        if (runtime.directDisconnectRetirement != null
                || directCurrentBody) {
            return advanceDirectListenerDisconnect(
                    runtime,
                    player,
                    listener,
                    connection,
                    generation);
        }
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
            abortUnstableListenerDisconnect(
                    runtime,
                    player,
                    listedPlayer,
                    "Disconnect retirement did not produce a safe pre-save generation receipt");
            return ListenerDisconnectDecision.ABORTED;
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
        PendingDirectDisconnectRetirement directRetirement =
                runtime.directDisconnectRetirement;
        if (directRetirement != null
                && directRetirement.matchesBinding(
                        player,
                        listener,
                        connection,
                        runtime.handle.generation())
                && directRetirement.status()
                        == GenerationRetirementStatus.PENDING) {
            return false;
        }
        PendingDeathRetirement deathRetirement =
                runtime.deathRetirement;
        if (deathRetirement != null
                && deathRetirement.matchesBinding(
                        player,
                        listener,
                        connection,
                        runtime.handle.generation())
                && isExactDeathRetirementAuthority(
                        runtime, deathRetirement)
                && deathRetirement.status()
                        != GenerationRetirementStatus.UNSAFE) {
            return false;
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
                    && hasCompleteDeathRetirement(runtime)
                    && BotPlayerConfig.AUTO_RESPAWN.get()) {
                runtime.respawnAtTick =
                        currentTick + BotPlayerConfig.RESPAWN_DELAY_TICKS.get();
            }
            if (runtime.state != BotLifecycleState.DEAD
                    || runtime.respawnAtTick < 0
                    || currentTick < runtime.respawnAtTick
                    || !hasCompleteDeathRetirement(runtime)) {
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
        advanceDirectDisconnectRetirements(currentTick);
        advanceDeathRetirements(currentTick);
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
        long currentTick = server.getTickCount();
        stopping = true;
        List<RuntimeEntry> shutdownRuntimes =
                new ArrayList<>(runtimes.values());
        RuntimeException preparationFailure = null;

        /*
         * 必须先于任何菜单/动作回调建立保存 fence。setter 本身不进入原版
         * 回调；随后完整 ticket 再覆盖 PlayerList、level、replacement 与
         * 已绑定 listener 能看到的全部 exact body。
         */
        for (RuntimeEntry runtime : shutdownRuntimes) {
            BotServerPlayer attached =
                    runtime.handle.player().orElse(null);
            armImmediatelyKnownNoSaveBodies(
                    runtime, attached, null);
            try {
                PendingNoSaveTeardown teardown =
                        rememberNoSaveTeardown(
                                runtime, attached, null);
                for (BotServerPlayer exactBody :
                        teardown.exactBodies) {
                    exactBody
                            .suppressPlayerDataSaveUntilReleased();
                }
            } catch (RuntimeException exception) {
                preparationFailure = appendFailure(
                        preparationFailure, exception);
            }
        }
        try {
            closeAllInventories(
                    InventoryCloseReason.SERVER_STOPPING);
        } catch (RuntimeException exception) {
            preparationFailure = appendFailure(
                    preparationFailure, exception);
        }
        try {
            safetyService.shutdown();
        } catch (RuntimeException exception) {
            preparationFailure = appendFailure(
                    preparationFailure, exception);
        }
        try {
            navigationService.close();
        } catch (RuntimeException exception) {
            preparationFailure = appendFailure(
                    preparationFailure, exception);
        }
        try {
            actionRuntime.shutdown(currentTick);
        } catch (RuntimeException exception) {
            preparationFailure = appendFailure(
                    preparationFailure, exception);
        }

        /*
         * 任一准备步骤失去证明后，不能再依据局部 runtime 视图走原版保存。
         * 仍继续为每个 exact body 建票、上持久 fence 并执行 no-save 移除，
         * 这样一次 shutdown 异常不会跳过后续 Bot 的隔离。
         */
        boolean allowNormalPlayerSave =
                preparationFailure == null;
        if (!allowNormalPlayerSave) {
            BotPlayer.LOGGER.error(
                    "BotPlayer shutdown preparation failed; all remaining bots will use no-save isolation",
                    preparationFailure);
        }
        try {
            for (RuntimeEntry runtime : shutdownRuntimes) {
                try {
                    PendingNoSaveTeardown teardown =
                            rememberNoSaveTeardown(
                                    runtime,
                                    runtime.handle.player()
                                            .orElse(null),
                                    null);
                    UUID botId = teardown.botId;
                    boolean normalDisconnectStarted =
                            allowNormalPlayerSave
                                    && tryNormalShutdownDisconnect(
                                            runtime,
                                            teardown);
                    if (!normalDisconnectStarted
                            || runtimes.get(botId) == runtime) {
                        RuntimeException isolationFailure =
                                finalizeRuntimeTeardownWithoutSave(
                                        runtime,
                                        runtime.handle.player()
                                                .orElse(null),
                                        null);
                        if (isolationFailure != null) {
                            throw isolationFailure;
                        }
                    }
                } catch (RuntimeException exception) {
                    BotPlayer.LOGGER.error(
                            "Failed to cleanly unload BotPlayer {} ({}) during server stop",
                            runtime.handle.name(),
                            runtime.handle.botId(),
                            exception);
                    closeFailedShutdownRuntime(runtime);
                }
            }
            try {
                survivalSkillService.shutdown(
                        currentTick,
                        (botId, generation) ->
                                allowNormalPlayerSave
                                        && actionRuntime
                                                .isGenerationSafe(
                                                        botId,
                                                        generation));
            } catch (RuntimeException exception) {
                BotPlayer.LOGGER.error(
                        "Failed to close BotPlayer survival skill state during server stop",
                        exception);
            }
        } finally {
            try {
                perceptionService.shutdown();
            } catch (RuntimeException exception) {
                BotPlayer.LOGGER.error(
                        "Failed to close BotPlayer perception state during server stop",
                        exception);
            } finally {
                runtimes.clear();
                activeAgentByBot.clear();
                botByActiveAgent.clear();
            }
        }
    }

    /**
     * 保持 persistent fence 完成动作、技能布局与 listener 票据的全部退休；只有
     * topology 仍是单 body/单 listener 时才释放真正会被 vanilla 保存的 body。
     */
    private boolean tryNormalShutdownDisconnect(
            RuntimeEntry runtime,
            PendingNoSaveTeardown teardown) {
        UUID botId = teardown.botId;
        long generation = teardown.retiredGeneration;
        BotServerPlayer player =
                runtime.handle.player().orElse(null);
        if (runtime.directDisconnectRetirement != null
                || runtime.deathRetirement != null
                || runtimes.get(botId) != runtime
                || runtime.handle.generation()
                        != generation
                || player == null
                || !actionRuntime.isGenerationSafe(
                        botId, generation)) {
            return false;
        }

        GenerationRetirement retirement =
                retireGenerationBestEffort(
                        runtime,
                        generation,
                        InventoryCloseReason.SERVER_STOPPING);
        if (!retirement.safelyClosed()
                || retirement.failure() != null
                || !prepareListenerDisconnect(
                        runtime,
                        player,
                        generation,
                        retirement)) {
            return false;
        }

        PendingNoSaveTeardown refreshed =
                rememberNoSaveTeardown(
                        runtime, player, null);
        if (refreshed != teardown
                || !isSingleShutdownSaveTopology(
                        runtime,
                        refreshed,
                        player,
                        generation)) {
            return false;
        }

        player.releasePlayerDataSaveSuppression();
        disconnect(
                runtime,
                Component.literal("Server stopping"));
        return true;
    }

    private boolean isSingleShutdownSaveTopology(
            RuntimeEntry runtime,
            PendingNoSaveTeardown teardown,
            BotServerPlayer player,
            long generation) {
        if (!(player.connection
                        instanceof BotGamePacketListener listener)) {
            return false;
        }
        Connection connection = listener.getConnection();
        return teardown.exactBodies.size() == 1
                && teardown.exactBodies.contains(player)
                && teardown.exactListeners.size() == 1
                && teardown.exactListeners.contains(listener)
                && teardown.boundDisconnectListener == listener
                && teardown.boundDisconnectConnection == connection
                && isPreparedListenerDisconnect(
                        runtime,
                        player,
                        listener,
                        connection,
                        generation)
                && runtimes.get(teardown.botId) == runtime
                && runtime.handle.player().orElse(null)
                        == player
                && runtime.handle.generation()
                        == generation
                && player.connection == listener
                && listener.player == player
                && server.getPlayerList()
                                .getPlayer(teardown.botId)
                        == player
                && player.serverLevel()
                                .getPlayerByUUID(
                                        teardown.botId)
                        == player;
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

    private ListenerDisconnectDecision
            advanceDirectListenerDisconnect(
                    RuntimeEntry runtime,
                    BotServerPlayer player,
                    ServerGamePacketListenerImpl listener,
                    Connection connection,
                    long generation) {
        PendingDirectDisconnectRetirement pending =
                runtime.directDisconnectRetirement;
        boolean opened = false;
        if (pending == null) {
            if (generation <= 0L
                    || runtime.handle.player()
                                    .orElse(null)
                            != player) {
                abortUnstableListenerDisconnect(
                        runtime,
                        player,
                        server.getPlayerList()
                                .getPlayer(player.getUUID()),
                        "Direct disconnect could not freeze its authoritative body");
                return ListenerDisconnectDecision.ABORTED;
            }
            long startedTick = server.getTickCount();
            GenerationRetirementKey key =
                    new GenerationRetirementKey(
                            UUID.randomUUID(),
                            runtime.handle.botId(),
                            generation,
                            GenerationRetirementContinuation
                                    .DISCONNECT_PRE_SAVE);
            pending = new PendingDirectDisconnectRetirement(
                    player,
                    listener,
                    connection,
                    generation,
                    GenerationRetirementSession.open(
                            key,
                            startedTick,
                            boundedRetirementDeadline(
                                    startedTick)));
            runtime.directDisconnectRetirement = pending;
            player.armDisconnectPreSaveFence();
            opened = true;
        }
        if (!isExactDirectDisconnectAuthority(
                runtime, pending)) {
            pending.failure = appendFailure(
                    pending.failure,
                    new IllegalStateException(
                            "Direct disconnect authority changed before retirement completed"));
            abortUnstableListenerDisconnect(
                    runtime,
                    player,
                    server.getPlayerList()
                            .getPlayer(player.getUUID()),
                    "Direct disconnect retirement lost exact authority");
            return ListenerDisconnectDecision.ABORTED;
        }

        GenerationRetirementStatus status = opened
                ? advanceDirectDisconnectRetirement(
                        runtime,
                        pending,
                        server.getTickCount())
                : pending.status();
        if (runtimes.get(runtime.handle.botId())
                != runtime) {
            return ListenerDisconnectDecision.ABORTED;
        }
        if (status == GenerationRetirementStatus.PENDING) {
            return ListenerDisconnectDecision.RETRY;
        }
        if (status == GenerationRetirementStatus.UNSAFE
                || !isExactDirectDisconnectAuthority(
                        runtime, pending)) {
            abortUnstableListenerDisconnect(
                    runtime,
                    player,
                    server.getPlayerList()
                            .getPlayer(player.getUUID()),
                    "Direct disconnect retirement did not reach a safe pre-save endpoint");
            return ListenerDisconnectDecision.ABORTED;
        }

        runtime.disconnectPreparationComplete = true;
        runtime.disconnectPreparationSafelyClosed = true;
        runtime.disconnectPreparationFailure = null;
        if (!player.hasDisconnectPreSaveFence()
                || !player.releaseDisconnectPreSaveFence()) {
            pending.failure = appendFailure(
                    pending.failure,
                    new IllegalStateException(
                            "Another save fence remained after direct disconnect retirement"));
            abortUnstableListenerDisconnect(
                    runtime,
                    player,
                    server.getPlayerList()
                            .getPlayer(player.getUUID()),
                    "Direct disconnect could not exclusively release its pre-save fence");
            return ListenerDisconnectDecision.ABORTED;
        }
        return ListenerDisconnectDecision.PROCEED;
    }

    private void advanceDirectDisconnectRetirements(
            long currentTick) {
        for (RuntimeEntry runtime :
                List.copyOf(runtimes.values())) {
            PendingDirectDisconnectRetirement pending =
                    runtime.directDisconnectRetirement;
            if (pending == null
                    || pending.status()
                            != GenerationRetirementStatus.PENDING) {
                continue;
            }
            if (!isExactDirectDisconnectAuthority(
                    runtime, pending)) {
                pending.failure = appendFailure(
                        pending.failure,
                        new IllegalStateException(
                                "Direct disconnect authority changed while cleanup was pending"));
                abortUnstableListenerDisconnect(
                        runtime,
                        pending.player,
                        server.getPlayerList()
                                .getPlayer(
                                        pending.player.getUUID()),
                        "Pending direct disconnect lost exact authority");
                continue;
            }
            GenerationRetirementStatus status =
                    advanceDirectDisconnectRetirement(
                            runtime,
                            pending,
                            currentTick);
            if (runtimes.get(runtime.handle.botId())
                    != runtime) {
                continue;
            }
            if (status == GenerationRetirementStatus.UNSAFE) {
                abortUnstableListenerDisconnect(
                        runtime,
                        pending.player,
                        server.getPlayerList()
                                .getPlayer(
                                        pending.player.getUUID()),
                        "Pending direct disconnect exhausted its safe cleanup contract");
            }
        }
    }

    private GenerationRetirementStatus
            advanceDirectDisconnectRetirement(
                    RuntimeEntry runtime,
                    PendingDirectDisconnectRetirement pending,
                    long currentTick) {
        GenerationRetirementStatus currentStatus =
                pending.status();
        if (currentStatus
                        != GenerationRetirementStatus.PENDING
                || pending.lastAdvanceTick == currentTick
                || pending.session.attempt() > 0
                        && currentTick
                                < pending.session.nextRetryTick()
                || runtime.generationRetirementInProgress) {
            return currentStatus;
        }
        pending.lastAdvanceTick = currentTick;
        runtime.generationRetirementInProgress = true;
        try {
            if (!pending.beginAttempted) {
                pending.beginAttempted = true;
                pending.failure = appendFailure(
                        pending.failure,
                        beginDirectDisconnectRetirement(
                                runtime, pending));
            }

            GenerationRetirementStatus observedStatus;
            if (pending.failure != null) {
                observedStatus =
                        GenerationRetirementStatus.UNSAFE;
            } else {
                GenerationDrainStatus drainStatus =
                        actionRuntime.generationDrainStatus(
                                runtime.handle.botId(),
                                pending.generation);
                observedStatus = switch (drainStatus) {
                    case PENDING ->
                            GenerationRetirementStatus.PENDING;
                    case UNSAFE ->
                            GenerationRetirementStatus.UNSAFE;
                    case COMPLETE ->
                            finishDirectDisconnectSurvival(
                                    runtime, pending)
                                    ? GenerationRetirementStatus.COMPLETE
                                    : GenerationRetirementStatus.UNSAFE;
                };
                if (drainStatus == GenerationDrainStatus.UNSAFE) {
                    pending.failure = appendFailure(
                            pending.failure,
                            new IllegalStateException(
                                    "Action generation was quarantined during direct disconnect"));
                }
            }

            GenerationRetirementTicket ticket;
            if (pending.lastTicket == null) {
                ticket = new GenerationRetirementTicket(
                        pending.session.key(),
                        pending.session.startedTick(),
                        pending.session.deadlineTick(),
                        currentTick,
                        1);
            } else {
                ticket = pending.lastTicket.next(
                        Objects.requireNonNull(
                                pending.lastReceipt,
                                "lastReceipt"),
                        currentTick);
            }
            long progressRevision =
                    observedStatus
                                    == GenerationRetirementStatus.PENDING
                            ? pending.session.progressRevision()
                            : incrementRetirementProgress(
                                    pending.session
                                            .progressRevision());
            long nextRetryTick =
                    observedStatus
                                    == GenerationRetirementStatus.PENDING
                            ? nextRetirementTick(currentTick)
                            : -1L;
            GenerationRetirementSession.Update update =
                    pending.session.observe(
                            ticket,
                            observedStatus,
                            progressRevision,
                            nextRetryTick);
            pending.session = update.session();
            pending.lastTicket = ticket;
            pending.lastReceipt = update.receipt();
            return pending.status();
        } catch (RuntimeException exception) {
            pending.failure = appendFailure(
                    pending.failure, exception);
            return GenerationRetirementStatus.UNSAFE;
        } finally {
            runtime.generationRetirementInProgress = false;
        }
    }

    @Nullable
    private RuntimeException beginDirectDisconnectRetirement(
            RuntimeEntry runtime,
            PendingDirectDisconnectRetirement pending) {
        RuntimeException failure = null;
        try {
            closeBotInventory(
                    runtime,
                    InventoryCloseReason.BOT_UNLOADED);
        } catch (RuntimeException exception) {
            failure = appendFailure(failure, exception);
        }
        failure = appendDirectAuthorityFailure(
                runtime, pending, failure);
        if (failure != null) {
            return failure;
        }
        try {
            cancelBotActions(
                    runtime,
                    pending.generation,
                    ActionCancellationReason.LIFECYCLE);
        } catch (RuntimeException exception) {
            failure = appendFailure(failure, exception);
        }
        failure = appendDirectAuthorityFailure(
                runtime, pending, failure);
        if (failure != null) {
            return failure;
        }
        try {
            inputController.forceClear(
                    runtime.handle.botId(),
                    pending.generation);
            MinecraftPlayerInputAdapter.clear(
                    pending.player);
        } catch (RuntimeException exception) {
            failure = appendFailure(failure, exception);
        }
        failure = appendDirectAuthorityFailure(
                runtime, pending, failure);
        if (failure != null) {
            return failure;
        }
        try {
            perceptionService.closeGeneration(
                    runtime.handle.botId(),
                    pending.generation);
        } catch (RuntimeException exception) {
            failure = appendFailure(failure, exception);
        }
        failure = appendDirectAuthorityFailure(
                runtime, pending, failure);
        if (failure != null) {
            return failure;
        }
        long currentTick = server.getTickCount();
        try {
            navigationService.closeGeneration(
                    runtime.handle.botId(),
                    pending.generation,
                    currentTick);
        } catch (RuntimeException exception) {
            failure = appendFailure(failure, exception);
        }
        try {
            safetyService.closeGeneration(
                    runtime.handle.botId(),
                    pending.generation,
                    currentTick);
        } catch (RuntimeException exception) {
            failure = appendFailure(failure, exception);
        }
        return appendDirectAuthorityFailure(
                runtime, pending, failure);
    }

    private boolean finishDirectDisconnectSurvival(
            RuntimeEntry runtime,
            PendingDirectDisconnectRetirement pending) {
        if (pending.survivalCloseAttempted) {
            return pending.failure == null;
        }
        pending.survivalCloseAttempted = true;
        try {
            if (!survivalSkillService.closeGeneration(
                    runtime.handle.botId(),
                    pending.generation,
                    server.getTickCount(),
                    true)) {
                pending.failure = appendFailure(
                        pending.failure,
                        new IllegalStateException(
                                "Survival layout did not produce a safe direct-disconnect receipt"));
            }
        } catch (RuntimeException exception) {
            pending.failure = appendFailure(
                    pending.failure, exception);
        }
        pending.failure = appendDirectAuthorityFailure(
                runtime,
                pending,
                pending.failure);
        return pending.failure == null;
    }

    @Nullable
    private RuntimeException appendDirectAuthorityFailure(
            RuntimeEntry runtime,
            PendingDirectDisconnectRetirement pending,
            @Nullable RuntimeException failure) {
        if (isExactDirectDisconnectAuthority(
                runtime, pending)) {
            return failure;
        }
        return appendFailure(
                failure,
                new IllegalStateException(
                        "Direct disconnect authority changed during generation retirement"));
    }

    private boolean isExactDirectDisconnectAuthority(
            RuntimeEntry runtime,
            PendingDirectDisconnectRetirement pending) {
        UUID botId = runtime.handle.botId();
        return runtimes.get(botId) == runtime
                && handlesByBot.get(botId)
                        == runtime.handle
                && runtime.state
                        == BotLifecycleState.DESPAWNING
                && runtime.handle.generation()
                        == pending.generation
                && runtime.handle.player()
                                .orElse(null)
                        == pending.player
                && pending.matchesBinding(
                        pending.player,
                        pending.listener,
                        pending.connection,
                        pending.generation)
                && runtime.disconnectingPlayer
                        == pending.player
                && runtime.disconnectingListener
                        == pending.listener
                && runtime.disconnectingConnection
                        == pending.connection
                && runtime.disconnectingGeneration
                        == pending.generation
                && pending.player.runtimeHandle()
                        == runtime.handle
                && pending.player.hasDisconnectPreSaveFence()
                && pending.player.connection
                        == pending.listener
                && pending.listener.player
                        == pending.player
                && pending.listener.getConnection()
                        == pending.connection
                && server.getPlayerList()
                                .getPlayer(botId)
                        == pending.player
                && pending.player.serverLevel()
                                .getPlayerByUUID(botId)
                        == pending.player
                && isBotConnectionOpen(
                        pending.player);
    }

    private void advanceDeathRetirements(long currentTick) {
        for (RuntimeEntry runtime :
                List.copyOf(runtimes.values())) {
            PendingDeathRetirement pending =
                    runtime.deathRetirement;
            if (pending == null) {
                continue;
            }
            GenerationRetirementStatus status =
                    pending.status();
            if (status
                    == GenerationRetirementStatus.PENDING) {
                if (!isExactDeathRetirementAuthority(
                        runtime, pending)) {
                    pending.failure = appendFailure(
                            pending.failure,
                            new IllegalStateException(
                                    "Death retirement lost exact authority while cleanup was pending"));
                    status =
                            GenerationRetirementStatus.UNSAFE;
                } else {
                    status = advanceDeathRetirementAttempt(
                            runtime,
                            pending,
                            currentTick);
                }
            }
            if (runtimes.get(runtime.handle.botId())
                    != runtime) {
                continue;
            }
            if (status
                            == GenerationRetirementStatus.COMPLETE
                    && finishDeathRetirement(
                            runtime, pending, currentTick)) {
                continue;
            }
            if (status
                            == GenerationRetirementStatus.UNSAFE
                    || pending.failure != null
                    || !isExactDeathRetirementAuthority(
                            runtime, pending)) {
                failDeathRetirementWithoutSave(
                        runtime,
                        pending.player,
                        null,
                        pending.failure);
            }
        }
    }

    private GenerationRetirementStatus
            advanceDeathRetirementAttempt(
                    RuntimeEntry runtime,
                    PendingDeathRetirement pending,
                    long currentTick) {
        GenerationRetirementStatus currentStatus =
                pending.status();
        if (currentStatus
                        != GenerationRetirementStatus.PENDING
                || pending.lastAdvanceTick == currentTick
                || pending.session.attempt() > 0
                        && currentTick
                                < pending.session.nextRetryTick()
                || runtime.generationRetirementInProgress) {
            return currentStatus;
        }
        pending.lastAdvanceTick = currentTick;
        runtime.generationRetirementInProgress = true;
        try {
            if (!pending.beginAttempted) {
                pending.beginAttempted = true;
                pending.failure = appendFailure(
                        pending.failure,
                        beginDeathRetirement(
                                runtime, pending));
            }

            GenerationRetirementStatus observedStatus;
            if (pending.failure != null) {
                observedStatus =
                        GenerationRetirementStatus.UNSAFE;
            } else {
                GenerationDrainStatus drainStatus =
                        actionRuntime.generationDrainStatus(
                                runtime.handle.botId(),
                                pending.generation);
                observedStatus = switch (drainStatus) {
                    case PENDING ->
                            GenerationRetirementStatus.PENDING;
                    case UNSAFE ->
                            GenerationRetirementStatus.UNSAFE;
                    case COMPLETE ->
                            finishDeathRetirementSurvival(
                                    runtime, pending)
                                    ? GenerationRetirementStatus.COMPLETE
                                    : GenerationRetirementStatus.UNSAFE;
                };
                if (drainStatus
                        == GenerationDrainStatus.UNSAFE) {
                    pending.failure = appendFailure(
                            pending.failure,
                            new IllegalStateException(
                                    "Action generation was quarantined during death retirement"));
                }
            }

            GenerationRetirementTicket ticket;
            if (pending.lastTicket == null) {
                ticket = new GenerationRetirementTicket(
                        pending.session.key(),
                        pending.session.startedTick(),
                        pending.session.deadlineTick(),
                        currentTick,
                        1);
            } else {
                ticket = pending.lastTicket.next(
                        Objects.requireNonNull(
                                pending.lastReceipt,
                                "lastReceipt"),
                        currentTick);
            }
            long progressRevision =
                    observedStatus
                                    == GenerationRetirementStatus.PENDING
                            ? pending.session.progressRevision()
                            : incrementRetirementProgress(
                                    pending.session
                                            .progressRevision());
            long nextRetryTick =
                    observedStatus
                                    == GenerationRetirementStatus.PENDING
                            ? nextRetirementTick(currentTick)
                            : -1L;
            GenerationRetirementSession.Update update =
                    pending.session.observe(
                            ticket,
                            observedStatus,
                            progressRevision,
                            nextRetryTick);
            pending.session = update.session();
            pending.lastTicket = ticket;
            pending.lastReceipt = update.receipt();
            return pending.status();
        } catch (RuntimeException exception) {
            pending.failure = appendFailure(
                    pending.failure, exception);
            return GenerationRetirementStatus.UNSAFE;
        } finally {
            runtime.generationRetirementInProgress = false;
        }
    }

    @Nullable
    private RuntimeException beginDeathRetirement(
            RuntimeEntry runtime,
            PendingDeathRetirement pending) {
        RuntimeException failure = null;
        try {
            closeBotInventory(
                    runtime,
                    InventoryCloseReason.BOT_DEATH);
        } catch (RuntimeException exception) {
            failure = appendFailure(failure, exception);
        }
        failure = appendDeathAuthorityFailure(
                runtime, pending, failure);
        if (failure != null) {
            return failure;
        }
        try {
            cancelBotActions(
                    runtime,
                    pending.generation,
                    ActionCancellationReason.LIFECYCLE);
        } catch (RuntimeException exception) {
            failure = appendFailure(failure, exception);
        }
        failure = appendDeathAuthorityFailure(
                runtime, pending, failure);
        if (failure != null) {
            return failure;
        }
        try {
            inputController.forceClear(
                    runtime.handle.botId(),
                    pending.generation);
            MinecraftPlayerInputAdapter.clear(
                    pending.player);
        } catch (RuntimeException exception) {
            failure = appendFailure(failure, exception);
        }
        failure = appendDeathAuthorityFailure(
                runtime, pending, failure);
        if (failure != null) {
            return failure;
        }
        try {
            perceptionService.closeGeneration(
                    runtime.handle.botId(),
                    pending.generation);
        } catch (RuntimeException exception) {
            failure = appendFailure(failure, exception);
        }
        failure = appendDeathAuthorityFailure(
                runtime, pending, failure);
        if (failure != null) {
            return failure;
        }
        long currentTick = server.getTickCount();
        try {
            navigationService.closeGeneration(
                    runtime.handle.botId(),
                    pending.generation,
                    currentTick);
        } catch (RuntimeException exception) {
            failure = appendFailure(failure, exception);
        }
        try {
            safetyService.closeGeneration(
                    runtime.handle.botId(),
                    pending.generation,
                    currentTick);
        } catch (RuntimeException exception) {
            failure = appendFailure(failure, exception);
        }
        return appendDeathAuthorityFailure(
                runtime, pending, failure);
    }

    private boolean finishDeathRetirementSurvival(
            RuntimeEntry runtime,
            PendingDeathRetirement pending) {
        if (pending.survivalCloseAttempted) {
            return pending.failure == null;
        }
        pending.survivalCloseAttempted = true;
        try {
            if (!survivalSkillService.closeGeneration(
                    runtime.handle.botId(),
                    pending.generation,
                    server.getTickCount(),
                    true)) {
                pending.failure = appendFailure(
                        pending.failure,
                        new IllegalStateException(
                                "Survival layout did not produce a safe death-retirement receipt"));
            }
        } catch (RuntimeException exception) {
            pending.failure = appendFailure(
                    pending.failure, exception);
        }
        pending.failure = appendDeathAuthorityFailure(
                runtime,
                pending,
                pending.failure);
        return pending.failure == null;
    }

    private boolean finishDeathRetirement(
            RuntimeEntry runtime,
            PendingDeathRetirement pending,
            long currentTick) {
        GenerationRetirementReceipt receipt =
                pending.lastReceipt;
        if (receipt == null
                || receipt.status()
                        != GenerationRetirementStatus.COMPLETE
                || !isExactDeathRetirementAuthority(
                        runtime, pending)) {
            pending.failure = appendFailure(
                    pending.failure,
                    new IllegalStateException(
                            "Death retirement completion lost its exact receipt or authority"));
            return false;
        }
        if (!pending.player.hasDeathRetirementSaveFence()
                || !pending.player
                        .releaseDeathRetirementSaveFence()) {
            pending.failure = appendFailure(
                    pending.failure,
                    new IllegalStateException(
                            "Another save fence remained after death retirement"));
            return false;
        }

        runtime.completedDeathRetirement = receipt;
        runtime.deathRetirement = null;
        runtime.respawnSuppressed = false;
        runtime.respawnAtTick =
                BotPlayerConfig.AUTO_RESPAWN.get()
                        ? currentTick
                                + BotPlayerConfig.RESPAWN_DELAY_TICKS.get()
                        : -1;
        return true;
    }

    @Nullable
    private RuntimeException appendDeathAuthorityFailure(
            RuntimeEntry runtime,
            PendingDeathRetirement pending,
            @Nullable RuntimeException failure) {
        if (isExactDeathRetirementAuthority(
                runtime, pending)) {
            return failure;
        }
        return appendFailure(
                failure,
                new IllegalStateException(
                        "Death retirement authority changed during generation cleanup"));
    }

    private boolean isExactDeathRetirementAuthority(
            RuntimeEntry runtime,
            PendingDeathRetirement pending) {
        UUID botId = runtime.handle.botId();
        return runtimes.get(botId) == runtime
                && handlesByBot.get(botId)
                        == runtime.handle
                && runtime.state == BotLifecycleState.DEAD
                && runtime.deathRetirement == pending
                && runtime.completedDeathRetirement == null
                && runtime.handle.generation()
                        == pending.generation
                && runtime.handle.player()
                                .orElse(null)
                        == pending.player
                && pending.matchesBinding(
                        pending.player,
                        pending.listener,
                        pending.connection,
                        pending.generation)
                && !runtime.replacementHandoffInProgress
                && !runtime.handoffAborted
                && runtime.stagedCleanupGeneration < 0L
                && runtime.stagedCleanupPredecessor == null
                && runtime.stagedCleanupPlayer == null
                && runtime.respawnCandidate == null
                && runtime.disconnectingPlayer == null
                && runtime.disconnectingListener == null
                && runtime.disconnectingConnection == null
                && runtime.directDisconnectRetirement == null
                && pending.player.runtimeHandle()
                        == runtime.handle
                && pending.player.isDeadOrDying()
                && pending.player
                        .hasDeathRetirementSaveFence()
                && pending.player.connection
                        == pending.listener
                && pending.listener.player
                        == pending.player
                && pending.listener.getConnection()
                        == pending.connection
                && server.getPlayerList()
                                .getPlayer(botId)
                        == pending.player
                && pending.player.serverLevel()
                                .getPlayerByUUID(botId)
                        == pending.player
                && isBotConnectionOpen(
                        pending.player);
    }

    private boolean hasCompleteDeathRetirement(
            RuntimeEntry runtime) {
        GenerationRetirementReceipt receipt =
                runtime.completedDeathRetirement;
        return runtime.deathRetirement == null
                && receipt != null
                && receipt.status()
                        == GenerationRetirementStatus.COMPLETE
                && receipt.failure()
                        == GenerationRetirementFailure.NONE
                && receipt.attemptedTick()
                        <= receipt.deadlineTick()
                && receipt.nextRetryTick() == -1L
                && receipt.key().botId()
                        .equals(runtime.handle.botId())
                && receipt.key().generation()
                        == runtime.handle.generation()
                && receipt.key().continuation()
                        == GenerationRetirementContinuation.DEATH_RESPAWN;
    }

    private void failDeathRetirementWithoutSave(
            RuntimeEntry runtime,
            @Nullable BotServerPlayer preferredPlayer,
            @Nullable BotServerPlayer additionalPlayer,
            @Nullable RuntimeException priorFailure) {
        if (runtimes.get(runtime.handle.botId())
                != runtime) {
            return;
        }
        if (runtime.deathRetirementFailClosedInProgress) {
            return;
        }
        runtime.deathRetirementFailClosedInProgress = true;
        try {
            runtime.respawnAtTick = -1;
            runtime.respawnFinalizeDeadlineTick = -1;
            runtime.respawnCandidate = null;
            runtime.respawnSuppressed = true;
            RuntimeException failure = priorFailure;
            long generation = runtime.deathRetirement == null
                    ? runtime.handle.generation()
                    : runtime.deathRetirement.generation;
            armImmediatelyKnownNoSaveBodies(
                    runtime,
                    preferredPlayer,
                    additionalPlayer);
            try {
                closeBotInventory(
                        runtime,
                        InventoryCloseReason.BOT_DEATH);
            } catch (RuntimeException exception) {
                failure = appendFailure(failure, exception);
            }
            if (generation > 0L) {
                try {
                    actionRuntime.quarantineBotGenerationNow(
                            runtime.handle.botId(),
                            generation,
                            server.getTickCount());
                } catch (RuntimeException exception) {
                    failure = appendFailure(failure, exception);
                }
                try {
                    inputController.forceClear(
                            runtime.handle.botId(),
                            generation);
                } catch (RuntimeException exception) {
                    failure = appendFailure(failure, exception);
                }
                try {
                    perceptionService.closeGeneration(
                            runtime.handle.botId(),
                            generation);
                } catch (RuntimeException exception) {
                    failure = appendFailure(failure, exception);
                }
                try {
                    navigationService.closeGeneration(
                            runtime.handle.botId(),
                            generation,
                            server.getTickCount());
                } catch (RuntimeException exception) {
                    failure = appendFailure(failure, exception);
                }
                try {
                    safetyService.closeGeneration(
                            runtime.handle.botId(),
                            generation,
                            server.getTickCount());
                } catch (RuntimeException exception) {
                    failure = appendFailure(failure, exception);
                }
            }
            failure = appendFailure(
                    failure,
                    finalizeRuntimeTeardownWithoutSave(
                            runtime,
                            preferredPlayer,
                            additionalPlayer));
            if (failure != null) {
                BotPlayer.LOGGER.error(
                        "Death retirement failed closed without saving BotPlayer {} ({})",
                        runtime.handle.name(),
                        runtime.handle.botId(),
                        failure);
            }
        } finally {
            if (runtimes.get(runtime.handle.botId())
                    == runtime) {
                runtime.deathRetirementFailClosedInProgress = false;
            }
        }
    }

    private static long boundedRetirementDeadline(
            long startedTick) {
        return startedTick
                        > Long.MAX_VALUE
                                - BotActionRuntime.MAX_CLEANUP_TICKS
                ? Long.MAX_VALUE
                : startedTick
                        + BotActionRuntime.MAX_CLEANUP_TICKS;
    }

    private static long nextRetirementTick(
            long currentTick) {
        return currentTick == Long.MAX_VALUE
                ? -1L
                : currentTick + 1L;
    }

    private static long incrementRetirementProgress(
            long revision) {
        return revision == Long.MAX_VALUE
                ? Long.MAX_VALUE
                : revision + 1L;
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
        runtime.directDisconnectRetirement = null;
        runtime.deathRetirement = null;
        runtime.completedDeathRetirement = null;
        runtime.deathRetirementFailClosedInProgress = false;
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
        RuntimeException removalFailure = removePlayerIdentity(
                attached, suppressPlayerDataSave);
        if (preferredPlayer != attached) {
            removalFailure = appendFailure(
                    removalFailure,
                    removePlayerIdentity(
                            preferredPlayer,
                            suppressPlayerDataSave));
        }
        if (staged != attached
                && staged != preferredPlayer) {
            removalFailure = appendFailure(
                    removalFailure,
                    removePlayerIdentity(
                            staged,
                            suppressPlayerDataSave));
        }
        failure = appendFailure(failure, removalFailure);
        return failure;
    }

    @Nullable
    private RuntimeException
            finalizeRuntimeTeardownWithoutSave(
                    RuntimeEntry runtime,
                    @Nullable BotServerPlayer preferredPlayer,
                    @Nullable BotServerPlayer additionalPlayer) {
        armImmediatelyKnownNoSaveBodies(
                runtime,
                preferredPlayer,
                additionalPlayer);
        PendingNoSaveTeardown teardown =
                rememberNoSaveTeardown(
                        runtime,
                        preferredPlayer,
                        additionalPlayer);
        UUID botId = teardown.botId;
        long retiredGeneration =
                teardown.retiredGeneration;
        BotRuntimeHandle handle = teardown.handle;
        LinkedHashSet<BotServerPlayer> exactBodies =
                teardown.exactBodies;
        LinkedHashSet<ServerGamePacketListenerImpl> exactListeners =
                teardown.exactListeners;

        for (BotServerPlayer exactBody : exactBodies) {
            exactBody.suppressPlayerDataSaveUntilReleased();
        }

        /*
         * 必须先撤销全部 listener 权威，再触发 PlayerList.remove。否则 remove/logout
         * 回调重入 disconnect 时仍可能走原版保存，把未验证的临时布局落盘。
         */
        RuntimeException failure = null;
        for (ServerGamePacketListenerImpl exactListener :
                exactListeners) {
            try {
                if (exactListener
                        instanceof BotGamePacketListener botListener) {
                    botListener.closeForNoSaveIsolation();
                } else {
                    failure = appendFailure(
                            failure,
                            new IllegalStateException(
                                    "No-save teardown captured a non-Bot listener"));
                }
            } catch (RuntimeException exception) {
                failure = appendFailure(failure, exception);
            }
        }
        if (teardown.boundDisconnectConnection != null) {
            try {
                if (teardown.boundDisconnectConnection
                        instanceof BotConnection botConnection) {
                    botConnection.markClosed();
                } else {
                    failure = appendFailure(
                            failure,
                            new IllegalStateException(
                                    "No-save teardown captured a non-Bot connection"));
                }
            } catch (RuntimeException exception) {
                failure = appendFailure(failure, exception);
            }
        }
        failure = appendFailure(
                failure,
                finalizeRuntimeTeardown(
                        runtime,
                        preferredPlayer,
                        true));
        for (BotServerPlayer exactBody : exactBodies) {
            failure = appendFailure(
                    failure,
                    removePlayerIdentity(
                            exactBody, true));
        }
        if (!noSaveRemovalConfirmed(
                runtime,
                handle,
                botId,
                teardown.expectedPostGeneration,
                exactBodies,
                exactListeners,
                teardown.boundDisconnectListener,
                teardown.boundDisconnectConnection)) {
            PendingNoSaveTeardown refreshed =
                    rememberNoSaveTeardown(
                            runtime, null, null);
            for (BotServerPlayer residualBody :
                    refreshed.exactBodies) {
                residualBody
                        .suppressPlayerDataSaveUntilReleased();
            }
            for (ServerGamePacketListenerImpl residualListener :
                    refreshed.exactListeners) {
                try {
                    if (residualListener
                            instanceof BotGamePacketListener botListener) {
                        botListener.closeForNoSaveIsolation();
                    }
                } catch (RuntimeException exception) {
                    failure = appendFailure(
                            failure, exception);
                }
            }
            return appendFailure(
                    failure,
                    new IllegalStateException(
                            "No-save teardown retained an exact player body or listener authority"));
        }
        for (BotServerPlayer exactBody : exactBodies) {
            exactBody.releasePlayerDataSaveSuppression();
        }
        if (retiredGeneration > 0L) {
            try {
                if (!survivalSkillService
                        .closeRemovedGenerationWithoutSave(
                                botId,
                                retiredGeneration,
                                server.getTickCount())) {
                    failure = appendFailure(
                            failure,
                            new IllegalStateException(
                                    "No-save generation removal retained an authoritative skill body"));
                }
            } catch (RuntimeException exception) {
                failure = appendFailure(
                        failure, exception);
            }
        }
        if (failure == null) {
            runtime.pendingNoSaveTeardown = null;
        }
        return failure;
    }

    /**
     * 在建票、generation 加法和全服身份扫描之前，先给无需外部查询即可取得的
     * body 上 fence。后续建票即使抛错，这些仍可能被原版 saveAll 看见的对象
     * 也不能写入临时布局。
     */
    private void armImmediatelyKnownNoSaveBodies(
            RuntimeEntry runtime,
            @Nullable BotServerPlayer preferredPlayer,
            @Nullable BotServerPlayer additionalPlayer) {
        UUID botId = runtime.handle.botId();
        LinkedHashSet<BotServerPlayer> bodies =
                new LinkedHashSet<>();
        addNoSaveBody(
                bodies,
                runtime.handle.player().orElse(null),
                botId);
        addNoSaveBody(bodies, preferredPlayer, botId);
        addNoSaveBody(bodies, additionalPlayer, botId);
        addNoSaveBody(
                bodies, runtime.stagedCleanupPlayer, botId);
        addNoSaveBody(
                bodies,
                runtime.stagedCleanupPredecessor,
                botId);
        addNoSaveBody(
                bodies, runtime.respawnCandidate, botId);
        addNoSaveBody(
                bodies,
                runtime.disconnectingPlayer,
                botId);
        if (runtime.disconnectingListener != null) {
            addNoSaveBody(
                    bodies,
                    runtime.disconnectingListener.player,
                    botId);
        }
        boolean expanded;
        do {
            expanded = false;
            for (BotServerPlayer body :
                    List.copyOf(bodies)) {
                int before = bodies.size();
                if (body.connection != null) {
                    addNoSaveBody(
                            bodies,
                            body.connection.player,
                            botId);
                }
                expanded |= bodies.size() != before;
            }
        } while (expanded);
        for (BotServerPlayer body : bodies) {
            body.suppressPlayerDataSaveUntilReleased();
        }
    }

    private PendingNoSaveTeardown rememberNoSaveTeardown(
            RuntimeEntry runtime,
            @Nullable BotServerPlayer preferredPlayer,
            @Nullable BotServerPlayer additionalPlayer) {
        PendingNoSaveTeardown teardown =
                runtime.pendingNoSaveTeardown;
        UUID botId = teardown == null
                ? runtime.handle.botId()
                : teardown.botId;
        LinkedHashSet<BotServerPlayer> discoveredBodies =
                collectNoSaveBodies(
                        runtime,
                        preferredPlayer,
                        additionalPlayer,
                        botId);
        for (BotServerPlayer discoveredBody :
                discoveredBodies) {
            discoveredBody
                    .suppressPlayerDataSaveUntilReleased();
        }
        if (teardown == null) {
            BotRuntimeHandle handle = runtime.handle;
            long retiredGeneration = handle.generation();
            boolean attachedBodyWillDetach =
                    handle.player().isPresent();
            teardown = new PendingNoSaveTeardown(
                    handle.botId(),
                    retiredGeneration,
                    handle,
                    attachedBodyWillDetach
                            ? Math.incrementExact(
                                    retiredGeneration)
                            : retiredGeneration);
            runtime.pendingNoSaveTeardown = teardown;
        }
        teardown.captureRuntimeBindings(runtime);
        teardown.exactBodies.addAll(discoveredBodies);
        for (BotServerPlayer exactBody :
                teardown.exactBodies) {
            if (exactBody.connection != null) {
                teardown.exactListeners.add(
                        exactBody.connection);
            }
        }
        return teardown;
    }

    private LinkedHashSet<BotServerPlayer>
            collectNoSaveBodies(
                    RuntimeEntry runtime,
                    @Nullable BotServerPlayer preferredPlayer,
                    @Nullable BotServerPlayer additionalPlayer,
                    UUID botId) {
        LinkedHashSet<BotServerPlayer> bodies =
                new LinkedHashSet<>();
        addNoSaveBody(
                bodies,
                runtime.handle.player().orElse(null),
                botId);
        addNoSaveBody(
                bodies, preferredPlayer, botId);
        addNoSaveBody(
                bodies, additionalPlayer, botId);
        addNoSaveBody(
                bodies,
                runtime.stagedCleanupPlayer,
                botId);
        addNoSaveBody(
                bodies,
                runtime.stagedCleanupPredecessor,
                botId);
        addNoSaveBody(
                bodies,
                runtime.respawnCandidate,
                botId);
        addNoSaveBody(
                bodies,
                runtime.disconnectingPlayer,
                botId);
        if (runtime.disconnectingListener != null) {
            addNoSaveBody(
                    bodies,
                    runtime.disconnectingListener.player,
                    botId);
        }
        for (ServerPlayer player :
                server.getPlayerList().getPlayers()) {
            addNoSaveBody(bodies, player, botId);
        }
        for (ServerLevel level : server.getAllLevels()) {
            for (ServerPlayer player : level.players()) {
                addNoSaveBody(bodies, player, botId);
            }
        }

        boolean expanded;
        do {
            expanded = false;
            for (BotServerPlayer body :
                    List.copyOf(bodies)) {
                int before = bodies.size();
                if (body.connection != null) {
                    addNoSaveBody(
                            bodies,
                            body.connection.player,
                            botId);
                }
                expanded |= bodies.size() != before;
            }
        } while (expanded);
        return bodies;
    }

    private static void addNoSaveBody(
            Set<BotServerPlayer> bodies,
            @Nullable ServerPlayer candidate,
            UUID botId) {
        if (candidate instanceof BotServerPlayer bot
                && bot.getUUID().equals(botId)) {
            bodies.add(bot);
        }
    }

    private boolean noSaveRemovalConfirmed(
            RuntimeEntry runtime,
            BotRuntimeHandle handle,
            UUID botId,
            long expectedPostGeneration,
            Set<BotServerPlayer> exactBodies,
            Set<ServerGamePacketListenerImpl> exactListeners,
            @Nullable ServerGamePacketListenerImpl
                    boundDisconnectListener,
            @Nullable Connection boundDisconnectConnection) {
        if (runtimes.get(botId) == runtime
                || handle.player().isPresent()
                || handle.generation()
                        != expectedPostGeneration
                || server.getPlayerList()
                                .getPlayer(botId)
                        != null
                || server.getPlayerList()
                        .getPlayers()
                        .stream()
                        .anyMatch(player ->
                                player.getUUID()
                                        .equals(botId))) {
            return false;
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getPlayerByUUID(botId) != null
                    || level.getEntity(botId) != null) {
                return false;
            }
            for (ServerPlayer candidate : level.players()) {
                if (candidate.getUUID().equals(botId)) {
                    return false;
                }
            }
        }
        for (BotServerPlayer player : exactBodies) {
            if (isPresentInAnyLevel(player)
                    || server.getPlayerList()
                                    .getPlayer(player.getUUID())
                            == player
                    || (player.connection != null
                            && (!(player.connection
                                            instanceof BotGamePacketListener
                                                    botListener)
                                    || botListener
                                            .acceptsRuntimeAuthority()))
                    || (player.connection != null
                            && (!(player.connection
                                                    .getConnection()
                                            instanceof BotConnection connection)
                                    || connection.snapshot()
                                            .open()))) {
                return false;
            }
        }
        for (ServerGamePacketListenerImpl listener :
                exactListeners) {
            if (!(listener
                            instanceof BotGamePacketListener botListener)
                    || botListener.acceptsRuntimeAuthority()
                    || !(listener.getConnection()
                            instanceof BotConnection connection)
                    || connection.snapshot().open()) {
                return false;
            }
        }
        if (boundDisconnectListener != null
                && (!(boundDisconnectListener
                                        instanceof BotGamePacketListener
                                                botListener)
                        || botListener.acceptsRuntimeAuthority())) {
            return false;
        }
        if (boundDisconnectConnection != null
                && (!(boundDisconnectConnection
                                        instanceof BotConnection connection)
                        || connection.snapshot().open())) {
            return false;
        }
        return true;
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
                    || server.getPlayerList()
                            .getPlayers()
                            .stream()
                            .anyMatch(candidate ->
                                    candidate == player)
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
            if (suppressPlayerDataSave
                    && player.connection
                            instanceof BotGamePacketListener botListener) {
                botListener.closeForNoSaveIsolation();
            }
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
        RuntimeException fallbackFailure;
        try {
            fallbackFailure =
                    finalizeRuntimeTeardownWithoutSave(
                            runtime,
                            runtime.handle.player().orElse(null),
                            null);
        } catch (RuntimeException exception) {
            fallbackFailure = exception;
        }
        if (fallbackFailure != null) {
            BotPlayer.LOGGER.error(
                    "No-save fallback removal also failed for BotPlayer {} ({})",
                    runtime.handle.name(),
                    runtime.handle.botId(),
                    fallbackFailure);
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
        if (!hasCompleteDeathRetirement(runtime)
                || oldPlayer == null
                || oldPlayer.connection == null
                || !oldPlayer.isDeadOrDying()
                || oldPlayer.hasDeathRetirementSaveFence()) {
            failDeathRetirementWithoutSave(
                    runtime,
                    oldPlayer,
                    runtime.respawnCandidate,
                    new IllegalStateException(
                            "Respawn finalization lacked its complete death-retirement authority"));
            return false;
        }
        ServerPlayer listenerPlayer = oldPlayer.connection.player;
        if (listenerPlayer == oldPlayer) {
            return false;
        }
        if (!(listenerPlayer instanceof BotServerPlayer replacement)) {
            failDeathRetirementWithoutSave(
                    runtime,
                    oldPlayer,
                    runtime.respawnCandidate,
                    new IllegalStateException(
                            "Respawn listener selected a non-BotPlayer body"));
            return false;
        }
        if (replacement == oldPlayer
                || replacement.runtimeHandle() != runtime.handle
                || replacement.connection != oldPlayer.connection
                || replacement.connection.player != replacement
                || !replacement.isAlive()
                || replacement.isDeadOrDying()
                || replacement.hasDeathRetirementSaveFence()
                || server.getPlayerList().getPlayer(replacement.getUUID())
                        != replacement
                || replacement.serverLevel()
                                .getPlayerByUUID(replacement.getUUID())
                        != replacement
                || (runtime.respawnCandidate != null
                        && runtime.respawnCandidate != replacement)) {
            failDeathRetirementWithoutSave(
                    runtime,
                    oldPlayer,
                    replacement,
                    new IllegalStateException(
                            "Respawn replacement failed final authority validation"));
            return false;
        }

        clearPlayerInput(runtime, false);
        if (runtime.state != BotLifecycleState.RESPAWNING
                || !hasCompleteDeathRetirement(runtime)
                || runtime.handle.player().orElse(null)
                        != oldPlayer
                || oldPlayer.connection == null
                || oldPlayer.connection.player != replacement
                || oldPlayer.hasDeathRetirementSaveFence()
                || replacement.runtimeHandle() != runtime.handle
                || replacement.connection != oldPlayer.connection
                || !replacement.isAlive()
                || replacement.isDeadOrDying()
                || replacement.hasDeathRetirementSaveFence()
                || server.getPlayerList().getPlayer(replacement.getUUID())
                        != replacement
                || replacement.serverLevel()
                                .getPlayerByUUID(replacement.getUUID())
                        != replacement
                || (runtime.respawnCandidate != null
                        && runtime.respawnCandidate != replacement)) {
            failDeathRetirementWithoutSave(
                    runtime,
                    oldPlayer,
                    replacement,
                    new IllegalStateException(
                            "Respawn authority changed immediately before attachment"));
            return false;
        }
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
        runtime.completedDeathRetirement = null;
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
        BotServerPlayer oldPlayer =
                runtime.handle.player().orElse(null);
        BotServerPlayer successor =
                knownDeathSuccessor(runtime, oldPlayer);
        if (successor != null) {
            failDeathRetirementWithoutSave(
                    runtime,
                    oldPlayer,
                    successor,
                    new IllegalStateException(
                            "Respawn failed after a successor body was partially installed: "
                                    + safeReason));
            return;
        }
        runtime.respawnCandidate = null;
        runtime.respawnFinalizeDeadlineTick = -1;
        transition(runtime, BotLifecycleState.DEAD);

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
        if (runtime.directDisconnectRetirement != null
                || generation <= 0L
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
        if (runtime.directDisconnectRetirement != null) {
            return;
        }
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
        BotServerPlayer listedBot =
                listedPlayer
                                instanceof BotServerPlayer candidate
                        && candidate.runtimeHandle()
                                == runtime.handle
                        ? candidate
                        : null;
        failure = appendFailure(
                failure,
                finalizeRuntimeTeardownWithoutSave(
                        runtime,
                        listenerPlayer,
                        listedBot));
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
        if (runtime.directDisconnectRetirement != null) {
            return;
        }
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
                                    finalizeRuntimeTeardownWithoutSave(
                                            runtime,
                                            replacement,
                                            null));
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
                    finalizeRuntimeTeardownWithoutSave(
                            runtime,
                            replacement,
                            null));
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
            if (level.getPlayerByUUID(player.getUUID())
                            == player
                    || level.getEntity(player.getUUID())
                            == player) {
                return true;
            }
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

    private static final class PendingNoSaveTeardown {
        private final UUID botId;
        private final long retiredGeneration;
        private final BotRuntimeHandle handle;
        private final long expectedPostGeneration;
        private final LinkedHashSet<BotServerPlayer> exactBodies =
                new LinkedHashSet<>();
        private final LinkedHashSet<ServerGamePacketListenerImpl>
                exactListeners = new LinkedHashSet<>();
        @Nullable
        private ServerGamePacketListenerImpl
                boundDisconnectListener;
        @Nullable
        private Connection boundDisconnectConnection;

        private PendingNoSaveTeardown(
                UUID botId,
                long retiredGeneration,
                BotRuntimeHandle handle,
                long expectedPostGeneration) {
            this.botId = botId;
            this.retiredGeneration = retiredGeneration;
            this.handle = handle;
            this.expectedPostGeneration =
                    expectedPostGeneration;
        }

        private void captureRuntimeBindings(
                RuntimeEntry runtime) {
            if (boundDisconnectListener == null
                    && runtime.disconnectingListener
                            != null) {
                boundDisconnectListener =
                        runtime.disconnectingListener;
                exactListeners.add(
                        boundDisconnectListener);
            }
            if (boundDisconnectConnection == null
                    && runtime.disconnectingConnection
                            != null) {
                boundDisconnectConnection =
                        runtime.disconnectingConnection;
            }
        }
    }

    private static final class PendingDirectDisconnectRetirement {
        private final BotServerPlayer player;
        private final ServerGamePacketListenerImpl listener;
        private final Connection connection;
        private final long generation;
        private GenerationRetirementSession session;
        @Nullable
        private GenerationRetirementTicket lastTicket;
        @Nullable
        private GenerationRetirementReceipt lastReceipt;
        @Nullable
        private RuntimeException failure;
        private long lastAdvanceTick = Long.MIN_VALUE;
        private boolean beginAttempted;
        private boolean survivalCloseAttempted;

        private PendingDirectDisconnectRetirement(
                BotServerPlayer player,
                ServerGamePacketListenerImpl listener,
                Connection connection,
                long generation,
                GenerationRetirementSession session) {
            this.player = Objects.requireNonNull(
                    player, "player");
            this.listener = Objects.requireNonNull(
                    listener, "listener");
            this.connection = Objects.requireNonNull(
                    connection, "connection");
            if (generation <= 0L) {
                throw new IllegalArgumentException(
                        "generation must be positive");
            }
            this.generation = generation;
            this.session = Objects.requireNonNull(
                    session, "session");
        }

        private boolean matchesBinding(
                BotServerPlayer candidatePlayer,
                ServerGamePacketListenerImpl candidateListener,
                Connection candidateConnection,
                long candidateGeneration) {
            return player == candidatePlayer
                    && listener == candidateListener
                    && connection == candidateConnection
                    && generation == candidateGeneration;
        }

        private GenerationRetirementStatus status() {
            return failure == null
                    ? session.status()
                    : GenerationRetirementStatus.UNSAFE;
        }
    }

    private static final class PendingDeathRetirement {
        private final BotServerPlayer player;
        private final ServerGamePacketListenerImpl listener;
        private final Connection connection;
        private final long generation;
        private GenerationRetirementSession session;
        @Nullable
        private GenerationRetirementTicket lastTicket;
        @Nullable
        private GenerationRetirementReceipt lastReceipt;
        @Nullable
        private RuntimeException failure;
        private long lastAdvanceTick = Long.MIN_VALUE;
        private boolean beginAttempted;
        private boolean survivalCloseAttempted;

        private PendingDeathRetirement(
                BotServerPlayer player,
                ServerGamePacketListenerImpl listener,
                Connection connection,
                long generation,
                GenerationRetirementSession session) {
            this.player = Objects.requireNonNull(
                    player, "player");
            this.listener = Objects.requireNonNull(
                    listener, "listener");
            this.connection = Objects.requireNonNull(
                    connection, "connection");
            if (generation <= 0L) {
                throw new IllegalArgumentException(
                        "generation must be positive");
            }
            this.generation = generation;
            this.session = Objects.requireNonNull(
                    session, "session");
        }

        private boolean matchesBinding(
                BotServerPlayer candidatePlayer,
                ServerGamePacketListenerImpl candidateListener,
                Connection candidateConnection,
                long candidateGeneration) {
            return player == candidatePlayer
                    && listener == candidateListener
                    && connection == candidateConnection
                    && generation == candidateGeneration;
        }

        private GenerationRetirementStatus status() {
            return failure == null
                    ? session.status()
                    : GenerationRetirementStatus.UNSAFE;
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
        @Nullable
        private PendingNoSaveTeardown
                pendingNoSaveTeardown;
        @Nullable
        private PendingDirectDisconnectRetirement
                directDisconnectRetirement;
        @Nullable
        private PendingDeathRetirement deathRetirement;
        @Nullable
        private GenerationRetirementReceipt
                completedDeathRetirement;
        private boolean deathRetirementFailClosedInProgress;

        private RuntimeEntry(BotRuntimeHandle handle, BotLifecycleState state) {
            this.handle = handle;
            this.state = state;
        }
    }

    private record GenerationRetirement(
            boolean safelyClosed,
            @Nullable RuntimeException failure) {}
}
