package io.github.greytaiwolf.botplayer.client.ai;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Java 21 {@link HttpClient} 的异步、客户端专用 DeepSeek 传输实现。
 *
 * <p>它不记录请求、header 或 body。响应使用有界 InputStream 读取，避免 Provider 无法在
 * 解析前限制异常大的 JSON/SSE body。</p>
 */
public final class JavaDeepSeekHttpExecutor implements DeepSeekHttpExecutor {
    public static final int MAX_RESPONSE_BYTES = 1_048_576;

    private final HttpClient httpClient;
    private final Executor bodyExecutor;
    private final ConcurrentHashMap<DeepSeekHttpRequest, RequestControl> inFlight =
            new ConcurrentHashMap<>();

    public JavaDeepSeekHttpExecutor(HttpClient httpClient, Executor bodyExecutor) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        if (this.httpClient.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException(
                    "DeepSeek HttpClient must reject redirects");
        }
        this.bodyExecutor = Objects.requireNonNull(bodyExecutor, "bodyExecutor");
    }

    public static JavaDeepSeekHttpExecutor defaults() {
        return new JavaDeepSeekHttpExecutor(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(15L))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                ForkJoinPool.commonPool());
    }

    @Override
    public CompletionStage<DeepSeekHttpResponse> execute(DeepSeekHttpRequest request) {
        DeepSeekHttpRequest checked = Objects.requireNonNull(request, "request");
        RequestControl control = new RequestControl();
        if (inFlight.putIfAbsent(checked, control) != null) {
            checked.clearSecret();
            throw new IllegalArgumentException("DeepSeek request is already in flight");
        }
        char[] secret = checked.copySecret();
        try {
            String authorization = "Bearer " + new String(secret);
            HttpRequest.Builder builder = HttpRequest.newBuilder(checked.endpoint())
                    .timeout(checked.timeout())
                    .header("Accept", checked.stream()
                            ? "text/event-stream"
                            : "application/json")
                    .header("Authorization", authorization)
                    .header("Content-Type", "application/json; charset=utf-8");
            if (checked.method() == DeepSeekHttpMethod.POST) {
                builder.POST(HttpRequest.BodyPublishers.ofString(
                        checked.body(), StandardCharsets.UTF_8));
            } else {
                builder.GET();
            }
            HttpRequest httpRequest = builder.build();
            checked.clearSecret();
            CompletableFuture<HttpResponse<InputStream>> exchange = control.dispatch(
                    httpRequest, requestToDispatch -> httpClient.sendAsync(
                            requestToDispatch, HttpResponse.BodyHandlers.ofInputStream()));
            if (exchange == null) {
                inFlight.remove(checked, control);
                checked.clearSecret();
                return CompletableFuture.failedFuture(new CancellationException(
                        "DeepSeek request was cancelled before dispatch"));
            }
            CompletableFuture<DeepSeekHttpResponse> result = new CompletableFuture<>();
            control.attachResult(result);
            exchange.thenApplyAsync(value -> toBoundedResponse(value, control), bodyExecutor)
                    .whenComplete((response, failure) -> {
                        if (failure == null) {
                            result.complete(response);
                        } else {
                            result.completeExceptionally(failure);
                        }
                    });
            result.orTimeout(checked.timeout().toMillis(), TimeUnit.MILLISECONDS);
            result.whenComplete((ignored, failure) -> {
                inFlight.remove(checked, control);
                if (result.isCancelled() || isTimeout(failure)) {
                    control.cancel();
                }
                checked.clearSecret();
            });
            return result;
        } catch (RuntimeException exception) {
            inFlight.remove(checked, control);
            control.cancel();
            checked.clearSecret();
            throw exception;
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    @Override
    public void cancel(DeepSeekHttpRequest request) {
        DeepSeekHttpRequest checked = Objects.requireNonNull(request, "request");
        RequestControl control = inFlight.remove(checked);
        if (control != null) {
            control.cancel();
        }
        checked.clearSecret();
    }

    private static DeepSeekHttpResponse toBoundedResponse(
            HttpResponse<InputStream> response, RequestControl control) {
        InputStream body = Objects.requireNonNull(response.body(), "response body");
        if (!control.attachBody(body)) {
            throw new CancellationException("DeepSeek request was cancelled");
        }
        try (body) {
            String content = readBoundedUtf8(body);
            Optional<String> contentType = response.headers().firstValue(
                    "Content-Type");
            Optional<String> retryAfter = response.headers().firstValue(
                    "Retry-After");
            return new DeepSeekHttpResponse(
                    response.statusCode(), contentType.orElse(""), content,
                    retryAfter);
        } catch (IOException exception) {
            throw new CompletionException(exception);
        } finally {
            control.releaseBody(body);
        }
    }

    private static String readBoundedUtf8(InputStream source) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8_192];
        int total = 0;
        int read;
        while ((read = source.read(buffer)) >= 0) {
            if (read > MAX_RESPONSE_BYTES - total) {
                throw new DeepSeekResponseLimitException();
            }
            output.write(buffer, 0, read);
            total += read;
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(output.toByteArray()))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new DeepSeekMalformedResponseException();
        }
    }

    private static boolean isTimeout(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current instanceof TimeoutException;
    }

    @FunctionalInterface
    interface ExchangeStarter {
        CompletableFuture<HttpResponse<InputStream>> start(HttpRequest request);
    }

    /**
     * 单个请求的取消状态机；包可见性仅用于同包并发回归测试。
     */
    static final class RequestControl {
        private final Object gate = new Object();
        private boolean cancelled;
        private CompletableFuture<HttpResponse<InputStream>> exchange;
        private CompletableFuture<DeepSeekHttpResponse> result;
        private InputStream activeBody;

        /**
         * 将取消和实际 {@link HttpClient#sendAsync(HttpRequest, HttpResponse.BodyHandler)}
         * 串行化。取消先取得 gate 时绝不启动网络；发送先取得 gate 时会先公布 future，随后
         * 取消必定能中止同一个 future。
         */
        CompletableFuture<HttpResponse<InputStream>> dispatch(
                HttpRequest request, ExchangeStarter starter) {
            synchronized (gate) {
                if (cancelled) {
                    return null;
                }
                CompletableFuture<HttpResponse<InputStream>> submitted =
                        Objects.requireNonNull(starter, "starter").start(request);
                exchange = Objects.requireNonNull(submitted, "HttpClient exchange");
                return submitted;
            }
        }

        private void attachResult(CompletableFuture<DeepSeekHttpResponse> future) {
            boolean cancelNow;
            synchronized (gate) {
                result = Objects.requireNonNull(future, "future");
                cancelNow = cancelled;
            }
            if (cancelNow) {
                future.cancel(true);
            }
        }

        private boolean attachBody(InputStream body) {
            boolean closeNow;
            synchronized (gate) {
                closeNow = cancelled || activeBody != null;
                if (!closeNow) {
                    activeBody = body;
                }
            }
            if (closeNow) {
                close(body);
                return false;
            }
            return true;
        }

        private void releaseBody(InputStream body) {
            synchronized (gate) {
                if (activeBody == body) {
                    activeBody = null;
                }
            }
        }

        void cancel() {
            InputStream body;
            CompletableFuture<HttpResponse<InputStream>> pendingExchange;
            CompletableFuture<DeepSeekHttpResponse> pendingResult;
            synchronized (gate) {
                if (cancelled) {
                    return;
                }
                cancelled = true;
                body = activeBody;
                activeBody = null;
                pendingExchange = exchange;
                pendingResult = result;
            }
            close(body);
            if (pendingExchange != null) {
                pendingExchange.cancel(true);
            }
            if (pendingResult != null) {
                pendingResult.cancel(true);
            }
        }

        private static void close(InputStream stream) {
            if (stream == null) {
                return;
            }
            try {
                stream.close();
            } catch (IOException ignored) {
                // 取消路径不能把远端 I/O 错误泄漏到调用方或日志。
            }
        }
    }
}
