package io.github.greytaiwolf.botplayer.worldmodel;

import io.github.greytaiwolf.botplayer.perception.event.ActorRef;
import io.github.greytaiwolf.botplayer.perception.event.PerceivedEvent;
import io.github.greytaiwolf.botplayer.perception.event.SemanticEventOutcome;
import io.github.greytaiwolf.botplayer.perception.event.SemanticEventType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 对感知事件执行确定性的滑动窗口活动推断。
 */
public final class ActivityInferenceService {
    public static final int MAX_WINDOW_TICKS = 20 * 60 * 10;
    public static final int MAX_EVENTS = 8_192;

    private final int windowTicks;
    private final int maximumEvents;

    public ActivityInferenceService(int windowTicks, int maximumEvents) {
        if (windowTicks < 1 || windowTicks > MAX_WINDOW_TICKS) {
            throw new IllegalArgumentException(
                    "windowTicks must be between 1 and " + MAX_WINDOW_TICKS);
        }
        if (maximumEvents < 1 || maximumEvents > MAX_EVENTS) {
            throw new IllegalArgumentException(
                    "maximumEvents must be between 1 and " + MAX_EVENTS);
        }
        this.windowTicks = windowTicks;
        this.maximumEvents = maximumEvents;
    }

    public ActivityHypothesis infer(
            UUID actorId, List<PerceivedEvent> input, long currentTick) {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(input, "input");
        if (currentTick < 0) {
            throw new IllegalArgumentException("currentTick must not be negative");
        }

        return inferCanonical(
                actorId,
                canonicalEvents(input, currentTick)
                        .stream()
                        .filter(event -> involves(event, actorId))
                        .toList(),
                currentTick);
    }

    /**
     * 多 actor 共用一次规范化/去重，避免每个快照重复排序同一事件窗口。
     */
    public Map<UUID, ActivityHypothesis> inferAll(
            Collection<UUID> actorIds,
            List<PerceivedEvent> input,
            long currentTick) {
        Objects.requireNonNull(actorIds, "actorIds");
        List<PerceivedEvent> events =
                canonicalEvents(input, currentTick);
        List<UUID> requested = actorIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted(Comparator.comparing(UUID::toString))
                .toList();
        Map<UUID, List<PerceivedEvent>> byActor =
                new LinkedHashMap<>();
        requested.forEach(actorId ->
                byActor.put(actorId, new ArrayList<>()));
        for (PerceivedEvent event : events) {
            event.actors().stream()
                    .map(ActorRef::actorId)
                    .distinct()
                    .forEach(actorId -> {
                        List<PerceivedEvent> actorEvents =
                                byActor.get(actorId);
                        if (actorEvents != null) {
                            actorEvents.add(event);
                        }
                    });
        }
        Map<UUID, ActivityHypothesis> result =
                new LinkedHashMap<>();
        requested.forEach(actorId -> result.put(
                actorId,
                inferCanonical(
                        actorId,
                        byActor.get(actorId),
                        currentTick)));
        return Map.copyOf(result);
    }

    private ActivityHypothesis inferCanonical(
            UUID actorId,
            List<PerceivedEvent> events,
            long currentTick) {
        EnumMap<ActivityType, Candidate> candidates =
                new EnumMap<>(ActivityType.class);
        for (PerceivedEvent event : events) {
            if (event.outcome()
                    != SemanticEventOutcome.COMMITTED) {
                continue;
            }
            applyEvidence(candidates, event, currentTick);
        }
        Candidate best = candidates.values().stream()
                .max(Comparator.comparingDouble(Candidate::score)
                        .thenComparingInt(candidate ->
                                candidate.type().ordinal()))
                .orElse(null);
        if (best == null || best.score() <= 0.0D) {
            return ActivityHypothesis.unknown(actorId, currentTick);
        }

        float confidence = (float) Math.max(
                0.0D,
                Math.min(1.0D, best.score() / (best.score() + 3.0D)));
        List<EvidenceRef> evidence = best.events().stream()
                .sorted(Comparator.comparingLong(PerceivedEvent::observedAtTick)
                        .thenComparingLong(PerceivedEvent::perceivedSeq))
                .limit(WorldFact.MAX_EVIDENCE)
                .map(ActivityInferenceService::evidence)
                .toList();
        long startedTick = best.events().stream()
                .mapToLong(PerceivedEvent::observedAtTick)
                .min()
                .orElse(currentTick);
        long lastTick = best.events().stream()
                .mapToLong(PerceivedEvent::observedAtTick)
                .max()
                .orElse(currentTick);
        return new ActivityHypothesis(
                actorId,
                best.type(),
                startedTick,
                lastTick,
                confidence,
                ActivityConfidenceBand.fromConfidence(confidence),
                evidence);
    }

