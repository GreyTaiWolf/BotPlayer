package io.github.greytaiwolf.botplayer.worldmodel;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 单个 bot 的有界短期世界模型。
 */
public final class WorldModelService {
    public static final int MAX_FACT_CAPACITY = 65_536;
    public static final int MAX_EXPIRATIONS_PER_CALL = 256;

    private final int capacity;
    private final LinkedHashMap<UUID, WorldFact> facts = new LinkedHashMap<>();
    private final Map<FactKey, UUID> currentByKey = new LinkedHashMap<>();
    private final Map<RevisionScope, Set<UUID>> activeByScope =
            new HashMap<>();
    private final NavigableMap<Long, Set<UUID>> expiriesByTick =
            new TreeMap<>();
    private final Map<UUID, Long> expiryByFact = new HashMap<>();
    private final Deque<UUID> evictionOrder = new ArrayDeque<>();
    private long nextFactSequence = 1L;
    private long droppedFacts;

    public WorldModelService(int capacity) {
        if (capacity < 1 || capacity > MAX_FACT_CAPACITY) {
            throw new IllegalArgumentException(
                    "capacity must be between 1 and " + MAX_FACT_CAPACITY);
        }
        this.capacity = capacity;
    }

    public WorldFact observe(WorldFactDraft draft) {
        Objects.requireNonNull(draft, "draft");
        UUID currentId = currentByKey.get(draft.key());
        WorldFact current = currentId == null ? null : facts.get(currentId);
        if (current != null && current.value().equals(draft.value())) {
            if (current.status() == FactStatus.ACTIVE) {
                deindexActive(current);
                unscheduleExpiry(current.factId());
            }
            WorldFact refreshed = refresh(current, draft);
            facts.put(refreshed.factId(), refreshed);
            if (refreshed.status() == FactStatus.ACTIVE) {
                indexActive(refreshed);
                scheduleExpiry(refreshed);
            }
            return refreshed;
        }
        if (current != null) {
            updateStatus(current, FactStatus.SUPERSEDED);
        }

        WorldFact created = new WorldFact(
                nextFactId(draft),
                draft.key(),
                draft.value(),
                draft.scope(),
                draft.revision(),
                draft.observedTick(),
                draft.observedTick(),
                draft.confidence(),
                draft.source(),
                draft.evidence(),
                draft.initialStatus(),
                draft.invalidation());
        facts.put(created.factId(), created);
        evictionOrder.addLast(created.factId());
        if (created.status() == FactStatus.ACTIVE) {
            currentByKey.put(created.key(), created.factId());
            indexActive(created);
            scheduleExpiry(created);
        }
        trim();
        return created;
    }

