package io.github.greytaiwolf.botplayer.client.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.greytaiwolf.botplayer.ai.AiCapabilities;
import io.github.greytaiwolf.botplayer.ai.AiCapability;
import io.github.greytaiwolf.botplayer.ai.AiFailureKind;
import io.github.greytaiwolf.botplayer.ai.AiModelCapabilities;
import io.github.greytaiwolf.botplayer.ai.AiProvider;
import io.github.greytaiwolf.botplayer.ai.AiProviderException;
import io.github.greytaiwolf.botplayer.ai.AiReasonCode;
import io.github.greytaiwolf.botplayer.ai.AiRequest;
import io.github.greytaiwolf.botplayer.ai.AiResponse;
import io.github.greytaiwolf.botplayer.ai.AiResponseFormat;
import io.github.greytaiwolf.botplayer.ai.CancellationToken;
import io.github.greytaiwolf.botplayer.ai.ContextBudget;
import io.github.greytaiwolf.botplayer.ai.ProviderHealth;
import io.github.greytaiwolf.botplayer.ai.ProviderHealthState;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * 物理客户端使用本地凭据调用 DeepSeek Chat Completions 的异步 Provider。
 *
 * <p>Endpoint 被固定为官方 HTTPS origin；密钥仅在创建 HttpRequest header 的短暂作用域内
 * 存在，绝不会进入本类返回的 DTO、日志、异常正文或 Minecraft 自定义 payload。响应只是
 * 不可信建议，仍需服务端的 ToolCallCodec/Firewall/ACL/revision 再验证。</p>
 */
public final class DeepSeekProvider implements AiProvider {
    public static final String PROVIDER_ID = "deepseek";
    private static final URI CHAT_COMPLETIONS_ENDPOINT = URI.create(
            "https://api.deepseek.com/chat/completions");
    private static final URI MODELS_ENDPOINT = URI.create(
            "https://api.deepseek.com/models");
    private static final int MAX_REMOTE_MODELS = 128;
    private static final int MAX_REMOTE_MODEL_ID_LENGTH = 128;
    private static final Pattern REMOTE_MODEL_ID = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private final DeepSeekProviderConfig config;
    private final DeepSeekCredentialSupplier credentials;
    private final DeepSeekHttpExecutor executor;
    private final Clock clock;
    private final DeepSeekRequestCodec requestCodec = new DeepSeekRequestCodec();
    private final DeepSeekResponseCodec responseCodec = new DeepSeekResponseCodec();
    private final AtomicReference<ProviderHealth> health;

    public DeepSeekProvider(
            DeepSeekProviderConfig config,
            DeepSeekCredentialSupplier credentials,
            DeepSeekHttpExecutor executor,
            Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.health = new AtomicReference<>(ProviderHealth.unknown(
                PROVIDER_ID, now()));
    }

    public DeepSeekProvider(
            DeepSeekProviderConfig config,
            DeepSeekCredentialSupplier credentials) {
        this(config, credentials, JavaDeepSeekHttpExecutor.defaults(), Clock.systemUTC());
    }

    @Override
    public CompletionStage<AiResponse> complete(
            AiRequest request, CancellationToken token) {
        AiRequest checkedRequest = Objects.requireNonNull(request, "request");
        CancellationToken checkedToken = Objects.requireNonNull(token, "token");
        try {
            requireNotCancelled(checkedToken);
            AiModelCapabilities model = requireRequestCapabilities(checkedRequest);
            String body = requestCodec.encode(checkedRequest, config.tools(),
                    config.streamResponses());
            requireWireContextBudget(checkedRequest, model, body);
            DeepSeekHttpRequest httpRequest = authenticatedRequest(
                    CHAT_COMPLETIONS_ENDPOINT, DeepSeekHttpMethod.POST, body,
                    checkedRequest.options().timeoutMillis(),
                    config.streamResponses());
            return attachCompletion(httpRequest, checkedToken, () -> executor.execute(
                    httpRequest), response -> decodeCompletion(checkedRequest, response));
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(sanitizeFailure(exception));
        }
    }

