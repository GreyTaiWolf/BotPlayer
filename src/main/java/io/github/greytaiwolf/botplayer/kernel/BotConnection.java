package io.github.greytaiwolf.botplayer.kernel;

import io.github.greytaiwolf.botplayer.mixin.ConnectionAccessor;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.PacketFlow;
import org.jetbrains.annotations.NotNull;

/**
 * An always-local connection that keeps vanilla's connected-player invariants without a remote client.
 */
public final class BotConnection extends Connection {
    public BotConnection() {
        super(PacketFlow.SERVERBOUND);
        ((ConnectionAccessor) this).botplayer$setChannel(new EmbeddedChannel());
    }

    @Override
    public void setReadOnly() {}

    @Override
    public void handleDisconnection() {}

    @Override
    public void setListenerForServerboundHandshake(@NotNull PacketListener listener) {}

    @Override
    public <T extends PacketListener> void setupInboundProtocol(
            @NotNull ProtocolInfo<T> protocolInfo, @NotNull T listener) {}

    public void markClosed() {
        Channel currentChannel = channel();
        if (currentChannel != null && currentChannel.isOpen()) {
            currentChannel.close();
        }
    }
}
