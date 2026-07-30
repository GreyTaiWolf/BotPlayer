package io.github.greytaiwolf.botplayer.skill.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 在入队时拒绝未知 run、错误 bot 和非当前 generation 的有界信号队列。
 */
public final class SkillSignalInbox implements AutoCloseable {
    public static final int MAX_CAPACITY = 65_536;

    private final int capacity;
    private final int maximumBindings;
    private final Deque<SkillSignal> signals = new ArrayDeque<>();
    private final Map<UUID, Binding> bindings = new LinkedHashMap<>();
    private boolean closed;
    private long rejectedFull;
    private long rejectedStale;

    public SkillSignalInbox(int capacity) {
        this(capacity, capacity);
    }

    public SkillSignalInbox(int capacity, int maximumBindings) {
        this.capacity = requireBound(
                capacity, "capacity");
        this.maximumBindings = requireBound(
                maximumBindings, "maximumBindings");
    }

    public synchronized BindStatus bind(
            UUID runId, UUID botId, long generation) {
        SkillSignal.requireNonZero(runId, "runId");
        SkillSignal.requireNonZero(botId, "botId");
        if (generation <= 0L) {
            throw new IllegalArgumentException(
                    "generation must be positive");
        }
        if (closed) {
            return BindStatus.CLOSED;
        }
        Binding existing = bindings.get(runId);
        if (existing == null) {
            if (bindings.size() >= maximumBindings) {
                return BindStatus.CAPACITY_EXCEEDED;
            }
            bindings.put(
                    runId, new Binding(botId, generation));
            return BindStatus.BOUND;
        }
        if (!existing.botId.equals(botId)) {
            return BindStatus.RUN_ID_CONFLICT;
        }
        if (generation < existing.generation) {
            return BindStatus.STALE_GENERATION;
        }
        if (generation == existing.generation) {
            return BindStatus.ALREADY_BOUND;
        }
        bindings.put(runId, new Binding(botId, generation));
        purgeRun(runId);
        return BindStatus.REBOUND;
    }

    public synchronized OfferStatus offer(SkillSignal signal) {
        Objects.requireNonNull(signal, "signal");
        if (closed) {
            return OfferStatus.CLOSED;
        }
        Binding binding = bindings.get(signal.runId());
        if (binding == null) {
            rejectedStale = incrementSaturated(rejectedStale);
            return OfferStatus.UNKNOWN_RUN;
        }
        if (!binding.botId.equals(signal.botId())) {
            rejectedStale = incrementSaturated(rejectedStale);
            return OfferStatus.BOT_MISMATCH;
        }
        if (binding.generation != signal.botGeneration()) {
            rejectedStale = incrementSaturated(rejectedStale);
            return OfferStatus.STALE_GENERATION;
        }
        if (signals.size() >= capacity) {
            rejectedFull = incrementSaturated(rejectedFull);
            return OfferStatus.FULL;
        }
        signals.addLast(signal);
        return OfferStatus.ENQUEUED;
    }

    public synchronized List<SkillSignal> drain(
            UUID runId, long generation, int maximum) {
        SkillSignal.requireNonZero(runId, "runId");
        if (generation <= 0L) {
            throw new IllegalArgumentException(
                    "generation must be positive");
        }
        if (maximum < 1 || maximum > capacity) {
            throw new IllegalArgumentException(
                    "maximum must be between 1 and inbox capacity");
        }
        Binding binding = bindings.get(runId);
        if (binding == null
                || binding.generation != generation) {
            return List.of();
        }
        List<SkillSignal> drained =
                new ArrayList<>(Math.min(maximum, signals.size()));
        Iterator<SkillSignal> iterator = signals.iterator();
        while (iterator.hasNext() && drained.size() < maximum) {
            SkillSignal signal = iterator.next();
            if (signal.runId().equals(runId)
                    && signal.botGeneration() == generation) {
                iterator.remove();
                drained.add(signal);
            }
        }
        return List.copyOf(drained);
    }

    public synchronized boolean unbind(UUID runId) {
        SkillSignal.requireNonZero(runId, "runId");
        Binding removed = bindings.remove(runId);
        purgeRun(runId);
        return removed != null;
    }

    public synchronized int size() {
        return signals.size();
    }

    public synchronized int bindingCount() {
        return bindings.size();
    }

    public synchronized long rejectedFullCount() {
        return rejectedFull;
    }

    public synchronized long rejectedStaleCount() {
        return rejectedStale;
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        signals.clear();
        bindings.clear();
    }

    private void purgeRun(UUID runId) {
        signals.removeIf(signal -> signal.runId().equals(runId));
    }

    private static int requireBound(int value, String name) {
        if (value < 1 || value > MAX_CAPACITY) {
            throw new IllegalArgumentException(
                    name + " must be between 1 and "
                            + MAX_CAPACITY);
        }
        return value;
    }

    private static long incrementSaturated(long value) {
        return value == Long.MAX_VALUE ? value : value + 1L;
    }

    private record Binding(UUID botId, long generation) {}

    public enum BindStatus {
        BOUND,
        ALREADY_BOUND,
        REBOUND,
        CAPACITY_EXCEEDED,
        RUN_ID_CONFLICT,
        STALE_GENERATION,
        CLOSED
    }

    public enum OfferStatus {
        ENQUEUED,
        FULL,
        UNKNOWN_RUN,
        BOT_MISMATCH,
        STALE_GENERATION,
        CLOSED
    }
}
