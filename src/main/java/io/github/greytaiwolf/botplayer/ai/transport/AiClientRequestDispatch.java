package io.github.greytaiwolf.botplayer.ai.transport;

import io.github.greytaiwolf.botplayer.ai.AiMessage;
import io.github.greytaiwolf.botplayer.ai.AiRequest;
import io.github.greytaiwolf.botplayer.ai.AiRequestOptions;
import io.github.greytaiwolf.botplayer.ai.RedactionFilter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 无 secret 的 server-to-owner-client AI 请求关联。
 *
 * <p>服务端只可从已经通过 owner、active agent、generation 与生命周期复核的
 * {@link AiProposalRequestEnvelope} 构造本 DTO。它携带客户端执行 Provider 所需的受限
 * {@link AiRequest} 字段，但绝不携带 endpoint、Authorization、credential profile、世界对象或
 * 服务器内部 Firewall policy。客户端仍必须复核本地 owner 与 credential binding；服务端仍必须在
 * C2S 回传时重新复核全部关联字段。
 *
 * <p>{@code expiresAtTick} 是服务端 proposal gate 的权威 TTL。{@code expiresAtEpochMillis}
 * 是给客户端取消本地 HTTP 请求的保守 wall-clock deadline，且其持续时间不得超过同一
 * {@code expiresAtTick} 的 20 TPS 换算；客户端时钟即使漂移，服务端 gate 仍会拒绝迟到的回传。
 */