    @Override
    public CompletionStage<AiCapabilities> probeCapabilities() {
        try {
            DeepSeekHttpRequest request = authenticatedRequest(
                    MODELS_ENDPOINT, DeepSeekHttpMethod.GET, "", 30_000L, false);
            CompletableFuture<AiCapabilities> completion = new CompletableFuture<>();
            AtomicReference<CancellationToken.ListenerRegistration> registration =
                    new AtomicReference<>(CancellationToken.ListenerRegistration.none());
            installTransportLifecycle(completion, request, registration);
            scheduleDeadline(completion, request.timeout());

            CompletionStage<DeepSeekHttpResponse> stage;
            try {
                stage = Objects.requireNonNull(executor.execute(request),
                        "DeepSeek executor result");
            } catch (RuntimeException exception) {
                completion.completeExceptionally(sanitizeFailure(exception));
                return completion;
            }
            if (completion.isDone()) {
                cancelTransportQuietly(request);
                return completion;
            }
            try {
                stage.whenComplete((response, failure) -> {
                    if (completion.isDone()) {
                        return;
                    }
                    try {
                        if (failure != null) {
                            throw sanitizeFailure(unwrap(failure));
                        }
                        AiCapabilities capabilities = decodeCapabilities(response);
                        if (completion.complete(capabilities)) {
                            markHealthy();
                        }
                    } catch (RuntimeException exception) {
                        completion.completeExceptionally(sanitizeFailure(exception));
                    }
                });
            } catch (RuntimeException exception) {
                completion.completeExceptionally(sanitizeFailure(exception));
            }
            return completion;
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(sanitizeFailure(exception));
        }
    }

    @Override
    public ProviderHealth health() {
        return health.get();
    }

    private CompletionStage<AiResponse> attachCompletion(
            DeepSeekHttpRequest request,
            CancellationToken token,
            StageSupplier supplier,
            ResponseDecoder decoder) {
        CompletableFuture<AiResponse> completion = new CompletableFuture<>();
        CancellationToken.ListenerRegistration none =
                CancellationToken.ListenerRegistration.none();
        AtomicReference<CancellationToken.ListenerRegistration> registration =
                new AtomicReference<>(none);
        installTransportLifecycle(completion, request, registration);
        scheduleDeadline(completion, request.timeout());
        try {
            CancellationToken.ListenerRegistration registered = Objects.requireNonNull(
                    token.onCancellation(() -> completeAsCancelled(completion)),
                    "Cancellation listener registration");
            if (!registration.compareAndSet(none, registered)) {
                closeRegistrationQuietly(registered);
            } else if (completion.isDone()) {
                closeRegistrationQuietly(registration.getAndSet(none));
            }
        } catch (RuntimeException exception) {
            completion.completeExceptionally(sanitizeFailure(exception));
            return completion;
        }
        if (completion.isDone() || token.isCancellationRequested()) {
            completeAsCancelled(completion);
            return completion;
        }

        CompletionStage<DeepSeekHttpResponse> stage;
        try {
            stage = Objects.requireNonNull(supplier.get(), "DeepSeek executor result");
        } catch (RuntimeException exception) {
            completion.completeExceptionally(sanitizeFailure(exception));
            return completion;
        }
        if (completion.isDone()) {
            cancelTransportQuietly(request);
            return completion;
        }
        try {
            stage.whenComplete((response, failure) -> {
                if (completion.isDone()) {
                    return;
                }
                try {
                    requireNotCancelled(token);
                    if (failure != null) {
                        throw sanitizeFailure(unwrap(failure));
                    }
                    if (response == null) {
                        throw new DeepSeekProviderException(
                                DeepSeekFailureCode.INVALID_PROVIDER_RESPONSE);
                    }
                    AiResponse decoded = decoder.decode(response);
                    requireNotCancelled(token);
                    if (completion.complete(decoded)) {
                        markHealthy();
                    }
                } catch (RuntimeException exception) {
                    completion.completeExceptionally(sanitizeFailure(exception));
                }
            });
        } catch (RuntimeException exception) {
            completion.completeExceptionally(sanitizeFailure(exception));
        }
        return completion;
    }

    private AiResponse decodeCompletion(
            AiRequest request, DeepSeekHttpResponse response) {
        DeepSeekHttpResponse checked = Objects.requireNonNull(response, "response");
        if (checked.statusCode() < 200 || checked.statusCode() >= 300) {
            throw failureForHttp(checked);
        }
        try {
            requireExpectedContentType(checked, config.streamResponses());
            AiResponse decoded = config.streamResponses()
                    ? responseCodec.decodeSse(request, config, checked.body())
                    : responseCodec.decodeJson(request, config, checked.body());
            return decoded;
        } catch (RuntimeException exception) {
            throw sanitizeFailure(exception);
        }
    }

