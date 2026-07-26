package io.github.greytaiwolf.botplayer.lifecycle;

import com.mojang.authlib.GameProfile;
import io.github.greytaiwolf.botplayer.BotPlayer;
import io.github.greytaiwolf.botplayer.config.BotPlayerConfig;
import io.github.greytaiwolf.botplayer.identity.BotIdentityIds;
import io.github.greytaiwolf.botplayer.kernel.BotConnection;
import io.github.greytaiwolf.botplayer.kernel.BotRuntimeHandle;
import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.nio.file.Files;
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
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.jetbrains.annotations.Nullable;

/**
 * Owns online bot lifecycle. Every mutation is required to run on the Minecraft server thread.
 */
public final class BotLifecycleManager {
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private final MinecraftServer server;
    private final Map<UUID, RuntimeEntry> runtimes = new LinkedHashMap<>();
    private boolean stopping;

    BotLifecycleManager(MinecraftServer server) {
        this.server = server;
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

        UUID botId = BotIdentityIds.forImmutableName(requestedName);
        if (server.getPlayerList().getPlayer(botId) != null) {
            throw new IllegalArgumentException("That BotPlayer identity is already online");
        }
        @Nullable UUID ownerId =
                source.getEntity() instanceof ServerPlayer owner ? owner.getUUID() : null;
        BotRuntimeHandle handle = new BotRuntimeHandle(botId, requestedName, ownerId);
        RuntimeEntry runtime = new RuntimeEntry(handle, BotLifecycleState.SPAWNING);
        runtimes.put(botId, runtime);

        ServerLevel level = source.getLevel();
        Vec3 position = source.getPosition();
        Vec2 rotation = source.getRotation();
        GameProfile profile = new GameProfile(botId, requestedName);
        ClientInformation clientInformation = ClientInformation.createDefault();
        BotServerPlayer player =
                new BotServerPlayer(server, level, profile, clientInformation, handle);
        BotConnection connection = new BotConnection();
        boolean hasExistingPlayerData = Files.isRegularFile(server
                .getWorldPath(LevelResource.PLAYER_DATA_DIR)
                .resolve(botId + ".dat"));

        try {
            CommonListenerCookie cookie = new CommonListenerCookie(
                    profile,
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
                    "Spawned BotPlayer {} ({}) in {}", requestedName, botId, level.dimension().location());
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
        for (RuntimeEntry runtime : new ArrayList<>(runtimes.values())) {
            disconnect(runtime, Component.literal("Server stopping"));
        }
        runtimes.clear();
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
