package io.github.greytaiwolf.botplayer.network.payload;

import io.github.greytaiwolf.botplayer.BotPlayer;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Owner client 回传的、完全不可信的模型提案。
 *
 * <p>wire contract 仅包含服务器已经签发的请求关联字段，以及模型输出/工具调用。它故意没有
 * provider endpoint、credential profile、Authorization、模型 API key 或任何可还原 credential
 * 字段。构造和解码阶段还会拒绝 {@code RedactionFilter} 可识别的 credential-like 文本。
 */
public record AiProposalPayload(
        UUID botId,
        UUID agentId,
        long generation,
        UUID requestId,
        UUID nonce,
        long revision,
        String outputText,
        List<AiProposalToolCallPayload> toolCalls)
        implements CustomPacketPayload {
    /**
     * NeoForge rejects serverbound custom payload frames at roughly 32 KiB.  Leave a full KiB for
     * the custom-payload id and transport framing, then enforce the bytes written by this codec
     * exactly rather than trusting a content-only aggregate.
     */
    public static final int MAX_ENCODED_FRAME_BYTES = 30 * 1024;
    public static final int MAX_OUTPUT_TEXT_UTF8_BYTES = 32_768;
    public static final int MAX_TOOL_CALLS = 16;
    /**
     * A cheap early allocation guard. {@link #encodedByteLength()} is the authoritative bound,
     * because every UTF-8 field also has a varint length prefix.
     */
    public static final int MAX_TOTAL_CONTENT_UTF8_BYTES =
            MAX_ENCODED_FRAME_BYTES - fixedWireBytes() - 2;

    public static final Type<AiProposalPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(BotPlayer.MOD_ID, "ai_proposal"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AiProposalPayload> STREAM_CODEC =
            StreamCodec.of(AiProposalPayload::encode, AiProposalPayload::decode);

    public AiProposalPayload {
        AiProposalPayloadChecks.requireNonZero(botId, "botId");
        AiProposalPayloadChecks.requireNonZero(agentId, "agentId");
        if (generation <= 0L) {
            throw new IllegalArgumentException("generation must be positive");
        }
        AiProposalPayloadChecks.requireNonZero(requestId, "requestId");
        AiProposalPayloadChecks.requireNonZero(nonce, "nonce");
        if (revision <= 0L) {
            throw new IllegalArgumentException("revision must be positive");
        }
        int totalBytes = AiProposalPayloadChecks.requireBoundedContent(
                outputText,
                "outputText",
                MAX_OUTPUT_TEXT_UTF8_BYTES,
                true);
        Objects.requireNonNull(toolCalls, "toolCalls");
        if (toolCalls.size() > MAX_TOOL_CALLS) {
            throw new IllegalArgumentException(
                    "toolCalls exceeds maximum size " + MAX_TOOL_CALLS);
        }
        if (outputText.isBlank() && toolCalls.isEmpty()) {
            throw new IllegalArgumentException(
                    "proposal requires outputText or a tool call");
        }

        List<AiProposalToolCallPayload> copied = new ArrayList<>(toolCalls.size());
        Set<String> callIds = new HashSet<>();
        for (AiProposalToolCallPayload toolCall : toolCalls) {
            AiProposalToolCallPayload checked = Objects.requireNonNull(
                    toolCall, "toolCall");
            if (!callIds.add(checked.callId())) {
                throw new IllegalArgumentException("duplicate tool call id");
            }
            totalBytes = AiProposalPayloadChecks.addWithinTotal(
                    totalBytes,
                    AiProposalPayloadChecks.utf8Length(
                            checked.callId(),
                            "callId",
                            MAX_TOTAL_CONTENT_UTF8_BYTES),
                    MAX_TOTAL_CONTENT_UTF8_BYTES,
                    "proposal content");
            totalBytes = AiProposalPayloadChecks.addWithinTotal(
                    totalBytes,
                    AiProposalPayloadChecks.utf8Length(
                            checked.name(),
                            "name",
                            MAX_TOTAL_CONTENT_UTF8_BYTES),
                    MAX_TOTAL_CONTENT_UTF8_BYTES,
                    "proposal content");
            totalBytes = AiProposalPayloadChecks.addWithinTotal(
                    totalBytes,
                    AiProposalPayloadChecks.utf8Length(
                            checked.argumentsJson(),
                            "argumentsJson",
                            AiProposalToolCallPayload.MAX_ARGUMENTS_UTF8_BYTES),
                    MAX_TOTAL_CONTENT_UTF8_BYTES,
                    "proposal content");
            copied.add(checked);
        }
        toolCalls = List.copyOf(copied);
        int encodedBytes = encodedByteLength(outputText, toolCalls);
        if (encodedBytes > MAX_ENCODED_FRAME_BYTES) {
            throw new IllegalArgumentException(
                    "proposal encoded frame exceeds maximum bytes "
                            + MAX_ENCODED_FRAME_BYTES);
        }
    }

    private static void encode(
            RegistryFriendlyByteBuf buffer, AiProposalPayload payload) {
        buffer.writeUUID(payload.botId);
        buffer.writeUUID(payload.agentId);
        buffer.writeLong(payload.generation);
        buffer.writeUUID(payload.requestId);
        buffer.writeUUID(payload.nonce);
        buffer.writeLong(payload.revision);
        writeUtf(buffer, payload.outputText, MAX_OUTPUT_TEXT_UTF8_BYTES);
        buffer.writeVarInt(payload.toolCalls.size());
        for (AiProposalToolCallPayload toolCall : payload.toolCalls) {
            writeUtf(buffer,
                    toolCall.callId(),
                    AiProposalToolCallPayload.MAX_CALL_ID_UTF8_BYTES);
            writeUtf(buffer,
                    toolCall.name(),
                    AiProposalToolCallPayload.MAX_NAME_UTF8_BYTES);
            writeUtf(buffer,
                    toolCall.argumentsJson(),
                    AiProposalToolCallPayload.MAX_ARGUMENTS_UTF8_BYTES);
        }
    }

    private static AiProposalPayload decode(RegistryFriendlyByteBuf buffer) {
        UUID botId = buffer.readUUID();
        UUID agentId = buffer.readUUID();
        long generation = buffer.readLong();
        UUID requestId = buffer.readUUID();
        UUID nonce = buffer.readUUID();
        long revision = buffer.readLong();
        DecodedUtf8 outputText = readUtf(
                buffer, MAX_OUTPUT_TEXT_UTF8_BYTES, "outputText");
        int totalBytes = outputText.byteLength;
        int toolCallCount = buffer.readVarInt();
        if (toolCallCount < 0 || toolCallCount > MAX_TOOL_CALLS) {
            throw new IllegalArgumentException(
                    "toolCalls count is outside the allowed range");
        }
        List<AiProposalToolCallPayload> toolCalls = new ArrayList<>(toolCallCount);
        for (int index = 0; index < toolCallCount; index++) {
            DecodedUtf8 callId = readUtf(
                    buffer,
                    Math.min(
                            AiProposalToolCallPayload.MAX_CALL_ID_UTF8_BYTES,
                            MAX_TOTAL_CONTENT_UTF8_BYTES - totalBytes),
                    "callId");
            totalBytes = AiProposalPayloadChecks.addWithinTotal(
                    totalBytes,
                    callId.byteLength,
                    MAX_TOTAL_CONTENT_UTF8_BYTES,
                    "proposal content");
            DecodedUtf8 name = readUtf(
                    buffer,
                    Math.min(
                            AiProposalToolCallPayload.MAX_NAME_UTF8_BYTES,
                            MAX_TOTAL_CONTENT_UTF8_BYTES - totalBytes),
                    "name");
            totalBytes = AiProposalPayloadChecks.addWithinTotal(
                    totalBytes,
                    name.byteLength,
                    MAX_TOTAL_CONTENT_UTF8_BYTES,
                    "proposal content");
            DecodedUtf8 argumentsJson = readUtf(
                    buffer,
                    Math.min(
                            AiProposalToolCallPayload.MAX_ARGUMENTS_UTF8_BYTES,
                            MAX_TOTAL_CONTENT_UTF8_BYTES - totalBytes),
                    "argumentsJson");
            totalBytes = AiProposalPayloadChecks.addWithinTotal(
                    totalBytes,
                    argumentsJson.byteLength,
                    MAX_TOTAL_CONTENT_UTF8_BYTES,
                    "proposal content");
            toolCalls.add(new AiProposalToolCallPayload(
                    callId.value, name.value, argumentsJson.value));
        }
        return new AiProposalPayload(
                botId,
                agentId,
                generation,
                requestId,
                nonce,
                revision,
                outputText.value,
                toolCalls);
    }

    /**
     * Exact byte count written by {@link #STREAM_CODEC}, excluding the surrounding custom-payload
     * id and transport framing. The public frame ceiling leaves those layers a conservative margin.
     */
    public int encodedByteLength() {
        return encodedByteLength(outputText, toolCalls);
    }

    private static int encodedByteLength(
            String outputText, List<AiProposalToolCallPayload> toolCalls) {
        long encodedBytes = fixedWireBytes();
        encodedBytes += lengthPrefixedUtf8Bytes(outputText, "outputText");
        encodedBytes += varIntBytes(toolCalls.size());
        for (AiProposalToolCallPayload toolCall : toolCalls) {
            encodedBytes += lengthPrefixedUtf8Bytes(toolCall.callId(), "callId");
            encodedBytes += lengthPrefixedUtf8Bytes(toolCall.name(), "name");
            encodedBytes += lengthPrefixedUtf8Bytes(
                    toolCall.argumentsJson(), "argumentsJson");
        }
        if (encodedBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("proposal encoded frame exceeds integer size");
        }
        return (int) encodedBytes;
    }

    private static int fixedWireBytes() {
        /* botId, agentId, requestId, nonce plus generation and revision. */
        return 4 * 16 + 2 * Long.BYTES;
    }

    private static int lengthPrefixedUtf8Bytes(String value, String name) {
        int utf8Bytes = AiProposalPayloadChecks.utf8Length(
                value, name, MAX_TOTAL_CONTENT_UTF8_BYTES);
        return varIntBytes(utf8Bytes) + utf8Bytes;
    }

    private static int varIntBytes(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("varint value must not be negative");
        }
        int bytes = 1;
        while ((value & ~0x7F) != 0) {
            bytes++;
            value >>>= 7;
        }
        return bytes;
    }

    /** Uses byte-counted UTF-8 so decoder allocations cannot bypass the proposal total budget. */
    private static void writeUtf(
            RegistryFriendlyByteBuf buffer, String value, int maximumUtf8Bytes) {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if (encoded.length > maximumUtf8Bytes) {
            throw new IllegalArgumentException(
                    "string exceeds maximum UTF-8 bytes " + maximumUtf8Bytes);
        }
        buffer.writeVarInt(encoded.length);
        buffer.writeBytes(encoded);
    }

    private static DecodedUtf8 readUtf(
            RegistryFriendlyByteBuf buffer, int maximumUtf8Bytes, String name) {
        if (maximumUtf8Bytes < 0) {
            throw new IllegalArgumentException(
                    name + " has no remaining UTF-8 byte budget");
        }
        int byteLength = buffer.readVarInt();
        if (byteLength < 0 || byteLength > maximumUtf8Bytes) {
            throw new IllegalArgumentException(
                    name + " exceeds maximum UTF-8 bytes " + maximumUtf8Bytes);
        }
        if (byteLength > buffer.readableBytes()) {
            throw new IllegalArgumentException(
                    name + " byte length exceeds remaining payload");
        }
        byte[] encoded = new byte[byteLength];
        buffer.readBytes(encoded);
        try {
            String value = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(encoded))
                    .toString();
            return new DecodedUtf8(value, byteLength);
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException(name + " is not valid UTF-8", exception);
        }
    }

    @Override
    public Type<AiProposalPayload> type() {
        return TYPE;
    }

    /** 不输出 nonce、模型文本、工具名、call ID 或工具参数。 */
    @Override
    public String toString() {
        return "AiProposalPayload[botId=" + botId
                + ", agentId=" + agentId
                + ", generation=" + generation
                + ", requestId=" + requestId
                + ", revision=" + revision
                + ", outputUtf8Bytes="
                + AiProposalPayloadChecks.utf8Length(
                        outputText,
                        "outputText",
                        MAX_OUTPUT_TEXT_UTF8_BYTES)
                + ", toolCallCount=" + toolCalls.size()
                + "]";
    }

    /** Avoid a raw-text record {@code toString()} on the decoder's temporary value. */
    private static final class DecodedUtf8 {
        private final String value;
        private final int byteLength;

        private DecodedUtf8(String value, int byteLength) {
            this.value = Objects.requireNonNull(value, "value");
            this.byteLength = byteLength;
        }
    }
}
