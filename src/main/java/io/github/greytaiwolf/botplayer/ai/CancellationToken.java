package io.github.greytaiwolf.botplayer.ai;

import java.util.Objects;
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

    /**
     * 注册取消监听器。
     *
     * <p>实现了监听能力的 token 必须在取消发生时至多调用一次 {@code listener}。若取消已
     * 发生，监听器可以在本方法返回前同步调用。返回的句柄可重复关闭；关闭先于取消时会阻止
     * 后续通知。默认实现保持 lambda token 的兼容性：它只能检查当前状态，未取消时返回空
     * 句柄，已取消时立即安全地通知监听器。
     *
     * <p>监听器是通知边界，运行时异常不会改变取消状态或阻止其他监听器。实现若选择重新抛出
     * {@link Error}，必须先完成同一轮其余可通知 listener；这样受信任调度边界可以把该 Error
     * 显式记录为未确认清理，而不会让一个 listener 饿死其余观察者。
     */
    default ListenerRegistration onCancellation(Runnable listener) {
        Runnable checkedListener = Objects.requireNonNull(listener, "listener");
        if (isCancellationRequested()) {
            notifyListenerSafely(checkedListener);
        }
        return ListenerRegistration.none();
    }

    static CancellationToken none() {
        return NONE;
    }

    /**
     * 取消监听器的可释放句柄。
     */
    @FunctionalInterface
    interface ListenerRegistration extends AutoCloseable {
        ListenerRegistration NONE = () -> {
        };

        /**
         * 释放监听器；该操作必须幂等。
         */
        @Override
        void close();

        static ListenerRegistration none() {
            return NONE;
        }
    }

    private static void notifyListenerSafely(Runnable listener) {
        try {
            listener.run();
        } catch (RuntimeException ignored) {
            // 监听器不可信，不能让其异常妨碍调用方完成取消路径。
        }
    }
}
