package io.github.greytaiwolf.botplayer.perception.event;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 尚未分配运行期序号的权威事件候选。
 */
public record SemanticEventDraft(
        UUID eventId,
        String dimension,
        long gameTick,
        Instant wallTime,
        SemanticEventType type,
        SemanticEventOutcome outcome,
        Optional<SpatialPoint> position,
        List<ActorRef> actors,
        List<String> objects,
        Map<String, String> delta,
        SemanticEventSource source,
        Set<PerceptionChannel> channels,
        float confidence,
        Set<String> tags,
        boolean changesWorld) {
    public static final int MAX_ACTORS = 16;
    public static final int MAX_OBJECTS = 32;
    public static final int MAX_DELTA_ENTRIES = 32;
    public static final int MAX_TAGS = 16;
    public static final int MAX_TEXT_LENGTH = 256;

    public SemanticEventDraft {
        Objects.requireNonNull(eventId, "eventId");
        dimension = requireText(dimension, "dimension", 128);
        if (gameTick < 0) {
            throw new IllegalArgumentException("gameTick must not be negative");
        }
        Objects.requireNonNull(wallTime, "wallTime");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(position, "position");
        actors = copyList(actors, "actors", MAX_ACTORS);
        objects = copyTextList(objects, "objects", MAX_OBJECTS);
        delta = copyTextMap(delta, "delta", MAX_DELTA_ENTRIES);
        Objects.requireNonNull(source, "source");
        channels = Set.copyOf(Objects.requireNonNull(channels, "channels"));
        if (channels.isEmpty()) {
            throw new IllegalArgumentException("channels must not be empty");
        }
        if (!Float.isFinite(confidence) || confidence < 0.0F || confidence > 1.0F) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        tags = copyTextSet(tags, "tags", MAX_TAGS);
    }

    public static SemanticEventDraft create(
            String dimension,
            long gameTick,
            SemanticEventType type,
            SemanticEventOutcome outcome,
            Optional<SpatialPoint> position,
            List<ActorRef> actors,
            List<String> objects,
            Map<String, String> delta,
            SemanticEventSource source,
            Set<PerceptionChannel> channels,
            float confidence,
            Set<String> tags,
            boolean changesWorld) {
        return new SemanticEventDraft(
                UUID.randomUUID(),
                dimension,
                gameTick,
                Instant.now(),
                type,
                outcome,
                position,
                actors,
                objects,
                delta,
                source,
                channels,
                confidence,
                tags,
                changesWorld);
    }

    private static <T> List<T> copyList(
            List<T> values, String field, int maximumSize) {
        Objects.requireNonNull(values, field);
        if (values.size() > maximumSize) {
            throw new IllegalArgumentException(
                    field + " exceeds maximum size " + maximumSize);
        }
        values.forEach(value -> Objects.requireNonNull(value, field + " entry"));
        return List.copyOf(values);
    }

    private static List<String> copyTextList(
            List<String> values, String field, int maximumSize) {
        List<String> copy = copyList(values, field, maximumSize);
        copy.forEach(value -> requireText(value, field + " entry", MAX_TEXT_LENGTH));
        return copy;
    }

    private static Map<String, String> copyTextMap(
            Map<String, String> values, String field, int maximumSize) {
        Objects.requireNonNull(values, field);
        if (values.size() > maximumSize) {
            throw new IllegalArgumentException(
                    field + " exceeds maximum size " + maximumSize);
        }
        values.forEach((key, value) -> {
            requireText(key, field + " key", 64);
            requireText(value, field + " value", MAX_TEXT_LENGTH);
        });
        return Map.copyOf(values);
    }

    private static Set<String> copyTextSet(
            Set<String> values, String field, int maximumSize) {
        Objects.requireNonNull(values, field);
        if (values.size() > maximumSize) {
            throw new IllegalArgumentException(
                    field + " exceeds maximum size " + maximumSize);
        }
        values.forEach(value -> requireText(value, field + " entry", 64));
        return Set.copyOf(values);
    }

    private static String requireText(String value, String field, int maximumLength) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(
                    field + " must contain 1-" + maximumLength + " characters");
        }
        return value;
    }
}