public record AiClientRequestDispatch(
        UUID serverInstanceId,
        UUID botId,
        UUID ownerId,
        UUID agentId,
        long generation,
        UUID requestId,
        UUID nonce,
        long revision,
        AiRequestPurpose purpose,
        long issuedAtTick,
        long expiresAtTick,
        long issuedAtEpochMillis,
        long expiresAtEpochMillis,
        String providerId,
        String model,
        List<AiMessage> messages,
        AiRequestOptions options,
        Optional<String> responseSchemaJson) {
    /** The wire contract deliberately stays below one normal custom-payload allocation. */
    public static final int MAX_MESSAGES = AiRequest.MAX_MESSAGES;
    public static final int MAX_MESSAGE_UTF8_BYTES = 64 * 1024;
    public static final int MAX_TOTAL_MESSAGE_UTF8_BYTES = 256 * 1024;
    public static final int MAX_RESPONSE_SCHEMA_UTF8_BYTES = 128 * 1024;
    public static final int MAX_TOTAL_CONTENT_UTF8_BYTES =
            MAX_TOTAL_MESSAGE_UTF8_BYTES + MAX_RESPONSE_SCHEMA_UTF8_BYTES;
    /** Absolute 20 TPS wall-clock cap; each dispatch is additionally bound to its own tick TTL. */
    public static final long MAX_TTL_MILLIS =
            AiProposalSessionLimits.MAX_REQUEST_TTL_TICKS * 50L;

    private static final Pattern PROVIDER_ID_PATTERN =
            Pattern.compile("[a-z0-9_.-]{1,64}");

    public AiClientRequestDispatch {
        requireNonZero(serverInstanceId, "serverInstanceId");
        requireNonZero(botId, "botId");
        requireNonZero(ownerId, "ownerId");
        requireNonZero(agentId, "agentId");
        if (generation <= 0L) {
            throw new IllegalArgumentException("generation must be positive");
        }
        requireNonZero(requestId, "requestId");
        requireNonZero(nonce, "nonce");
        if (revision <= 0L) {
            throw new IllegalArgumentException("revision must be positive");
        }
        purpose = Objects.requireNonNull(purpose, "purpose");
        if (issuedAtTick < 0L || expiresAtTick <= issuedAtTick) {
            throw new IllegalArgumentException("request tick expiry is invalid");
        }
        long ttlTicks = expiresAtTick - issuedAtTick;
        if (ttlTicks > AiProposalSessionLimits.MAX_REQUEST_TTL_TICKS) {
            throw new IllegalArgumentException("request tick TTL exceeds maximum");
        }
        long maximumEpochTtlMillis = Math.multiplyExact(ttlTicks, 50L);
        if (issuedAtEpochMillis < 0L || expiresAtEpochMillis <= issuedAtEpochMillis) {
            throw new IllegalArgumentException("request epoch expiry is invalid");
        }
        long ttlMillis = expiresAtEpochMillis - issuedAtEpochMillis;
        if (ttlMillis > MAX_TTL_MILLIS || ttlMillis > maximumEpochTtlMillis) {
            throw new IllegalArgumentException(
                    "request epoch TTL exceeds the server tick TTL");
        }

        providerId = requireProviderId(providerId);
        model = requireModel(model);
        Objects.requireNonNull(messages, "messages");
        if (messages.isEmpty() || messages.size() > MAX_MESSAGES) {
            throw new IllegalArgumentException(
                    "messages must contain between 1 and "
                            + MAX_MESSAGES + " entries");
        }
        List<AiMessage> copiedMessages = new ArrayList<>(messages.size());
        int totalBytes = 0;
        for (AiMessage message : messages) {
            AiMessage checked = Objects.requireNonNull(message, "message");
            int messageBytes = requireSafeUtf8(
                    checked.content(), "message content", MAX_MESSAGE_UTF8_BYTES);
            totalBytes = addWithinTotal(
                    totalBytes,
                    messageBytes,
                    MAX_TOTAL_MESSAGE_UTF8_BYTES,
                    "message content");
            copiedMessages.add(checked);
        }
        messages = List.copyOf(copiedMessages);

        options = Objects.requireNonNull(options, "options");
        if (options.timeoutMillis() > ttlMillis) {
            throw new IllegalArgumentException(
                    "request timeout must not outlive dispatch TTL");
        }
        responseSchemaJson = Objects.requireNonNull(
                responseSchemaJson, "responseSchemaJson");
        int schemaBytes = responseSchemaJson.map(value -> requireSafeUtf8(
                value,
                "responseSchemaJson",
                MAX_RESPONSE_SCHEMA_UTF8_BYTES)).orElse(0);
        addWithinTotal(
                totalBytes,
                schemaBytes,
                MAX_TOTAL_CONTENT_UTF8_BYTES,
                "request content");

        // Reuse the provider-neutral request invariant, including format/schema consistency.
        new AiRequest(requestId, model, messages, options, responseSchemaJson);
    }

    /**
     * Compatibility constructor for pre-purpose pure DTO users. The physical client rejects this
     * legacy purpose before any credential-bound Provider can be constructed.
     */
    public AiClientRequestDispatch(
            UUID serverInstanceId,
            UUID botId,
            UUID ownerId,
            UUID agentId,
            long generation,
            UUID requestId,
            UUID nonce,
            long revision,
            long issuedAtTick,
            long expiresAtTick,
            long issuedAtEpochMillis,
            long expiresAtEpochMillis,
            String providerId,
            String model,
            List<AiMessage> messages,
            AiRequestOptions options,
            Optional<String> responseSchemaJson) {
        this(
                serverInstanceId,
                botId,
                ownerId,
                agentId,
                generation,
                requestId,
                nonce,
                revision,
                AiRequestPurpose.UNSPECIFIED_V1,
                issuedAtTick,
                expiresAtTick,
                issuedAtEpochMillis,
                expiresAtEpochMillis,
                providerId,
                model,
                messages,
                options,
                responseSchemaJson);
    }

    /** Builds the provider DTO only after the client has accepted this dispatch session. */
    public AiRequest toAiRequest() {
        return new AiRequest(requestId, model, messages, options, responseSchemaJson);
    }

    /**
     * Binds a prevalidated request template to a server-generated proposal envelope.
     *
     * <p>The template's request id is intentionally discarded: the gate is the only source of the
     * correlation id and nonce accepted by the server.
     */
    public static AiClientRequestDispatch fromEnvelope(
            UUID serverInstanceId,
            AiProposalRequestEnvelope envelope,
            long issuedAtEpochMillis,
            long expiresAtEpochMillis,
            String providerId,
            AiRequest requestTemplate) {
        Objects.requireNonNull(envelope, "envelope");
        AiRequest request = Objects.requireNonNull(requestTemplate, "requestTemplate");
        return new AiClientRequestDispatch(
                serverInstanceId,
                envelope.botId(),
                envelope.ownerId(),
                envelope.agentId(),
                envelope.generation(),
                envelope.requestId(),
                envelope.nonce(),
                envelope.revision(),
                envelope.purpose(),
                envelope.issuedAtTick(),
                envelope.expiresAtTick(),
                issuedAtEpochMillis,
                expiresAtEpochMillis,
                providerId,
                request.model(),
                request.messages(),
                request.options(),
                request.responseSchemaJson());
    }

    /**
     * Validates all client-visible request content before a server gate replaces an older request.
     *
     * <p>This deliberately uses fixed non-zero placeholders only to exercise the immutable DTO
     * invariant. The real request id, nonce, owner, generation and expiry must still come from the
     * server lifecycle and {@link AiProposalSessionGate}.
     */
    public static void requireDispatchableTemplate(
            String providerId, AiRequest requestTemplate, int ttlTicks) {
        requireDispatchableTemplate(
                AiRequestPurpose.UNSPECIFIED_V1,
                providerId,
                requestTemplate,
                ttlTicks);
    }

    /** Validates a purpose-bound template before a server gate can replace an older request. */
    public static void requireDispatchableTemplate(
            AiRequestPurpose purpose,
            String providerId,
            AiRequest requestTemplate,
            int ttlTicks) {
        AiRequest request = Objects.requireNonNull(requestTemplate, "requestTemplate");
        AiRequestPurpose checkedPurpose = Objects.requireNonNull(purpose, "purpose");
        if (ttlTicks < 1 || ttlTicks > AiProposalSessionLimits.MAX_REQUEST_TTL_TICKS) {
            throw new IllegalArgumentException("ttlTicks is outside the allowed range");
        }
        long ttlMillis = Math.multiplyExact(ttlTicks, 50L);
        new AiClientRequestDispatch(
                new UUID(0L, 1L),
                new UUID(0L, 2L),
                new UUID(0L, 3L),
                new UUID(0L, 4L),
                1L,
                new UUID(0L, 5L),
                new UUID(0L, 6L),
                1L,
                checkedPurpose,
                0L,
                ttlTicks,
                0L,
                ttlMillis,
                providerId,
                request.model(),
                request.messages(),
                request.options(),
                request.responseSchemaJson());
    }

    /** Does not reveal nonce, prompt/schema text, owner id, or credential-like content. */
    @Override
    public String toString() {
        int totalMessageCharacters = messages.stream()
                .mapToInt(message -> message.content().length())
                .sum();
        return "AiClientRequestDispatch[serverInstanceId=" + serverInstanceId
                + ", botId=" + botId
                + ", agentId=" + agentId
                + ", generation=" + generation
                + ", requestId=" + requestId
                + ", revision=" + revision
                + ", purpose=" + purpose
                + ", expiresAtTick=" + expiresAtTick
                + ", expiresAtEpochMillis=" + expiresAtEpochMillis
                + ", providerId=" + providerId
                + ", model=" + model
                + ", messageCount=" + messages.size()
                + ", messageCharacters=" + totalMessageCharacters
                + ", options=" + options
                + ", responseSchemaPresent=" + responseSchemaJson.isPresent()
                + ", responseSchemaLength="
                + responseSchemaJson.map(String::length).orElse(0) + "]";
    }

    private static String requireProviderId(String value) {
        Objects.requireNonNull(value, "providerId");
        if (!PROVIDER_ID_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("providerId is invalid");
        }
        return value;
    }

    private static String requireModel(String value) {
        Objects.requireNonNull(value, "model");
        if (value.isEmpty() || value.length() > 128
                || value.codePoints().anyMatch(codePoint ->
                Character.isWhitespace(codePoint)
                        || Character.isISOControl(codePoint))) {
            throw new IllegalArgumentException("model is invalid");
        }
        return value;
    }

    private static int requireSafeUtf8(
            String value, String name, int maximumUtf8Bytes) {
        Objects.requireNonNull(value, name);
        if (RedactionFilter.redact(value).redactionCount() != 0) {
            throw new IllegalArgumentException(
                    name + " contains credential-like content");
        }
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if (encoded.length > maximumUtf8Bytes) {
            throw new IllegalArgumentException(
                    name + " exceeds maximum UTF-8 bytes " + maximumUtf8Bytes);
        }
        // AiMessage/AiRequest reject unpaired surrogates before this point.  Keep this method
        // independent so decode failures cannot rely on a replacement UTF-8 encoder either.
        requireUnicodeScalars(value, name);
        return encoded.length;
    }

    private static void requireUnicodeScalars(String value, String name) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException(
                            name + " must not contain an unpaired surrogate");
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException(
                        name + " must not contain an unpaired surrogate");
            }
        }
    }

    private static int addWithinTotal(
            int current, int additional, int maximum, String name) {
        if (additional > maximum - current) {
            throw new IllegalArgumentException(
                    name + " exceeds maximum UTF-8 bytes " + maximum);
        }
        return current + additional;
    }

    private static void requireNonZero(UUID value, String name) {
        Objects.requireNonNull(value, name);
        if (value.getMostSignificantBits() == 0L
                && value.getLeastSignificantBits() == 0L) {
            throw new IllegalArgumentException(name + " must not be zero");
        }
    }
}
