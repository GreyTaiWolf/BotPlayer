package io.github.greytaiwolf.botplayer.skill.task;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 有界 TaskSensor 的纯调度器。它先核验 run 身份，再复用未过期快照，最后才允许主线程采样。
 */
public final class TaskSensorService {
    private static final long NO_ACTIVE_TICK = -1L;

    private final TaskSensorAuthority authority;
    private final TaskSensorLimits limits;
    private final Map<TaskSensorQuery, CacheEntry> cache =
            new LinkedHashMap<>();
    private final Map<TaskSensorRunIdentity, Usage> runUsage =
            new HashMap<>();

    private long activeTick = NO_ACTIVE_TICK;
    private int sampledQueries;
    private int consumedWorkUnits;

    public TaskSensorService(
            TaskSensorAuthority authority, TaskSensorLimits limits) {
        this.authority = Objects.requireNonNull(authority, "authority");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /**
     * 每个服务器 Tick 只能调用一次。重复调用同一个 Tick 不会重置已消耗额度。
     */
    public synchronized void beginTick(long currentTick) {
        if (currentTick < 0L) {
            throw new IllegalArgumentException(
                    "currentTick must be non-negative");
        }
        if (activeTick != NO_ACTIVE_TICK && currentTick < activeTick) {
            throw new IllegalArgumentException(
                    "currentTick must not move backwards");
        }
        if (currentTick == activeTick) {
            return;
        }
        activeTick = currentTick;
        sampledQueries = 0;
        consumedWorkUnits = 0;
        runUsage.clear();
        cache.entrySet().removeIf(entry ->
                entry.getValue().expiredAt(currentTick));
    }

    public synchronized TaskSensorResponse query(
            TaskSensorQuery query,
            long currentTick,
            TaskSensorSampler sampler) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(sampler, "sampler");
        if (currentTick != activeTick) {
            return rejected(TaskSensorResponse.Status.TICK_NOT_OPEN);
        }
        if (!currentIdentity(query.identity())) {
            cache.remove(query);
            return rejected(TaskSensorResponse.Status.STALE_IDENTITY);
        }

        CacheEntry cached = cache.get(query);
        if (cached != null) {
            if (!cached.expiredAt(currentTick)) {
                return new TaskSensorResponse(
                        TaskSensorResponse.Status.CACHE_HIT,
                        Optional.of(cached.snapshot()));
            }
            cache.remove(query);
        }
        int workUnits = query.budget().workUnits();
        Usage usage = runUsage.getOrDefault(
                query.identity(), Usage.EMPTY);
        if (sampledQueries >= limits.maximumSamplesPerTick()
                || consumedWorkUnits + workUnits
                        > limits.maximumWorkUnitsPerTick()) {
            return rejected(
                    TaskSensorResponse.Status.TICK_BUDGET_EXHAUSTED);
        }
        if (usage.samples()
                >= limits.maximumSamplesPerRunPerTick()
                || usage.workUnits() + workUnits
                        > limits.maximumWorkUnitsPerRunPerTick()) {
            return rejected(
                    TaskSensorResponse.Status.RUN_BUDGET_EXHAUSTED);
        }
        if (cache.size() >= limits.maximumCachedSnapshots()) {
            return rejected(
                    TaskSensorResponse.Status.CACHE_CAPACITY_EXCEEDED);
        }

        sampledQueries++;
        consumedWorkUnits += workUnits;
        runUsage.put(query.identity(), usage.add(workUnits));
        TaskSensorSnapshot snapshot;
        try {
            snapshot = sampler.sample(query, currentTick);
        } catch (RuntimeException exception) {
            return rejected(TaskSensorResponse.Status.SAMPLER_FAILURE);
        }
        if (!validSnapshot(snapshot, query, currentTick)) {
            return rejected(TaskSensorResponse.Status.INVALID_SNAPSHOT);
        }
        long effectiveMaximumAge = Math.min(
                query.budget().maximumAgeTicks(),
                limits.maximumCacheAgeTicks());
        cache.put(query, new CacheEntry(
                snapshot, effectiveMaximumAge));
        return new TaskSensorResponse(
                TaskSensorResponse.Status.SAMPLED,
                Optional.of(snapshot));
    }

    public synchronized int cacheSize() {
        return cache.size();
    }

    public synchronized int sampledQueriesThisTick() {
        return sampledQueries;
    }

    private boolean currentIdentity(TaskSensorRunIdentity requested) {
        Optional<TaskSensorRunIdentity> actual = authority.current(
                requested.botId(), requested.skillRunId());
        return actual.filter(requested::equals).isPresent();
    }

    private static boolean validSnapshot(
            TaskSensorSnapshot snapshot,
            TaskSensorQuery query,
            long currentTick) {
        return snapshot != null
                && snapshot.query().equals(query)
                && snapshot.sampledAtTick() == currentTick;
    }

    private static TaskSensorResponse rejected(
            TaskSensorResponse.Status status) {
        return new TaskSensorResponse(status, Optional.empty());
    }

    private record CacheEntry(
            TaskSensorSnapshot snapshot, long maximumAgeTicks) {
        private CacheEntry {
            Objects.requireNonNull(snapshot, "snapshot");
            if (maximumAgeTicks < 0L) {
                throw new IllegalArgumentException(
                        "maximumAgeTicks must be non-negative");
            }
        }

        private boolean expiredAt(long currentTick) {
            return snapshot.expiredAt(currentTick, maximumAgeTicks);
        }
    }

    private record Usage(int samples, int workUnits) {
        private static final Usage EMPTY = new Usage(0, 0);

        private Usage {
            if (samples < 0 || workUnits < 0) {
                throw new IllegalArgumentException(
                        "usage values must be non-negative");
            }
        }

        private Usage add(int workUnitsToAdd) {
            return new Usage(samples + 1, workUnits + workUnitsToAdd);
        }
    }
}
