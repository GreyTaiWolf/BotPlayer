package io.github.greytaiwolf.botplayer.network.payload;

import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AiProposalPayloadTest {
    private static final UUID BOT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000101");
    private static final UUID AGENT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000201");
    private static final UUID REQUEST_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000301");
    private static final UUID NONCE = UUID.fromString(
            "00000000-0000-0000-0000-000000000401");

    @Test
    void streamCodecRoundTripsACompleteBoundedProposal() {
        AiProposalPayload original = payload(
                "模型输出不会被服务端执行。",
                List.of(new AiProposalToolCallPayload(
                        "call_1", "safe_tool", "{\"count\":1}")));
        RegistryFriendlyByteBuf buffer = buffer();
        try {
            AiProposalPayload.STREAM_CODEC.encode(buffer, original);

            AiProposalPayload decoded = AiProposalPayload.STREAM_CODEC.decode(buffer);

            Assertions.assertEquals(original, decoded);
        } finally {
            buffer.release();
        }
    }

    @Test
    void exactCodecFrameLimitStaysBelowTheServerboundCustomPayloadCeiling() {
        AiProposalPayload valid = payload(
                "x".repeat(AiProposalPayload.MAX_ENCODED_FRAME_BYTES - 100),
                List.of());
        Assertions.assertTrue(valid.encodedByteLength()
                <= AiProposalPayload.MAX_ENCODED_FRAME_BYTES);

        RegistryFriendlyByteBuf encoded = buffer();
        try {
            AiProposalPayload.STREAM_CODEC.encode(encoded, valid);
            Assertions.assertEquals(valid.encodedByteLength(), encoded.writerIndex());
            Assertions.assertTrue(encoded.writerIndex()
                    <= AiProposalPayload.MAX_ENCODED_FRAME_BYTES);
        } finally {
            encoded.release();
        }

        Assertions.assertThrows(IllegalArgumentException.class,
                () -> payload(
                        "x".repeat(AiProposalPayload.MAX_ENCODED_FRAME_BYTES),
                        List.of()));
    }

    @Test
    void decoderRejectsAFrameThatFitsPerFieldLimitsButExceedsTheExactFrameLimit() {
        RegistryFriendlyByteBuf buffer = buffer();
        try {
            writeHeader(buffer);
            writeUtf(buffer, "x".repeat(AiProposalPayload.MAX_TOTAL_CONTENT_UTF8_BYTES));
            buffer.writeVarInt(0);

            Assertions.assertThrows(IllegalArgumentException.class,
                    () -> AiProposalPayload.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void streamCodecRejectsAnOversizedToolCallCountBeforeAllocatingTheList() {
        RegistryFriendlyByteBuf buffer = buffer();
        try {
            writeHeader(buffer);
            byte[] output = "safe".getBytes(StandardCharsets.UTF_8);
            buffer.writeVarInt(output.length);
            buffer.writeBytes(output);
            buffer.writeVarInt(AiProposalPayload.MAX_TOOL_CALLS + 1);

            Assertions.assertThrows(IllegalArgumentException.class,
                    () -> AiProposalPayload.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void streamCodecRejectsMalformedOrOverBudgetUtf8BeforeConstructingPayload() {
        RegistryFriendlyByteBuf malformed = buffer();
        try {
            writeHeader(malformed);
            malformed.writeVarInt(1);
            malformed.writeByte(0x80);

            Assertions.assertThrows(IllegalArgumentException.class,
                    () -> AiProposalPayload.STREAM_CODEC.decode(malformed));
        } finally {
            malformed.release();
        }

        RegistryFriendlyByteBuf oversized = buffer();
        try {
            writeHeader(oversized);
            oversized.writeVarInt(AiProposalPayload.MAX_OUTPUT_TEXT_UTF8_BYTES + 1);

            Assertions.assertThrows(IllegalArgumentException.class,
                    () -> AiProposalPayload.STREAM_CODEC.decode(oversized));
        } finally {
            oversized.release();
        }
    }

    @Test
    void streamCodecEnforcesTheAggregateBudgetBeforeAllocatingLaterArguments() {
        RegistryFriendlyByteBuf buffer = buffer();
        try {
            writeHeader(buffer);
            writeUtf(buffer, "x".repeat(AiProposalPayload.MAX_OUTPUT_TEXT_UTF8_BYTES));
            buffer.writeVarInt(AiProposalPayload.MAX_TOOL_CALLS);
            String arguments = "x".repeat(
                    AiProposalToolCallPayload.MAX_ARGUMENTS_UTF8_BYTES);
            for (int index = 0; index < AiProposalPayload.MAX_TOOL_CALLS; index++) {
                writeUtf(buffer, "call_" + index);
                writeUtf(buffer, "safe_tool");
                writeUtf(buffer, arguments);
            }

            Assertions.assertThrows(IllegalArgumentException.class,
                    () -> AiProposalPayload.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void rejectsBlankResponseAndCredentialLikeOrMalformedUnicodeContent() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> payload("  ", List.of()));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> payload("Authorization: Bearer sk-live-abcdef", List.of()));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> payload("\uD800", List.of()));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new AiProposalToolCallPayload(
                        "call_1", "safe_tool", "{\"api_key\":\"value\"}"));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new AiProposalToolCallPayload(
                        "call_\uD800", "safe_tool", "{}"));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new AiProposalToolCallPayload(
                        "call_1", "safe_\uD800", "{}"));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new AiProposalToolCallPayload(
                        "汉".repeat(43), "safe_tool", "{}"));
    }

    @Test
    void copiesToolCallsAndNeverPrintsNonceOrUntrustedModelValues() {
        String output = "model-output-sentinel";
        String arguments = "{\"value\":\"argument-sentinel\"}";
        List<AiProposalToolCallPayload> source = new ArrayList<>();
        source.add(new AiProposalToolCallPayload("call_sentinel", "safe_tool", arguments));

        AiProposalPayload proposal = payload(output, source);
        source.clear();

        Assertions.assertEquals(1, proposal.toolCalls().size());
        String diagnostic = proposal.toString();
        Assertions.assertFalse(diagnostic.contains(output));
        Assertions.assertFalse(diagnostic.contains(arguments));
        Assertions.assertFalse(diagnostic.contains(NONCE.toString()));
        Assertions.assertFalse(diagnostic.contains("safe_tool"));
        Assertions.assertFalse(proposal.toolCalls().getFirst().toString()
                .contains(arguments));
    }

    @Test
    void rejectsPerFieldUtf8BudgetOverflow() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> payload(
                        "x".repeat(AiProposalPayload.MAX_OUTPUT_TEXT_UTF8_BYTES + 1),
                        List.of()));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new AiProposalToolCallPayload(
                        "call_1",
                        "safe_tool",
                        "x".repeat(
                                AiProposalToolCallPayload.MAX_ARGUMENTS_UTF8_BYTES + 1)));
    }

    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(
                Unpooled.buffer(), RegistryAccess.EMPTY, ConnectionType.NEOFORGE);
    }

    private static void writeHeader(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(BOT_ID);
        buffer.writeUUID(AGENT_ID);
        buffer.writeLong(1L);
        buffer.writeUUID(REQUEST_ID);
        buffer.writeUUID(NONCE);
        buffer.writeLong(1L);
    }

    private static void writeUtf(RegistryFriendlyByteBuf buffer, String value) {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        buffer.writeVarInt(encoded.length);
        buffer.writeBytes(encoded);
    }

    private static AiProposalPayload payload(
            String outputText, List<AiProposalToolCallPayload> toolCalls) {
        return new AiProposalPayload(
                BOT_ID,
                AGENT_ID,
                1L,
                REQUEST_ID,
                NONCE,
                1L,
                outputText,
                toolCalls);
    }
}
