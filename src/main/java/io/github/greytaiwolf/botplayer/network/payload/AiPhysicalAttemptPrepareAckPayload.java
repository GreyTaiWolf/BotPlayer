package io.github.greytaiwolf.botplayer.network.payload;

import io.github.greytaiwolf.botplayer.BotPlayer;
import io.github.greytaiwolf.botplayer.ai.AiPhysicalAttemptPrepareAck;
import java.util.Objects;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client-to-server acknowledgement that one exact local R1 attempt was staged.
 *
 * <p>This packet never reports that a Provider started or completed. The server must authenticate
 * its sender and revalidate the entire active lifecycle/ticket correlation before it can settle a
 * reservation and send a start grant.
 */
public record AiPhysicalAttemptPrepareAckPayload(AiPhysicalAttemptPrepareAck prepareAck)
        implements CustomPacketPayload {
    public static final Type<AiPhysicalAttemptPrepareAckPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                    BotPlayer.MOD_ID, "ai_physical_attempt_prepare_ack"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AiPhysicalAttemptPrepareAckPayload>
            STREAM_CODEC = StreamCodec.of(
                    AiPhysicalAttemptPrepareAckPayload::encode,
                    AiPhysicalAttemptPrepareAckPayload::decode);

    public AiPhysicalAttemptPrepareAckPayload {
        prepareAck = Objects.requireNonNull(prepareAck, "prepareAck");
    }

    private static void encode(
            RegistryFriendlyByteBuf buffer, AiPhysicalAttemptPrepareAckPayload payload) {
        AiPhysicalAttemptPayloadCodecs.writeIdentity(
                buffer, payload.prepareAck.identity());
    }

    private static AiPhysicalAttemptPrepareAckPayload decode(RegistryFriendlyByteBuf buffer) {
        return new AiPhysicalAttemptPrepareAckPayload(
                new AiPhysicalAttemptPrepareAck(
                        AiPhysicalAttemptPayloadCodecs.readIdentity(buffer)));
    }

    @Override
    public Type<AiPhysicalAttemptPrepareAckPayload> type() {
        return TYPE;
    }

    @Override
    public String toString() {
        return "AiPhysicalAttemptPrepareAckPayload[bound=true]";
    }
}
