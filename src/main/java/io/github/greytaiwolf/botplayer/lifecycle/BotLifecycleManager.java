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
import io.github.greytaiwolf.botplayer.kernel.BotConnection;
import io.github.greytaiwolf.botplayer.kernel.BotRuntimeHandle;
import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
import io.github.greytaiwolf.botplayer.network.payload.AgentBindingStatus;
import io.github.greytaiwolf.botplayer.network.payload.OpenCredentialScreenPayload;
import io.github.greytaiwolf.botplayer.persistence.BotRosterSavedData;
import io.github.greytaiwolf.botplayer.perception.AuthorityEventCollector;
import io.github.greytaiwolf.botplayer.perception.ObservationSnapshot;
import io.github.greytaiwolf.botplayer.perception.PerceptionService;
import io.github.greytaiwolf.botplayer.perception.PerceptionSettings;
import io.github.greytaiwolf.botplayer.perception.SoundObservationCandidate;
import io.github.greytaiwolf.botplayer.profile.BotProfile;
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
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.SimpleMenuProvider;
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

    private final MinecraftServer server;
    private final BotRosterSavedData roster;
    private final BotInventorySessionManager inventorySessions;
    private final PlayerInputController inputController;
    private final PerceptionService perceptionService;
    private final BotActionRuntime actionRuntime;
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
        this.actionRuntime = new BotActionRuntime(
                new MinecraftActionBackend(this, inputController),
                BotPlayerConfig.ACTION_MAILBOX_CAPACITY.get(),
                BotPlayerConfig.ACTION_LEDGER_CAPACITY.get(),
                BotPlayerConfig.ACTION_COMMANDS_PER_TICK.get(),
                BotPlayerConfig.ACTION_ACTIVE_CAPACITY.get(),
                BotPlayerConfig.ACTION_COMPLETION_CAPACITY.get(),
                perceptionService.actionOutcomeSink());
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
            inventorySessions.failOpen(token);
            BotPlayer.LOGGER.error(
                    "Refused BotPlayer inventory menu for bot {} generation {} because "
                            + "the exclusive action drain failed",
                    bot.getUUID(),
                    generation,
                    exception);
            return BotInventorySessionManager.OpenStatus.SESSION_CLOSING;
        }
        if (!cancellation.safeForExclusiveMutation()) {
            inventorySessions.failOpen(token);
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
            inventorySessions.failOpen(token);
            BotPlayer.LOGGER.error(
                    "Failed to open BotPlayer inventory menu for bot {} generation {}",
                    bot.getUUID(),
                    generation,
                    exception);
            return BotInventorySessionManager.OpenStatus.SESSION_CLOSING;
        }
        if (menuId.isEmpty()) {
            inventorySessions.failOpen(token);
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
            closeInventorySession(
                    token, InventoryCloseReason.OPEN_FAILED);
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
        closeBotInventory(
                runtime,
                InventoryCloseReason.BOT_DIMENSION_CHANGE);
        cancelBotActions(
                runtime,
                oldGeneration,
                ActionCancellationReason.LIFECYCLE);
        inputController.forceClear(
                runtime.handle.botId(), oldGeneration);
        MinecraftPlayerInputAdapter.clear(player);
        perceptionService.closeGeneration(
                runtime.handle.botId(), oldGeneration);
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
        if (runtime.state != BotLifecycleState.ACTIVE) {
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
     * Resolves the exact body for cleanup, including a dead body before listener replacement.
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
        BotServerPlayer player = runtime.handle.player().orElse(null);
        return isAuthoritativeInstance(runtime, player, false)
                ? Optional.of(player)
                : Optional.empty();
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
        closeBotInventory(runtime, InventoryCloseReason.BOT_DEATH);
        cancelBotActions(
                runtime,
                generation,
                ActionCancellationReason.LIFECYCLE);
        perceptionService.closeGeneration(
                runtime.handle.botId(), generation);
        clearPlayerInput(runtime, false);
        transition(runtime, BotLifecycleState.DEAD);
        runtime.respawnCandidate = null;
        runtime.respawnFinalizeDeadlineTick = -1;
        runtime.respawnAttempts = 0;
        runtime.respawnSuppressed = false;
        if (BotPlayerConfig.AUTO_RESPAWN.get()) {
            runtime.respawnAtTick =
                    server.getTickCount() + BotPlayerConfig.RESPAWN_DELAY_TICKS.get();
        } else {
            runtime.respawnAtTick = -1;
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
                || replacement.runtimeHandle() != runtime.handle
                || replacement.connection == null
                || replacement.connection.player != replacement
                || server.getPlayerList().getPlayer(replacement.getUUID())
                        != replacement
                || replacement.serverLevel()
                                .getPlayerByUUID(replacement.getUUID())
                        != replacement) {
            return;
        }

        long oldGeneration = runtime.handle.generation();
        closeBotInventory(
                runtime, InventoryCloseReason.BOT_RESPAWN);
        cancelBotActions(
                runtime,
                oldGeneration,
                ActionCancellationReason.LIFECYCLE);
        inputController.forceClear(
                runtime.handle.botId(), oldGeneration);
        MinecraftPlayerInputAdapter.clear(oldPlayer);
        perceptionService.closeGeneration(
                runtime.handle.botId(), oldGeneration);
        runtime.handle.attach(replacement);
        MinecraftPlayerInputAdapter.clear(replacement);
        perceptionService.activate(
                runtime.handle.botId(),
                runtime.handle.generation());
    }

    public void onDisconnected(BotServerPlayer player) {
        requireServerThread();
        RuntimeEntry runtime = runtimes.get(player.getUUID());
        if (runtime == null) {
            return;
        }

        BotServerPlayer current = runtime.handle.player().orElse(null);
        if (current == player) {
            closeBotInventory(
                    runtime, InventoryCloseReason.BOT_UNLOADED);
            cancelBotActions(
                    runtime,
                    runtime.handle.generation(),
                    ActionCancellationReason.LIFECYCLE);
            perceptionService.closeBot(player.getUUID());
            clearPlayerInput(runtime, true);
            clearAgentBinding(player.getUUID());
            runtime.handle.detach(player);
            runtimes.remove(player.getUUID());
            BotPlayer.LOGGER.info("Unloaded BotPlayer {} ({})", runtime.handle.name(), player.getUUID());
        }
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
        actionRuntime.shutdown(server.getTickCount());
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
        closeBotInventory(
                runtime, InventoryCloseReason.BOT_UNLOADED);
        cancelBotActions(
                runtime,
                runtime.handle.generation(),
                ActionCancellationReason.LIFECYCLE);
        perceptionService.closeGeneration(
                runtime.handle.botId(),
                runtime.handle.generation());
        clearPlayerInput(runtime, false);
        transition(runtime, BotLifecycleState.DESPAWNING);
        runtime.respawnAtTick = -1;
        runtime.respawnFinalizeDeadlineTick = -1;
        runtime.respawnCandidate = null;
        runtime.handle.player().ifPresent(player -> player.connection.disconnect(reason));
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
        if (runtime.state != BotLifecycleState.RESPAWNING) {
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

    private static boolean isListenerAuthority(
            BotServerPlayer player) {
        return player.connection != null
                && player.connection.player == player;
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
        @Nullable
        private BotServerPlayer respawnCandidate;

        private RuntimeEntry(BotRuntimeHandle handle, BotLifecycleState state) {
            this.handle = handle;
            this.state = state;
        }
    }
}
