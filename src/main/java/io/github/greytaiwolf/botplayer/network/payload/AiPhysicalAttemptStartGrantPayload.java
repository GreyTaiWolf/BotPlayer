package io.github.greytaiwolf.botplayer.network.payload;

import io.github.greytaiwolf.botplayer.BotPlayer;
import io.github.greytaiwolf.botplayer.ai.AiPhysicalAttemptStartGrant;
import java.util.Objects;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server-to-owner-client committed permission for one locally fenced physical Provider start.
 *
 * <p>The grant says only that the server settled the exact reservation. The receiving client must
 * still pass its connection, binding, session and deadline fences and atomically claim its local
 * lease immediately before invoking a Provider.
 */
public record AiPhysicalAttemptStartGrantPayload(AiPhysicalAttemptStartGrant startGrant)
        implements CustomPacketPayload {
    public static final Type<AiPhysicalAttemptStartGrantPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                    BotPlayer.MOD_ID, "ai_physical_attempt_start_grant"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AiPhysicalAttemptStartGrantPayload>
            STREAM_CODEC = StreamCodec.of(
                    AiPhysicalAttemptStartGrantPayload::encode,
                    AiPhysicalAttemptStartGrantPayload::decode);

    public AiPhysicalAttemptStartGrantPayload {
        startGrant = Objects.requireNonNull(startGrant, "startGrant");
    }

    private static void encode(
            RegistryFriendlyByteBuf buffer, AiPhysicalAttemptStartGrantPayload payload) {
        AiPhysicalAttemptPayloadCodecs.writeIdentity(
                buffer, payload.startGrant.identity());
    }

    private static AiPhysicalAttemptStartGrantPayload decode(RegistryFriendlyByteBuf buffer) {
        return new AiPhysicalAttemptStartGrantPayload(
                new AiPhysicalAttemptStartGrant(
                        AiPhysicalAttemptPayloadCodecs.readIdentity(buffer)));
    }

    @Override
    public Type<AiPhysicalAttemptStartGrantPayload> type() {
        return TYPE;
    }

    @Override
    public String toString() {
        return "AiPhysicalAttemptStartGrantPayload[bound=true]";
    }
}
