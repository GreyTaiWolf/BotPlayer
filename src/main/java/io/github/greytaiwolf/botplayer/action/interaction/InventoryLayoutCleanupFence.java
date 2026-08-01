package io.github.greytaiwolf.botplayer.action.interaction;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Generation-scoped identity fence for lifecycle inventory compensation.
 *
 * <p>The fence is owned by the server thread. A run must be armed before it
 * can begin compensation. A physically untouched BLOCKED attempt may be
 * re-armed; terminal results are retained as bounded idempotent receipts, so
 * exact and stale replays cannot invoke the mutation handler a second time.
 */
public final class InventoryLayoutCleanupFence {
    public static final int MAX_CONSUMED_RUNS = 4096;
    private static final UUID ZERO_UUID = new UUID(0L, 0L);

    private final Map<GenerationKey, Lease> leases =
            new HashMap<>();
    private final Map<GenerationKey, Map<UUID, TerminalLease>>
            terminalRuns = new HashMap<>();
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
            UUID botId,
            long generation,
            UUID runId,
            InventoryLayoutCleanupResult result) {
        GenerationKey key = key(botId, generation);
        UUID requiredRunId = runId(runId);
        InventoryLayoutCleanupResult requiredResult =
                Objects.requireNonNull(result, "result");
        if (requiredResult
                == InventoryLayoutCleanupResult.BLOCKED) {
            throw new IllegalArgumentException(
                    "BLOCKED cleanup must be re-armed, not completed");
        }
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
        rememberTerminal(
                key,
                existing.layoutLease(),
                Optional.of(requiredResult));
        return true;
    }

    /**
     * 瞬时阻塞没有执行物理补偿；把精确 lease 放回 ARMED，允许后续 Tick 重试。
     */
    public boolean retry(
            UUID botId, long generation, UUID runId) {
        GenerationKey key = key(botId, generation);
        UUID requiredRunId = runId(runId);
        Lease existing = leases.get(key);
        if (existing == null
                || !existing.layoutLease()
                        .runId()
                        .equals(requiredRunId)
                || existing.state()
                        != LeaseState.EXECUTING) {
            return false;
        }
        return leases.replace(
                key,
                existing,
                new Lease(
                        existing.layoutLease(),
                        LeaseState.ARMED));
    }

    /**
     * 返回同一精确 lease 已完成的不可变回执；不同 payload 不能复用旧 runId。
     */
    public Optional<InventoryLayoutCleanupResult>
            completedResult(
                    UUID botId,
                    long generation,
                    InventoryLayoutCleanupLease layoutLease) {
        GenerationKey key = key(botId, generation);
        InventoryLayoutCleanupLease requiredLease =
                Objects.requireNonNull(
                        layoutLease, "layoutLease");
        Map<UUID, TerminalLease> records =
                terminalRuns.get(key);
        if (records == null) {
            return Optional.empty();
        }
        TerminalLease terminal = records.get(
                requiredLease.runId());
        return terminal != null
                        && terminal.layoutLease()
                                .equals(requiredLease)
                ? terminal.result()
                : Optional.empty();
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
            rememberTerminal(
                    key,
                    existing.layoutLease(),
                    Optional.empty());
            return true;
        }
        return false;
    }

    public void closeGeneration(
            UUID botId, long generation) {
        GenerationKey key = key(botId, generation);
        leases.remove(key);
        Map<UUID, TerminalLease> removed =
                terminalRuns.remove(key);
        if (removed != null) {
            consumedRunCount = Math.subtractExact(
                    consumedRunCount,
                    removed.size());
        }
    }

    public void clear() {
        leases.clear();
        terminalRuns.clear();
        consumedRunCount = 0;
    }

    private boolean wasConsumed(
            GenerationKey key, UUID runId) {
        Map<UUID, TerminalLease> terminal =
                terminalRuns.get(key);
        return terminal != null
                && terminal.containsKey(runId);
    }

    private void rememberTerminal(
            GenerationKey key,
            InventoryLayoutCleanupLease layoutLease,
            Optional<InventoryLayoutCleanupResult> result) {
        TerminalLease previous = terminalRuns
                .computeIfAbsent(
                        key, ignored -> new HashMap<>())
                .putIfAbsent(
                        layoutLease.runId(),
                        new TerminalLease(
                                layoutLease, result));
        if (previous == null) {
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

    private record TerminalLease(
            InventoryLayoutCleanupLease layoutLease,
            Optional<InventoryLayoutCleanupResult> result) {
        private TerminalLease {
            Objects.requireNonNull(
                    layoutLease, "layoutLease");
            result = Objects.requireNonNull(
                    result, "result");
        }
    }
}
