package io.github.greytaiwolf.botplayer.ai.transport;

import io.github.greytaiwolf.botplayer.ai.AiMessage;
import io.github.greytaiwolf.botplayer.ai.AiMessageRole;
import io.github.greytaiwolf.botplayer.ai.AiRequest;
import io.github.greytaiwolf.botplayer.ai.AiRequestOptions;
import io.github.greytaiwolf.botplayer.ai.AiResponseFormat;
import io.github.greytaiwolf.botplayer.ai.tool.ToolFirewallPolicy;
import io.github.greytaiwolf.botplayer.ai.tool.ToolRiskLevel;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AiClientRequestDispatchTest {
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
    void carriesOnlyBoundedRequestDataAndKeepsCorrelationOpaqueInDiagnostics() {
        AiClientRequestDispatch dispatch = dispatch(
                "prompt-sentinel",
                new AiRequestOptions(
                        512,
                        500L,
                        AiResponseFormat.JSON_SCHEMA,
                        false,
                        true,
                        Optional.of(0.5D)),
                Optional.of("{\"type\":\"object\",\"schema\":\"sentinel\"}"));

        AiRequest providerRequest = dispatch.toAiRequest();
        Assertions.assertEquals(REQUEST_ID, providerRequest.requestId());
        Assertions.assertEquals("deepseek-chat", providerRequest.model());
        Assertions.assertEquals(AiResponseFormat.JSON_SCHEMA,
                providerRequest.options().responseFormat());
        Assertions.assertEquals(1, providerRequest.messages().size());
        Assertions.assertEquals("prompt-sentinel",
                providerRequest.messages().get(0).content());

        String diagnostic = dispatch.toString();
        Assertions.assertFalse(diagnostic.contains(NONCE.toString()));
        Assertions.assertFalse(diagnostic.contains(OWNER_ID.toString()));
        Assertions.assertFalse(diagnostic.contains("prompt-sentinel"));
        Assertions.assertFalse(diagnostic.contains("sentinel"));
    }

    @Test
    void rejectsSecretLikeContextAndRequestsThatCanOutliveTheirServerTtl() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> dispatch(
                        "Authorization: Bearer sk-live-abcdef",
                        textOptions(500L),
                        Optional.empty()));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> dispatch(
                        "safe",
                        textOptions(1_001L),
                        Optional.empty()));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> dispatch(
                        "safe",
                        new AiRequestOptions(
                                512,
                                500L,
                                AiResponseFormat.JSON_SCHEMA,
                                false,
                                false,
                                Optional.empty()),
                        Optional.of("{\"api_key\":\"not-permitted\"}")));
    }

    @Test
    void epochDeadlineAndProviderTimeoutAreBoundToTheActualTickDeadline() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> dispatchWithTimes(
                        100L,
                        101L,
                        1_000L,
                        1_051L,
                        textOptions(50L)));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> dispatchWithTimes(
                        100L,
                        120L,
                        1_000L,
                        1_500L,
                        textOptions(501L)));

        AiClientRequestDispatch shorterLocalDeadline = dispatchWithTimes(
                100L,
                120L,
                1_000L,
                1_500L,
                textOptions(500L));
        Assertions.assertEquals(1_500L, shorterLocalDeadline.expiresAtEpochMillis());
        Assertions.assertEquals(500L, shorterLocalDeadline.options().timeoutMillis());
    }

    @Test
    void templateValidationUsesTheWireTtlBeforeItCanReplaceAnExistingGateRequest() {
        AiRequest valid = new AiRequest(
                UUID.fromString("00000000-0000-0000-0000-000000000501"),
                "deepseek-chat",
                List.of(new AiMessage(AiMessageRole.USER, "safe")),
                textOptions(500L),
                Optional.empty());
        AiClientRequestDispatch.requireDispatchableTemplate(
                "deepseek", valid, 20);

        AiRequest tooSlow = new AiRequest(
                UUID.fromString("00000000-0000-0000-0000-000000000502"),
                "deepseek-chat",
                List.of(new AiMessage(AiMessageRole.USER, "safe")),
                textOptions(1_001L),
                Optional.empty());
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> AiClientRequestDispatch.requireDispatchableTemplate(
                        "deepseek", tooSlow, 20));
    }

    @Test
    void serverEnvelopeReplacesTheTemplateRequestIdAndKeepsTheNonceCorrelated() {
        UUID templateRequestId = UUID.fromString(
                "00000000-0000-0000-0000-000000000599");
        AiProposalRequestEnvelope envelope = new AiProposalRequestEnvelope(
                BOT_ID,
                OWNER_ID,
                AGENT_ID,
                4L,
                REQUEST_ID,
                NONCE,
                7L,
                100L,
                120L,
                new ToolFirewallPolicy(
                        Map.of(), Set.of(), ToolRiskLevel.SAFE, 1));
        AiRequest template = new AiRequest(
                templateRequestId,
                "deepseek-chat",
                List.of(new AiMessage(AiMessageRole.USER, "safe")),
                textOptions(500L),
                Optional.empty());

        AiClientRequestDispatch dispatch = AiClientRequestDispatch.fromEnvelope(
                SERVER_ID, envelope, 1_000L, 2_000L, "deepseek", template);

        Assertions.assertEquals(REQUEST_ID, dispatch.requestId());
        Assertions.assertEquals(NONCE, dispatch.nonce());
        Assertions.assertNotEquals(templateRequestId, dispatch.requestId());
        Assertions.assertEquals(BOT_ID, dispatch.botId());
        Assertions.assertEquals(OWNER_ID, dispatch.ownerId());
        Assertions.assertEquals(AGENT_ID, dispatch.agentId());
        Assertions.assertEquals(4L, dispatch.generation());
        Assertions.assertEquals(7L, dispatch.revision());
        Assertions.assertEquals(100L, dispatch.issuedAtTick());
        Assertions.assertEquals(120L, dispatch.expiresAtTick());
        Assertions.assertEquals(1_000L, dispatch.issuedAtEpochMillis());
        Assertions.assertEquals(2_000L, dispatch.expiresAtEpochMillis());
        Assertions.assertEquals(AiRequestPurpose.UNSPECIFIED_V1, dispatch.purpose());
    }

    private static AiClientRequestDispatch dispatch(
            String content,
            AiRequestOptions options,
            Optional<String> schema) {
        return new AiClientRequestDispatch(
                SERVER_ID,
                BOT_ID,
                OWNER_ID,
                AGENT_ID,
                4L,
                REQUEST_ID,
                NONCE,
                7L,
                100L,
                120L,
                1_000L,
                2_000L,
                "deepseek",
                "deepseek-chat",
                List.of(new AiMessage(AiMessageRole.USER, content)),
                options,
                schema);
    }

    private static AiClientRequestDispatch dispatchWithTimes(
            long issuedAtTick,
            long expiresAtTick,
            long issuedAtEpochMillis,
            long expiresAtEpochMillis,
            AiRequestOptions options) {
        return new AiClientRequestDispatch(
                SERVER_ID,
                BOT_ID,
                OWNER_ID,
                AGENT_ID,
                4L,
                REQUEST_ID,
                NONCE,
                7L,
                issuedAtTick,
                expiresAtTick,
                issuedAtEpochMillis,
                expiresAtEpochMillis,
                "deepseek",
                "deepseek-chat",
                List.of(new AiMessage(AiMessageRole.USER, "safe")),
                options,
                Optional.empty());
    }

    private static AiRequestOptions textOptions(long timeoutMillis) {
        return new AiRequestOptions(
                512,
                timeoutMillis,
                AiResponseFormat.TEXT,
                false,
                true,
                Optional.empty());
    }
}
