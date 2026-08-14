package io.github.greytaiwolf.botplayer.ai.review;

import io.github.greytaiwolf.botplayer.perception.BudgetKind;
import io.github.greytaiwolf.botplayer.perception.InventoryObservation;
import io.github.greytaiwolf.botplayer.perception.ObservationSnapshot;
import io.github.greytaiwolf.botplayer.perception.PerceptionBudgetReport;
import io.github.greytaiwolf.botplayer.perception.PerceptionLimits;
import io.github.greytaiwolf.botplayer.perception.PerceptionPressure;
import io.github.greytaiwolf.botplayer.perception.SelfObservation;
import io.github.greytaiwolf.botplayer.perception.VisionObservation;
import io.github.greytaiwolf.botplayer.perception.event.SpatialPoint;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AiReviewOnlySnapshotProjectionTest {
    private static final UUID BOT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000101");

    @Test
    void projectsOnlyTheFixedCurrentTickFields() {
        ObservationSnapshot snapshot = snapshot(100L);

        AiReviewOnlySnapshotProjection projection =
                AiReviewOnlySnapshotProjection.fromCurrent(snapshot, BOT_ID, 2L, 100L);

        Assertions.assertEquals(
                "snapshot_id=7\n"
                        + "tick=100\n"
                        + "dimension_id=minecraft:overworld\n"
                        + "health=19.5\n"
                        + "food=18\n"
                        + "air=294\n"
                        + "threat_count=0",
                projection.canonicalUserMessage());
        Assertions.assertTrue(AiReviewOnlySnapshotProjection.isCanonicalUserMessage(
                projection.canonicalUserMessage()));
        Assertions.assertFalse(projection.canonicalUserMessage().contains("123.25"));
        Assertions.assertFalse(projection.canonicalUserMessage().contains("inventory-sentinel"));
        Assertions.assertFalse(projection.toString().contains(BOT_ID.toString()));
        Assertions.assertFalse(projection.toString().contains("minecraft:overworld"));
        Assertions.assertFalse(projection.toString().contains("19.5"));
    }

    @Test
    void strictCurrentFactoryRejectsPredecessorAndNonCanonicalSnapshotText() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> AiReviewOnlySnapshotProjection.fromCurrent(
                        snapshot(99L), BOT_ID, 2L, 100L));
        Assertions.assertFalse(AiReviewOnlySnapshotProjection.isCanonicalUserMessage(
                "snapshot_id=7\n"
                        + "tick=100\n"
                        + "dimension_id=minecraft:overworld\n"
                        + "health=19.5\n"
                        + "food=18\n"
                        + "air=-0\n"
                        + "threat_count=0"));
        Assertions.assertFalse(AiReviewOnlySnapshotProjection.isCanonicalUserMessage(
                "snapshot_id=7\n"
                        + "tick=100\n"
                        + "dimension_id=minecraft:overworld\n"
                        + "health=19.5\n"
                        + "food=18\n"
                        + "air=294\n"
                        + "threat_count=0\n"
                        + "inventory=inventory-sentinel"));
    }

    @Test
    void requestAiReviewFactoryAcceptsOnlyCurrentOrImmediatelyPrecedingCompletedSnapshots() {
        AiReviewOnlySnapshotProjection current =
                AiReviewOnlySnapshotProjection.fromRequestAiReviewCompletedSnapshot(
                        snapshot(100L), BOT_ID, 2L, 100L);
        AiReviewOnlySnapshotProjection predecessor =
                AiReviewOnlySnapshotProjection.fromRequestAiReviewCompletedSnapshot(
                        snapshot(99L), BOT_ID, 2L, 100L);

        Assertions.assertEquals(7L, current.snapshotId());
        Assertions.assertEquals(100L, current.gameTick());
        Assertions.assertEquals(7L, predecessor.snapshotId());
        Assertions.assertEquals(99L, predecessor.gameTick());
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> AiReviewOnlySnapshotProjection.fromRequestAiReviewCompletedSnapshot(
                        snapshot(101L), BOT_ID, 2L, 100L));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> AiReviewOnlySnapshotProjection.fromRequestAiReviewCompletedSnapshot(
                        snapshot(98L), BOT_ID, 2L, 100L));
    }

    @Test
    void requestAiReviewFactoryRejectsWrongBotAndGeneration() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> AiReviewOnlySnapshotProjection.fromRequestAiReviewCompletedSnapshot(
                        snapshot(100L, new UUID(0L, 402L), 2L), BOT_ID, 2L, 100L));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> AiReviewOnlySnapshotProjection.fromRequestAiReviewCompletedSnapshot(
                        snapshot(100L, BOT_ID, 3L), BOT_ID, 2L, 100L));
    }

    private static ObservationSnapshot snapshot(long gameTick) {
        return snapshot(gameTick, BOT_ID, 2L);
    }

    private static ObservationSnapshot snapshot(long gameTick, UUID botId, long generation) {
        SelfObservation self = new SelfObservation(
                new SpatialPoint(123.25D, 64.0D, -90.75D),
                new SpatialPoint(0.0D, 0.0D, 0.0D),
                0.0F,
                0.0F,
                19.5F,
                20.0F,
                18,
                5.0F,
                294,
                300,
                false,
                false,
                true,
                false,
                false,
                "standing",
                Map.of("speed", "inventory-sentinel"));
        return new ObservationSnapshot(
                new UUID(0L, 401L),
                7L,
                0L,
                botId,
                generation,
                "minecraft:overworld",
                gameTick,
                self,
                new InventoryObservation(
                        "inventory-sentinel", 0, 0, 1, List.of(), false),
                VisionObservation.miss(16.0D),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                limits());
    }

    private static PerceptionLimits limits() {
        EnumMap<BudgetKind, Integer> zero = new EnumMap<>(BudgetKind.class);
        for (BudgetKind kind : BudgetKind.values()) {
            zero.put(kind, 0);
        }
        return new PerceptionLimits(
                PerceptionPressure.NORMAL,
                new PerceptionBudgetReport(zero, zero, false),
                Set.of(),
                Set.of(),
                Map.of(),
                false);
    }
}
