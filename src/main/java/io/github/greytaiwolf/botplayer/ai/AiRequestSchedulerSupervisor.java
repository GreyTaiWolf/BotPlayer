package io.github.greytaiwolf.botplayer.ai;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * 由生命周期拥有的 AI 调度器执行监督器。
 *
 * <p>它不是 {@code Executor} 的薄包装，也不向生产调用方暴露任意 executor 注入口。每类可能
 * 进入 Provider、取消 token 或调用方 continuation 的工作都有自己的有界 broker 和物理 lane；
 * deadline timer 与 start watchdog 也彼此独立。这样即使某个外部边界永久阻塞，也只能耗尽该
 * lane 的固定物理容量，不能借 cached pool 无限创建线程。</p>
 *
 * <p>调度器提交的每项工作都由一个单次 {@link DispatchAttempt} 表示。监督器先在独立 watchdog
 * 上挂起 start deadline，随后才把 wrapper 交给 broker。{@code execute()} 的正常返回从不被
 * 当作工作已经开始的证据：只有 wrapper 成功取得 attempt 的 STARTED 状态后，调度器才会作出
 * 相应的 provider/publication 提交。迟到、重复或在 watchdog 后被调用的 wrapper 均为 no-op。</p>
 */
public final class AiRequestSchedulerSupervisor implements AutoCloseable {
    private static final Duration DEFAULT_START_TIMEOUT = Duration.ofSeconds(1L);
    private static final Duration DEFAULT_STALL_TIMEOUT = Duration.ofSeconds(5L);
    private static final int MAX_SIDE_LANE_THREADS = 4;

    private final Duration startTimeout;
    private final Duration stallTimeout;
    private final ScheduledThreadPoolExecutor deadlineTimer;
    private final ScheduledThreadPoolExecutor startWatchdog;
    private final EnumMap<Lane, ThreadPoolExecutor> brokers;
    private final EnumMap<Lane, LaneHandoff> handoffs;
    private final Set<DispatchAttempt> outstandingAttempts = ConcurrentHashMap.newKeySet();
    /* Serializes close against the exact ARMED -> STARTED transition, never external work. */
    private final Object lifecycleLock = new Object();
    private final Runnable beforePrestartInvalidation;
    private final AtomicBoolean claimed = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    private AiRequestSchedulerSupervisor(
            String threadNamePrefix,
            AiRequestSchedulerPolicy policy,
            Duration startTimeout,
            Duration stallTimeout,
            AiSchedulerAdversarialHandoff injectedHandoff,
            Runnable beforePrestartInvalidation) {
        String checkedPrefix = requirePrefix(threadNamePrefix);
        AiRequestSchedulerPolicy checkedPolicy = Objects.requireNonNull(policy, "policy");
        this.startTimeout = positive(startTimeout, "startTimeout");
        this.stallTimeout = positive(stallTimeout, "stallTimeout");
        this.beforePrestartInvalidation = Objects.requireNonNull(
                beforePrestartInvalidation, "beforePrestartInvalidation");
        deadlineTimer = scheduledExecutor(checkedPrefix + "-deadline");
        startWatchdog = scheduledExecutor(checkedPrefix + "-start-watchdog");
        brokers = new EnumMap<>(Lane.class);
        handoffs = new EnumMap<>(Lane.class);

        int providerCapacity = checkedPolicy.maximumGlobalInFlight();
        int sideCapacity = Math.min(providerCapacity, MAX_SIDE_LANE_THREADS);
        for (Lane lane : Lane.values()) {
            int capacity = lane == Lane.PROVIDER_START ? providerCapacity : sideCapacity;
            int handoffQueueCapacity = handoffQueueCapacity(checkedPolicy, capacity);
            brokers.put(lane, brokerExecutor(
                    checkedPrefix + "-" + lane.threadSuffix() + "-broker",
                    handoffQueueCapacity));
            if (injectedHandoff == null) {
                ThreadPoolExecutor physical = physicalExecutor(
                        checkedPrefix + "-" + lane.threadSuffix(), capacity,
                        handoffQueueCapacity);
                handoffs.put(lane, new OwnedLaneHandoff(physical));
            } else {
                handoffs.put(lane, injectedHandoff::handoff);
            }
        }
    }

