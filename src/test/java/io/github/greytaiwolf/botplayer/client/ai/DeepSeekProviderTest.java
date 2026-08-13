package io.github.greytaiwolf.botplayer.client.ai;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.greytaiwolf.botplayer.ai.AiCapabilities;
import io.github.greytaiwolf.botplayer.ai.AiCapability;
import io.github.greytaiwolf.botplayer.ai.AiCircuitBreaker;
import io.github.greytaiwolf.botplayer.ai.AiCircuitBreakerPolicy;
import io.github.greytaiwolf.botplayer.ai.AiFailureKind;
import io.github.greytaiwolf.botplayer.ai.AiFinishReason;
import io.github.greytaiwolf.botplayer.ai.AiMessage;
import io.github.greytaiwolf.botplayer.ai.AiMessageRole;
import io.github.greytaiwolf.botplayer.ai.AiModelCapabilities;
import io.github.greytaiwolf.botplayer.ai.AiRawToolCall;
import io.github.greytaiwolf.botplayer.ai.AiRequest;
import io.github.greytaiwolf.botplayer.ai.AiRequestOptions;
import io.github.greytaiwolf.botplayer.ai.AiResponse;
import io.github.greytaiwolf.botplayer.ai.AiResponseFormat;
import io.github.greytaiwolf.botplayer.ai.AiProviderException;
import io.github.greytaiwolf.botplayer.ai.AiReasonCode;
import io.github.greytaiwolf.botplayer.ai.AiRetryJitter;
import io.github.greytaiwolf.botplayer.ai.AiRetryPolicy;
import io.github.greytaiwolf.botplayer.ai.CancellationToken;
import io.github.greytaiwolf.botplayer.ai.CancellationTokenSource;
import io.github.greytaiwolf.botplayer.ai.ProviderHealthState;
import io.github.greytaiwolf.botplayer.ai.RetryingAiProvider;
import io.github.greytaiwolf.botplayer.ai.tool.ToolDefinition;
import io.github.greytaiwolf.botplayer.ai.tool.ToolParameterRule;
import io.github.greytaiwolf.botplayer.ai.tool.ToolRiskLevel;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DeepSeekProviderTest {
    private static final Instant NOW = Instant.parse("2026-08-11T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final char[] TEST_SECRET = "sk-test-not-real".toCharArray();

    @Test
    void completesViaFixedEndpointWithoutLeakingTheCredential() {
        AtomicReference<DeepSeekHttpRequest> captured = new AtomicReference<>();
        DeepSeekProvider provider = provider(false, request -> {
            captured.set(request);
            return CompletableFuture.completedFuture(jsonResponse("""
                    {"model":"deepseek-chat","choices":[{"index":0,
                    "message":{"role":"assistant","content":"已经准备好"},"finish_reason":"stop"}],
                    "usage":{"prompt_tokens":12,"completion_tokens":4,
                    "prompt_cache_hit_tokens":2}}"""));
        });

        AiResponse response = provider.complete(request(AiRequestOptions.defaults()),
                CancellationToken.none()).toCompletableFuture().join();

        DeepSeekHttpRequest sent = captured.get();
        assertEquals(URI.create("https://api.deepseek.com/chat/completions"),
                sent.endpoint());
        assertTrue(sent.body().contains("deepseek-chat"));
        assertFalse(sent.body().contains(String.valueOf(TEST_SECRET)));
        assertArrayEquals(new char[TEST_SECRET.length], sent.copySecret());
        assertEquals(AiFinishReason.STOP, response.finishReason());
        assertEquals("已经准备好", response.outputText());
        assertEquals(12L, response.usage().inputTokens());
        assertEquals(ProviderHealthState.HEALTHY, provider.health().state());
    }

    @Test
    void parsesSseToolCallFragmentsOnlyFromTheStaticCatalog() {
        DeepSeekProvider provider = provider(true, ignored -> CompletableFuture
                .completedFuture(sseResponse("""
                data: {"model":"deepseek-chat","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call-1","type":"function","function":{"name":"collect_"}}]},"finish_reason":null}]}

                data: {"model":"deepseek-chat","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"name":"resource","arguments":"{\\"itemId\\":\\"minecraft:oak_log\\"}"}}]},"finish_reason":null}]}

                data: {"model":"deepseek-chat","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

                data: [DONE]

                """)));
        AiRequestOptions options = new AiRequestOptions(
                512, 10_000L, AiResponseFormat.TEXT, false, true,
                Optional.empty());

        AiResponse response = provider.complete(request(options), CancellationToken.none())
                .toCompletableFuture().join();

        assertEquals(AiFinishReason.TOOL_CALLS, response.finishReason());
        assertEquals(1, response.toolCalls().size());
        assertEquals("collect_resource", response.toolCalls().getFirst().name());
        assertEquals("{\"itemId\":\"minecraft:oak_log\"}",
                response.toolCalls().getFirst().argumentsJson());
    }

    @Test
    void mapsRateLimitWithoutReturningRawErrorBodies() {
        String rawBody = "{\"error\":\"Bearer sk-private-should-never-leak\"}";
        DeepSeekProvider provider = provider(false, ignored -> CompletableFuture
                .completedFuture(new DeepSeekHttpResponse(
                        429, "application/json", rawBody, Optional.of("45"))));

        AiProviderException failure = failureOf(provider.complete(
                request(AiRequestOptions.defaults()), CancellationToken.none()));

        assertEquals(AiFailureKind.RATE_LIMITED, failure.failureKind());
        assertEquals(AiReasonCode.RATE_LIMITED, failure.reasonCode());
        assertFalse(failure.getMessage().contains("sk-private"));
        assertEquals(Optional.of(NOW.plusSeconds(45L)), failure.retryAfter());
        assertEquals(ProviderHealthState.RATE_LIMITED, provider.health().state());
        assertEquals(Optional.of(AiReasonCode.RATE_LIMITED.wireCode()),
                provider.health().reasonCode());
    }

    @Test
    void remoteRejectedRequestsDoNotDegradeProviderHealth() {
        DeepSeekProvider provider = provider(false, ignored -> CompletableFuture
                .completedFuture(new DeepSeekHttpResponse(
                        400, "application/json", "{}", Optional.empty())));

        assertFailure(failureOf(provider.complete(request(AiRequestOptions.defaults()),
                        CancellationToken.none())),
                AiFailureKind.INVALID_REQUEST, AiReasonCode.REMOTE_REJECTED);
        assertEquals(ProviderHealthState.UNKNOWN, provider.health().state());
    }

    @Test
    void rejectsWrongContentTypeAndOversizedConfiguredOutput() {
        DeepSeekProvider wrongType = provider(false, ignored -> CompletableFuture
                .completedFuture(new DeepSeekHttpResponse(
                        200, "text/plain", "{}", Optional.empty())));
        assertFailure(
                failureOf(wrongType.complete(request(AiRequestOptions.defaults()),
                        CancellationToken.none())),
                AiFailureKind.MALFORMED_RESPONSE,
                AiReasonCode.INVALID_PROVIDER_RESPONSE);

        AiRequestOptions tooLarge = new AiRequestOptions(
                9_000, 10_000L, AiResponseFormat.TEXT, false, false,
                Optional.empty());
        DeepSeekProvider provider = provider(false, ignored -> {
            throw new AssertionError("unsupported request must not reach HTTP");
        });
        assertFailure(failureOf(provider.complete(
                        request(tooLarge), CancellationToken.none())),
                AiFailureKind.INVALID_REQUEST,
                AiReasonCode.INVALID_REQUEST);

        DeepSeekProvider invalidUnicode = provider(false, ignored -> CompletableFuture
                .completedFuture(jsonResponse("{\"model\":\"deepseek-chat\",\"choices\":[{\"index\":0,"
                        + "\"message\":{\"role\":\"assistant\",\"content\":\"\\uD800\"},"
                        + "\"finish_reason\":\"stop\"}]}")));
        assertFailure(failureOf(invalidUnicode.complete(
                        request(AiRequestOptions.defaults()), CancellationToken.none())),
                AiFailureKind.MALFORMED_RESPONSE,
                AiReasonCode.INVALID_PROVIDER_RESPONSE);

        AiRequest malformedRequest = new AiRequest(
                UUID.fromString("37373737-3737-3737-3737-373737373737"),
                "deepseek-chat",
                List.of(new AiMessage(AiMessageRole.USER,
                        Character.toString(Character.MIN_HIGH_SURROGATE))),
                AiRequestOptions.defaults(),
                Optional.empty());
        assertFailure(failureOf(provider.complete(
                        malformedRequest, CancellationToken.none())),
                AiFailureKind.INVALID_REQUEST,
                AiReasonCode.INVALID_REQUEST);

        AtomicInteger credentialCalls = new AtomicInteger();
        DeepSeekProvider unavailableCredential = new DeepSeekProvider(
                config(false),
                () -> null,
                ignored -> {
                    credentialCalls.incrementAndGet();
                    return CompletableFuture.<DeepSeekHttpResponse>failedFuture(
                            new AssertionError("missing credential must not reach HTTP"));
                },
                CLOCK);
        assertFailure(failureOf(unavailableCredential.complete(
                        request(AiRequestOptions.defaults()), CancellationToken.none())),
                AiFailureKind.INVALID_REQUEST,
                AiReasonCode.CREDENTIAL_UNAVAILABLE);
        assertEquals(0, credentialCalls.get());
        assertEquals(ProviderHealthState.UNKNOWN,
                unavailableCredential.health().state());
    }

    @Test
    void jsonObjectRequestsCarryTheFixedSystemInstruction() {
        AtomicReference<DeepSeekHttpRequest> captured = new AtomicReference<>();
        DeepSeekProvider provider = provider(false, request -> {
            captured.set(request);
            return CompletableFuture.completedFuture(jsonResponse("""
                    {"model":"deepseek-chat","choices":[{"index":0,
                    "message":{"role":"assistant","content":"{}"},"finish_reason":"stop"}]}"""));
        });
        AiRequestOptions options = new AiRequestOptions(
                512, 10_000L, AiResponseFormat.JSON_OBJECT, false, false,
                Optional.empty());

        provider.complete(request(options), CancellationToken.none())
                .toCompletableFuture().join();

        assertTrue(captured.get().body().contains("exactly one valid JSON object"));
        assertTrue(captured.get().body().contains("\"role\":\"system\""));
    }

    @Test
    void rejectsProviderInjectedMessagesAndWireBudgetsBeforeTransport() {
        AtomicInteger jsonObjectCalls = new AtomicInteger();
        DeepSeekProvider jsonObjectProvider = provider(false, ignored -> {
            jsonObjectCalls.incrementAndGet();
            throw new AssertionError("invalid request must not reach HTTP");
        });
        AiRequestOptions jsonObject = new AiRequestOptions(
                512, 10_000L, AiResponseFormat.JSON_OBJECT, false, false,
                Optional.empty());
        AiMessage message = new AiMessage(AiMessageRole.USER, "x");
        AiRequest fullMessageRequest = new AiRequest(
                UUID.fromString("38383838-3838-3838-3838-383838383838"),
                "deepseek-chat",
                java.util.Collections.nCopies(AiRequest.MAX_MESSAGES, message),
                jsonObject,
                Optional.empty());

        assertFailure(failureOf(jsonObjectProvider.complete(fullMessageRequest,
                        CancellationToken.none())),
                AiFailureKind.INVALID_REQUEST, AiReasonCode.INVALID_REQUEST);
        assertEquals(0, jsonObjectCalls.get());

        AtomicInteger constrainedCalls = new AtomicInteger();
        DeepSeekProvider constrained = new DeepSeekProvider(
                config(false, 128L, 100),
                () -> TEST_SECRET.clone(),
                ignored -> {
                    constrainedCalls.incrementAndGet();
                    throw new AssertionError("over-context request must not reach HTTP");
                },
                CLOCK);
        AiRequestOptions reserveOutput = new AiRequestOptions(
                100, 10_000L, AiResponseFormat.TEXT, false, false,
                Optional.empty());

        assertFailure(failureOf(constrained.complete(
                        request(reserveOutput), CancellationToken.none())),
                AiFailureKind.INVALID_REQUEST, AiReasonCode.INVALID_REQUEST);
        assertEquals(0, constrainedCalls.get());
    }

    @Test
    void rejectsRemoteInterruptionsUnknownFinishReasonsAndOversizedNestedJson() {
        DeepSeekProvider interrupted = provider(false, ignored -> CompletableFuture
                .completedFuture(jsonResponse("""
                        {"model":"deepseek-chat","choices":[{"index":0,
                        "message":{"role":"assistant","content":""},
                        "finish_reason":"insufficient_system_resource"}]}""")));
        assertFailure(failureOf(interrupted.complete(request(AiRequestOptions.defaults()),
                        CancellationToken.none())),
                AiFailureKind.UNAVAILABLE, AiReasonCode.REMOTE_UNAVAILABLE);

        DeepSeekProvider unknownFinish = provider(false, ignored -> CompletableFuture
                .completedFuture(jsonResponse("""
                        {"model":"deepseek-chat","choices":[{"index":0,
                        "message":{"role":"assistant","content":""},
                        "finish_reason":"unexpected_value"}]}""")));
        assertFailure(failureOf(unknownFinish.complete(request(AiRequestOptions.defaults()),
                        CancellationToken.none())),
                AiFailureKind.MALFORMED_RESPONSE,
                AiReasonCode.INVALID_PROVIDER_RESPONSE);

        DeepSeekProvider duplicateField = provider(false, ignored -> CompletableFuture
                .completedFuture(jsonResponse("""
                        {"model":"deepseek-chat","model":"deepseek-chat","choices":[{"index":0,
                        "message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}""")));
        assertFailure(failureOf(duplicateField.complete(request(AiRequestOptions.defaults()),
                        CancellationToken.none())),
                AiFailureKind.MALFORMED_RESPONSE,
                AiReasonCode.INVALID_PROVIDER_RESPONSE);

        DeepSeekProvider streamedInterruption = provider(true, ignored -> CompletableFuture
                .completedFuture(sseResponse("""
                        data: {"model":"deepseek-chat","choices":[{"index":0,
                        "delta":{},"finish_reason":"insufficient_system_resource"}]}

                        data: [DONE]

                        """)));
        assertFailure(failureOf(streamedInterruption.complete(
                        request(AiRequestOptions.defaults()), CancellationToken.none())),
                AiFailureKind.UNAVAILABLE, AiReasonCode.REMOTE_UNAVAILABLE);

        DeepSeekProvider streamAfterFinish = provider(true, ignored -> CompletableFuture
                .completedFuture(sseResponse("""
                        data: {"model":"deepseek-chat","choices":[{"index":0,
                        "delta":{"content":"first"},"finish_reason":"stop"}]}

                        data: {"model":"deepseek-chat","choices":[{"index":0,
                        "delta":{"content":"late"},"finish_reason":null}]}

                        data: [DONE]

                        """)));
        assertFailure(failureOf(streamAfterFinish.complete(
                        request(AiRequestOptions.defaults()), CancellationToken.none())),
                AiFailureKind.MALFORMED_RESPONSE,
                AiReasonCode.INVALID_PROVIDER_RESPONSE);

        String oversizedArguments = "{\"itemId\":\"minecraft:oak_log\",\"padding\":\""
                + "x".repeat(AiRawToolCall.MAX_ARGUMENTS_LENGTH) + "\"}";
        String oversizedToolResponse = "{\"model\":\"deepseek-chat\",\"choices\":[{\"index\":0,"
                + "\"message\":{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{"
                + "\"id\":\"call-1\",\"type\":\"function\",\"function\":{\"name\":"
                + "\"collect_resource\",\"arguments\":" + jsonString(oversizedArguments)
                + "}}]},\"finish_reason\":\"tool_calls\"}]}";
        AiRequestOptions toolsAllowed = new AiRequestOptions(
                512, 10_000L, AiResponseFormat.TEXT, false, true, Optional.empty());
        DeepSeekProvider oversizedTools = provider(false, ignored -> CompletableFuture
                .completedFuture(jsonResponse(oversizedToolResponse)));
        assertFailure(failureOf(oversizedTools.complete(request(toolsAllowed),
                        CancellationToken.none())),
                AiFailureKind.MALFORMED_RESPONSE,
                AiReasonCode.INVALID_PROVIDER_RESPONSE);

        String validToolResponse = "{\"model\":\"deepseek-chat\",\"choices\":[{\"index\":0,"
                + "\"message\":{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{"
                + "\"id\":\"call-1\",\"type\":\"function\",\"function\":{\"name\":"
                + "\"collect_resource\",\"arguments\":"
                + jsonString("{\"itemId\":\"minecraft:oak_log\"}")
                + "}}]},\"finish_reason\":\"tool_calls\"}]}";
        DeepSeekProvider jsonObjectTools = provider(false, ignored -> CompletableFuture
                .completedFuture(jsonResponse(validToolResponse)));
        AiRequestOptions jsonObjectToolsAllowed = new AiRequestOptions(
                512, 10_000L, AiResponseFormat.JSON_OBJECT, false, true,
                Optional.empty());
        AiResponse toolResponse = jsonObjectTools.complete(
                        request(jsonObjectToolsAllowed), CancellationToken.none())
                .toCompletableFuture().join();
        assertEquals(AiFinishReason.TOOL_CALLS, toolResponse.finishReason());
        assertEquals(1, toolResponse.toolCalls().size());
        assertTrue(toolResponse.structuredOutputJson().isEmpty());

        DeepSeekProvider nonBlankToolContent = provider(false, ignored -> CompletableFuture
                .completedFuture(jsonResponse(validToolResponse.replace(
                        "\"content\":\"\"", "\"content\":\"unexpected\""))));
        assertFailure(failureOf(nonBlankToolContent.complete(
                        request(jsonObjectToolsAllowed), CancellationToken.none())),
                AiFailureKind.MALFORMED_RESPONSE,
                AiReasonCode.INVALID_PROVIDER_RESPONSE);

        String oversizedStructured = "{\"padding\":\""
                + "x".repeat(AiResponse.MAX_OUTPUT_TEXT_LENGTH) + "\"}";
        String structuredResponse = "{\"model\":\"deepseek-chat\",\"choices\":[{\"index\":0,"
                + "\"message\":{\"role\":\"assistant\",\"content\":"
                + jsonString(oversizedStructured) + "},\"finish_reason\":\"stop\"}]}";
        DeepSeekProvider oversizedJsonObject = provider(false, ignored -> CompletableFuture
                .completedFuture(jsonResponse(structuredResponse)));
        AiRequestOptions jsonObject = new AiRequestOptions(
                512, 10_000L, AiResponseFormat.JSON_OBJECT, false, false,
                Optional.empty());
        assertFailure(failureOf(oversizedJsonObject.complete(request(jsonObject),
                        CancellationToken.none())),
                AiFailureKind.MALFORMED_RESPONSE,
                AiReasonCode.INVALID_PROVIDER_RESPONSE);

        String escapedLoneSurrogate = "{\"value\":\"" + '\\' + "uD800\"}";
        String nestedUnicodeResponse = "{\"model\":\"deepseek-chat\",\"choices\":[{\"index\":0,"
                + "\"message\":{\"role\":\"assistant\",\"content\":"
                + jsonString(escapedLoneSurrogate) + "},\"finish_reason\":\"stop\"}]}";
        DeepSeekProvider nestedUnicode = provider(false, ignored -> CompletableFuture
                .completedFuture(jsonResponse(nestedUnicodeResponse)));
        assertFailure(failureOf(nestedUnicode.complete(request(jsonObject),
                        CancellationToken.none())),
                AiFailureKind.MALFORMED_RESPONSE,
                AiReasonCode.INVALID_PROVIDER_RESPONSE);

        String deeplyNested = "[".repeat(DeepSeekJsonStructureGuard.MAX_NESTING_DEPTH + 1)
                + "0" + "]".repeat(DeepSeekJsonStructureGuard.MAX_NESTING_DEPTH + 1);
        DeepSeekProvider deepDocument = provider(false, ignored -> CompletableFuture
                .completedFuture(jsonResponse(deeplyNested)));
        assertFailure(failureOf(deepDocument.complete(request(AiRequestOptions.defaults()),
                        CancellationToken.none())),
                AiFailureKind.MALFORMED_RESPONSE,
                AiReasonCode.INVALID_PROVIDER_RESPONSE);
    }

    @Test
    void cancellationAndUntrustedEndpointsFailClosed() {
        DeepSeekProvider provider = provider(false, ignored -> CompletableFuture
                .completedFuture(jsonResponse("""
                        {"model":"deepseek-chat","choices":[{"index":0,
                        "message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}""")));
        assertFailure(failureOf(provider.complete(
                        request(AiRequestOptions.defaults()), () -> true)),
                AiFailureKind.CANCELLED,
                AiReasonCode.CANCELLED);
        assertEquals(ProviderHealthState.UNKNOWN, provider.health().state());

        assertThrows(IllegalArgumentException.class,
                () -> new DeepSeekHttpRequest(
                        URI.create("https://example.invalid/chat/completions"),
                        DeepSeekHttpMethod.POST,
                        "{}",
                        java.time.Duration.ofSeconds(1L),
                        false,
                        TEST_SECRET));
        assertThrows(IllegalArgumentException.class,
                () -> new DeepSeekHttpResponse(
                        200, "application/json",
                        "汉".repeat(DeepSeekHttpResponse.MAX_BODY_BYTES / 3 + 1),
                        Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new DeepSeekHttpResponse(
                        200, "application/json", "bad\uD800", Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new DeepSeekHttpResponse(
                        200, "application/json\r\nX-Unsafe: value",
                        "{}", Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new JavaDeepSeekHttpExecutor(
                        HttpClient.newBuilder()
                                .followRedirects(HttpClient.Redirect.ALWAYS)
                                .build(),
                        Runnable::run));
    }

    @Test
    void callerCancellationForwardsToTheTransportBoundary() {
        AtomicReference<DeepSeekHttpRequest> cancelled = new AtomicReference<>();
        CompletableFuture<DeepSeekHttpResponse> pending = new CompletableFuture<>();
        DeepSeekHttpExecutor executor = new DeepSeekHttpExecutor() {
            @Override
            public CompletableFuture<DeepSeekHttpResponse> execute(
                    DeepSeekHttpRequest request) {
                return pending;
            }

            @Override
            public void cancel(DeepSeekHttpRequest request) {
                cancelled.set(request);
                pending.cancel(true);
            }
        };
        DeepSeekProvider provider = provider(false, executor);

        CompletableFuture<AiResponse> request = provider.complete(
                request(AiRequestOptions.defaults()), CancellationToken.none())
                .toCompletableFuture();
        assertTrue(request.cancel(true));

        assertTrue(cancelled.get() != null);
        assertArrayEquals(new char[TEST_SECRET.length], cancelled.get().copySecret());
    }

    @Test
    void cancellationSourceImmediatelyStopsTheInFlightTransport() {
        AtomicReference<DeepSeekHttpRequest> cancelled = new AtomicReference<>();
        CompletableFuture<DeepSeekHttpResponse> pending = new CompletableFuture<>();
        DeepSeekHttpExecutor executor = new DeepSeekHttpExecutor() {
            @Override
            public CompletableFuture<DeepSeekHttpResponse> execute(
                    DeepSeekHttpRequest request) {
                return pending;
            }

            @Override
            public void cancel(DeepSeekHttpRequest request) {
                cancelled.set(request);
                pending.cancel(true);
            }
        };
        DeepSeekProvider provider = provider(false, executor);
        CancellationTokenSource source = new CancellationTokenSource();

        java.util.concurrent.CompletionStage<AiResponse> result = provider.complete(
                request(AiRequestOptions.defaults()), source.token());
        assertTrue(source.cancel());

        assertFailure(failureOf(result), AiFailureKind.CANCELLED,
                AiReasonCode.CANCELLED);
        assertTrue(cancelled.get() != null);
        assertArrayEquals(new char[TEST_SECRET.length], cancelled.get().copySecret());
    }

    @Test
    void cancellationHookFailureCannotPreventCredentialCleanup() {
        AtomicReference<DeepSeekHttpRequest> captured = new AtomicReference<>();
        CompletableFuture<DeepSeekHttpResponse> pending = new CompletableFuture<>();
        DeepSeekHttpExecutor executor = new DeepSeekHttpExecutor() {
            @Override
            public CompletableFuture<DeepSeekHttpResponse> execute(
                    DeepSeekHttpRequest request) {
                captured.set(request);
                return pending;
            }

            @Override
            public void cancel(DeepSeekHttpRequest request) {
                throw new IllegalStateException("transport cancellation failure");
            }
        };
        DeepSeekProvider provider = provider(false, executor);

        CompletableFuture<AiResponse> request = provider.complete(
                request(AiRequestOptions.defaults()), CancellationToken.none())
                .toCompletableFuture();
        assertTrue(request.cancel(true));

        assertArrayEquals(new char[TEST_SECRET.length], captured.get().copySecret());
    }

    @Test
    void providerDeadlineStopsPendingInjectedTransport() {
        AtomicReference<DeepSeekHttpRequest> cancelled = new AtomicReference<>();
        CompletableFuture<DeepSeekHttpResponse> pending = new CompletableFuture<>();
        DeepSeekHttpExecutor executor = new DeepSeekHttpExecutor() {
            @Override
            public CompletableFuture<DeepSeekHttpResponse> execute(
                    DeepSeekHttpRequest request) {
                return pending;
            }

            @Override
            public void cancel(DeepSeekHttpRequest request) {
                cancelled.set(request);
                pending.cancel(true);
            }
        };
        DeepSeekProvider provider = provider(false, executor);
        AiRequestOptions shortTimeout = new AiRequestOptions(
                512, 50L, AiResponseFormat.TEXT, false, false, Optional.empty());

        assertFailure(failureOf(provider.complete(request(shortTimeout),
                        CancellationToken.none())),
                AiFailureKind.TIMEOUT, AiReasonCode.REQUEST_TIMEOUT);
        assertTrue(cancelled.get() != null);
        assertArrayEquals(new char[TEST_SECRET.length], cancelled.get().copySecret());
        assertEquals(ProviderHealthState.UNAVAILABLE, provider.health().state());
    }

    @Test
    void commonFailureMappingsDriveRetryAndAuthenticationCircuitBehavior()
            throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            AtomicInteger rateCalls = new AtomicInteger();
            DeepSeekProvider rateLimited = provider(false, ignored -> CompletableFuture
                    .completedFuture(rateCalls.getAndIncrement() == 0
                            ? new DeepSeekHttpResponse(
                                    429, "application/json", "{}", Optional.of("0"))
                            : jsonResponse("""
                                    {"model":"deepseek-chat","choices":[{"index":0,
                                    "message":{"role":"assistant","content":"ok"},
                                    "finish_reason":"stop"}]}""")));
            AiResponse retriedRateLimit = retrying(rateLimited, scheduler).complete(
                    request(AiRequestOptions.defaults()), CancellationToken.none())
                    .toCompletableFuture().get(2L, TimeUnit.SECONDS);
            assertEquals("ok", retriedRateLimit.outputText());
            assertEquals(2, rateCalls.get());

            AtomicInteger unavailableCalls = new AtomicInteger();
            DeepSeekProvider unavailable = provider(false, ignored -> CompletableFuture
                    .completedFuture(unavailableCalls.getAndIncrement() == 0
                            ? new DeepSeekHttpResponse(
                                    503, "application/json", "{}", Optional.empty())
                            : jsonResponse("""
                                    {"model":"deepseek-chat","choices":[{"index":0,
                                    "message":{"role":"assistant","content":"recovered"},
                                    "finish_reason":"stop"}]}""")));
            assertEquals("recovered", retrying(unavailable, scheduler).complete(
                    request(AiRequestOptions.defaults()), CancellationToken.none())
                    .toCompletableFuture().get(2L, TimeUnit.SECONDS).outputText());
            assertEquals(2, unavailableCalls.get());

            AtomicInteger timeoutCalls = new AtomicInteger();
            DeepSeekProvider timeout = provider(false, ignored -> timeoutCalls
                    .getAndIncrement() == 0
                            ? CompletableFuture.<DeepSeekHttpResponse>failedFuture(
                                    new TimeoutException())
                            : CompletableFuture.completedFuture(jsonResponse("""
                                    {"model":"deepseek-chat","choices":[{"index":0,
                                    "message":{"role":"assistant","content":"timed"},
                                    "finish_reason":"stop"}]}""")));
            assertEquals("timed", retrying(timeout, scheduler).complete(
                    request(AiRequestOptions.defaults()), CancellationToken.none())
                    .toCompletableFuture().get(2L, TimeUnit.SECONDS).outputText());
            assertEquals(2, timeoutCalls.get());

            DeepSeekProvider unauthorized = provider(false, ignored -> CompletableFuture
                    .completedFuture(new DeepSeekHttpResponse(
                            401, "application/json", "{}", Optional.empty())));
            RetryingAiProvider protectedProvider = retrying(unauthorized, scheduler);
            assertFailure(failureOf(protectedProvider.complete(
                            request(AiRequestOptions.defaults()), CancellationToken.none())),
                    AiFailureKind.AUTHENTICATION,
                    AiReasonCode.AUTHENTICATION_FAILED);
            assertFailure(failureOf(protectedProvider.complete(
                            request(UUID.fromString(
                                    "39393939-3939-3939-3939-393939393939"),
                                    AiRequestOptions.defaults()), CancellationToken.none())),
                    AiFailureKind.CIRCUIT_OPEN,
                    AiReasonCode.CIRCUIT_OPEN);
        } finally {
            scheduler.shutdownNow();
            assertTrue(scheduler.awaitTermination(2L, TimeUnit.SECONDS));
        }
    }

    @Test
    void probesOnlyModelsReturnedByTheRemoteCatalog() {
        AtomicReference<DeepSeekHttpRequest> captured = new AtomicReference<>();
        DeepSeekProvider provider = provider(false, request -> {
            captured.set(request);
            return CompletableFuture.completedFuture(jsonResponse("""
                    {"data":[{"id":"deepseek-chat"},{"id":"unconfigured"}]}"""));
        });

        AiCapabilities capabilities = provider.probeCapabilities()
                .toCompletableFuture().join();

        assertEquals(URI.create("https://api.deepseek.com/models"),
                captured.get().endpoint());
        assertEquals(List.of("deepseek-chat"), capabilities.models().stream()
                .map(AiModelCapabilities::model).toList());
    }

    @Test
    void rejectsOversizedOrMalformedRemoteModelCatalogs() {
        StringBuilder catalog = new StringBuilder("{\"data\":[");
        for (int index = 0; index < 129; index++) {
            if (index > 0) {
                catalog.append(',');
            }
            catalog.append("{\"id\":\"model-").append(index).append("\"}");
        }
        catalog.append("]}");
        DeepSeekProvider oversized = provider(false, ignored -> CompletableFuture
                .completedFuture(jsonResponse(catalog.toString())));
        assertFailure(failureOf(oversized.probeCapabilities()),
                AiFailureKind.MALFORMED_RESPONSE,
                AiReasonCode.INVALID_PROVIDER_RESPONSE);

        DeepSeekProvider malformed = provider(false, ignored -> CompletableFuture
                .completedFuture(jsonResponse("{\"data\":[{\"id\":\"bad/model\"}]}")));
        assertFailure(failureOf(malformed.probeCapabilities()),
                AiFailureKind.MALFORMED_RESPONSE,
                AiReasonCode.INVALID_PROVIDER_RESPONSE);
    }

    private static DeepSeekProvider provider(
            boolean stream,
            DeepSeekHttpExecutor executor) {
        return new DeepSeekProvider(config(stream), () -> TEST_SECRET.clone(),
                executor, CLOCK);
    }

    private static DeepSeekProviderConfig config(boolean stream) {
        return config(stream, 32_768L, 8_192);
    }

    private static DeepSeekProviderConfig config(
            boolean stream, long contextWindowTokens, int maximumOutputTokens) {
        return new DeepSeekProviderConfig(
                List.of(new AiModelCapabilities(
                        "deepseek-chat",
                        contextWindowTokens,
                        maximumOutputTokens,
                        Set.of(
                                AiCapability.CHAT,
                                AiCapability.REASONING,
                                AiCapability.TOOL_CALLS,
                                AiCapability.JSON_OBJECT,
                                AiCapability.STREAMING))),
                List.of(new DeepSeekToolDefinition(
                        new ToolDefinition(
                                "collect_resource",
                                ToolRiskLevel.LOW,
                                java.util.Map.of("itemId",
                                        ToolParameterRule
                                                .requiredResourceIdentifier(128))),
                        "收集已注册的原版资源")),
                stream);
    }

    private static AiRequest request(AiRequestOptions options) {
        return request(UUID.fromString(
                "36363636-3636-3636-3636-363636363636"), options);
    }

    private static AiRequest request(UUID requestId, AiRequestOptions options) {
        return new AiRequest(
                requestId,
                "deepseek-chat",
                List.of(new AiMessage(AiMessageRole.USER, "请准备橡木")),
                options,
                Optional.empty());
    }

    private static DeepSeekHttpResponse jsonResponse(String body) {
        return new DeepSeekHttpResponse(200, "application/json; charset=utf-8",
                body, Optional.empty());
    }

    private static DeepSeekHttpResponse sseResponse(String body) {
        return new DeepSeekHttpResponse(200, "text/event-stream; charset=utf-8",
                body, Optional.empty());
    }

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\")
                .replace("\"", "\\\"") + "\"";
    }

    private static RetryingAiProvider retrying(
            DeepSeekProvider delegate, ScheduledExecutorService scheduler) {
        return new RetryingAiProvider(
                DeepSeekProvider.PROVIDER_ID,
                delegate,
                new AiCircuitBreaker(new AiCircuitBreakerPolicy(
                        3,
                        1,
                        4,
                        Duration.ofSeconds(30L),
                        Duration.ofSeconds(30L))),
                new AiRetryPolicy(
                        2,
                        4,
                        Duration.ZERO,
                        Duration.ZERO,
                        Duration.ZERO,
                        AiRetryJitter.none()),
                scheduler,
                CLOCK);
    }

    private static void assertFailure(
            AiProviderException failure,
            AiFailureKind failureKind,
            AiReasonCode reasonCode) {
        assertEquals(failureKind, failure.failureKind());
        assertEquals(reasonCode, failure.reasonCode());
    }

    private static AiProviderException failureOf(
            java.util.concurrent.CompletionStage<?> stage) {
        CompletionException completion = assertThrows(CompletionException.class,
                () -> stage.toCompletableFuture().join());
        assertTrue(completion.getCause() instanceof AiProviderException);
        return (AiProviderException) completion.getCause();
    }
}
