package io.github.greytaiwolf.botplayer.perception;

import io.github.greytaiwolf.botplayer.perception.event.ActorRef;
import io.github.greytaiwolf.botplayer.perception.event.PerceivedEvent;
import io.github.greytaiwolf.botplayer.perception.event.PerceptionChannel;
import io.github.greytaiwolf.botplayer.perception.event.SemanticEventDraft;
import io.github.greytaiwolf.botplayer.perception.event.SemanticEventOutcome;
import io.github.greytaiwolf.botplayer.perception.event.SemanticEventSource;
import io.github.greytaiwolf.botplayer.perception.event.SemanticEventType;
import io.github.greytaiwolf.botplayer.perception.event.SpatialPoint;
import io.github.greytaiwolf.botplayer.worldmodel.ActivityHypothesis;
import io.github.greytaiwolf.botplayer.worldmodel.FactValue;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class PerceptionDtoTest {
    private static final UUID BOT_ID = new UUID(0L, 301L);
    private static final UUID ACTOR_ID = new UUID(0L, 302L);

    @Test
    void semanticDraftCopiesAllMutableCollectionInputs() {
        List<ActorRef> actors = new ArrayList<>();
        actors.add(new ActorRef(ACTOR_ID, "minecraft:player", "Alex"));
        List<String> objects = new ArrayList<>(List.of("minecraft:stone"));
        Map<String, String> delta = new HashMap<>(Map.of("before", "air"));
        Set<PerceptionChannel> channels =
                new HashSet<>(Set.of(PerceptionChannel.VISUAL));
        Set<String> tags = new HashSet<>(Set.of("block"));

        SemanticEventDraft draft = new SemanticEventDraft(
                new UUID(0L, 1L),
                "minecraft:overworld",
                1L,
                Instant.EPOCH,
                SemanticEventType.BLOCK_PLACED,
                SemanticEventOutcome.COMMITTED,
                Optional.of(new SpatialPoint(1.0D, 64.0D, 1.0D)),
                actors,
                objects,
                delta,
                SemanticEventSource.NEOFORGE_EVENT,
                channels,
                1.0F,
                tags,
                true);
        actors.clear();
        objects.clear();
        delta.clear();
        channels.clear();
        tags.clear();

        Assertions.assertEquals(1, draft.actors().size());
        Assertions.assertEquals(List.of("minecraft:stone"), draft.objects());
        Assertions.assertEquals(Map.of("before", "air"), draft.delta());
        Assertions.assertEquals(Set.of(PerceptionChannel.VISUAL), draft.channels());
        Assertions.assertEquals(Set.of("block"), draft.tags());
        Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> draft.objects().add("minecraft:dirt"));
        Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> draft.delta().put("after", "stone"));
    }

    @Test
    void observationSnapshotAndNestedDtosDoNotRetainMutableInputs() {
        Map<String, String> effects = new HashMap<>(Map.of("speed", "1"));
        SelfObservation self = new SelfObservation(
                new SpatialPoint(0.0D, 64.0D, 0.0D),
                new SpatialPoint(0.0D, 0.0D, 0.0D),
                0.0F,
                0.0F,
                20.0F,
                20.0F,
                20,
                5.0F,
                300,
                300,
                false,
                false,
                true,
                false,
                false,
                "standing",
                effects);
        List<ItemObservation> itemInputs =
                new ArrayList<>(List.of(new ItemObservation(
                        0, "minecraft:stone", 1, 0, 0)));
        InventoryObservation inventory = new InventoryObservation(
                "inventory-digest", 0, 1, 41, itemInputs, false);
        List<EntityObservation> entities = new ArrayList<>();
        entities.add(new EntityObservation(
                ACTOR_ID,
                "minecraft:player",
                "neutral",
                new SpatialPoint(1.0D, 64.0D, 1.0D),
                new SpatialPoint(0.0D, 0.0D, 0.0D),
                1.5D,
                true,
                true));
        List<PerceivedEvent> events =
                new ArrayList<>(List.of(perceivedEvent()));
        List<ActivityHypothesis> activities =
                new ArrayList<>(List.of(ActivityHypothesis.unknown(ACTOR_ID, 1L)));
        Set<SensorId> sampled =
                new HashSet<>(Set.of(SensorId.SELF_STATE));
        Map<SensorId, Long> successfulSampleTicks =
                new HashMap<>(Map.of(SensorId.SELF_STATE, 1L));
        EnumMap<BudgetKind, Integer> counters =
                new EnumMap<>(BudgetKind.class);
        for (BudgetKind kind : BudgetKind.values()) {
            counters.put(kind, 0);
        }
        PerceptionBudgetReport report =
                new PerceptionBudgetReport(counters, counters, false);
        PerceptionLimits limits = new PerceptionLimits(
                PerceptionPressure.NORMAL,
                report,
                sampled,
                Set.of(),
                successfulSampleTicks,
                false);

        ObservationSnapshot snapshot = new ObservationSnapshot(
                new UUID(0L, 400L),
                1L,
                0L,
                BOT_ID,
                1L,
                "minecraft:overworld",
                1L,
                self,
                inventory,
                VisionObservation.miss(16.0D),
                entities,
                List.of(),
                List.of(),
                List.of(),
                events,
                activities,
                limits);
        effects.clear();
        itemInputs.clear();
        entities.clear();
        events.clear();
        activities.clear();
        sampled.clear();
        successfulSampleTicks.put(SensorId.SELF_STATE, 99L);
        counters.put(BudgetKind.BLOCK_READ, 99);

        Assertions.assertEquals(Map.of("speed", "1"), snapshot.self().effects());
        Assertions.assertEquals(1, snapshot.inventory().items().size());
        Assertions.assertEquals(1, snapshot.entities().size());
        Assertions.assertEquals(1, snapshot.recentEvents().size());
        Assertions.assertEquals(1, snapshot.activities().size());
        Assertions.assertEquals(Set.of(SensorId.SELF_STATE), snapshot.limits().sampledSensors());
        Assertions.assertEquals(
                Map.of(SensorId.SELF_STATE, 1L),
                snapshot.limits().lastSuccessfulSampleTicks());
        Assertions.assertEquals(0, snapshot.limits().budget().used().get(BudgetKind.BLOCK_READ));
        Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.entities().clear());
        Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.limits()
                        .lastSuccessfulSampleTicks()
                        .put(SensorId.VISION_RAY, 2L));
    }

    @Test
    void perceptionLimitsRejectNegativeSuccessfulSampleTicks() {
        EnumMap<BudgetKind, Integer> counters =
                new EnumMap<>(BudgetKind.class);
        for (BudgetKind kind : BudgetKind.values()) {
            counters.put(kind, 0);
        }
        PerceptionBudgetReport report =
                new PerceptionBudgetReport(counters, counters, false);

        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new PerceptionLimits(
                        PerceptionPressure.NORMAL,
                        report,
                        Set.of(),
                        Set.of(),
                        Map.of(SensorId.VISION_RAY, -1L),
                        false));
    }

    @Test
    void factValuesCopyTheirInputMapAndRejectOversizedPayloads() {
        Map<String, String> fields =
                new HashMap<>(Map.of("block", "minecraft:stone"));
        FactValue value = new FactValue(fields);
        fields.clear();

        Assertions.assertEquals(
                Map.of("block", "minecraft:stone"), value.fields());
        Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> value.fields().put("other", "value"));

        Map<String, String> oversized = new HashMap<>();
        for (int index = 0; index <= FactValue.MAX_FIELDS; index++) {
            oversized.put("key-" + index, "value");
        }
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new FactValue(oversized));
    }

    private static PerceivedEvent perceivedEvent() {
        return new PerceivedEvent(
                1L,
                BOT_ID,
                1L,
                new UUID(0L, 1L),
                1L,
                SemanticEventType.BLOCK_CHANGED,
                SemanticEventOutcome.COMMITTED,
                Optional.empty(),
                List.of(new ActorRef(
                        ACTOR_ID, "minecraft:player", "Alex")),
                List.of("minecraft:stone"),
                Map.of("block", "minecraft:stone"),
                SemanticEventSource.SENSOR_OBSERVATION,
                PerceptionChannel.VISUAL,
                1.0F,
                Set.of("test"));
    }
}
