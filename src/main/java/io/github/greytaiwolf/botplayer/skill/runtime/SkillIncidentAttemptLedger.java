package io.github.greytaiwolf.botplayer.skill.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 按 bot、generation 和安全 incident 限制技能启动次数的主线程账本。
 */
public final class SkillIncidentAttemptLedger {
    public static final int MAXIMUM_CAPACITY = 65_536;
    public static final int MAXIMUM_ATTEMPTS = 16;
    public static final int MAXIMUM_BACKOFF_TICKS = 72_000;

    private static final UUID ZERO_UUID = new UUID(0L, 0L);

    private final Thread ownerThread;
    private final int capacity;
    private final int maximumAttempts;
    private final int retryBackoffTicks;
    private final Map<UUID, Attempt> attemptsByBot =
            new LinkedHashMap<>();
    private long lastObservedTick = -1L;

    public SkillIncidentAttemptLedger(
            int capacity,
            int maximumAttempts,
            int retryBackoffTicks) {
        if (capacity < 1 || capacity > MAXIMUM_CAPACITY) {
            throw new IllegalArgumentException(
                    "capacity must be between 1 and "
                            + MAXIMUM_CAPACITY);
        }
        if (maximumAttempts < 1
                || maximumAttempts > MAXIMUM_ATTEMPTS) {
            throw new IllegalArgumentException(
                    "maximumAttempts must be between 1 and "
                            + MAXIMUM_ATTEMPTS);
        }
        if (retryBackoffTicks < 1
                || retryBackoffTicks
                        > MAXIMUM_BACKOFF_TICKS) {
            throw new IllegalArgumentException(
                    "retryBackoffTicks must be between 1 and "
                            + MAXIMUM_BACKOFF_TICKS);
        }
        this.ownerThread = Thread.currentThread();
        this.capacity = capacity;
        this.maximumAttempts = maximumAttempts;
        this.retryBackoffTicks = retryBackoffTicks;
    }

    public AllowStatus allow(
            UUID botId,
            long generation,
            UUID incidentId,
            long currentTick) {
        requireOwnerThread();
        requireNonZero(botId, "botId");
        requireNonZero(incidentId, "incidentId");
        if (generation <= 0L) {
            throw new IllegalArgumentException(
                    "generation must be positive");
        }
        observeTick(currentTick);

        Attempt current = attemptsByBot.get(botId);
        if (current == null
                || current.generation != generation
                || !current.incidentId.equals(incidentId)) {
            if (current == null
                    && attemptsByBot.size() >= capacity) {
                return AllowStatus.CAPACITY_EXHAUSTED;
            }
            attemptsByBot.put(
                    botId,
                    new Attempt(
                            incidentId,
                            generation,
                            1,
                            Math.addExact(
                                    currentTick,
                                    retryBackoffTicks)));
            return AllowStatus.ALLOWED;
        }
        if (current.attempts >= maximumAttempts) {
            return AllowStatus.EXHAUSTED;
        }
        if (currentTick < current.nextEligibleTick) {
            return AllowStatus.BACKOFF;
        }
        attemptsByBot.put(
                botId,
                new Attempt(
                        incidentId,
                        generation,
                        current.attempts + 1,
                        Math.addExact(
                                currentTick,
                                retryBackoffTicks)));
        return AllowStatus.ALLOWED;
    }

    public boolean closeGeneration(
            UUID botId, long generation) {
        requireOwnerThread();
        requireNonZero(botId, "botId");
        if (generation <= 0L) {
            throw new IllegalArgumentException(
                    "generation must be positive");
        }
        Attempt current = attemptsByBot.get(botId);
        return current != null
                && current.generation == generation
                && attemptsByBot.remove(botId, current);
    }

    public int size() {
        requireOwnerThread();
        return attemptsByBot.size();
    }

    public void clear() {
        requireOwnerThread();
        attemptsByBot.clear();
    }

    private void observeTick(long currentTick) {
        if (currentTick < 0L
                || currentTick < lastObservedTick) {
            throw new IllegalArgumentException(
                    "currentTick must be monotonic and non-negative");
        }
        lastObservedTick = currentTick;
    }

    private void requireOwnerThread() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException(
                    "incident attempts require the owner server thread");
        }
    }

    private static void requireNonZero(
            UUID value, String name) {
        Objects.requireNonNull(value, name);
        if (ZERO_UUID.equals(value)) {
            throw new IllegalArgumentException(
                    name + " must not be zero");
        }
    }

    private record Attempt(
            UUID incidentId,
            long generation,
            int attempts,
            long nextEligibleTick) {}

    public enum AllowStatus {
        ALLOWED,
        BACKOFF,
        EXHAUSTED,
        CAPACITY_EXHAUSTED
    }
}
