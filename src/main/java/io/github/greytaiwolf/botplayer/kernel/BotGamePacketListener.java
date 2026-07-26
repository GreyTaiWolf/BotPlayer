package io.github.greytaiwolf.botplayer.kernel;

import io.github.greytaiwolf.botplayer.lifecycle.BotPlayerManagers;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
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
    public void send(@NotNull Packet<?> packet) {}

    @Override
    public void send(@NotNull Packet<?> packet, @Nullable PacketSendListener listener) {}

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

    @Override
    public void disconnect(@NotNull Component reason) {
        disconnect(new DisconnectionDetails(reason));
    }

    @Override
    public void disconnect(@NotNull DisconnectionDetails details) {
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
                botConnection.markClosed();
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
}
