package io.github.greytaiwolf.botplayer.kernel;

import io.github.greytaiwolf.botplayer.BotPlayer;
import io.github.greytaiwolf.botplayer.lifecycle.BotPlayerManagers;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.RelativeMovement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Executes serverbound player actions locally and intentionally discards clientbound packets.
 */
public final class BotGamePacketListener extends ServerGamePacketListenerImpl {
    private final AtomicBoolean closed = new AtomicBoolean();

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
        if (closed.get()) {
            return;
        }
        refreshChunkTracking();
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
        if (closed.get()) {
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
                        .ifPresent(manager -> manager.onDisconnected(botPlayer));
            }
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