    private AiCapabilities decodeCapabilities(DeepSeekHttpResponse response) {
        DeepSeekHttpResponse checked = Objects.requireNonNull(response, "response");
        if (checked.statusCode() < 200 || checked.statusCode() >= 300) {
            throw failureForHttp(checked);
        }
        try {
            requireExpectedContentType(checked, false);
            DeepSeekJsonStructureGuard.requireDocument(checked.body());
            JsonObject root = JsonParser.parseString(checked.body()).getAsJsonObject();
            JsonElement dataValue = root.get("data");
            if (dataValue == null || !dataValue.isJsonArray()) {
                throw new DeepSeekProviderException(
                        DeepSeekFailureCode.INVALID_PROVIDER_RESPONSE);
            }
            Set<String> remoteModels = new HashSet<>();
            JsonArray data = dataValue.getAsJsonArray();
            if (data.size() > MAX_REMOTE_MODELS) {
                throw new DeepSeekProviderException(
                        DeepSeekFailureCode.INVALID_PROVIDER_RESPONSE);
            }
            for (JsonElement element : data) {
                if (!element.isJsonObject()) {
                    throw new DeepSeekProviderException(
                            DeepSeekFailureCode.INVALID_PROVIDER_RESPONSE);
                }
                JsonElement identifier = element.getAsJsonObject().get("id");
                if (identifier == null || !identifier.isJsonPrimitive()
                        || !identifier.getAsJsonPrimitive().isString()) {
                    throw new DeepSeekProviderException(
                            DeepSeekFailureCode.INVALID_PROVIDER_RESPONSE);
                }
                String modelId = identifier.getAsString();
                requireRemoteModelId(modelId);
                if (!remoteModels.add(modelId)) {
                    throw new DeepSeekProviderException(
                            DeepSeekFailureCode.INVALID_PROVIDER_RESPONSE);
                }
            }
            List<AiModelCapabilities> available = new ArrayList<>();
            for (AiModelCapabilities configured : config.models()) {
                if (remoteModels.contains(configured.model())) {
                    available.add(configured);
                }
            }
            return new AiCapabilities(PROVIDER_ID, now(), available);
        } catch (DeepSeekProviderException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new DeepSeekProviderException(
                    DeepSeekFailureCode.INVALID_PROVIDER_RESPONSE);
        }
    }

    private DeepSeekHttpRequest authenticatedRequest(
            URI endpoint,
            DeepSeekHttpMethod method,
            String body,
            long timeoutMillis,
            boolean stream) {
        char[] secret = null;
        try {
            secret = credentials.copySecret();
            if (secret == null || secret.length == 0) {
                throw new DeepSeekProviderException(
                        DeepSeekFailureCode.CREDENTIAL_UNAVAILABLE);
            }
        } catch (DeepSeekProviderException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new DeepSeekProviderException(
                    DeepSeekFailureCode.CREDENTIAL_UNAVAILABLE);
        }
        try {
            return new DeepSeekHttpRequest(endpoint, method, body,
                    java.time.Duration.ofMillis(timeoutMillis), stream, secret);
        } catch (IllegalArgumentException exception) {
            throw new DeepSeekProviderException(
                    DeepSeekFailureCode.REQUEST_INVALID);
        } finally {
            if (secret != null) {
                Arrays.fill(secret, '\0');
            }
        }
    }

    private AiModelCapabilities requireRequestCapabilities(AiRequest request) {
        AiModelCapabilities model = config.findModel(request.model())
                .orElseThrow(() -> new DeepSeekProviderException(
                        DeepSeekFailureCode.MODEL_NOT_ALLOWED));
        if (!model.supports(AiCapability.CHAT)
                || request.options().maximumOutputTokens()
                        > model.maximumOutputTokens()
                || request.options().reasoningAllowed()
                        && !model.supports(AiCapability.REASONING)
                || request.options().toolCallsAllowed()
                        && (!model.supports(AiCapability.TOOL_CALLS)
                                || config.tools().isEmpty())
                || request.options().responseFormat() == AiResponseFormat.JSON_OBJECT
                        && !model.supports(AiCapability.JSON_OBJECT)
                || request.options().responseFormat() == AiResponseFormat.JSON_SCHEMA
                || config.streamResponses()
                        && !model.supports(AiCapability.STREAMING)) {
            throw new DeepSeekProviderException(
                    DeepSeekFailureCode.CAPABILITY_UNAVAILABLE);
        }
        return model;
    }

