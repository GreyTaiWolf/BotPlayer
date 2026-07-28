package io.github.greytaiwolf.botplayer.perception.event;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SemanticEventBusTest {
    private static final UUID SESSION_ID = new UUID(0L, 10L);
    private static final UUID OTHER_SESSION_ID = new UUID(0L, 11L);
    private static final UUID BOT_ID = new UUID(0L, 20L);
    private static final UUID ACTOR_ID = new UUID(0L, 30L);

    @Test
    void authorityEventsDoNotBecomeBotKnowledgeWithoutAnExplicitProjection() {
        SemanticEventBus bus = new SemanticEventBus(4, 4, SESSION_ID);
        SemanticEventDraft draft = draft(
                1L,
                false,
                Set.of(PerceptionChannel.VISUAL),
                Map.of("private_detail", "server-only"));

        AuthorityEvent authority = bus.publishAuthority(draft);

        Assertions.assertEquals(1L, bus.currentAuthoritySeq());
        Assertions.assertEquals(
                List.of(authority), bus.authoritySince(0L, 4).events());
        Assertions.assertTrue(
                bus.perceivedSince(BOT_ID, 1L, 0L, 4).events().isEmpty());

        PerceptionProjection projection = new PerceptionProjection(
                2L,
                draft.type(),
                draft.outcome(),
                draft.position(),
                List.of(),
                List.of("minecraft:stone"),
                Map.of("visible_detail", "stone"),
                draft.source(),
                PerceptionChannel.VISUAL,
                0.75F,
                Set.of("projected"));
        PerceivedEvent perceived =
                bus.publishPerceived(BOT_ID, 1L, authority, projection);

        Assertions.assertEquals(
                authority.event().eventId(), perceived.authorityEventId());
        Assertions.assertEquals(
                Map.of("visible_detail", "stone"), perceived.delta());
        Assertions.assertFalse(perceived.delta().containsKey("private_detail"));
        Assertions.assertTrue(perceived.actors().isEmpty());
        Assertions.assertEquals(
                List.of(perceived),
                bus.perceivedSince(BOT_ID, 1L, 0L, 4).events());
        Assertions.assertEquals(
                "server-only", authority.event().delta().get("private_detail"));
    }

    @Test
    void authorityAndPerBotRingsReportEvictionAndRemainGenerationScoped() {
        SemanticEventBus bus = new SemanticEventBus(2, 2, SESSION_ID);
        AuthorityEvent first =
                bus.publishAuthority(draft(1L, false, Set.of(PerceptionChannel.SELF), Map.of()));
        AuthorityEvent second =
                bus.publishAuthority(draft(2L, true, Set.of(PerceptionChannel.SELF), Map.of()));
        AuthorityEvent third =
                bus.publishAuthority(draft(3L, true, Set.of(PerceptionChannel.SELF), Map.of()));

        EventReadWindow<AuthorityEvent> authorityWindow =
                bus.authoritySince(0L, 8);
        Assertions.assertEquals(List.of(second, third), authorityWindow.events());
        Assertions.assertTrue(authorityWindow.truncatedBefore());
        Assertions.assertEquals(2L, authorityWindow.oldestAvailableSeq());
        Assertions.assertEquals(3L, authorityWindow.newestAvailableSeq());
        Assertions.assertEquals(2L, bus.worldRevision());

        bus.publishPerceived(
                BOT_ID,
                1L,
                first,
                PerceptionProjection.full(first, 1L, PerceptionChannel.SELF, 1.0F));
        PerceivedEvent secondPerceived = bus.publishPerceived(
                BOT_ID,
                1L,
                second,
                PerceptionProjection.full(second, 2L, PerceptionChannel.SELF, 1.0F));
        PerceivedEvent thirdPerceived = bus.publishPerceived(
                BOT_ID,
                1L,
                third,
                PerceptionProjection.full(third, 3L, PerceptionChannel.SELF, 1.0F));

        EventReadWindow<PerceivedEvent> generationOne =
                bus.perceivedSince(BOT_ID, 1L, 0L, 8);
        Assertions.assertEquals(
                List.of(secondPerceived, thirdPerceived),
                generationOne.events());
        Assertions.assertTrue(generationOne.truncatedBefore());
        Assertions.assertTrue(
                bus.perceivedSince(BOT_ID, 2L, 0L, 8).events().isEmpty());

        PerceivedEvent generationTwo = bus.publishPerceived(
                BOT_ID,
                2L,
                third,
                PerceptionProjection.full(third, 4L, PerceptionChannel.SELF, 1.0F));
        Assertions.assertEquals(1L, generationTwo.perceivedSeq());
        Assertions.assertEquals(1L, bus.currentPerceivedSeq(BOT_ID, 2L));
        Assertions.assertEquals(
                List.of(generationTwo),
                bus.perceivedSince(BOT_ID, 2L, 0L, 8).events());
        Assertions.assertEquals(
                List.of(secondPerceived, thirdPerceived),
                bus.recentPerceived(BOT_ID, 1L, 2));

        bus.clearGeneration(BOT_ID, 1L);
        Assertions.assertTrue(
                bus.perceivedSince(BOT_ID, 1L, 0L, 8).events().isEmpty());
        Assertions.assertEquals(
                List.of(generationTwo),
                bus.perceivedSince(BOT_ID, 2L, 0L, 8).events());

        bus.clearBot(BOT_ID);
        Assertions.assertTrue(
                bus.perceivedSince(BOT_ID, 2L, 0L, 8).events().isEmpty());
    }

    @Test
    void deduplicatesAnAuthorityEventWithinOneGenerationWithoutSpendingSequence() {
        SemanticEventBus bus = new SemanticEventBus(4, 4, SESSION_ID);
        AuthorityEvent authority = bus.publishAuthority(
                draft(1L, false, Set.of(PerceptionChannel.SELF), Map.of()));

        PerceivedEvent first = bus.publishPerceived(
                BOT_ID,
                1L,
                authority,
                PerceptionProjection.full(
                        authority,
                        1L,
                        PerceptionChannel.SELF,
                        1.0F));
        PerceivedEvent duplicate = bus.publishPerceived(
                BOT_ID,
                1L,
                authority,
                PerceptionProjection.full(
                        authority,
                        2L,
                        PerceptionChannel.SELF,
                        0.5F));

        Assertions.assertSame(first, duplicate);
        Assertions.assertEquals(1L, bus.currentPerceivedSeq(BOT_ID, 1L));
        Assertions.assertEquals(
                List.of(first),
                bus.perceivedSince(BOT_ID, 1L, 0L, 4).events());

        PerceivedEvent nextGeneration = bus.publishPerceived(
                BOT_ID,
                2L,
                authority,
                PerceptionProjection.full(
                        authority,
                        3L,
                        PerceptionChannel.SELF,
                        1.0F));
        Assertions.assertEquals(1L, nextGeneration.perceivedSeq());
        Assertions.assertEquals(1L, bus.currentPerceivedSeq(BOT_ID, 2L));
    }

    @Test
    void rejectsAProjectionThroughAnUndeclaredChannel() {
        SemanticEventBus bus = new SemanticEventBus(2, 2, SESSION_ID);
        AuthorityEvent authority = bus.publishAuthority(
                draft(1L, false, Set.of(PerceptionChannel.VISUAL), Map.of()));
        PerceptionProjection projection = PerceptionProjection.full(
                authority, 1L, PerceptionChannel.DIRECT, 1.0F);

        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> bus.publishPerceived(BOT_ID, 1L, authority, projection));
        Assertions.assertTrue(
                bus.perceivedSince(BOT_ID, 1L, 0L, 2).events().isEmpty());
    }

    @Test
    void rejectsAnAuthorityEventFromAnotherRuntimeSession() {
        SemanticEventBus firstBus = new SemanticEventBus(2, 2, SESSION_ID);
        SemanticEventBus secondBus =
                new SemanticEventBus(2, 2, OTHER_SESSION_ID);
        AuthorityEvent foreignAuthority = secondBus.publishAuthority(
                draft(1L, false, Set.of(PerceptionChannel.SELF), Map.of()));

        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> firstBus.publishPerceived(
                        BOT_ID,
                        1L,
                        foreignAuthority,
                        PerceptionProjection.full(
                                foreignAuthority,
                                1L,
                                PerceptionChannel.SELF,
                                1.0F)));
    }

    @Test
    void routedSoundsUseUniqueAuthorityIdsAndCannotEvictSemanticEvidence() {
        SemanticEventBus bus = new SemanticEventBus(4, 2, SESSION_ID);
        AuthorityEvent first =
                bus.publishAuthority(draft(
                        1L,
                        false,
                        Set.of(PerceptionChannel.SELF),
                        Map.of()));
        bus.publishPerceived(
                BOT_ID,
                1L,
                first,
                PerceptionProjection.full(
                        first,
                        1L,
                        PerceptionChannel.SELF,
                        1.0F));

        AuthorityEvent firstSound =
                bus.publishRoutedSoundAudit(soundDraft(2L));
        AuthorityEvent secondSound =
                bus.publishRoutedSoundAudit(soundDraft(3L));
        AuthorityEvent thirdSound =
                bus.publishRoutedSoundAudit(soundDraft(4L));
        for (AuthorityEvent sound :
                List.of(firstSound, secondSound, thirdSound)) {
            bus.publishPerceived(
                    BOT_ID,
                    1L,
                    sound,
                    PerceptionProjection.full(
                            sound,
                            sound.event().gameTick(),
                            PerceptionChannel.AUDIBLE,
                            1.0F));
        }
        AuthorityEvent second =
                bus.publishAuthority(draft(
                        5L,
                        false,
                        Set.of(PerceptionChannel.SELF),
                        Map.of()));
        PerceivedEvent secondSemantic = bus.publishPerceived(
                BOT_ID,
                1L,
                second,
                PerceptionProjection.full(
                        second,
                        5L,
                        PerceptionChannel.SELF,
                        1.0F));

        Assertions.assertEquals(
                Set.of(1L, 2L, 3L, 4L, 5L),
                Set.of(
                        first.eventSeq(),
                        firstSound.eventSeq(),
                        secondSound.eventSeq(),
                        thirdSound.eventSeq(),
                        second.eventSeq()));
        Assertions.assertEquals(
                List.of(
                        first.event().eventId(),
                        second.event().eventId()),
                bus.recentSemanticEvents(BOT_ID, 1L, 2)
                        .stream()
                        .map(PerceivedEvent::authorityEventId)
                        .toList());
        Assertions.assertEquals(
                List.of(
                        secondSound.event().eventId(),
                        thirdSound.event().eventId()),
                bus.recentSounds(BOT_ID, 1L, 2)
                        .stream()
                        .map(PerceivedEvent::authorityEventId)
                        .toList());
        Assertions.assertEquals(5L, secondSemantic.perceivedSeq());
    }

    @Test
    void targetedAuthorityFloodCannotEvictTheSpatialProjectionRing() {
        SemanticEventBus bus =
                new SemanticEventBus(2, 2, SESSION_ID);
        AuthorityEvent firstVisual = bus.publishAuthority(
                draft(
                        1L,
                        false,
                        Set.of(PerceptionChannel.VISUAL),
                        Map.of()));
        bus.publishAuthority(draft(
                2L,
                false,
                Set.of(PerceptionChannel.SELF),
                Map.of()));
        bus.publishAuthority(draft(
                3L,
                false,
                Set.of(PerceptionChannel.DIRECT),
                Map.of()));
        bus.publishAuthority(draft(
                4L,
                false,
                Set.of(PerceptionChannel.SELF),
                Map.of()));
        AuthorityEvent secondVisual = bus.publishAuthority(
                draft(
                        5L,
                        false,
                        Set.of(PerceptionChannel.AUDIBLE),
                        Map.of()));

        Assertions.assertEquals(
                List.of(firstVisual, secondVisual),
                bus.recentSpatialAuthority(2));
        Assertions.assertEquals(
                List.of(4L, 5L),
                bus.recentAuthority(2).stream()
                        .map(AuthorityEvent::eventSeq)
                        .toList());
    }

    private static SemanticEventDraft draft(
            long sequence,
            boolean changesWorld,
            Set<PerceptionChannel> channels,
            Map<String, String> delta) {
        return new SemanticEventDraft(
                new UUID(0L, sequence),
                "minecraft:overworld",
                sequence,
                Instant.ofEpochSecond(sequence),
                SemanticEventType.BLOCK_CHANGED,
                SemanticEventOutcome.COMMITTED,
                Optional.of(new SpatialPoint(sequence, 64.0D, 0.0D)),
                List.of(new ActorRef(ACTOR_ID, "minecraft:player", "Alex")),
                List.of("minecraft:stone"),
                delta,
                SemanticEventSource.NEOFORGE_EVENT,
                channels,
                1.0F,
                Set.of("test"),
                changesWorld);
    }

    private static SemanticEventDraft soundDraft(long sequence) {
        return new SemanticEventDraft(
                new UUID(2L, sequence),
                "minecraft:overworld",
                sequence,
                Instant.ofEpochSecond(sequence),
                SemanticEventType.SOUND_PLAYED,
                SemanticEventOutcome.COMMITTED,
                Optional.of(new SpatialPoint(0.0D, 64.0D, 0.0D)),
                List.of(),
                List.of("minecraft:block.note_block.harp"),
                Map.of("sound.source", "block"),
                SemanticEventSource.VANILLA_CLIENTBOUND,
                Set.of(PerceptionChannel.AUDIBLE),
                1.0F,
                Set.of("sound:test"),
                false);
    }
}