    private List<PerceivedEvent> canonicalEvents(
            List<PerceivedEvent> input, long currentTick) {
        if (input.size() > maximumEvents) {
            throw new IllegalArgumentException(
                    "input exceeds maximum size " + maximumEvents);
        }
        Map<UUID, PerceivedEvent> unique = new LinkedHashMap<>();
        input.stream()
                .filter(Objects::nonNull)
                .filter(event -> event.observedAtTick() <= currentTick)
                .filter(event -> currentTick - event.observedAtTick() <= windowTicks)
                .sorted(Comparator.comparingLong(PerceivedEvent::observedAtTick)
                        .thenComparingLong(PerceivedEvent::perceivedSeq)
                        .thenComparing(event ->
                                event.authorityEventId().toString()))
                .forEach(event -> unique.putIfAbsent(
                        event.authorityEventId(),
                        event));
        return List.copyOf(unique.values());
    }

    private void applyEvidence(
            EnumMap<ActivityType, Candidate> candidates,
            PerceivedEvent event,
            long currentTick) {
        boolean farming = event.tags().contains("activity:farming");
        switch (event.type()) {
            case BLOCK_BROKEN -> add(
                    candidates,
                    farming ? ActivityType.FARMING : ActivityType.MINING,
                    event,
                    weight(event, currentTick, farming ? 3.4D : 3.0D));
            case BLOCK_PLACED -> add(
                    candidates,
                    farming ? ActivityType.FARMING : ActivityType.BUILDING,
                    event,
                    weight(event, currentTick, farming ? 3.4D : 2.8D));
            case ENTITY_DAMAGED, ENTITY_DIED -> add(
                    candidates,
                    ActivityType.COMBAT,
                    event,
                    weight(
                            event,
                            currentTick,
                            event.type() == SemanticEventType.ENTITY_DIED
                                    ? 3.5D
                                    : 2.3D));
            case REGION_ENTERED -> add(
                    candidates,
                    ActivityType.EXPLORING,
                    event,
                    weight(event, currentTick, 1.5D));
            case ITEM_PICKED_UP -> applyPickupEvidence(
                    candidates, event, currentTick);
            case ACTION_COMPLETED -> applyActionEvidence(
                    candidates, event, currentTick);
            case PLAYER_CORRECTION -> applyCorrection(
                    candidates, event, currentTick);
            default -> {
                // 其他事件只保留在“刚才发生了什么”的证据流中，不强行推断活动。
            }
        }
    }

    private void applyPickupEvidence(
            EnumMap<ActivityType, Candidate> candidates,
            PerceivedEvent event,
            long currentTick) {
        if (event.tags().contains("item:crop")) {
            add(
                    candidates,
                    ActivityType.FARMING,
                    event,
                    weight(event, currentTick, 1.2D));
        } else if (event.tags().contains("item:ore")) {
            add(
                    candidates,
                    ActivityType.MINING,
                    event,
                    weight(event, currentTick, 1.2D));
        }
    }

    private void applyActionEvidence(
            EnumMap<ActivityType, Candidate> candidates,
            PerceivedEvent event,
            long currentTick) {
        String kind = event.delta().getOrDefault("action.kind", "");
        ActivityType type = switch (kind) {
            case "break_block" -> ActivityType.MINING;
            case "attack_entity" -> ActivityType.COMBAT;
            default -> ActivityType.UNKNOWN;
        };
        if (type != ActivityType.UNKNOWN) {
            add(
                    candidates,
                    type,
                    event,
                    weight(event, currentTick, 3.6D));
        }
    }

    private void applyCorrection(
            EnumMap<ActivityType, Candidate> candidates,
            PerceivedEvent event,
            long currentTick) {
        String corrected = event.delta().getOrDefault("corrected_activity", "");
        if (corrected.equals("none")) {
            candidates.clear();
            return;
        }
        try {
            ActivityType type = ActivityType.valueOf(corrected.toUpperCase(java.util.Locale.ROOT));
            if (type != ActivityType.UNKNOWN) {
                candidates.clear();
                add(
                        candidates,
                        type,
                        event,
                        weight(event, currentTick, 20.0D));
            }
        } catch (IllegalArgumentException ignored) {
            // 非法标注不会污染已有证据。
        }
    }

    private double weight(
            PerceivedEvent event, long currentTick, double baseWeight) {
        long age = currentTick - event.observedAtTick();
        double decay = 1.0D - (double) age / (double) windowTicks;
        return baseWeight
                * Math.max(0.1D, decay)
                * event.confidence();
    }

    private static void add(
            EnumMap<ActivityType, Candidate> candidates,
            ActivityType type,
            PerceivedEvent event,
            double score) {
        candidates.computeIfAbsent(type, Candidate::new).add(event, score);
    }

    private static boolean involves(PerceivedEvent event, UUID actorId) {
        return event.actors().stream()
                .map(ActorRef::actorId)
                .anyMatch(actorId::equals);
    }

    private static EvidenceRef evidence(PerceivedEvent event) {
        return new EvidenceRef(
                "perceived_event",
                event.authorityEventId(),
                event.perceivedSeq(),
                event.type().name().toLowerCase(java.util.Locale.ROOT));
    }

    private static final class Candidate {
        private final ActivityType type;
        private final List<PerceivedEvent> events = new ArrayList<>();
        private double score;

        private Candidate(ActivityType type) {
            this.type = type;
        }

        private void add(PerceivedEvent event, double amount) {
            events.add(event);
            score += amount;
        }

        private ActivityType type() {
            return type;
        }

        private List<PerceivedEvent> events() {
            return events;
        }

        private double score() {
            return score;
        }
    }
}