    /**
     * 最终 wire JSON 仍可能带有角色、工具和协议字段；故在发送前以实际字节上界连同最大输出
     * 再复核一次模型上下文窗口。不能只信任调用方在组装阶段的估算值。
     */
    private static void requireWireContextBudget(
            AiRequest request, AiModelCapabilities model, String encodedBody) {
        int encodedInputTokens = ContextBudget.estimateTextTokens(encodedBody);
        long projectedTokens = (long) encodedInputTokens
                + request.options().maximumOutputTokens();
        if (projectedTokens > model.contextWindowTokens()) {
            throw new DeepSeekProviderException(
                    DeepSeekFailureCode.REQUEST_INVALID);
        }
    }

    private DeepSeekProviderException failureForHttp(
            DeepSeekHttpResponse response) {
        DeepSeekFailureCode code = switch (response.statusCode()) {
            case 401, 403 -> DeepSeekFailureCode.AUTHENTICATION_FAILED;
            case 402 -> DeepSeekFailureCode.QUOTA_EXHAUSTED;
            case 429 -> DeepSeekFailureCode.RATE_LIMITED;
            default -> response.statusCode() >= 500
                    ? DeepSeekFailureCode.REMOTE_UNAVAILABLE
                    : DeepSeekFailureCode.REMOTE_REJECTED;
        };
        Optional<Instant> retryAfter = code == DeepSeekFailureCode.RATE_LIMITED
                ? parseRetryAfter(response.retryAfterHeader())
                : Optional.empty();
        DeepSeekProviderException failure = new DeepSeekProviderException(
                code, retryAfter);
        if (affectsHealth(code)) {
            markFailure(failure);
        }
        return failure;
    }

    private AiProviderException sanitizeFailure(Throwable failure) {
        if (failure instanceof AiProviderException providerFailure) {
            markCommonFailure(providerFailure);
            return providerFailure;
        }
        DeepSeekProviderException safeFailure;
        if (failure instanceof DeepSeekProviderException providerFailure) {
            safeFailure = providerFailure;
        } else if (failure instanceof DeepSeekResponseLimitException) {
            safeFailure = new DeepSeekProviderException(
                    DeepSeekFailureCode.RESPONSE_TOO_LARGE);
        } else if (failure instanceof DeepSeekMalformedResponseException) {
            safeFailure = new DeepSeekProviderException(
                    DeepSeekFailureCode.INVALID_PROVIDER_RESPONSE);
        } else if (failure instanceof CancellationException) {
            safeFailure = new DeepSeekProviderException(
                    DeepSeekFailureCode.REQUEST_CANCELLED);
        } else if (failure instanceof HttpTimeoutException
                || failure instanceof TimeoutException) {
            safeFailure = new DeepSeekProviderException(
                    DeepSeekFailureCode.REQUEST_TIMEOUT);
        } else {
            safeFailure = new DeepSeekProviderException(
                    DeepSeekFailureCode.NETWORK_FAILURE);
        }
        return completeSafeFailure(safeFailure);
    }

    private AiProviderException completeSafeFailure(
            DeepSeekProviderException failure) {
        if (affectsHealth(failure.code())) {
            markFailure(failure);
        }
        return toAiProviderException(failure);
    }

    private static AiProviderException toAiProviderException(
            DeepSeekProviderException failure) {
        return new AiProviderException(
                failureKindFor(failure.code()),
                failure.retryAfter(),
                reasonCodeFor(failure.code()));
    }

