package io.github.greytaiwolf.botplayer.gametest;

import io.github.greytaiwolf.botplayer.ai.AiPhysicalAttemptIdentity;
import io.github.greytaiwolf.botplayer.network.payload.AiPhysicalAttemptOfferPayload;
import io.github.greytaiwolf.botplayer.network.payload.AiPhysicalAttemptPrepareAckPayload;
import io.github.greytaiwolf.botplayer.network.payload.AiPhysicalAttemptStartGrantPayload;
import io.github.greytaiwolf.botplayer.network.payload.AiRequestCancellationPayload;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.server.level.ServerPlayer;

/** P6-R1 GameTest helpers that keep the real mock-connection payload path intact. */
final class P6GameTestSupport {
    static final int SNAPSHOT_WAIT_TICKS = 100;
    static final int PACKET_WAIT_TICKS = 20;

    private P6GameTestSupport() {}

    /**
     * Adds a tail outbound observer to the configured mock connection without replacing its
     * NeoForge payload configuration. The observer sees the real S2C custom-payload packets before
     * the mock transport consumes them.
     */
    static OutboundPayloadCapture captureOutbound(
            ServerPlayer viewer, P2GameTestSupport.Cleanup cleanup) {
        ServerPlayer checkedViewer = Objects.requireNonNull(viewer, "viewer");
        P2GameTestSupport.Cleanup checkedCleanup = Objects.requireNonNull(cleanup, "cleanup");
        Channel channel = Objects.requireNonNull(
                checkedViewer.connection.getConnection().channel(),
                "mock viewer connection channel");
        String handlerName = "botplayer_p6_payload_capture_"
                + UUID.randomUUID().toString().replace('-', '_');
        OutboundPayloadCapture capture = new OutboundPayloadCapture(channel, handlerName);
        channel.pipeline().addLast(handlerName, capture);
        checkedCleanup.add(capture::close);
        return capture;
    }

    /** Sends the exact C2S preparation ACK through the real server listener custom-payload dispatch. */
    static void acknowledge(
            ServerPlayer viewer, AiPhysicalAttemptOfferPayload offerPayload) {
        ServerPlayer checkedViewer = Objects.requireNonNull(viewer, "viewer");
        AiPhysicalAttemptOfferPayload checkedOffer = Objects.requireNonNull(
                offerPayload, "offerPayload");
        checkedViewer.connection.handleCustomPayload(
                new ServerboundCustomPayloadPacket(
                        new AiPhysicalAttemptPrepareAckPayload(
                                checkedOffer.offer().prepareAck())));
    }

    /**
     * Captures only the R1 payloads used by these acceptance tests. It deliberately forwards every
     * packet, preserving the ordinary mock connection's NeoForge routing and capability state.
     */
    static final class OutboundPayloadCapture extends ChannelOutboundHandlerAdapter
            implements AutoCloseable {
        private final Channel channel;
        private final String handlerName;
        private final List<AiPhysicalAttemptOfferPayload> offers = new ArrayList<>();
        private final List<AiPhysicalAttemptStartGrantPayload> grants = new ArrayList<>();
        private final List<AiRequestCancellationPayload> cancellations = new ArrayList<>();
        private boolean closed;

        private OutboundPayloadCapture(Channel channel, String handlerName) {
            this.channel = Objects.requireNonNull(channel, "channel");
            this.handlerName = Objects.requireNonNull(handlerName, "handlerName");
        }

        @Override
        public void write(
                ChannelHandlerContext context,
                Object message,
                ChannelPromise promise) throws Exception {
            if (message instanceof ClientboundCustomPayloadPacket packet) {
                record(packet);
            }
            context.write(message, promise);
        }

        synchronized int offerCount() {
            return offers.size();
        }

        synchronized AiPhysicalAttemptOfferPayload offerAt(int index) {
            return offers.get(index);
        }

        synchronized int grantCount(AiPhysicalAttemptIdentity identity) {
            AiPhysicalAttemptIdentity checkedIdentity = Objects.requireNonNull(
                    identity, "identity");
            return (int) grants.stream()
                    .filter(payload -> checkedIdentity.equals(
                            payload.startGrant().identity()))
                    .count();
        }

        synchronized List<AiPhysicalAttemptStartGrantPayload> grantsFor(
                AiPhysicalAttemptIdentity identity) {
            AiPhysicalAttemptIdentity checkedIdentity = Objects.requireNonNull(
                    identity, "identity");
            return grants.stream()
                    .filter(payload -> checkedIdentity.equals(
                            payload.startGrant().identity()))
                    .toList();
        }

        synchronized int cancellationCountFor(AiPhysicalAttemptOfferPayload offerPayload) {
            AiPhysicalAttemptOfferPayload checkedOffer = Objects.requireNonNull(
                    offerPayload, "offerPayload");
            return (int) cancellations.stream()
                    .filter(payload -> payload.matches(checkedOffer.dispatch()))
                    .count();
        }

        private synchronized void record(ClientboundCustomPayloadPacket packet) {
            Object payload = packet.payload();
            if (payload instanceof AiPhysicalAttemptOfferPayload offer) {
                offers.add(offer);
            } else if (payload instanceof AiPhysicalAttemptStartGrantPayload grant) {
                grants.add(grant);
            } else if (payload instanceof AiRequestCancellationPayload cancellation) {
                cancellations.add(cancellation);
            }
        }

        @Override
        public void close() {
            synchronized (this) {
                if (closed) {
                    return;
                }
                closed = true;
            }
            try {
                if (channel.pipeline().get(handlerName) == this) {
                    channel.pipeline().remove(handlerName);
                }
            } catch (RuntimeException ignored) {
                /* The mock connection can already be closed during GameTest teardown. */
            }
        }
    }
}
