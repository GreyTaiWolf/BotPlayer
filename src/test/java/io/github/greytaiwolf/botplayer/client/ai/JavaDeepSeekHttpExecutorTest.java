package io.github.greytaiwolf.botplayer.client.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class JavaDeepSeekHttpExecutorTest {
    @Test
    void cancellationBeforeDispatchDoesNotStartTheHttpExchange() {
        JavaDeepSeekHttpExecutor.RequestControl control =
                new JavaDeepSeekHttpExecutor.RequestControl();
        AtomicInteger dispatches = new AtomicInteger();

        control.cancel();
        CompletableFuture<HttpResponse<InputStream>> exchange = control.dispatch(
                request(), ignored -> {
                    dispatches.incrementAndGet();
                    return new CompletableFuture<>();
                });

        assertNull(exchange);
        assertEquals(0, dispatches.get());
    }

    @Test
    void cancellationAfterDispatchCancelsThePublishedExchange() {
        JavaDeepSeekHttpExecutor.RequestControl control =
                new JavaDeepSeekHttpExecutor.RequestControl();
        CompletableFuture<HttpResponse<InputStream>> published = new CompletableFuture<>();

        CompletableFuture<HttpResponse<InputStream>> exchange = control.dispatch(
                request(), ignored -> published);
        control.cancel();

        assertSame(published, exchange);
        assertTrue(published.isCancelled());
    }

    private static HttpRequest request() {
        return HttpRequest.newBuilder(
                        URI.create("https://api.deepseek.com/chat/completions"))
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
    }
}
