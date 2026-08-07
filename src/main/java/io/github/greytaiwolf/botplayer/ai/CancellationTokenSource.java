package io.github.greytaiwolf.botplayer.ai;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 线程安全、幂等的取消源。
 */
public final class CancellationTokenSource {
    private final AtomicBoolean cancellationRequested =
            new AtomicBoolean();
    private final CancellationToken token =
            cancellationRequested::get;

    public CancellationToken token() {
        return token;
    }

    public boolean cancel() {
        return cancellationRequested.compareAndSet(false, true);
    }

    public boolean isCancellationRequested() {
        return cancellationRequested.get();
    }
}
