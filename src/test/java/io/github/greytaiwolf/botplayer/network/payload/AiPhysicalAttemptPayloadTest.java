package io.github.greytaiwolf.botplayer.network.payload;

import io.github.greytaiwolf.botplayer.ai.AiMessage;
import io.github.greytaiwolf.botplayer.ai.AiMessageRole;
import io.github.greytaiwolf.botplayer.ai.AiPhysicalAttemptIdentity;
import io.github.greytaiwolf.botplayer.ai.AiPhysicalAttemptOffer;
import io.github.greytaiwolf.botplayer.ai.AiPhysicalAttemptPrepareAck;
import io.github.greytaiwolf.botplayer.ai.AiPhysicalAttemptStartGrant;
import io.github.greytaiwolf.botplayer.ai.AiRequestOptions;
import io.github.greytaiwolf.botplayer.ai.AiResponseFormat;
import io.github.greytaiwolf.botplayer.ai.transport.AiClientRequestDispatch;
import io.github.greytaiwolf.botplayer.ai.transport.AiRequestPurpose;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AiPhysicalAttemptPayloadTest {
    private static final UUID SERVER_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000001");
    private static final UUID BOT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000101");
    private static final UUID OWNER_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000151");
    private static final UUID AGENT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000201");
    private static final UUID REQUEST_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000301");
    private static final UUID NONCE = UUID.fromString(
            "00000000-0000-0000-0000-000000000401");
    private static final UUID ATTEMPT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000501");

    @Test
    void offerCodecRoundTripsOneAtomicExactDispatchAndOffer() {
        AiPhysicalAttemptOfferPayload original = new AiPhysicalAttemptOfferPayload(
                dispatch(), offer());
        RegistryFriendlyByteBuf buffer = buffer();
        try {
            AiPhysicalAttemptOfferPayload.STREAM_CODEC.encode(buffer, original);

            AiPhysicalAttemptOfferPayload decoded =
                    AiPhysicalAttemptOfferPayload.STREAM_CODEC.decode(buffer);

            Assertions.assertEquals(original, decoded);
            Assertions.assertEquals(REQUEST_ID,
                    decoded.offer().identity().dispatchReceipt().requestId());
            Assertions.assertTrue(decoded.offer().identity().matches(decoded.dispatch()));
        } finally {
            buffer.release();
        }
    }

    @Test
    void prepareAckAndStartGrantCodecsRoundTripTheSameExactIdentity() {
        AiPhysicalAttemptPrepareAckPayload ack = new AiPhysicalAttemptPrepareAckPayload(
                offer().prepareAck());
        AiPhysicalAttemptStartGrantPayload grant = new AiPhysicalAttemptStartGrantPayload(
                new AiPhysicalAttemptStartGrant(offer().identity()));
        RegistryFriendlyByteBuf buffer = buffer();
        try {
            AiPhysicalAttemptPrepareAckPayload.STREAM_CODEC.encode(buffer, ack);
            AiPhysicalAttemptStartGrantPayload.STREAM_CODEC.encode(buffer, grant);

            Assertions.assertEquals(ack,
                    AiPhysicalAttemptPrepareAckPayload.STREAM_CODEC.decode(buffer));
            Assertions.assertEquals(grant,
                    AiPhysicalAttemptStartGrantPayload.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void offerRefusesAnIdentityForAnotherDispatch() {
        AiClientRequestDispatch other = new AiClientRequestDispatch(
                SERVER_ID,
                BOT_ID,
                OWNER_ID,
                AGENT_ID,
                1L,
                UUID.fromString("00000000-0000-0000-0000-000000000302"),
                NONCE,
                3L,
                AiRequestPurpose.REVIEW_ONLY_V1,
                100L,
                120L,
                1_000L,
                2_000L,
                "deepseek",
                "deepseek-chat",
                List.of(new AiMessage(AiMessageRole.SYSTEM, "structured tool proposal")),
                new AiRequestOptions(
                        256,
                        500L,
                        AiResponseFormat.JSON_SCHEMA,
                        false,
                        true,
                        Optional.of(0.2D)),
                Optional.of("{\"type\":\"object\"}"));

        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new AiPhysicalAttemptOfferPayload(other, offer()));
    }

    @Test
    void diagnosticsNeverExposeOwnerNonceOrRequestContent() {
        String prompt = "physical-attempt-prompt-sentinel";
        AiClientRequestDispatch dispatch = dispatchWithPrompt(prompt);
        AiPhysicalAttemptOffer offer = new AiPhysicalAttemptOffer(
                AiPhysicalAttemptIdentity.fromDispatch(dispatch, ATTEMPT_ID, 1_500L));

        String offerDiagnostic = new AiPhysicalAttemptOfferPayload(dispatch, offer).toString();
        String ackDiagnostic = new AiPhysicalAttemptPrepareAckPayload(
                new AiPhysicalAttemptPrepareAck(offer.identity())).toString();
        String grantDiagnostic = new AiPhysicalAttemptStartGrantPayload(
                new AiPhysicalAttemptStartGrant(offer.identity())).toString();

        for (String diagnostic : List.of(offerDiagnostic, ackDiagnostic, grantDiagnostic)) {
            Assertions.assertFalse(diagnostic.contains(OWNER_ID.toString()));
            Assertions.assertFalse(diagnostic.contains(NONCE.toString()));
            Assertions.assertFalse(diagnostic.contains(prompt));
        }
    }

    private static AiPhysicalAttemptOffer offer() {
        return new AiPhysicalAttemptOffer(
                AiPhysicalAttemptIdentity.fromDispatch(dispatch(), ATTEMPT_ID, 1_500L));
    }

    private static AiClientRequestDispatch dispatch() {
        return dispatchWithPrompt("structured tool proposal");
    }

    private static AiClientRequestDispatch dispatchWithPrompt(String prompt) {
        return new AiClientRequestDispatch(
                SERVER_ID,
                BOT_ID,
                OWNER_ID,
                AGENT_ID,
                1L,
                REQUEST_ID,
                NONCE,
                3L,
                AiRequestPurpose.REVIEW_ONLY_V1,
                100L,
                120L,
                1_000L,
                2_000L,
                "deepseek",
                "deepseek-chat",
                List.of(new AiMessage(AiMessageRole.SYSTEM, prompt)),
                new AiRequestOptions(
                        256,
                        500L,
                        AiResponseFormat.JSON_SCHEMA,
                        false,
                        true,
                        Optional.of(0.2D)),
                Optional.of("{\"type\":\"object\"}"));
    }

    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(
                Unpooled.buffer(), RegistryAccess.EMPTY, ConnectionType.NEOFORGE);
    }
}