    /**
     * 创建一组由调用方生命周期拥有、每次只能绑定一个调度器的受信任有界 lanes。
     *
     * <p>调用方若把实例传给 {@link AiRequestScheduler}，应在调度器完成终态 publication 后再
     * 调用 {@link #close()}。默认构造的调度器会自己创建并在关闭收口后释放监督器。</p>
     */
    public static AiRequestSchedulerSupervisor create(
            String threadNamePrefix, AiRequestSchedulerPolicy policy) {
        return new AiRequestSchedulerSupervisor(
                threadNamePrefix, policy, DEFAULT_START_TIMEOUT,
                DEFAULT_STALL_TIMEOUT, null, () -> {
                });
    }

    /**
     * 创建受信任 lanes，并显式配置 start 与物理 stall 的观察窗口。
     *
     * <p>窗口不是 Provider deadline；它们只用于发现已接受却未开始、或已开始却永久占住物理
     * lane 的 handoff。请求本身仍使用 {@link AiRequestOptions#timeoutMillis()} 的绝对 deadline。</p>
     */
    public static AiRequestSchedulerSupervisor create(
            String threadNamePrefix,
            AiRequestSchedulerPolicy policy,
            Duration startTimeout,
            Duration stallTimeout) {
        return new AiRequestSchedulerSupervisor(
                threadNamePrefix, policy, startTimeout, stallTimeout, null, () -> {
                });
    }

    /** Package-private hostile-executor seam. Production code must use {@link #create}. */
    static AiRequestSchedulerSupervisor forAdversarialTesting(
            String threadNamePrefix,
            AiRequestSchedulerPolicy policy,
            Duration startTimeout,
            Duration stallTimeout,
            AiSchedulerAdversarialHandoff handoff) {
        return new AiRequestSchedulerSupervisor(
                threadNamePrefix, policy, startTimeout, stallTimeout,
                Objects.requireNonNull(handoff, "handoff"), () -> {
                });
    }

    /** Package-private close-race seam; it may only be used with the hostile handoff factory. */
    static AiRequestSchedulerSupervisor forAdversarialTesting(
            String threadNamePrefix,
            AiRequestSchedulerPolicy policy,
            Duration startTimeout,
            Duration stallTimeout,
            AiSchedulerAdversarialHandoff handoff,
            Runnable beforePrestartInvalidation) {
        return new AiRequestSchedulerSupervisor(
                threadNamePrefix, policy, startTimeout, stallTimeout,
                Objects.requireNonNull(handoff, "handoff"),
                Objects.requireNonNull(beforePrestartInvalidation,
                        "beforePrestartInvalidation"));
    }

    boolean claim() {
        synchronized (lifecycleLock) {
            if (closed.get() || !claimed.compareAndSet(false, true)) {
                return false;
            }
            return true;
        }
    }

    ScheduledFuture<?> scheduleDeadline(
            Runnable task, long delay, TimeUnit unit) {
        synchronized (lifecycleLock) {
            if (closed.get()) {
                throw new RejectedExecutionException("scheduler supervisor is closed");
            }
            return deadlineTimer.schedule(Objects.requireNonNull(task, "task"), delay,
                    Objects.requireNonNull(unit, "unit"));
        }
    }

    /**
     * Arms a trusted start watchdog before placing work on the lane's isolated broker. This
     * method never calls an external Provider/token/caller continuation on its caller thread.
     */
    void handoff(DispatchAttempt attempt, Runnable body) {
        DispatchAttempt checkedAttempt = Objects.requireNonNull(attempt, "attempt");
        Runnable checkedBody = Objects.requireNonNull(body, "body");
        boolean rejectBeforeArm = false;
        synchronized (lifecycleLock) {
            if (closed.get()) {
                rejectBeforeArm = true;
            } else {
                trackAttempt(checkedAttempt);
            }
        }
        if (rejectBeforeArm) {
            checkedAttempt.rejectBeforeStart();
            return;
        }
        if (!checkedAttempt.arm(startWatchdog, startTimeout, stallTimeout)) {
            return;
        }
        boolean rejectBeforeBroker = false;
        synchronized (lifecycleLock) {
            if (closed.get() || !checkedAttempt.awaitingStart()) {
                rejectBeforeBroker = true;
            }
        }
        if (rejectBeforeBroker) {
            checkedAttempt.rejectBeforeStart();
            return;
        }
        ThreadPoolExecutor broker = brokers.get(checkedAttempt.lane());
        try {
            broker.execute(() -> handoffFromBroker(checkedAttempt, checkedBody));
        } catch (Throwable failure) {
            checkedAttempt.rejectBeforeStart();
            DispatchAttempt.rethrowIfFatal(failure);
        }
    }

