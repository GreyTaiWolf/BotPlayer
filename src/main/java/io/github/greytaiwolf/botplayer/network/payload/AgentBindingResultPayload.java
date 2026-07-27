package io.github.greytaiwolf.botplayer.network.payload;

import io.github.greytaiwolf.botplayer.BotPlayer;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Reports the server-authoritative result of an agent binding request.
 */
public record AgentBindingResultPayload(
        UUID botId, UUID agentId, AgentBindingStatus status)
        implements CustomPacketPayload {
    public static final Type<AgentBindingResultPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                    BotPlayer.MOD_ID, "agent_binding_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AgentBindingResultPayload>
            STREAM_CODEC = StreamCodec.of(
                    AgentBindingResultPayload::encode,
                    AgentBindingResultPayload::decode);

    public AgentBindingResultPayload {
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(status, "status");
    }

    private static void encode(
            RegistryFriendlyByteBuf buffer, AgentBindingResultPayload payload) {
        buffer.writeUUID(payload.botId);
        buffer.writeUUID(payload.agentId);
        buffer.writeEnum(payload.status);
    }

    private static AgentBindingResultPayload decode(RegistryFriendlyByteBuf buffer) {
        return new AgentBindingResultPayload(
                buffer.readUUID(),
                buffer.readUUID(),
                buffer.readEnum(AgentBindingStatus.class));
    }

    @Override
    public Type<AgentBindingResultPayload> type() {
        return TYPE;
    }
}
