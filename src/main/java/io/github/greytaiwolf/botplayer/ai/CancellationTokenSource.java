package io.github.greytaiwolf.botplayer.ai;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 线程安全、幂等且支持一次性监听器的取消源。
 *
 * <p>取消状态先发布，再在锁外执行监听器。因此注册、释放和取消之间没有死锁：关闭在
 * 取消开始前取得锁会阻止通知；取消先取得锁则会保留本轮通知。运行时异常被隔离，不能
 * 阻止其他监听器或改变最终取消状态。Error 会在其余监听器均已获得通知后重新抛出，使
 * 受信任的调用边界能把该次外部失败显式记录为失败/隔离，而不会悄悄把部分清理当作成功。
 */
public final class CancellationTokenSource {
    private final AtomicBoolean cancellationRequested =
            new AtomicBoolean();
    private final Object listenerLock = new Object();
    private final Map<ListenerRegistrationImpl, Runnable> listeners =
            new IdentityHashMap<>();
    private final CancellationToken token = new CancellationToken() {
        @Override
        public boolean isCancellationRequested() {
            return cancellationRequested.get();
        }

        @Override
        public CancellationToken.ListenerRegistration onCancellation(
                Runnable listener) {
            return CancellationTokenSource.this.onCancellation(listener);
        }
    };

    public CancellationToken token() {
        return token;
    }

    /**
     * 注册会在取消时至多执行一次的监听器。
     *
     * <p>若取消已经开始，监听器会在本方法返回前执行，返回的句柄不再持有任何资源。
     */
    public CancellationToken.ListenerRegistration onCancellation(
            Runnable listener) {
        Runnable checkedListener = Objects.requireNonNull(listener, "listener");
        ListenerRegistrationImpl registration = new ListenerRegistrationImpl();
        boolean notifyImmediately;
        synchronized (listenerLock) {
            notifyImmediately = cancellationRequested.get();
            if (!notifyImmediately) {
                listeners.put(registration, checkedListener);
            }
        }
        if (notifyImmediately) {
            rethrowListenerFailure(notifyListenerSafely(checkedListener));
        }
        return registration;
    }

    public boolean cancel() {
        if (!cancellationRequested.compareAndSet(false, true)) {
            return false;
        }
        List<Runnable> pendingListeners;
        synchronized (listenerLock) {
            pendingListeners = new ArrayList<>(listeners.values());
            listeners.clear();
        }
        Throwable listenerFailure = null;
        for (Runnable listener : pendingListeners) {
            Throwable failure = notifyListenerSafely(listener);
            if (listenerFailure == null) {
                listenerFailure = failure;
            }
        }
        rethrowListenerFailure(listenerFailure);
        return true;
    }

    public boolean isCancellationRequested() {
        return cancellationRequested.get();
    }

    private void removeListener(ListenerRegistrationImpl registration) {
        synchronized (listenerLock) {
            listeners.remove(registration);
        }
    }

    private static Throwable notifyListenerSafely(Runnable listener) {
        try {
            listener.run();
        } catch (RuntimeException ignored) {
            // 取消状态已经确定；一个观察者失败不能饿死后续观察者。
            return null;
        } catch (Throwable failure) {
            return failure;
        }
        return null;
    }

    private static void rethrowListenerFailure(Throwable failure) {
        if (failure != null) {
            CancellationTokenSource.<RuntimeException>throwUnchecked(failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void throwUnchecked(Throwable failure) throws T {
        throw (T) failure;
    }

    private final class ListenerRegistrationImpl
            implements CancellationToken.ListenerRegistration {
        private final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                removeListener(this);
            }
        }
    }
}