    private void handoffFromBroker(DispatchAttempt attempt, Runnable body) {
        /* A watchdog-invalidated queue entry must never re-enter a hostile lane. */
        if (!attempt.awaitingStart() || closed.get()) {
            attempt.rejectBeforeStart();
            return;
        }
        try {
            handoffs.get(attempt.lane()).handoff(attempt.lane(),
                    () -> attempt.run(body, () -> claimAttemptStart(attempt)));
        } catch (Throwable failure) {
            /* A wrapper that already committed wins; otherwise this is a start loss. */
            attempt.rejectBeforeStart();
            /* An asynchronously started wrapper is now an exact physical-quarantine concern. */
            attempt.reportStall();
            DispatchAttempt.rethrowIfFatal(failure);
        }
        /* Normal handoff return is deliberately not interpreted as liveness. */
    }

    private void trackAttempt(DispatchAttempt attempt) {
        attempt.setTerminalObserver(() -> outstandingAttempts.remove(attempt));
        if (!outstandingAttempts.add(attempt)) {
            throw new IllegalStateException("DispatchAttempt was handed off more than once");
        }
    }

    private boolean claimAttemptStart(DispatchAttempt attempt) {
        synchronized (lifecycleLock) {
            return !closed.get() && attempt.claimStarted();
        }
    }

