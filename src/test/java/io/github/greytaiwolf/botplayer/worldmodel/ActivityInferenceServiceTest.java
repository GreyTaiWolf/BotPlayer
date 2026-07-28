package io.github.greytaiwolf.botplayer.worldmodel;

import io.github.greytaiwolf.botplayer.perception.event.ActorRef;
import io.github.greytaiwolf.botplayer.perception.event.PerceivedEvent;
import io.github.greytaiwolf.botplayer.perception.event.PerceptionChannel;
import io.github.greytaiwolf.botplayer.perception.event.SemanticEventOutcome;
import io.github.greytaiwolf.botplayer.perception.event.SemanticEventSource;
import io.github.greytaiwolf.botplayer.perception.event.SemanticEventType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ActivityInferenceServiceTest {
    private static final UUID BOT_ID = new UUID(0L, 201L);
    private static final UUID ACTOR_ID = new UUID(0L, 202L);
    private static final UUID OTHER_ACTOR_ID = new UUID(0L, 203L);

    @Test
    void shuffledAndDuplicatedReplayProducesTheSameHypothesis() {
        ActivityInferenceService inference =
                new ActivityInferenceService(200, 16);
        PerceivedEvent broken = event(
                1L,
                1L,
                100L,
                ACTOR_ID,
                SemanticEventType.BLOCK_BROKEN,
                SemanticEventOutcome.COMMITTED,
                1.0F,
                Map.of(),
                Set.of());
        PerceivedEvent action = event(
                2L,
                2L,
                104L,
                ACTOR_ID,
                SemanticEventType.ACTION_COMPLETED,
                SemanticEventOutcome.COMMITTED,
                1.0F,
                Map.of("action.kind", "break_block"),
                Set.of());
        PerceivedEvent placed = event(
                3L,
                3L,
                103L,
                ACTOR_ID,
                SemanticEventType.BLOCK_PLACED,
                SemanticEventOutcome.COMMITTED,
                1.0F,
                Map.of(),
                Set.of());
        PerceivedEvent duplicateAction = event(
                20L,
                2L,
                104L,
                ACTOR_ID,
                SemanticEventType.ACTION_COMPLETED,
                SemanticEventOutcome.COMMITTED,
                1.0F,
                Map.of("action.kind", "break_block"),
                Set.of());

        ActivityHypothesis canonical =
                inference.infer(ACTOR_ID, List.of(broken, placed, action), 105L);
        ActivityHypothesis replayed = inference.infer(
                ACTOR_ID,
                List.of(duplicateAction, action, placed, broken),
                105L);

        Assertions.assertEquals(canonical, replayed);
        Assertions.assertEquals(ActivityType.MINING, replayed.type());
        Assertions.assertEquals(100L, replayed.startedTick());
        Assertions.assertEquals(104L, replayed.lastEvidenceTick());
        Assertions.assertEquals(2, replayed.evidence().size());
    }

    @Test
    void committedPlayerCorrectionOverridesEarlierEvidenceAndCanRejectIt() {
        ActivityInferenceService inference =
                new ActivityInferenceService(200, 16);
        PerceivedEvent mining = event(
                1L,
                1L,
                100L,
                ACTOR_ID,
                SemanticEventType.BLOCK_BROKEN,
                SemanticEventOutcome.COMMITTED,
                1.0F,
                Map.of(),
                Set.of());
        PerceivedEvent farmingCorrection = event(
                2L,
                2L,
                105L,
                ACTOR_ID,
                SemanticEventType.PLAYER_CORRECTION,
                SemanticEventOutcome.COMMITTED,
                1.0F,
                Map.of("corrected_activity", "farming"),
                Set.of());
        ActivityHypothesis corrected = inference.infer(
                ACTOR_ID, List.of(farmingCorrection, mining), 105L);

        Assertions.assertEquals(ActivityType.FARMING, corrected.type());
        Assertions.assertEquals(ActivityConfidenceBand.CONFIRMED, corrected.band());
        Assertions.assertEquals(1, corrected.evidence().size());
        Assertions.assertEquals("player_correction", corrected.evidence().getFirst().detail());

        PerceivedEvent rejection = event(
                3L,
                3L,
                106L,
                ACTOR_ID,
                SemanticEventType.PLAYER_CORRECTION,
                SemanticEventOutcome.COMMITTED,
                1.0F,
                Map.of("corrected_activity", "none"),
                Set.of());
        ActivityHypothesis rejected =
                inference.infer(ACTOR_ID, List.of(mining, rejection), 106L);
        Assertions.assertEquals(ActivityType.UNKNOWN, rejected.type());
        Assertions.assertTrue(rejected.evidence().isEmpty());
    }

    @Test
    void weakEvidenceUsesExplicitlyUncertainChineseWording() {
        ActivityInferenceService inference =
                new ActivityInferenceService(200, 8);
        PerceivedEvent weakExploration = event(
                1L,
                1L,
                50L,
                ACTOR_ID,
                SemanticEventType.REGION_ENTERED,
                SemanticEventOutcome.COMMITTED,
                0.20F,
                Map.of(),
                Set.of());

        ActivityHypothesis hypothesis =
                inference.infer(ACTOR_ID, List.of(weakExploration), 50L);

        Assertions.assertEquals(ActivityType.EXPLORING, hypothesis.type());
        Assertions.assertEquals(ActivityConfidenceBand.UNCERTAIN, hypothesis.band());
        Assertions.assertTrue(hypothesis.confidence() < 0.40F);
        Assertions.assertTrue(
                ActivityReportFormatter.formatChinese(hypothesis)
                        .startsWith("我不确定是否正在探索"));
    }

    @Test
    void ignoresExpiredAttemptedAndUnrelatedActorEvidence() {
        ActivityInferenceService inference =
                new ActivityInferenceService(20, 8);
        PerceivedEvent expired = event(
                1L,
                1L,
                10L,
                ACTOR_ID,
                SemanticEventType.BLOCK_BROKEN,
                SemanticEventOutcome.COMMITTED,
                1.0F,
                Map.of(),
                Set.of());
        PerceivedEvent attempted = event(
                2L,
                2L,
                29L,
                ACTOR_ID,
                SemanticEventType.ENTITY_DAMAGED,
                SemanticEventOutcome.ATTEMPTED,
                1.0F,
                Map.of(),
                Set.of());
        PerceivedEvent unrelated = event(
                3L,
                3L,
                30L,
                OTHER_ACTOR_ID,
                SemanticEventType.ENTITY_DIED,
                SemanticEventOutcome.COMMITTED,
                1.0F,
                Map.of(),
                Set.of());

        ActivityHypothesis hypothesis = inference.infer(
                ACTOR_ID, List.of(expired, attempted, unrelated), 31L);

        Assertions.assertEquals(ActivityType.UNKNOWN, hypothesis.type());
        Assertions.assertEquals(0.0F, hypothesis.confidence());
    }

    @Test
    void useOnBlockAloneDoesNotProveBuildingActivity() {
        ActivityInferenceService inference =
                new ActivityInferenceService(200, 8);
        PerceivedEvent interaction = event(
                1L,
                1L,
                30L,
                ACTOR_ID,
                SemanticEventType.ACTION_COMPLETED,
                SemanticEventOutcome.COMMITTED,
                1.0F,
                Map.of("action.kind", "use_on_block"),
                Set.of());

        ActivityHypothesis hypothesis =
                inference.infer(ACTOR_ID, List.of(interaction), 30L);

        Assertions.assertEquals(ActivityType.UNKNOWN, hypothesis.type());
        Assertions.assertTrue(hypothesis.evidence().isEmpty());
    }

    @Test
    void canonicalEventsCoverMiningBuildingCombatAndFarming() {
        ActivityInferenceService inference =
                new ActivityInferenceService(200, 8);
        List<PerceivedEvent> events = List.of(
                event(
                        1L,
                        1L,
                        30L,
                        ACTOR_ID,
                        SemanticEventType.BLOCK_BROKEN,
                        SemanticEventOutcome.COMMITTED,
                        1.0F,
                        Map.of(),
                        Set.of()),
                event(
                        2L,
                        2L,
                        30L,
                        ACTOR_ID,
                        SemanticEventType.BLOCK_PLACED,
                        SemanticEventOutcome.COMMITTED,
                        1.0F,
                        Map.of(),
                        Set.of()),
                event(
                        3L,
                        3L,
                        30L,
                        ACTOR_ID,
                        SemanticEventType.ENTITY_DAMAGED,
                        SemanticEventOutcome.COMMITTED,
                        1.0F,
                        Map.of(),
                        Set.of()),
                event(
                        4L,
                        4L,
                        30L,
                        ACTOR_ID,
                        SemanticEventType.BLOCK_BROKEN,
                        SemanticEventOutcome.COMMITTED,
                        1.0F,
                        Map.of(),
                        Set.of("activity:farming")));

        List<ActivityType> expected = List.of(
                ActivityType.MINING,
                ActivityType.BUILDING,
                ActivityType.COMBAT,
                ActivityType.FARMING);
        for (int index = 0; index < events.size(); index++) {
            ActivityHypothesis hypothesis = inference.infer(
                    ACTOR_ID, List.of(events.get(index)), 30L);
            Assertions.assertEquals(expected.get(index), hypothesis.type());
            Assertions.assertFalse(hypothesis.evidence().isEmpty());
        }
    }

    private static PerceivedEvent event(
            long perceivedSeq,
            long authoritySeq,
            long tick,
            UUID actorId,
            SemanticEventType type,
            SemanticEventOutcome outcome,
            float confidence,
            Map<String, String> delta,
            Set<String> tags) {
        return new PerceivedEvent(
                perceivedSeq,
                BOT_ID,
                1L,
                new UUID(1L, authoritySeq),
                tick,
                type,
                outcome,
                Optional.empty(),
                List.of(new ActorRef(actorId, "minecraft:player", "Alex")),
                List.of(),
                delta,
                SemanticEventSource.SENSOR_OBSERVATION,
                PerceptionChannel.VISUAL,
                confidence,
                tags);
    }
}