    private static AiFailureKind failureKindFor(DeepSeekFailureCode code) {
        return switch (code) {
            case REQUEST_CANCELLED -> AiFailureKind.CANCELLED;
            case REQUEST_INVALID, MODEL_NOT_ALLOWED, CAPABILITY_UNAVAILABLE,
                    TOOL_CATALOG_UNAVAILABLE, REMOTE_REJECTED ->
                    AiFailureKind.INVALID_REQUEST;
            case AUTHENTICATION_FAILED -> AiFailureKind.AUTHENTICATION;
            case RATE_LIMITED -> AiFailureKind.RATE_LIMITED;
            case QUOTA_EXHAUSTED -> AiFailureKind.QUOTA_EXHAUSTED;
            case REQUEST_TIMEOUT -> AiFailureKind.TIMEOUT;
            case INVALID_PROVIDER_RESPONSE, RESPONSE_TOO_LARGE ->
                    AiFailureKind.MALFORMED_RESPONSE;
            case CREDENTIAL_UNAVAILABLE -> AiFailureKind.INVALID_REQUEST;
            case REMOTE_UNAVAILABLE, NETWORK_FAILURE -> AiFailureKind.UNAVAILABLE;
        };
    }

    private static AiReasonCode reasonCodeFor(DeepSeekFailureCode code) {
        return switch (code) {
            case CREDENTIAL_UNAVAILABLE -> AiReasonCode.CREDENTIAL_UNAVAILABLE;
            case REQUEST_CANCELLED -> AiReasonCode.CANCELLED;
            case REQUEST_INVALID, MODEL_NOT_ALLOWED, CAPABILITY_UNAVAILABLE,
                    TOOL_CATALOG_UNAVAILABLE -> AiReasonCode.INVALID_REQUEST;
            case INVALID_PROVIDER_RESPONSE -> AiReasonCode.INVALID_PROVIDER_RESPONSE;
            case RESPONSE_TOO_LARGE -> AiReasonCode.RESPONSE_TOO_LARGE;
            case AUTHENTICATION_FAILED -> AiReasonCode.AUTHENTICATION_FAILED;
            case QUOTA_EXHAUSTED -> AiReasonCode.QUOTA_EXHAUSTED;
            case RATE_LIMITED -> AiReasonCode.RATE_LIMITED;
            case REMOTE_UNAVAILABLE -> AiReasonCode.REMOTE_UNAVAILABLE;
            case REMOTE_REJECTED -> AiReasonCode.REMOTE_REJECTED;
            case NETWORK_FAILURE -> AiReasonCode.NETWORK_FAILURE;
            case REQUEST_TIMEOUT -> AiReasonCode.REQUEST_TIMEOUT;
        };
    }

    private void markCommonFailure(AiProviderException failure) {
        if (failure.failureKind() == AiFailureKind.CANCELLED
                || failure.failureKind() == AiFailureKind.INVALID_REQUEST) {
            return;
        }
        Instant observedAt = now();
        health.set(new ProviderHealth(
                PROVIDER_ID,
                failure.failureKind().healthState(),
                observedAt,
                failure.retryAfter().filter(value -> !value.isBefore(observedAt)),
                Optional.of(failure.reasonCode().wireCode())));
    }

    private void markHealthy() {
        health.set(ProviderHealth.healthy(PROVIDER_ID, now()));
    }

    private static void requireRemoteModelId(String value) {
        if (value == null || value.length() > MAX_REMOTE_MODEL_ID_LENGTH
                || !REMOTE_MODEL_ID.matcher(value).matches()) {
            throw new DeepSeekProviderException(
                    DeepSeekFailureCode.INVALID_PROVIDER_RESPONSE);
        }
    }

    private void markFailure(DeepSeekProviderException failure) {
        Instant observedAt = now();
        ProviderHealthState state = switch (failure.code()) {
            case AUTHENTICATION_FAILED -> ProviderHealthState.AUTHENTICATION_FAILED;
            case QUOTA_EXHAUSTED -> ProviderHealthState.QUOTA_EXHAUSTED;
            case RATE_LIMITED -> ProviderHealthState.RATE_LIMITED;
            case REMOTE_UNAVAILABLE, NETWORK_FAILURE, REQUEST_TIMEOUT,
                    RESPONSE_TOO_LARGE -> ProviderHealthState.UNAVAILABLE;
            default -> ProviderHealthState.DEGRADED;
        };
        health.set(new ProviderHealth(
                PROVIDER_ID, state, observedAt,
                failure.retryAfter().filter(value -> !value.isBefore(observedAt)),
                Optional.of(reasonCodeFor(failure.code()).wireCode())));
    }