    /**
     * Closes the start gate before invalidating every pre-start attempt. A wrapper can therefore
     * commit STARTED only before this method linearizes, never in the interval before queued
     * attempts are invalidated. A premature owner close fails closed into the scheduler's bounded
     * recovery records instead of silently dropping a cleanup/publication wrapper. The owner must
     * still keep a shared supervisor alive until it has either resumed that retained work or
     * intentionally ended the scheduler lifecycle.
     */
    @Override
    public void close() {
        List<DispatchAttempt> prestartAttempts;
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            prestartAttempts = List.copyOf(outstandingAttempts);
        }
        /*
         * A shutdown must not silently discard a broker/physical queue entry. Invalidate every
         * pre-start attempt while its callbacks can still retain terminal cleanup/publication
         * recovery; already STARTED work remains a bounded physical-quarantine concern.
         */
        beforePrestartInvalidation.run();
        for (DispatchAttempt attempt : prestartAttempts) {
            attempt.rejectBeforeStart();
        }
        for (ThreadPoolExecutor broker : brokers.values()) {
            broker.shutdownNow();
        }
        for (LaneHandoff handoff : handoffs.values()) {
            if (handoff instanceof OwnedLaneHandoff owned) {
                owned.executor.shutdownNow();
            }
        }
        deadlineTimer.shutdownNow();
        startWatchdog.shutdownNow();
    }

    private static String requirePrefix(String prefix) {
        String checked = Objects.requireNonNull(prefix, "threadNamePrefix");
        if (checked.isBlank()) {
            throw new IllegalArgumentException("threadNamePrefix must not be blank");
        }
        return checked;
    }

    private static Duration positive(Duration value, String name) {
        Duration checked = Objects.requireNonNull(value, name);
        if (checked.isNegative() || checked.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return checked;
    }

    /**
     * Every accepted live request can own at most one normal handoff on a given lane. The policy
     * already caps that set at global in-flight plus queued requests, so deriving the queue from
     * it avoids an arbitrary small broker becoming a new overload source while remaining hard
     * bounded (at most 1,152 with the current policy limits).
     */
    private static int handoffQueueCapacity(
            AiRequestSchedulerPolicy policy, int laneCapacity) {
        long maximumLive = (long) policy.maximumGlobalInFlight()
                + policy.maximumQueuedRequests();
        return (int) Math.max(laneCapacity, maximumLive);
    }

    private static ScheduledThreadPoolExecutor scheduledExecutor(String threadName) {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
                1, namedFactory(threadName));
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return executor;
    }

    private static ThreadPoolExecutor brokerExecutor(String threadName, int queueCapacity) {
        return new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), namedFactory(threadName),
                new ThreadPoolExecutor.AbortPolicy());
    }

    private static ThreadPoolExecutor physicalExecutor(
            String threadName, int capacity, int queueCapacity) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                capacity, capacity, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), namedFactory(threadName),
                new ThreadPoolExecutor.AbortPolicy());
        return executor;
    }

    private static ThreadFactory namedFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable,
                    prefix + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    enum Lane {
        PROVIDER_START("provider"),
        TOKEN_SETUP("token"),
        TERMINAL_CLEANUP("cleanup"),
        COMPLETION_DELIVERY("delivery");

        private final String threadSuffix;

        Lane(String threadSuffix) {
            this.threadSuffix = threadSuffix;
        }

        private String threadSuffix() {
            return threadSuffix;
        }
    }

    interface DispatchCallbacks {
        /** Returns whether this wrapper has a current, linearized operation to execute. */
        boolean started(DispatchAttempt attempt);

        /** The independently armed start watchdog invalidated this exact attempt. */
        void startLost(DispatchAttempt attempt);

        /** The wrapper started but has occupied its physical lane beyond the bounded window. */
        void stalled(DispatchAttempt attempt);

        /** The body returned normally, so its exact lane-specific success may now be committed. */
        void bodySucceeded(DispatchAttempt attempt);

        /**
         * The body threw after a committed start. The callback must fail closed for this exact
         * attempt; it must not treat the finally path as a cleanup/publication acknowledgement.
         */
        void bodyFailed(DispatchAttempt attempt, Throwable failure);
    }

    /**
     * One generation-bound, single-use external handoff. All state transitions are CAS based so
     * a retained executor wrapper cannot execute a stale body after watchdog recovery has moved
     * the run to a new attempt.
     */
    static final class DispatchAttempt {
        private final long attemptId;
        private final Lane lane;
        private final DispatchCallbacks callbacks;
        private final AtomicReference<AttemptState> state = new AtomicReference<>(
                AttemptState.NEW);
        private final AtomicBoolean stalled = new AtomicBoolean();
        private volatile ScheduledFuture<?> startWatchdog;
        private volatile ScheduledFuture<?> stallWatchdog;
        private volatile ScheduledThreadPoolExecutor watchdog;
        private volatile Duration stallTimeout;
        private final AtomicReference<Runnable> terminalObserver = new AtomicReference<>();

        DispatchAttempt(long attemptId, Lane lane, DispatchCallbacks callbacks) {
            if (attemptId <= 0L) {
                throw new IllegalArgumentException("attemptId must be positive");
            }
            this.attemptId = attemptId;
            this.lane = Objects.requireNonNull(lane, "lane");
            this.callbacks = Objects.requireNonNull(callbacks, "callbacks");
        }

        long attemptId() {
            return attemptId;
        }

        Lane lane() {
            return lane;
        }

        boolean isStalled() {
            return stalled.get();
        }

        private boolean awaitingStart() {
            return state.get() == AttemptState.ARMED;
        }

        private void setTerminalObserver(Runnable observer) {
            if (!terminalObserver.compareAndSet(null, Objects.requireNonNull(observer, "observer"))) {
                throw new IllegalStateException("DispatchAttempt terminal observer is already set");
            }
        }

        private boolean arm(
                ScheduledThreadPoolExecutor watchdog,
                Duration startTimeout,
                Duration stallTimeout) {
            if (!state.compareAndSet(AttemptState.NEW, AttemptState.ARMED)) {
                return false;
            }
            this.watchdog = watchdog;
            this.stallTimeout = stallTimeout;
            try {
                ScheduledFuture<?> scheduled = watchdog.schedule(this::startWatchdogFired,
                        delayNanos(startTimeout), TimeUnit.NANOSECONDS);
                startWatchdog = scheduled;
                /* close/watchdog can invalidate between CAS(ARMED) and this assignment. */
                if (!awaitingStart()) {
                    cancelQuietly(scheduled);
                    return false;
                }
                return true;
            } catch (Throwable failure) {
                rejectBeforeStart();
                rethrowIfFatal(failure);
                return false;
            }
        }

        private boolean claimStarted() {
            return state.compareAndSet(AttemptState.ARMED, AttemptState.STARTED);
        }

        private void run(Runnable body, BooleanSupplier startGate) {
            if (!Objects.requireNonNull(startGate, "startGate").getAsBoolean()) {
                /* A close that won the lifecycle gate owns the pre-start invalidation. */
                rejectBeforeStart();
                return;
            }
            cancelQuietly(startWatchdog);
            boolean committed = false;
            Throwable bodyFailure = null;
            try {
                committed = callbacks.started(this);
                if (!committed) {
                    return;
                }
                try {
                    stallWatchdog = watchdog.schedule(this::reportStall,
                            delayNanos(stallTimeout), TimeUnit.NANOSECONDS);
                } catch (RuntimeException exception) {
                    /* A closed watchdog is indistinguishable from a physical lane loss. */
                    reportStall();
                }
                body.run();
            } catch (Throwable failure) {
                bodyFailure = failure;
            } finally {
                cancelQuietly(stallWatchdog);
                state.set(AttemptState.FINISHED);
                try {
                    if (committed) {
                        if (bodyFailure == null) {
                            callbacks.bodySucceeded(this);
                        } else {
                            callbacks.bodyFailed(this, bodyFailure);
                        }
                    }
                } finally {
                    notifyTerminalObserver();
                }
            }
            if (bodyFailure != null) {
                rethrowIfFatal(bodyFailure);
            }
        }

        private void startWatchdogFired() {
            if (state.compareAndSet(AttemptState.ARMED, AttemptState.INVALIDATED)) {
                try {
                    callbacks.startLost(this);
                } finally {
                    notifyTerminalObserver();
                }
            }
        }

        private void rejectBeforeStart() {
            if (state.compareAndSet(AttemptState.ARMED, AttemptState.INVALIDATED)
                    || state.compareAndSet(AttemptState.NEW, AttemptState.INVALIDATED)) {
                cancelQuietly(startWatchdog);
                try {
                    callbacks.startLost(this);
                } finally {
                    notifyTerminalObserver();
                }
            }
        }

        private void reportStall() {
            if (state.get() == AttemptState.STARTED && stalled.compareAndSet(false, true)) {
                callbacks.stalled(this);
            }
        }

        private static long delayNanos(Duration value) {
            try {
                return Math.max(1L, value.toNanos());
            } catch (ArithmeticException exception) {
                return Long.MAX_VALUE;
            }
        }

        private static void cancelQuietly(ScheduledFuture<?> task) {
            if (task != null) {
                try {
                    task.cancel(false);
                } catch (Throwable ignored) {
                    // The watchdog has no external callback surface.
                }
            }
        }

        @SuppressWarnings("removal")
        static boolean isFatal(Throwable failure) {
            return failure instanceof VirtualMachineError
                    || failure instanceof ThreadDeath
                    || failure instanceof LinkageError;
        }

        @SuppressWarnings("removal")
        static void rethrowIfFatal(Throwable failure) {
            if (failure instanceof VirtualMachineError error) {
                throw error;
            }
            if (failure instanceof ThreadDeath death) {
                throw death;
            }
            if (failure instanceof LinkageError error) {
                throw error;
            }
        }

        private void notifyTerminalObserver() {
            Runnable observer = terminalObserver.getAndSet(null);
            if (observer != null) {
                observer.run();
            }
        }
    }

    private enum AttemptState {
        NEW,
        ARMED,
        STARTED,
        INVALIDATED,
        FINISHED
    }

    @FunctionalInterface
    private interface LaneHandoff {
        void handoff(Lane lane, Runnable wrapper);
    }

    private static final class OwnedLaneHandoff implements LaneHandoff {
        private final ThreadPoolExecutor executor;

        private OwnedLaneHandoff(ThreadPoolExecutor executor) {
            this.executor = executor;
        }

        @Override
        public void handoff(Lane ignored, Runnable wrapper) {
            executor.execute(wrapper);
        }
    }
}

/** Explicitly hostile, package-private executor seam for scheduler liveness tests only. */
@FunctionalInterface
interface AiSchedulerAdversarialHandoff {
    void handoff(AiRequestSchedulerSupervisor.Lane lane, Runnable wrapper);
}
