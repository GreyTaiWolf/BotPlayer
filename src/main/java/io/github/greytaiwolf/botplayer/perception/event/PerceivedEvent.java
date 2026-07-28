package io.github.greytaiwolf.botplayer.perception.event;

import java.util.Objects;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 某个 generation 的 bot 通过明确通道实际获得的事件。
 */
public record PerceivedEvent(
        long perceivedSeq,
        UUID botId,
        long botGeneration,
        UUID authorityEventId,
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
    public PerceivedEvent {
        if (perceivedSeq <= 0) {
            throw new IllegalArgumentException("perceivedSeq must be positive");
        }
        Objects.requireNonNull(botId, "botId");
        if (botGeneration <= 0) {
            throw new IllegalArgumentException("botGeneration must be positive");
        }
        Objects.requireNonNull(authorityEventId, "authorityEventId");
        PerceptionProjection projection = new PerceptionProjection(
                observedAtTick,
                type,
                outcome,
                position,
                actors,
                objects,
                delta,
                source,
                channel,
                confidence,
                tags);
        observedAtTick = projection.observedAtTick();
        type = projection.type();
        outcome = projection.outcome();
        position = projection.position();
        actors = projection.actors();
        objects = projection.objects();
        delta = projection.delta();
        source = projection.source();
        channel = projection.channel();
        confidence = projection.confidence();
        tags = projection.tags();
    }
}
