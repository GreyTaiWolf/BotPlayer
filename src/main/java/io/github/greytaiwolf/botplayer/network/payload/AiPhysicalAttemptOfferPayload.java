package io.github.greytaiwolf.botplayer.network.payload;

import io.github.greytaiwolf.botplayer.BotPlayer;
import io.github.greytaiwolf.botplayer.ai.AiPhysicalAttemptOffer;
import io.github.greytaiwolf.botplayer.ai.transport.AiClientRequestDispatch;
import java.util.Objects;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Atomic server-to-owner-client R1 dispatch plus its exact physical-attempt offer.
 *
 * <p>The payload is deliberately atomic: a queued physical client never observes a usable
 * dispatch without the identity that must fence its later prepare ACK and start grant. Receiving
 * this packet still authorizes neither Provider work nor token settlement. The physical client
 * must locally stage it, then send only {@link AiPhysicalAttemptPrepareAckPayload}.
 */
public record AiPhysicalAttemptOfferPayload(
        AiClientRequestDispatch dispatch, AiPhysicalAttemptOffer offer)
        implements CustomPacketPayload {
    public static final Type<AiPhysicalAttemptOfferPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                    BotPlayer.MOD_ID, "ai_physical_attempt_offer"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AiPhysicalAttemptOfferPayload>
            STREAM_CODEC = StreamCodec.of(
                    AiPhysicalAttemptOfferPayload::encode,
                    AiPhysicalAttemptOfferPayload::decode);

    public AiPhysicalAttemptOfferPayload {
        dispatch = Objects.requireNonNull(dispatch, "dispatch");
        offer = Objects.requireNonNull(offer, "offer");
        if (!offer.identity().matches(dispatch)) {
            throw new IllegalArgumentException(
                    "physical attempt offer identity must exactly match dispatch");
        }
    }

    private static void encode(
            RegistryFriendlyByteBuf buffer, AiPhysicalAttemptOfferPayload payload) {
        AiRequestDispatchPayload.STREAM_CODEC.encode(
                buffer, new AiRequestDispatchPayload(payload.dispatch));
        AiPhysicalAttemptPayloadCodecs.writeIdentity(buffer, payload.offer.identity());
    }

    private static AiPhysicalAttemptOfferPayload decode(RegistryFriendlyByteBuf buffer) {
        AiClientRequestDispatch dispatch = AiRequestDispatchPayload.STREAM_CODEC.decode(buffer)
                .dispatch();
        return new AiPhysicalAttemptOfferPayload(
                dispatch,
                new AiPhysicalAttemptOffer(
                        AiPhysicalAttemptPayloadCodecs.readIdentity(buffer)));
    }

    @Override
    public Type<AiPhysicalAttemptOfferPayload> type() {
        return TYPE;
    }

    /** Do not render request content, owner, nonce, or attempt identity into packet diagnostics. */
    @Override
    public String toString() {
        return "AiPhysicalAttemptOfferPayload[bound=true]";
    }
}
