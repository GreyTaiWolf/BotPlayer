package io.github.greytaiwolf.botplayer.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AiResponseContractTest {
    private static final UUID REQUEST_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void acceptsTextResponseAndTokenUsage() {
        AiResponse response = new AiResponse(
                REQUEST_ID,
                "deepseek",
                "deepseek-chat",
                AiFinishReason.STOP,
                "可以执行。",
                Optional.empty(),
                Optional.empty(),
                List.of(),
                new AiTokenUsage(40L, 12L, 8L));

        Assertions.assertEquals("可以执行。", response.outputText());
        Assertions.assertEquals(52L, response.usage().totalTokens());
    }

    @Test
    void keepsRawToolCallsImmutableAndExplicitlyUntrusted() {
        List<AiRawToolCall> source = new ArrayList<>();
        source.add(new AiRawToolCall(
                "call_1",
                "collect_resource",
                "{\"count\":64}"));
        AiResponse response = new AiResponse(
                REQUEST_ID,
                "deepseek",
                "deepseek-chat",
                AiFinishReason.TOOL_CALLS,
                "",
                Optional.empty(),
                Optional.empty(),
                source,
                AiTokenUsage.empty());
        source.clear();

        Assertions.assertEquals(1, response.toolCalls().size());
        Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> response.toolCalls().clear());
    }

    @Test
    void rejectsFinishReasonMismatchAndUnsafeUsage() {
        AiRawToolCall toolCall = new AiRawToolCall(
                "call_1", "collect_resource", "{}");
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> response(AiFinishReason.TOOL_CALLS, List.of()));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> response(AiFinishReason.STOP, List.of(toolCall)));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new AiTokenUsage(3L, 1L, 4L));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new AiRawToolCall(
                        "call 1", "collect_resource", "{}"));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new AiRawToolCall(
                        "call_1",
                        "collect_resource",
                        "x".repeat(
                                AiRawToolCall.MAX_ARGUMENTS_LENGTH + 1)));
    }

    private static AiResponse response(
            AiFinishReason finishReason,
            List<AiRawToolCall> toolCalls) {
        return new AiResponse(
                REQUEST_ID,
                "deepseek",
                "deepseek-chat",
                finishReason,
                "",
                Optional.empty(),
                Optional.empty(),
                toolCalls,
                AiTokenUsage.empty());
    }
}
