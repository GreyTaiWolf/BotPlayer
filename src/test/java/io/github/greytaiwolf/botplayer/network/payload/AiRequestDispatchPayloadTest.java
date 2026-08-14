package io.github.greytaiwolf.botplayer.network.payload;

import io.github.greytaiwolf.botplayer.ai.AiMessage;
import io.github.greytaiwolf.botplayer.ai.AiMessageRole;
import io.github.greytaiwolf.botplayer.ai.AiRequestOptions;
import io.github.greytaiwolf.botplayer.ai.AiResponseFormat;
import io.github.greytaiwolf.botplayer.ai.transport.AiClientRequestDispatch;
import io.github.greytaiwolf.botplayer.ai.transport.AiRequestPurpose;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AiRequestDispatchPayloadTest {
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

    @Test
    void streamCodecRoundTripsTheCompleteSecretFreeDispatch() {
        AiRequestDispatchPayload original = new AiRequestDispatchPayload(dispatch());
        RegistryFriendlyByteBuf buffer = buffer();
        try {
            AiRequestDispatchPayload.STREAM_CODEC.encode(buffer, original);

            AiRequestDispatchPayload decoded = AiRequestDispatchPayload.STREAM_CODEC.decode(buffer);

            Assertions.assertEquals(original, decoded);
            Assertions.assertEquals(REQUEST_ID, decoded.dispatch().toAiRequest().requestId());
            Assertions.assertEquals("deepseek", decoded.dispatch().providerId());
            Assertions.assertEquals(AiRequestPurpose.REVIEW_ONLY_V1,
                    decoded.dispatch().purpose());
        } finally {
            buffer.release();
        }
    }

    @Test
    void decoderRejectsOversizedMessageCountBeforeAllocatingMessages() {
        RegistryFriendlyByteBuf buffer = buffer();
        try {
            writeHeader(buffer);
            writeUtf(buffer, "deepseek");
            writeUtf(buffer, "deepseek-chat");
            buffer.writeVarInt(129);

            Assertions.assertThrows(IllegalArgumentException.class,
                    () -> AiRequestDispatchPayload.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void decoderRejectsAnEpochDeadlineThatOutlivesItsTickGate() {
        RegistryFriendlyByteBuf buffer = buffer();
        try {
            writeHeader(buffer, 100L, 101L, 1_000L, 1_051L);
            writeUtf(buffer, "deepseek");
            writeUtf(buffer, "deepseek-chat");
            buffer.writeVarInt(1);
            buffer.writeEnum(AiMessageRole.USER);
            writeUtf(buffer, "safe");
            buffer.writeVarInt(256);
            buffer.writeLong(50L);
            buffer.writeEnum(AiResponseFormat.TEXT);
            buffer.writeBoolean(false);
            buffer.writeBoolean(true);
            buffer.writeBoolean(false);
            buffer.writeBoolean(false);

            Assertions.assertThrows(IllegalArgumentException.class,
                    () -> AiRequestDispatchPayload.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void diagnosticsNeverExposeNoncePromptOrSchema() {
        String prompt = "dispatch-prompt-sentinel";
        String schema = "schema-sentinel";
        AiClientRequestDispatch dispatch = new AiClientRequestDispatch(
                SERVER_ID,
                BOT_ID,
                OWNER_ID,
                AGENT_ID,
                1L,
                REQUEST_ID,
                NONCE,
                1L,
                100L,
                120L,
                1_000L,
                2_000L,
                "deepseek",
                "deepseek-chat",
                List.of(new AiMessage(AiMessageRole.USER, prompt)),
                new AiRequestOptions(
                        256,
                        500L,
                        AiResponseFormat.JSON_SCHEMA,
                        false,
                        false,
                        Optional.empty()),
                Optional.of("{\"schema\":\"" + schema + "\"}"));

        String diagnostic = new AiRequestDispatchPayload(dispatch).toString();
        Assertions.assertFalse(diagnostic.contains(NONCE.toString()));
        Assertions.assertFalse(diagnostic.contains(prompt));
        Assertions.assertFalse(diagnostic.contains(schema));
    }

    private static AiClientRequestDispatch dispatch() {
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
                List.of(new AiMessage(AiMessageRole.SYSTEM, "structured tool proposal")),
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

    private static void writeHeader(RegistryFriendlyByteBuf buffer) {
        writeHeader(buffer, 100L, 120L, 1_000L, 2_000L);
    }

    private static void writeHeader(
            RegistryFriendlyByteBuf buffer,
            long issuedAtTick,
            long expiresAtTick,
            long issuedAtEpochMillis,
            long expiresAtEpochMillis) {
        buffer.writeUUID(SERVER_ID);
        buffer.writeUUID(BOT_ID);
        buffer.writeUUID(OWNER_ID);
        buffer.writeUUID(AGENT_ID);
        buffer.writeLong(1L);
        buffer.writeUUID(REQUEST_ID);
        buffer.writeUUID(NONCE);
        buffer.writeLong(1L);
        buffer.writeEnum(AiRequestPurpose.UNSPECIFIED_V1);
        buffer.writeLong(issuedAtTick);
        buffer.writeLong(expiresAtTick);
        buffer.writeLong(issuedAtEpochMillis);
        buffer.writeLong(expiresAtEpochMillis);
    }

    private static void writeUtf(RegistryFriendlyByteBuf buffer, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        buffer.writeVarInt(bytes.length);
        buffer.writeBytes(bytes);
    }
}
