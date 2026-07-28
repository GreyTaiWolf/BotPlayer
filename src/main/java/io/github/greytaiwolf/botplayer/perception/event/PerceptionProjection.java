package io.github.greytaiwolf.botplayer.perception.event;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 从权威事件投影到单个 bot 后允许进入认知层的脱敏内容。
 */
public record PerceptionProjection(
        long observedAtTick,
        SemanticEventType type,
        SemanticEventOutcome outcome,
        Optional<SpatialPoint> position,
        List<ActorRef> actors,
        List<String> objects,
        Map<String, String> delta,
        SemanticEventSource source,
        PerceptionChannel channel,
        float confidence,
        Set<String> tags) {
    public PerceptionProjection {
        if (observedAtTick < 0) {
            throw new IllegalArgumentException("observedAtTick must not be negative");
        }
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(position, "position");
        actors = List.copyOf(Objects.requireNonNull(actors, "actors"));
        objects = List.copyOf(Objects.requireNonNull(objects, "objects"));
        delta = Map.copyOf(Objects.requireNonNull(delta, "delta"));
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(channel, "channel");
        if (!Float.isFinite(confidence) || confidence < 0.0F || confidence > 1.0F) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        tags = Set.copyOf(Objects.requireNonNull(tags, "tags"));

        // 复用权威草稿的严格大小与文本边界，但不会把草稿本身交给认知层。
        new SemanticEventDraft(
                new java.util.UUID(0L, 1L),
                "botplayer:projection",
                observedAtTick,
                java.time.Instant.EPOCH,
                type,
                outcome,
                position,
                actors,
                objects,
                delta,
                source,
                Set.of(channel),
                confidence,
                tags,
                false);
    }

    public static PerceptionProjection full(
            AuthorityEvent authority,
            long observedAtTick,
            PerceptionChannel channel,
            float confidence) {
        SemanticEventDraft event = authority.event();
        Map<String, String> visibleDelta = event.delta().entrySet().stream()
                .filter(entry -> !entry.getKey().contains("revision."))
                .filter(entry -> !entry.getKey().startsWith("scope."))
                .filter(entry -> !entry.getKey().startsWith("audit."))
                .filter(entry -> !entry.getKey().startsWith("routing."))
                .filter(entry -> !entry.getKey().startsWith("target.bot_"))
                .filter(entry -> !entry.getKey().equals("action.generation"))
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, Map.Entry::getValue));
        return new PerceptionProjection(
                observedAtTick,
                event.type(),
                event.outcome(),
                event.position(),
                event.actors(),
                event.objects(),
                visibleDelta,
                event.source(),
                channel,
                confidence,
                event.tags());
    }

    /**
     * SELF 只证明事件涉及当前 bot，不证明其他参与者身份；因此仅保留 bot 自身
     * actor 与非身份字段。
     */
    public static PerceptionProjection self(
            AuthorityEvent authority,
            java.util.UUID botId,
            long observedAtTick,
            float confidence) {
        Objects.requireNonNull(botId, "botId");
        PerceptionProjection base = full(
                authority,
                observedAtTick,
                PerceptionChannel.SELF,
                confidence);
        List<ActorRef> selfActors = base.actors()
                .stream()
                .filter(actor -> actor.actorId().equals(botId))
                .toList();
        Map<String, String> safeDelta = base.delta()
                .entrySet()
                .stream()
                .filter(entry -> !isActorIdentityField(
                        entry.getKey()))
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue));
        return new PerceptionProjection(
                base.observedAtTick(),
                base.type(),
                base.outcome(),
                base.position(),
                selfActors,
                base.objects(),
                safeDelta,
                base.source(),
                base.channel(),
                base.confidence(),
                base.tags());
    }

    private static boolean isActorIdentityField(String key) {
        String normalized =
                key.toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("target.id")
                || normalized.equals("actor.id")
                || normalized.equals("attacker.id")
                || normalized.equals("victim.id")
                || normalized.equals("entity.id")
                || normalized.endsWith(".actor.id")
                || normalized.endsWith(".target.id")
                || normalized.endsWith(".attacker.id")
                || normalized.endsWith(".victim.id")
                || normalized.endsWith(".entity.id")
                || normalized.endsWith(".actor_id")
                || normalized.endsWith(".target_id")
                || normalized.endsWith(".attacker_id")
                || normalized.endsWith(".victim_id")
                || normalized.contains("uuid");
    }
}
