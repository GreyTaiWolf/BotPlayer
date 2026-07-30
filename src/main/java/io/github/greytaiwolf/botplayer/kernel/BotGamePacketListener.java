package io.github.greytaiwolf.botplayer.kernel;

import io.github.greytaiwolf.botplayer.BotPlayer;
import io.github.greytaiwolf.botplayer.lifecycle.BotLifecycleManager.ListenerDisconnectDecision;
import io.github.greytaiwolf.botplayer.lifecycle.BotPlayerManagers;
import io.github.greytaiwolf.botplayer.perception.SoundObservationCandidate;
import io.github.greytaiwolf.botplayer.perception.event.SpatialPoint;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.RelativeMovement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Executes serverbound player actions locally and intentionally discards clientbound packets.
 */
public final class BotGamePacketListener extends ServerGamePacketListenerImpl {
    private static final int MAX_DISCONNECT_DEFERRALS =
            1;
    private final AtomicBoolean disconnectRequested =
            new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private int disconnectDeferrals;

    public BotGamePacketListener(
            MinecraftServer server,
            Connection connection,
            ServerPlayer player,
            CommonListenerCookie cookie) {
        super(server, connection, player, cookie);
    }

    @Override
    public void send(@NotNull Packet<?> packet) {
        discardClientbound(packet, null);
    }

    @Override
    public void send(@NotNull Packet<?> packet, @Nullable PacketSendListener listener) {
        discardClientbound(packet, listener);
    }

    @Override
    public void teleport(
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            @NotNull Set<RelativeMovement> relativeMovements) {
        super.teleport(x, y, z, yaw, pitch, relativeMovements);
        refreshChunkTracking();
    }

    /**
     * Advances only the connection bookkeeping a clientless player still needs.
     *
     * <p>Calling {@link #tick()} would run {@code player.doTick()} a second time and then move the
     * bot back to the listener's previous network position. The lifecycle manager already owns
     * that player phase, so this method only commits the new authoritative position to vanilla
     * connection and chunk-tracking state.
     */
    public void tickVirtualProtocol() {
        if (!server.isSameThread()) {
            throw new IllegalStateException(
                    "BotPlayer virtual protocol must run on the server thread");
        }
        if (disconnectRequested.get()
                || closed.get()) {
            return;
        }
        refreshChunkTracking();
    }

    /**
     * Reports whether this listener may still authorize generation-bound
     * runtime work.
     */
    public boolean acceptsRuntimeAuthority() {
        return !disconnectRequested.get()
                && !closed.get();
    }

    public boolean disconnectRequested() {
        return disconnectRequested.get();
    }

    @Override
    public void disconnect(@NotNull Component reason) {
        onDisconnect(new DisconnectionDetails(reason));
    }

    @Override
    public void disconnect(@NotNull DisconnectionDetails details) {
        onDisconnect(details);
    }

    @Override
    public void onDisconnect(@NotNull DisconnectionDetails details) {
        if (closed.get()
                || !disconnectRequested.compareAndSet(
                        false, true)) {
            return;
        }

        Runnable closeAction = () -> closeOnServerThread(details);
        if (server.isSameThread()) {
            closeAction.run();
        } else {
            server.execute(closeAction);
        }
    }

    private void closeOnServerThread(DisconnectionDetails details) {
        if (closed.get()) {
            return;
        }
        ListenerDisconnectDecision decision =
                player instanceof BotServerPlayer botPlayer
                        ? BotPlayerManagers.find(server)
                                .map(manager ->
                                        manager.onDisconnecting(
                                                botPlayer,
                                                this,
                                                connection))
                                .orElse(
                                        ListenerDisconnectDecision
                                                .PROCEED)
                        : ListenerDisconnectDecision
                                .PROCEED;
        if (decision
                == ListenerDisconnectDecision.RETRY) {
            if (disconnectDeferrals++
                            >= MAX_DISCONNECT_DEFERRALS
                    && player
                            instanceof BotServerPlayer
                                    botPlayer
                    && BotPlayerManagers.find(server)
                            .map(manager ->
                                    manager.onDisconnectRetryExhausted(
                                            botPlayer,
                                            this,
                                            connection))
                            .orElse(true)) {
                closeWithoutVanillaDisconnect();
                return;
            }
            /*
             * A lifecycle retirement or vanilla replacement stack already
             * owns this body. ProcessorHandle#tell always appends a concrete
             * TickTask; Executor#execute-style helpers may run inline on the
             * server thread and recurse before that owner can finish.
             */
            server.tell(new TickTask(
                    server.getTickCount(),
                    () -> closeOnServerThread(details)));
            return;
        }
        if (decision
                == ListenerDisconnectDecision.ABORTED) {
            closeWithoutVanillaDisconnect();
            return;
        }
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        try {
            super.onDisconnect(details);
        } finally {
            if (connection instanceof BotConnection botConnection) {
                botConnection.markClosed(BotConnectionCloseReason.LISTENER_DISCONNECT);
            }
            if (player instanceof BotServerPlayer botPlayer) {
                BotPlayerManagers.find(server)
                        .ifPresent(manager ->
                                manager.onDisconnected(
                                        botPlayer,
                                        this,
                                        connection));
            }
        }
    }