    private static boolean affectsHealth(DeepSeekFailureCode code) {
        return switch (code) {
            case REQUEST_CANCELLED, REQUEST_INVALID, MODEL_NOT_ALLOWED,
                    CAPABILITY_UNAVAILABLE, TOOL_CATALOG_UNAVAILABLE,
                    CREDENTIAL_UNAVAILABLE, REMOTE_REJECTED -> false;
            default -> true;
        };
    }

    private static void requireExpectedContentType(
            DeepSeekHttpResponse response, boolean sse) {
        String actual = response.contentType().toLowerCase(Locale.ROOT);
        String expected = sse ? "text/event-stream" : "application/json";
        if (!actual.equals(expected) && !actual.startsWith(expected + ";")) {
            throw new DeepSeekProviderException(
                    DeepSeekFailureCode.INVALID_PROVIDER_RESPONSE);
        }
    }

    private Optional<Instant> parseRetryAfter(Optional<String> header) {
        if (header.isEmpty()) {
            return Optional.empty();
        }
        String value = header.orElseThrow().trim();
        try {
            long seconds = Long.parseLong(value);
            if (seconds < 0L || seconds > 3_600L) {
                return Optional.empty();
            }
            try {
                return Optional.of(now().plusSeconds(seconds));
            } catch (ArithmeticException exception) {
                return Optional.empty();
            }
        } catch (NumberFormatException exception) {
            try {
                Instant parsed = DateTimeFormatter.RFC_1123_DATE_TIME
                        .parse(value, Instant::from);
                return parsed.isBefore(now()) ? Optional.empty()
                        : Optional.of(parsed);
            } catch (DateTimeParseException ignored) {
                return Optional.empty();
            }
        }
    }

    private Instant now() {
        return clock.instant();
    }

    private static void requireNotCancelled(CancellationToken token) {
        if (token.isCancellationRequested()) {
            throw new DeepSeekProviderException(
                    DeepSeekFailureCode.REQUEST_CANCELLED);
        }
    }

    private void scheduleDeadline(
            CompletableFuture<?> completion, Duration timeout) {
        try {
            long timeoutMillis = timeout.toMillis();
            CompletableFuture.delayedExecutor(timeoutMillis, TimeUnit.MILLISECONDS)
                    .execute(() -> {
                        DeepSeekProviderException failure = new DeepSeekProviderException(
                                DeepSeekFailureCode.REQUEST_TIMEOUT);
                        AiProviderException safeFailure = toAiProviderException(failure);
                        if (completion.completeExceptionally(safeFailure)
                                && affectsHealth(failure.code())) {
                            markFailure(failure);
                        }
                    });
        } catch (RuntimeException exception) {
            completion.completeExceptionally(sanitizeFailure(exception));
        }
    }

    private void completeAsCancelled(CompletableFuture<?> completion) {
        DeepSeekProviderException failure = new DeepSeekProviderException(
                DeepSeekFailureCode.REQUEST_CANCELLED);
        completion.completeExceptionally(toAiProviderException(failure));
    }

    private void installTransportLifecycle(
            CompletableFuture<?> completion,
            DeepSeekHttpRequest request,
            AtomicReference<CancellationToken.ListenerRegistration> registration) {
        completion.whenComplete((ignored, failure) -> {
            try {
                if (completion.isCancelled() || failure != null) {
                    cancelTransportQuietly(request);
                }
            } finally {
                closeRegistrationQuietly(registration.getAndSet(
                        CancellationToken.ListenerRegistration.none()));
                request.clearSecret();
            }
        });
    }

    private static void closeRegistrationQuietly(
            CancellationToken.ListenerRegistration registration) {
        try {
            registration.close();
        } catch (RuntimeException ignored) {
            // 第三方 token 的释放异常不能越过传输终态或阻断 secret 清理。
        }
    }

    /** 取消 hook 属于不可信第三方边界，绝不能阻断 secret 清理或覆盖主失败。 */
    private void cancelTransportQuietly(DeepSeekHttpRequest request) {
        try {
            executor.cancel(request);
        } catch (RuntimeException ignored) {
            // 原始超时、取消或解析失败仍是对调用方唯一可见的安全终态。
        }
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    @FunctionalInterface
    private interface StageSupplier {
        CompletionStage<DeepSeekHttpResponse> get();
    }

    @FunctionalInterface
    private interface ResponseDecoder {
        AiResponse decode(DeepSeekHttpResponse response);
    }
}
