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
    private final BotConnectionTelemetry telemetry = new BotConnectionTelemetry();

    public BotConnection() {
        super(PacketFlow.SERVERBOUND);
        ((ConnectionAccessor) (Object) this).botplayer$setChannel(new EmbeddedChannel());
    }

    @Override
    public void setReadOnly() {
        telemetry.markReadOnly();
    }

    @Override
    public void handleDisconnection() {}

    @Override
    public void setListenerForServerboundHandshake(@NotNull PacketListener listener) {}

    @Override
    public <T extends PacketListener> void setupInboundProtocol(
            @NotNull ProtocolInfo<T> protocolInfo, @NotNull T listener) {}

    public void markClosed() {
        markClosed(BotConnectionCloseReason.EXPLICIT_CLOSE);
    }

    public void markClosed(BotConnectionCloseReason reason) {
        telemetry.markClosed(reason);
        Channel currentChannel = channel();
        if (currentChannel != null && currentChannel.isOpen()) {
            currentChannel.close();
        }
    }

    public BotConnectionSnapshot snapshot() {
        return telemetry.snapshot();
    }

    void recordDiscarded(String packetType) {
        telemetry.recordDiscarded(packetType);
    }

    void recordRejected(String packetType) {
        telemetry.recordRejected(packetType);
    }

    void recordSuccessfulCallback() {
        telemetry.recordSuccessfulCallback();
    }

    void recordFailedCallback() {
        telemetry.recordFailedCallback();
    }

    void recordKeepAliveAcknowledgement() {
        telemetry.recordKeepAliveAcknowledgement();
    }

    void recordTeleportAcknowledgement() {
        telemetry.recordTeleportAcknowledgement();
    }
}