    private void closeWithoutVanillaDisconnect() {
        closed.set(true);
        if (connection
                instanceof BotConnection botConnection) {
            botConnection.markClosed(
                    BotConnectionCloseReason
                            .LISTENER_DISCONNECT);
        }
    }

    private void refreshChunkTracking() {
        resetPosition();
        if (player.serverLevel().getPlayerByUUID(player.getUUID()) != null) {
            player.serverLevel().getChunkSource().move(player);
        }
    }

    private void discardClientbound(
            Packet<?> packet, @Nullable PacketSendListener sendListener) {
        String packetType = packet.getClass().getSimpleName();
        if (!(connection instanceof BotConnection botConnection)) {
            safelyComplete(sendListener, null);
            return;
        }

        botConnection.recordDiscarded(packetType);
        try {
            captureSoundObservation(packet);
            if (packet instanceof ClientboundPlayerPositionPacket positionPacket) {
                handleAcceptTeleportPacket(
                        new ServerboundAcceptTeleportationPacket(positionPacket.getId()));
                botConnection.recordTeleportAcknowledgement();
            } else if (packet instanceof ClientboundKeepAlivePacket) {
                // The connection is in-process and cannot time out in transit. Recording the
                // acknowledgement is sufficient; invoking the vanilla handler without its
                // private pending challenge would incorrectly disconnect a healthy bot.
                botConnection.recordKeepAliveAcknowledgement();
            }
            safelyComplete(sendListener, botConnection);
        } catch (RuntimeException exception) {
            botConnection.recordRejected(packetType);
            safelyComplete(sendListener, botConnection);
            BotPlayer.LOGGER.error(
                    "BotPlayer virtual protocol rejected clientbound packet {}",
                    packetType,
                    exception);
        }
    }

    private void captureSoundObservation(Packet<?> packet) {
        /*
         * 发包接口允许被异步调用；P3 不能在该路径读取 ServerPlayer、level 或
         * generation。异步声音候选保守丢弃，包本身仍按虚拟连接契约完成。
         */
        if (!server.isSameThread()) {
            return;
        }
        if (!(player instanceof BotServerPlayer botPlayer)) {
            return;
        }
        try {
            if (packet instanceof ClientboundSoundPacket soundPacket) {
                offerSound(
                        botPlayer,
                        soundPacket.getX(),
                        soundPacket.getY(),
                        soundPacket.getZ(),
                        BuiltInRegistries.SOUND_EVENT
                                .getKey(soundPacket.getSound().value())
                                .toString(),
                        soundPacket.getSource()
                                .name()
                                .toLowerCase(Locale.ROOT),
                        soundPacket.getVolume(),
                        soundPacket.getPitch());
            } else if (packet instanceof ClientboundSoundEntityPacket soundPacket) {
                Entity sourceEntity =
                        botPlayer.serverLevel().getEntity(soundPacket.getId());
                if (sourceEntity != null) {
                    offerSound(
                            botPlayer,
                            sourceEntity.getX(),
                            sourceEntity.getY(),
                            sourceEntity.getZ(),
                            BuiltInRegistries.SOUND_EVENT
                                    .getKey(soundPacket.getSound().value())
                                    .toString(),
                            soundPacket.getSource()
                                    .name()
                                    .toLowerCase(Locale.ROOT),
                            soundPacket.getVolume(),
                            soundPacket.getPitch());
                }
            }
        } catch (RuntimeException exception) {
            BotPlayer.LOGGER.debug(
                    "Ignored an invalid BotPlayer sound observation candidate",
                    exception);
        }
    }

    private void offerSound(
            BotServerPlayer botPlayer,
            double x,
            double y,
            double z,
            String soundId,
            String source,
            float volume,
            float pitch) {
        long generation =
                botPlayer.runtimeHandle().generation();
        if (generation <= 0
                || !Double.isFinite(x)
                || !Double.isFinite(y)
                || !Double.isFinite(z)
                || !Float.isFinite(volume)
                || volume < 0.0F
                || !Float.isFinite(pitch)
                || pitch < 0.0F) {
            return;
        }
        SoundObservationCandidate candidate =
                new SoundObservationCandidate(
                        botPlayer.getUUID(),
                        generation,
                        botPlayer.serverLevel()
                                .dimension()
                                .location()
                                .toString(),
                        server.getTickCount(),
                        new SpatialPoint(x, y, z),
                        soundId,
                        source,
                        volume,
                        pitch);
        BotPlayerManagers.find(server)
                .ifPresent(manager ->
                        manager.offerSoundObservation(candidate));
    }

    private static void safelyComplete(
            @Nullable PacketSendListener sendListener,
            @Nullable BotConnection botConnection) {
        if (sendListener == null) {
            return;
        }
        try {
            sendListener.onSuccess();
            if (botConnection != null) {
                botConnection.recordSuccessfulCallback();
            }
        } catch (RuntimeException exception) {
            if (botConnection != null) {
                botConnection.recordFailedCallback();
            }
            BotPlayer.LOGGER.error(
                    "BotPlayer isolated a failed clientbound packet callback", exception);
        }
    }
}
