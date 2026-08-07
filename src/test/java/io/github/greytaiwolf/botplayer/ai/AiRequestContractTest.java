package io.github.greytaiwolf.botplayer.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AiRequestContractTest {
    private static final UUID REQUEST_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void acceptsBoundedRequestAndDefensivelyCopiesMessages() {
        List<AiMessage> source = new ArrayList<>();
        source.add(new AiMessage(AiMessageRole.USER, "收集橡木"));
        AiRequest request = new AiRequest(
                REQUEST_ID,
                "deepseek-chat",
                source,
                AiRequestOptions.defaults(),
                Optional.empty());
        source.add(new AiMessage(AiMessageRole.USER, "额外消息"));

        Assertions.assertEquals(1, request.messages().size());
        Assertions.assertEquals(
                "收集橡木", request.messages().getFirst().content());
        Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> request.messages().clear());
    }

    @Test
    void requiresSchemaOnlyForJsonSchemaRequests() {
        AiRequestOptions schemaOptions = new AiRequestOptions(
                1_024,
                30_000L,
                AiResponseFormat.JSON_SCHEMA,
                false,
                false,
                Optional.empty());
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> request(schemaOptions, Optional.empty()));

        AiRequest valid = request(
                schemaOptions,
                Optional.of("{\"type\":\"object\"}"));
        Assertions.assertEquals(
                AiResponseFormat.JSON_SCHEMA,
                valid.options().responseFormat());

        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> request(
                        AiRequestOptions.defaults(),
                        Optional.of("{\"type\":\"object\"}")));
    }

    @Test
    void rejectsInvalidIdentityModelMessagesAndBudgets() {
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new AiRequest(
                        new UUID(0L, 0L),
                        "deepseek-chat",
                        List.of(new AiMessage(
                                AiMessageRole.USER, "hello")),
                        AiRequestOptions.defaults(),
                        Optional.empty()));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new AiRequest(
                        REQUEST_ID,
                        "model with spaces",
                        List.of(new AiMessage(
                                AiMessageRole.USER, "hello")),
                        AiRequestOptions.defaults(),
                        Optional.empty()));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new AiRequest(
                        REQUEST_ID,
                        "deepseek-chat",
                        List.of(),
                        AiRequestOptions.defaults(),
                        Optional.empty()));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new AiMessage(
                        AiMessageRole.USER,
                        "x".repeat(AiMessage.MAX_CONTENT_LENGTH + 1)));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new AiRequestOptions(
                        0,
                        1L,
                        AiResponseFormat.TEXT,
                        false,
                        false,
                        Optional.empty()));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new AiRequestOptions(
                        1,
                        AiRequestOptions.MAX_TIMEOUT_MILLIS + 1L,
                        AiResponseFormat.TEXT,
                        false,
                        false,
                        Optional.empty()));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new AiRequestOptions(
                        1,
                        1L,
                        AiResponseFormat.TEXT,
                        false,
                        false,
                        Optional.of(Double.NaN)));
    }

    @Test
    void rejectsOversizedAggregateContext() {
        String chunk = "x".repeat(AiMessage.MAX_CONTENT_LENGTH);
        List<AiMessage> messages = List.of(
                new AiMessage(AiMessageRole.SYSTEM, chunk),
                new AiMessage(AiMessageRole.USER, chunk),
                new AiMessage(AiMessageRole.ASSISTANT, chunk),
                new AiMessage(AiMessageRole.USER, chunk),
                new AiMessage(AiMessageRole.USER, "x"));

        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new AiRequest(
                        REQUEST_ID,
                        "deepseek-chat",
                        messages,
                        AiRequestOptions.defaults(),
                        Optional.empty()));
    }

    private static AiRequest request(
            AiRequestOptions options,
            Optional<String> responseSchemaJson) {
        return new AiRequest(
                REQUEST_ID,
                "deepseek-chat",
                List.of(new AiMessage(AiMessageRole.USER, "hello")),
                options,
                responseSchemaJson);
    }
}
