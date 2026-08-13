package io.github.greytaiwolf.botplayer.network.payload;

import io.github.greytaiwolf.botplayer.BotPlayer;
import io.github.greytaiwolf.botplayer.ai.transport.AiClientRequestDispatch;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server-to-owner-client cancellation for one already dispatched AI request.
 *
 * <p>This is a correlation-only payload: it has no provider data, model output, credential, or
 * reason text. A client cancels only an active session whose complete immutable correlation tuple
 * matches this payload, so a delayed cancellation cannot retire a newer session accidentally.
 */
public record AiRequestCancellationPayload(
        UUID serverInstanceId,
        UUID botId,
        UUID ownerId,
        UUID agentId,
        long generation,
        UUID requestId,
        UUID nonce,
        long revision)
        implements CustomPacketPayload {
    public static final Type<AiRequestCancellationPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(BotPlayer.MOD_ID, "ai_request_cancel"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AiRequestCancellationPayload>
            STREAM_CODEC = StreamCodec.of(
                    AiRequestCancellationPayload::encode,
                    AiRequestCancellationPayload::decode);

    public AiRequestCancellationPayload {
        AiProposalPayloadChecks.requireNonZero(serverInstanceId, "serverInstanceId");
        AiProposalPayloadChecks.requireNonZero(botId, "botId");
        AiProposalPayloadChecks.requireNonZero(ownerId, "ownerId");
        AiProposalPayloadChecks.requireNonZero(agentId, "agentId");
        if (generation <= 0L) {
            throw new IllegalArgumentException("generation must be positive");
        }
        AiProposalPayloadChecks.requireNonZero(requestId, "requestId");
        AiProposalPayloadChecks.requireNonZero(nonce, "nonce");
        if (revision <= 0L) {
            throw new IllegalArgumentException("revision must be positive");
        }
    }

    /** Returns true only for the exact dispatch session this cancellation is allowed to retire. */
    public boolean matches(AiClientRequestDispatch dispatch) {
        AiClientRequestDispatch checked = Objects.requireNonNull(dispatch, "dispatch");
        return serverInstanceId.equals(checked.serverInstanceId())
                && botId.equals(checked.botId())
                && ownerId.equals(checked.ownerId())
                && agentId.equals(checked.agentId())
                && generation == checked.generation()
                && requestId.equals(checked.requestId())
                && nonce.equals(checked.nonce())
                && revision == checked.revision();
    }

    private static void encode(
            RegistryFriendlyByteBuf buffer, AiRequestCancellationPayload payload) {
        buffer.writeUUID(payload.serverInstanceId);
        buffer.writeUUID(payload.botId);
        buffer.writeUUID(payload.ownerId);
        buffer.writeUUID(payload.agentId);
        buffer.writeLong(payload.generation);
        buffer.writeUUID(payload.requestId);
        buffer.writeUUID(payload.nonce);
        buffer.writeLong(payload.revision);
    }

    private static AiRequestCancellationPayload decode(RegistryFriendlyByteBuf buffer) {
        return new AiRequestCancellationPayload(
                buffer.readUUID(),
                buffer.readUUID(),
                buffer.readUUID(),
                buffer.readUUID(),
                buffer.readLong(),
                buffer.readUUID(),
                buffer.readUUID(),
                buffer.readLong());
    }

    @Override
    public Type<AiRequestCancellationPayload> type() {
        return TYPE;
    }

    /** Do not expose the owner or nonce in diagnostics. */
    @Override
    public String toString() {
        return "AiRequestCancellationPayload[serverInstanceId=" + serverInstanceId
                + ", botId=" + botId
                + ", agentId=" + agentId
                + ", generation=" + generation
                + ", requestId=" + requestId
                + ", revision=" + revision + "]";
    }
}
