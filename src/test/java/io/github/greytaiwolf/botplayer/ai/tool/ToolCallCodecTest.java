package io.github.greytaiwolf.botplayer.ai.tool;

import io.github.greytaiwolf.botplayer.ai.AiFinishReason;
import io.github.greytaiwolf.botplayer.ai.AiRawToolCall;
import io.github.greytaiwolf.botplayer.ai.AiResponse;
import io.github.greytaiwolf.botplayer.ai.AiTokenUsage;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ToolCallCodecTest {
    private static final UUID REQUEST_ID = UUID.fromString(
            "12345678-1234-1234-1234-123456789abc");

    @Test
    void decodesProviderToolCallsIntoAnImmutableProposalOnly() throws Exception {
        AiResponse response = new AiResponse(
                REQUEST_ID,
                "fake",
                "fake-model",
                AiFinishReason.TOOL_CALLS,
                "",
                Optional.empty(),
                Optional.empty(),
                List.of(new AiRawToolCall(
                        "call_1",
                        "collect_resource",
                        "{\"itemId\":\"minecraft:oak_log\",\"count\":32,"
                                + "\"nearby\":[true,false]}")),
                AiTokenUsage.empty());

        ProposedSkillPlan proposal = ToolCallCodec.strictDefaults().decode(response);
        ProposedToolCall call = proposal.toolCalls().getFirst();

        Assertions.assertAll(
                () -> Assertions.assertEquals(REQUEST_ID, proposal.requestId()),
                () -> Assertions.assertEquals("call_1", call.callId()),
                () -> Assertions.assertEquals("collect_resource", call.toolName()),
                () -> Assertions.assertInstanceOf(
                        ToolValue.StringValue.class,
                        call.arguments().get("itemId")),
                () -> Assertions.assertInstanceOf(
                        ToolValue.IntegerValue.class,
                        call.arguments().get("count")),
                () -> Assertions.assertThrows(
                        UnsupportedOperationException.class,
                        () -> proposal.toolCalls().clear()),
                () -> Assertions.assertThrows(
                        UnsupportedOperationException.class,
                        () -> call.arguments().clear()));
    }

    @Test
    void strictFixtureRejectsUnknownDuplicateAndMissingEnvelopeFields() {
        ToolCallCodec codec = ToolCallCodec.strictDefaults();
        String validCall = "{\"callId\":\"call_1\","
                + "\"name\":\"collect_resource\",\"arguments\":{}}";

        assertFailure(
                ToolCallCodecFailure.UNKNOWN_FIELD,
                () -> codec.decodePlanJson(REQUEST_ID,
                        "{\"toolCalls\":[" + validCall + "],\"extra\":true}"));
        assertFailure(
                ToolCallCodecFailure.DUPLICATE_FIELD,
                () -> codec.decodePlanJson(REQUEST_ID,
                        "{\"toolCalls\":[" + validCall + "],\"toolCalls\":["
                                + validCall + "]}"));
        assertFailure(
                ToolCallCodecFailure.UNKNOWN_FIELD,
                () -> codec.decodePlanJson(REQUEST_ID,
                        "{\"toolCalls\":[{\"callId\":\"call_1\","
                                + "\"name\":\"collect_resource\","
                                + "\"arguments\":{},\"extra\":true}]}"));
        assertFailure(
                ToolCallCodecFailure.MISSING_REQUIRED_FIELD,
                () -> codec.decodePlanJson(REQUEST_ID,
                        "{\"toolCalls\":[{\"callId\":\"call_1\","
                                + "\"name\":\"collect_resource\"}]}"));
    }

    @Test
    void rejectsDuplicateArgumentNamesUnsupportedNumbersAndTrailingInput() {
        ToolCallCodec codec = ToolCallCodec.strictDefaults();

        assertFailure(
                ToolCallCodecFailure.DUPLICATE_FIELD,
                () -> codec.decode(REQUEST_ID, List.of(raw(
                        "{\"count\":1,\"count\":2}"))));
        assertFailure(
                ToolCallCodecFailure.TYPE_NOT_SUPPORTED,
                () -> codec.decode(REQUEST_ID, List.of(raw(
                        "{\"count\":1.5}"))));
        assertFailure(
                ToolCallCodecFailure.MALFORMED_JSON,
                () -> codec.decode(REQUEST_ID, List.of(raw(
                        "{\"count\":1} false"))));
    }

    @Test
    void enforcesCallDepthArrayObjectAndStringBudgets() {
        ToolCallCodec limitsCodec = new ToolCallCodec(new ToolCallCodecLimits(
                128,
                128,
                1,
                2,
                1,
                1,
                8,
                3));

        assertFailure(
                ToolCallCodecFailure.LIMIT_EXCEEDED,
                () -> limitsCodec.decode(REQUEST_ID, List.of(raw(
                        "{\"first\":1,\"second\":2}"))));
        assertFailure(
                ToolCallCodecFailure.LIMIT_EXCEEDED,
                () -> limitsCodec.decode(REQUEST_ID, List.of(raw(
                        "{\"items\":[1,2]}"))));
        assertFailure(
                ToolCallCodecFailure.LIMIT_EXCEEDED,
                () -> limitsCodec.decode(REQUEST_ID, List.of(raw(
                        "{\"outer\":{\"inner\":{\"value\":1}}}"))));
        assertFailure(
                ToolCallCodecFailure.LIMIT_EXCEEDED,
                () -> limitsCodec.decode(REQUEST_ID, List.of(raw(
                        "{\"text\":\"four\"}"))));
        assertFailure(
                ToolCallCodecFailure.LIMIT_EXCEEDED,
                () -> limitsCodec.decode(REQUEST_ID, List.of(
                        raw("{}"), raw("{}"))));
        assertFailure(
                ToolCallCodecFailure.LIMIT_EXCEEDED,
                () -> limitsCodec.decode(REQUEST_ID, List.of(raw(
                        "{\"" + "a".repeat(65) + "\":1}"))));
    }

    @Test
    void measuresRawInputInUtf8BytesBeforeParsing() {
        ToolCallCodec codec = new ToolCallCodec(new ToolCallCodecLimits(
                22,
                22,
                1,
                4,
                4,
                4,
                16,
                16));

        assertFailure(
                ToolCallCodecFailure.LIMIT_EXCEEDED,
                () -> codec.decode(REQUEST_ID, List.of(raw(
                        "{\"text\":\"汉汉汉汉\"}"))));
        assertFailure(
                ToolCallCodecFailure.LIMIT_EXCEEDED,
                () -> codec.decodePlanJson(REQUEST_ID,
                        "{\"toolCalls\":[{\"callId\":\"call_1\","
                                + "\"name\":\"collect_resource\","
                                + "\"arguments\":{\"text\":\"汉汉\"}}]}"));
    }

    @Test
    void fixtureUsesTheSamePerArgumentUtf8BudgetAsProviderCalls() {
        ToolCallCodec codec = new ToolCallCodec(new ToolCallCodecLimits(
                128,
                16,
                1,
                8,
                8,
                8,
                32,
                32));

        /* {"text":"汉汉汉"} 是 20 UTF-8 字节；整个 envelope 仍低于 128。 */
        assertFailure(
                ToolCallCodecFailure.LIMIT_EXCEEDED,
                () -> codec.decodePlanJson(REQUEST_ID,
                        "{\"toolCalls\":[{\"callId\":\"call_1\","
                                + "\"name\":\"collect_resource\","
                                + "\"arguments\":{\"text\":\"汉汉汉\"}}]}"));
    }

    @Test
    void rawOpaqueCallIdIsMappedToAStableCodecFailure() {
        AiRawToolCall opaqueProviderId = new AiRawToolCall(
                "!provider-id!", "collect_resource", "{}");

        assertFailure(
                ToolCallCodecFailure.INVALID_FIELD_NAME,
                () -> ToolCallCodec.strictDefaults().decode(
                        REQUEST_ID, List.of(opaqueProviderId)));
    }

    @Test
    void boundedUtf8CounterStopsAtTheConfiguredLimit() {
        Assertions.assertEquals(17, ToolChecks.utf8LengthAtMost(
                "汉".repeat(6), "fixture", 16));
        Assertions.assertEquals(16, ToolChecks.utf8LengthAtMost(
                "汉".repeat(5) + "a", "fixture", 16));
    }

    @Test
    void rejectsEscapedLoneSurrogatesBeforeTheyReachTheFirewall() {
        assertFailure(
                ToolCallCodecFailure.INVALID_STRING,
                () -> ToolCallCodec.strictDefaults().decode(REQUEST_ID,
                        List.of(raw("{\"text\":\"\\uD800\"}"))));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new ToolValue.StringValue(
                        Character.toString(Character.MIN_HIGH_SURROGATE)));
    }

    @Test
    void responseWithoutToolCallsCannotBecomeAProposal() {
        AiResponse response = new AiResponse(
                REQUEST_ID,
                "fake",
                "fake-model",
                AiFinishReason.STOP,
                "只是一段文本",
                Optional.empty(),
                Optional.empty(),
                List.of(),
                AiTokenUsage.empty());

        assertFailure(ToolCallCodecFailure.INVALID_RESPONSE,
                () -> ToolCallCodec.strictDefaults().decode(response));
    }

    private static AiRawToolCall raw(String argumentsJson) {
        return new AiRawToolCall("call_1", "collect_resource", argumentsJson);
    }

    private static void assertFailure(
            ToolCallCodecFailure expected, CodecCall action) {
        ToolCallCodecException exception = Assertions.assertThrows(
                ToolCallCodecException.class, action::run);
        Assertions.assertEquals(expected, exception.failure());
    }

    @FunctionalInterface
    private interface CodecCall {
        void run() throws ToolCallCodecException;
    }
}
