package io.github.greytaiwolf.botplayer.ai;

import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CancellationTokenContractTest {
    @Test
    void noneTokenNeverCancels() {
        CancellationToken token = CancellationToken.none();

        Assertions.assertFalse(token.isCancellationRequested());
        Assertions.assertDoesNotThrow(
                token::throwIfCancellationRequested);
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
}
