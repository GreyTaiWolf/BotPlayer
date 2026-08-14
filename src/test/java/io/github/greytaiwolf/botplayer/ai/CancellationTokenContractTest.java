package io.github.greytaiwolf.botplayer.ai;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CancellationTokenContractTest {
    @Test
    void noneTokenNeverCancels() {
        CancellationToken token = CancellationToken.none();
        AtomicInteger calls = new AtomicInteger();

        Assertions.assertFalse(token.isCancellationRequested());
        Assertions.assertDoesNotThrow(
                token::throwIfCancellationRequested);
        token.onCancellation(calls::incrementAndGet).close();
        Assertions.assertEquals(0, calls.get());
    }

    @Test
    void lambdaTokensRetainTheDefaultListenerContract() {
        CancellationToken token = () -> false;
        AtomicInteger calls = new AtomicInteger();

        CancellationToken.ListenerRegistration registration =
                token.onCancellation(calls::incrementAndGet);

        registration.close();
        registration.close();
        Assertions.assertEquals(0, calls.get());

        CancellationToken alreadyCancelled = () -> true;
        alreadyCancelled.onCancellation(calls::incrementAndGet).close();
        Assertions.assertEquals(1, calls.get());
    }

    @Test
    void sourceCancelsExactlyOnceAndSharesStateWithToken() {
        CancellationTokenSource source =
                new CancellationTokenSource();
        CancellationToken token = source.token();

        Assertions.assertFalse(source.isCancellationRequested());
        Assertions.assertTrue(source.cancel());
        Assertions.assertFalse(source.cancel());
        Assertions.assertTrue(source.isCancellationRequested());
        Assertions.assertTrue(token.isCancellationRequested());
        Assertions.assertThrows(
                CancellationException.class,
                token::throwIfCancellationRequested);
    }

    @Test
    void sourceDeliversAtMostOnceAndClosedListenersAreReleased() {
        CancellationTokenSource source = new CancellationTokenSource();
        AtomicInteger calls = new AtomicInteger();
        CancellationToken.ListenerRegistration released =
                source.token().onCancellation(calls::incrementAndGet);
        released.close();
        released.close();
        source.onCancellation(calls::incrementAndGet);

        Assertions.assertTrue(source.cancel());
        Assertions.assertFalse(source.cancel());
        Assertions.assertEquals(1, calls.get());

        source.token().onCancellation(calls::incrementAndGet).close();
        Assertions.assertEquals(2, calls.get());
    }

    @Test
    void sourceIsolatesListenerFailures() {
        CancellationTokenSource source = new CancellationTokenSource();
        AtomicInteger delivered = new AtomicInteger();
        source.onCancellation(() -> {
            throw new IllegalStateException("listener failure");
        });
        source.token().onCancellation(delivered::incrementAndGet);

        Assertions.assertDoesNotThrow(source::cancel);
        Assertions.assertEquals(1, delivered.get());
    }

    @Test
    void sourceNotifiesRemainingListenersBeforeRethrowingAnError() {
        CancellationTokenSource source = new CancellationTokenSource();
        AtomicInteger delivered = new AtomicInteger();
        source.onCancellation(() -> {
            throw new AssertionError("listener error");
        });
        source.token().onCancellation(delivered::incrementAndGet);

        Assertions.assertThrows(AssertionError.class, source::cancel);
        Assertions.assertTrue(source.isCancellationRequested());
        Assertions.assertEquals(1, delivered.get());
    }

    @Test
    void registrationAndCancellationRaceStillDeliversExactlyOnce()
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int iteration = 0; iteration < 64; iteration++) {
                CancellationTokenSource source = new CancellationTokenSource();
                AtomicInteger calls = new AtomicInteger();
                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch start = new CountDownLatch(1);
                Future<?> registration = executor.submit(() -> {
                    ready.countDown();
                    await(start);
                    source.token().onCancellation(calls::incrementAndGet);
                });
                Future<?> cancellation = executor.submit(() -> {
                    ready.countDown();
                    await(start);
                    source.cancel();
                });

                Assertions.assertTrue(ready.await(2L, TimeUnit.SECONDS));
                start.countDown();
                registration.get(2L, TimeUnit.SECONDS);
                cancellation.get(2L, TimeUnit.SECONDS);

                Assertions.assertEquals(1, calls.get());
            }
        } finally {
            executor.shutdownNow();
            Assertions.assertTrue(executor.awaitTermination(
                    2L, TimeUnit.SECONDS));
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("test thread interrupted", exception);
        }
    }
}
