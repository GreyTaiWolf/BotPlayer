package io.github.greytaiwolf.botplayer.network.payload;

import io.github.greytaiwolf.botplayer.BotPlayer;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Requests activation or removal of a client-local agent for an online bot.
 *
 * <p>The server derives the owner exclusively from the sending connection.
 */
public record AgentBindingPayload(UUID botId, UUID agentId, boolean active)
        implements CustomPacketPayload {
    public static final Type<AgentBindingPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(BotPlayer.MOD_ID, "agent_binding"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AgentBindingPayload> STREAM_CODEC =
            StreamCodec.of(AgentBindingPayload::encode, AgentBindingPayload::decode);

    public AgentBindingPayload {
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(agentId, "agentId");
    }

    private static void encode(
            RegistryFriendlyByteBuf buffer, AgentBindingPayload payload) {
        buffer.writeUUID(payload.botId);
        buffer.writeUUID(payload.agentId);
        buffer.writeBoolean(payload.active);
    }

    private static AgentBindingPayload decode(RegistryFriendlyByteBuf buffer) {
        return new AgentBindingPayload(
                buffer.readUUID(), buffer.readUUID(), buffer.readBoolean());
    }

    @Override
    public Type<AgentBindingPayload> type() {
        return TYPE;
    }
}
