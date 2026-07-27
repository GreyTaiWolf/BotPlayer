package io.github.greytaiwolf.botplayer.lifecycle;

import com.mojang.authlib.GameProfile;
import io.github.greytaiwolf.botplayer.BotPlayer;
import io.github.greytaiwolf.botplayer.config.BotPlayerConfig;
import io.github.greytaiwolf.botplayer.kernel.BotConnection;
import io.github.greytaiwolf.botplayer.kernel.BotRuntimeHandle;
import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
import io.github.greytaiwolf.botplayer.network.payload.AgentBindingStatus;
import io.github.greytaiwolf.botplayer.network.payload.OpenCredentialScreenPayload;
import io.github.greytaiwolf.botplayer.persistence.BotRosterSavedData;
import io.github.greytaiwolf.botplayer.profile.BotProfile;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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

    private final MinecraftServer server;
    private final BotRosterSavedData roster;
    private final Map<UUID, RuntimeEntry> runtimes = new LinkedHashMap<>();
    private final Map<UUID, UUID> activeAgentByBot = new LinkedHashMap<>();
    private final Map<UUID, UUID> botByActiveAgent = new LinkedHashMap<>();
    private boolean stopping;

    BotLifecycleManager(MinecraftServer server) {
        this.server = server;
        this.roster = BotRosterSavedData.get(server);
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
        BotRuntimeHandle handle = new BotRuntimeHandle(
                botId, canonicalName, profile.ownerId().orElse(null));
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
                runtime.state = BotLifecycleState.ACTIVE;
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
                        runtime.handle.botId(), runtime.handle.name(), runtime.state))
                .sorted(Comparator.comparing(BotSnapshot::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
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
        if (runtime == null || runtime.state == BotLifecycleState.DESPAWNING) {
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
        if (runtime == null || runtime.state == BotLifecycleState.DESPAWNING) {
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

    public void onDeath(BotServerPlayer player) {
        requireServerThread();
        RuntimeEntry runtime = runtimes.get(player.getUUID());
        if (runtime == null || runtime.handle.player().orElse(null) != player) {
            return;
        }
        if (runtime.state == BotLifecycleState.DEAD
                || runtime.state == BotLifecycleState.RESPAWNING
                || runtime.state == BotLifecycleState.DESPAWNING) {
            return;
        }

        runtime.state = BotLifecycleState.DEAD;
        if (BotPlayerConfig.AUTO_RESPAWN.get()) {
            runtime.respawnAtTick =
                    server.getTickCount() + BotPlayerConfig.RESPAWN_DELAY_TICKS.get();
        } else {
            runtime.respawnAtTick = -1;
        }
    }

    public void onRespawn(BotServerPlayer player) {
        requireServerThread();
        RuntimeEntry runtime = runtimes.get(player.getUUID());
        if (runtime == null) {
            return;
        }
        runtime.handle.attach(player);
        runtime.state = BotLifecycleState.ACTIVE;
        runtime.respawnAtTick = -1;
    }

    public void onDisconnected(BotServerPlayer player) {
        requireServerThread();
        RuntimeEntry runtime = runtimes.get(player.getUUID());
        if (runtime == null) {
            return;
        }

        BotServerPlayer current = runtime.handle.player().orElse(null);
        if (current == player) {
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

        int currentTick = server.getTickCount();
        for (RuntimeEntry runtime : List.copyOf(runtimes.values())) {
            if (runtime.state == BotLifecycleState.DEAD
                    && runtime.respawnAtTick < 0
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

            runtime.state = BotLifecycleState.RESPAWNING;
            player.connection.handleClientCommand(new ServerboundClientCommandPacket(
                    ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
        }
    }

    public void shutdown() {
        requireServerThread();
        if (stopping) {
            return;
        }
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
            runtimes.clear();
            activeAgentByBot.clear();
            botByActiveAgent.clear();
        }
    }

    private void disconnect(RuntimeEntry runtime, Component reason) {
        runtime.state = BotLifecycleState.DESPAWNING;
        runtime.respawnAtTick = -1;
        runtime.handle.player().ifPresent(player -> player.connection.disconnect(reason));
    }

    private void rollbackFailedSpawn(
            BotServerPlayer player, BotConnection connection, RuntimeEntry runtime) {
        boolean isRegistered = server.getPlayerList().getPlayer(player.getUUID()) == player;
        boolean isInLevel =
                player.serverLevel().getPlayerByUUID(player.getUUID()) == player;
        if ((isRegistered || isInLevel) && player.connection != null) {
            player.connection.disconnect(Component.literal("BotPlayer spawn failed"));
        } else if (isRegistered || isInLevel) {
            server.getPlayerList().remove(player);
        }
        connection.markClosed();
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
            if (player.connection != null
                    && player.connection.getConnection() instanceof BotConnection botConnection) {
                botConnection.markClosed();
            }
            runtime.handle.detach(player);
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

        private RuntimeEntry(BotRuntimeHandle handle, BotLifecycleState state) {
            this.handle = handle;
            this.state = state;
        }
    }
}
