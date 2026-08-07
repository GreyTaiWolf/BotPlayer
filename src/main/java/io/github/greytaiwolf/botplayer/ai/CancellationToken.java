package io.github.greytaiwolf.botplayer.ai;

import java.util.concurrent.CancellationException;

/**
 * 不绑定线程或 Provider 实现的协作式取消信号。
 */
@FunctionalInterface
public interface CancellationToken {
    CancellationToken NONE = () -> false;

    boolean isCancellationRequested();

    default void throwIfCancellationRequested() {
        if (isCancellationRequested()) {
            throw new CancellationException("AI request was cancelled");
        }
    }

    static CancellationToken none() {
        return NONE;
    }
}