    public int invalidateScope(
            RevisionScope scope, RevisionStamp revision, long tick, boolean hidden) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(revision, "revision");
        if (tick < 0) {
            throw new IllegalArgumentException("tick must not be negative");
        }
        FactStatus next =
                hidden ? FactStatus.STALE_UNKNOWN : FactStatus.STALE;
        int changed = 0;
        Set<UUID> scoped = activeByScope.get(scope);
        if (scoped == null || scoped.isEmpty()) {
            return 0;
        }
        for (UUID factId : List.copyOf(scoped)) {
            WorldFact fact = facts.get(factId);
            if (fact != null
                    && fact.status() == FactStatus.ACTIVE
                    && fact.invalidation().staleOnScopeChange()
                    && revision.target() > fact.revision().target()) {
                updateStatus(fact, next);
                changed++;
            }
        }
        return changed;
    }

    public int expire(long currentTick) {
        return expire(
                currentTick, MAX_EXPIRATIONS_PER_CALL);
    }

    public int expire(
            long currentTick, int maximumFacts) {
        if (currentTick < 0) {
            throw new IllegalArgumentException("currentTick must not be negative");
        }
        if (maximumFacts < 1
                || maximumFacts > MAX_EXPIRATIONS_PER_CALL) {
            throw new IllegalArgumentException(
                    "maximumFacts must be between 1 and "
                            + MAX_EXPIRATIONS_PER_CALL);
        }
        int changed = 0;
        int inspected = 0;
        while (!expiriesByTick.isEmpty()
                && expiriesByTick.firstKey() <= currentTick
                && inspected < maximumFacts) {
            Map.Entry<Long, Set<UUID>> due =
                    expiriesByTick.firstEntry();
            if (due == null || due.getValue().isEmpty()) {
                if (due != null) {
                    expiriesByTick.remove(due.getKey());
                }
                break;
            }
            UUID factId =
                    due.getValue().iterator().next();
            due.getValue().remove(factId);
            inspected++;
            Long indexedTick = expiryByFact.get(factId);
            WorldFact fact = facts.get(factId);
            if (indexedTick != null
                    && indexedTick.equals(due.getKey())
                    && fact != null
                    && fact.status() == FactStatus.ACTIVE) {
                updateStatus(fact, FactStatus.STALE_UNKNOWN);
                changed++;
            } else {
                expiryByFact.remove(factId);
            }
            if (due.getValue().isEmpty()) {
                expiriesByTick.remove(
                        due.getKey(), due.getValue());
            }
        }
        return changed;
    }

    public Optional<WorldFact> current(FactKey key) {
        Objects.requireNonNull(key, "key");
        UUID factId = currentByKey.get(key);
        return Optional.ofNullable(factId == null ? null : facts.get(factId));
    }

    public List<WorldFact> recent(int limit) {
        if (limit < 0 || limit > capacity) {
            throw new IllegalArgumentException(
                    "limit must be between 0 and " + capacity);
        }
        if (limit == 0) {
            return List.of();
        }
        List<WorldFact> ordered = new ArrayList<>(facts.values());
        ordered.sort(Comparator.comparingLong(WorldFact::lastConfirmedTick)
                .thenComparing(WorldFact::factId));
        return ordered.subList(Math.max(0, ordered.size() - limit), ordered.size());
    }

    public long droppedFacts() {
        return droppedFacts;
    }

    private static WorldFact refresh(WorldFact current, WorldFactDraft draft) {
        List<EvidenceRef> evidence = new ArrayList<>(current.evidence());
        for (EvidenceRef candidate : draft.evidence()) {
            if (!evidence.contains(candidate)) {
                if (evidence.size() == WorldFact.MAX_EVIDENCE) {
                    evidence.remove(0);
                }
                evidence.add(candidate);
            }
        }
        return new WorldFact(
                current.factId(),
                current.key(),
                current.value(),
                draft.scope(),
                draft.revision(),
                current.firstObservedTick(),
                draft.observedTick(),
                Math.max(current.confidence(), draft.confidence()),
                draft.source(),
                evidence,
                current.status() == FactStatus.ACTIVE
                                || draft.initialStatus()
                                        == FactStatus.ACTIVE
                        ? FactStatus.ACTIVE
                        : draft.initialStatus(),
                draft.invalidation());
    }

    private static WorldFact withStatus(WorldFact fact, FactStatus status) {
        return new WorldFact(
                fact.factId(),
                fact.key(),
                fact.value(),
                fact.scope(),
                fact.revision(),
                fact.firstObservedTick(),
                fact.lastConfirmedTick(),
                fact.confidence(),
                fact.source(),
                fact.evidence(),
                status,
                fact.invalidation());
    }

    private void updateStatus(
            WorldFact fact, FactStatus status) {
        if (fact.status() == status) {
            return;
        }
        if (fact.status() == FactStatus.ACTIVE) {
            deindexActive(fact);
            unscheduleExpiry(fact.factId());
            currentByKey.remove(fact.key(), fact.factId());
        }
        WorldFact updated = withStatus(fact, status);
        facts.put(updated.factId(), updated);
        if (status == FactStatus.ACTIVE) {
            currentByKey.put(updated.key(), updated.factId());
            indexActive(updated);
            scheduleExpiry(updated);
        }
    }

    private void indexActive(WorldFact fact) {
        activeByScope.computeIfAbsent(
                        fact.scope(),
                        ignored -> new LinkedHashSet<>())
                .add(fact.factId());
    }

    private void deindexActive(WorldFact fact) {
        Set<UUID> scoped = activeByScope.get(fact.scope());
        if (scoped == null) {
            return;
        }
        scoped.remove(fact.factId());
        if (scoped.isEmpty()) {
            activeByScope.remove(fact.scope());
        }
    }

    private void scheduleExpiry(WorldFact fact) {
        unscheduleExpiry(fact.factId());
        long expiryTick = expiryTick(fact);
        if (expiryTick >= 0L) {
            expiryByFact.put(fact.factId(), expiryTick);
            expiriesByTick.computeIfAbsent(
                            expiryTick,
                            ignored -> new LinkedHashSet<>())
                    .add(fact.factId());
        }
    }

    private void unscheduleExpiry(UUID factId) {
        Long previous = expiryByFact.remove(factId);
        if (previous == null) {
            return;
        }
        Set<UUID> scheduled = expiriesByTick.get(previous);
        if (scheduled == null) {
            return;
        }
        scheduled.remove(factId);
        if (scheduled.isEmpty()) {
            expiriesByTick.remove(previous);
        }
    }

    private static long expiryTick(WorldFact fact) {
        long ttl = fact.invalidation().ttlTicks();
        if (ttl <= 0L) {
            return -1L;
        }
        try {
            return Math.addExact(
                    Math.addExact(fact.lastConfirmedTick(), ttl),
                    1L);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private UUID nextFactId(WorldFactDraft draft) {
        StringBuilder canonical = new StringBuilder()
                .append(nextFactSequence++)
                .append('\0')
                .append(draft.key().namespace())
                .append('\0')
                .append(draft.key().dimension())
                .append('\0')
                .append(draft.key().subject())
                .append('\0')
                .append(draft.observedTick());
        draft.value().fields().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> canonical
                        .append('\0')
                        .append(entry.getKey())
                        .append('=')
                        .append(entry.getValue()));
        return UUID.nameUUIDFromBytes(
                canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void trim() {
        while (facts.size() > capacity) {
            UUID victim;
            do {
                victim = evictionOrder.removeFirst();
            } while (!facts.containsKey(victim));
            WorldFact removed = facts.remove(victim);
            if (removed != null) {
                if (removed.status() == FactStatus.ACTIVE) {
                    deindexActive(removed);
                    unscheduleExpiry(removed.factId());
                }
                currentByKey.remove(removed.key(), victim);
                droppedFacts++;
            }
        }
    }

}
