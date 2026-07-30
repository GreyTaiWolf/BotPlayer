package io.github.greytaiwolf.botplayer.action.interaction;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Generation-scoped, one-shot identity fence for lifecycle inventory
 * compensation.
 *
 * <p>The fence is owned by the server thread. A run must be armed before it
 * can begin compensation; exact and stale replays cannot invoke the mutation
 * handler a second time.
 */
public final class InventoryLayoutCleanupFence {
    public static final int MAX_CONSUMED_RUNS = 4096;
    private static final UUID ZERO_UUID = new UUID(0L, 0L);

    private final Map<GenerationKey, Lease> leases =
            new HashMap<>();
    private final Map<GenerationKey, Set<UUID>>
            consumedRuns = new HashMap<>();
    private int consumedRunCount;

    public ArmResult arm(
            UUID botId,
            long generation,
            InventoryLayoutCleanupLease layoutLease) {
        GenerationKey key = key(botId, generation);
        InventoryLayoutCleanupLease requiredLease =
                Objects.requireNonNull(
                        layoutLease, "layoutLease");
        if (wasConsumed(
                key, requiredLease.runId())) {
            return ArmResult.CONFLICT;
        }
        Lease existing = leases.get(key);
        if (existing == null) {
            if (Math.addExact(
                            consumedRunCount,
                            leases.size())
                    >= MAX_CONSUMED_RUNS) {
                return ArmResult.CAPACITY_EXHAUSTED;
            }
            leases.put(
                    key,
                    new Lease(
                            requiredLease,
                            LeaseState.ARMED));
            return ArmResult.ARMED;
        }
        return existing.layoutLease().equals(
                        requiredLease)
                        && existing.state()
                                == LeaseState.ARMED
                ? ArmResult.ALREADY_ARMED
                : ArmResult.CONFLICT;
    }

    public StartResult begin(
            UUID botId,
            long generation,
            InventoryLayoutCleanupLease layoutLease) {
        GenerationKey key = key(botId, generation);
        InventoryLayoutCleanupLease requiredLease =
                Objects.requireNonNull(
                        layoutLease, "layoutLease");
        Lease existing = leases.get(key);
        if (existing == null
                || !existing.layoutLease().equals(
                        requiredLease)) {
            return StartResult.STALE;
        }
        if (existing.state() != LeaseState.ARMED) {
            return StartResult.BUSY;
        }
        leases.put(
                key,
                new Lease(
                        requiredLease,
                        LeaseState.EXECUTING));
        return StartResult.STARTED;
    }

    public boolean complete(
            UUID botId, long generation, UUID runId) {
        GenerationKey key = key(botId, generation);
        UUID requiredRunId = runId(runId);
        Lease existing = leases.get(key);
        if (existing == null
                || !existing.layoutLease()
                        .runId()
                        .equals(requiredRunId)
                || existing.state()
                        != LeaseState.EXECUTING
                || !leases.remove(key, existing)) {
            return false;
        }
        rememberConsumed(key, requiredRunId);
        return true;
    }

    public boolean release(
            UUID botId, long generation, UUID runId) {
        GenerationKey key = key(botId, generation);
        UUID requiredRunId = runId(runId);
        Lease existing = leases.get(key);
        if (existing != null
                && existing.layoutLease()
                        .runId()
                        .equals(requiredRunId)
                && existing.state()
                        == LeaseState.ARMED
                && leases.remove(key, existing)) {
            rememberConsumed(key, requiredRunId);
            return true;
        }
        return false;
    }

    public void closeGeneration(
            UUID botId, long generation) {
        GenerationKey key = key(botId, generation);
        leases.remove(key);
        Set<UUID> removed = consumedRuns.remove(key);
        if (removed != null) {
            consumedRunCount = Math.subtractExact(
                    consumedRunCount,
                    removed.size());
        }
    }

    public void clear() {
        leases.clear();
        consumedRuns.clear();
        consumedRunCount = 0;
    }

    private boolean wasConsumed(
            GenerationKey key, UUID runId) {
        Set<UUID> consumed =
                consumedRuns.get(key);
        return consumed != null
                && consumed.contains(runId);
    }

    private void rememberConsumed(
            GenerationKey key, UUID runId) {
        if (consumedRuns
                .computeIfAbsent(
                        key, ignored -> new HashSet<>())
                .add(runId)) {
            consumedRunCount = Math.incrementExact(
                    consumedRunCount);
        }
    }

    private static GenerationKey key(
            UUID botId, long generation) {
        Objects.requireNonNull(botId, "botId");
        if (ZERO_UUID.equals(botId)
                || generation <= 0L) {
            throw new IllegalArgumentException(
                    "bot generation must be non-zero and positive");
        }
        return new GenerationKey(botId, generation);
    }

    private static UUID runId(UUID runId) {
        Objects.requireNonNull(runId, "runId");
        if (ZERO_UUID.equals(runId)) {
            throw new IllegalArgumentException(
                    "runId must be non-zero");
        }
        return runId;
    }

    public enum ArmResult {
        ARMED,
        ALREADY_ARMED,
        CONFLICT,
        CAPACITY_EXHAUSTED
    }

    public enum StartResult {
        STARTED,
        STALE,
        BUSY
    }

    private enum LeaseState {
        ARMED,
        EXECUTING
    }

    private record GenerationKey(
            UUID botId, long generation) {}

    private record Lease(
            InventoryLayoutCleanupLease layoutLease,
            LeaseState state) {}
}
