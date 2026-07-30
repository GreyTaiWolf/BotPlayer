package io.github.greytaiwolf.botplayer.skill.core;

import io.github.greytaiwolf.botplayer.action.ActionEvidence;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SkillSignalInboxTest {
    private static final UUID FIRST_RUN = new UUID(0L, 1L);
    private static final UUID SECOND_RUN = new UUID(0L, 2L);
    private static final UUID FIRST_BOT = new UUID(0L, 3L);
    private static final UUID SECOND_BOT = new UUID(0L, 4L);

    @Test
    void acceptsOnlyBoundRunBotAndGeneration() {
        SkillSignalInbox inbox = new SkillSignalInbox(8);
        Assertions.assertEquals(
                SkillSignalInbox.BindStatus.BOUND,
                inbox.bind(FIRST_RUN, FIRST_BOT, 2L));
        Assertions.assertEquals(
                SkillSignalInbox.OfferStatus.ENQUEUED,
                inbox.offer(signal(
                        FIRST_RUN, FIRST_BOT, 2L, 1L)));
        Assertions.assertEquals(
                SkillSignalInbox.OfferStatus.UNKNOWN_RUN,
                inbox.offer(signal(
                        SECOND_RUN, FIRST_BOT, 2L, 2L)));
        Assertions.assertEquals(
                SkillSignalInbox.OfferStatus.BOT_MISMATCH,
                inbox.offer(signal(
                        FIRST_RUN, SECOND_BOT, 2L, 3L)));
        Assertions.assertEquals(
                SkillSignalInbox.OfferStatus.STALE_GENERATION,
                inbox.offer(signal(
                        FIRST_RUN, FIRST_BOT, 1L, 4L)));

        List<SkillSignal> drained =
                inbox.drain(FIRST_RUN, 2L, 8);
        Assertions.assertEquals(1, drained.size());
        Assertions.assertEquals(
                new UUID(0L, 1L),
                drained.get(0).signalId());
        Assertions.assertEquals(
                1L, drained.get(0).runRevision());
        Assertions.assertEquals(3L, inbox.rejectedStaleCount());
    }

    @Test
    void drainsOneRunWithoutDisturbingOtherRunOrder() {
        SkillSignalInbox inbox = new SkillSignalInbox(8);
        inbox.bind(FIRST_RUN, FIRST_BOT, 1L);
        inbox.bind(SECOND_RUN, SECOND_BOT, 1L);
        inbox.offer(signal(FIRST_RUN, FIRST_BOT, 1L, 1L));
        inbox.offer(signal(SECOND_RUN, SECOND_BOT, 1L, 2L));
        inbox.offer(signal(FIRST_RUN, FIRST_BOT, 1L, 3L));

        Assertions.assertEquals(
                List.of(new UUID(0L, 1L), new UUID(0L, 3L)),
                inbox.drain(FIRST_RUN, 1L, 8).stream()
                        .map(SkillSignal::signalId)
                        .toList());
        Assertions.assertEquals(
                List.of(new UUID(0L, 2L)),
                inbox.drain(SECOND_RUN, 1L, 8).stream()
                        .map(SkillSignal::signalId)
                        .toList());
    }

    @Test
    void rebindPurgesOldGenerationAndRejectsRollback() {
        SkillSignalInbox inbox = new SkillSignalInbox(8);
        inbox.bind(FIRST_RUN, FIRST_BOT, 1L);
        inbox.offer(signal(FIRST_RUN, FIRST_BOT, 1L, 1L));

        Assertions.assertEquals(
                SkillSignalInbox.BindStatus.REBOUND,
                inbox.bind(FIRST_RUN, FIRST_BOT, 2L));
        Assertions.assertEquals(0, inbox.size());
        Assertions.assertEquals(
                SkillSignalInbox.BindStatus.STALE_GENERATION,
                inbox.bind(FIRST_RUN, FIRST_BOT, 1L));
        Assertions.assertEquals(
                SkillSignalInbox.BindStatus.RUN_ID_CONFLICT,
                inbox.bind(FIRST_RUN, SECOND_BOT, 3L));
        Assertions.assertEquals(
                SkillSignalInbox.OfferStatus.STALE_GENERATION,
                inbox.offer(signal(
                        FIRST_RUN, FIRST_BOT, 1L, 2L)));
    }

    @Test
    void enforcesSignalAndBindingCapacity() {
        SkillSignalInbox inbox = new SkillSignalInbox(1, 1);
        Assertions.assertEquals(
                SkillSignalInbox.BindStatus.BOUND,
                inbox.bind(FIRST_RUN, FIRST_BOT, 1L));
        Assertions.assertEquals(
                SkillSignalInbox.BindStatus.CAPACITY_EXCEEDED,
                inbox.bind(SECOND_RUN, SECOND_BOT, 1L));
        Assertions.assertEquals(
                SkillSignalInbox.OfferStatus.ENQUEUED,
                inbox.offer(signal(
                        FIRST_RUN, FIRST_BOT, 1L, 1L)));
        Assertions.assertEquals(
                SkillSignalInbox.OfferStatus.FULL,
                inbox.offer(signal(
                        FIRST_RUN, FIRST_BOT, 1L, 2L)));
        Assertions.assertEquals(1L, inbox.rejectedFullCount());
    }

    @Test
    void unbindAndCloseDiscardPendingSignals() {
        SkillSignalInbox inbox = new SkillSignalInbox(4);
        inbox.bind(FIRST_RUN, FIRST_BOT, 1L);
        inbox.offer(signal(FIRST_RUN, FIRST_BOT, 1L, 1L));
        Assertions.assertTrue(inbox.unbind(FIRST_RUN));
        Assertions.assertEquals(0, inbox.size());
        Assertions.assertFalse(inbox.unbind(FIRST_RUN));

        inbox.bind(FIRST_RUN, FIRST_BOT, 1L);
        inbox.close();
        Assertions.assertTrue(inbox.isClosed());
        Assertions.assertEquals(0, inbox.bindingCount());
        Assertions.assertEquals(
                SkillSignalInbox.OfferStatus.CLOSED,
                inbox.offer(signal(
                        FIRST_RUN, FIRST_BOT, 1L, 2L)));
        Assertions.assertEquals(
                SkillSignalInbox.BindStatus.CLOSED,
                inbox.bind(FIRST_RUN, FIRST_BOT, 2L));
    }

    @Test
    void signalRejectsUnsafeIdentityAndPayload() {
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new SkillSignal(
                        new UUID(0L, 0L),
                        FIRST_RUN,
                        FIRST_BOT,
                        1L,
                        0L,
                        new UUID(0L, 9L),
                        SkillSignalType.ACTION,
                        SkillSignalStatus.SUCCEEDED,
                        SkillFailureCode.NONE,
                        List.of(),
                        "",
                        1L));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new SkillSignal(
                        new UUID(0L, 1L),
                        FIRST_RUN,
                        FIRST_BOT,
                        1L,
                        0L,
                        new UUID(0L, 9L),
                        SkillSignalType.ACTION,
                        SkillSignalStatus.SUCCEEDED,
                        SkillFailureCode.ACTION_FAILED,
                        List.of(),
                        "",
                        1L));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new SkillSignal(
                        new UUID(0L, 1L),
                        FIRST_RUN,
                        FIRST_BOT,
                        1L,
                        0L,
                        new UUID(0L, 9L),
                        SkillSignalType.ACTION,
                        SkillSignalStatus.FAILED,
                        SkillFailureCode.NONE,
                        List.of(),
                        "",
                        1L));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new SkillSignal(
                        new UUID(0L, 1L),
                        FIRST_RUN,
                        FIRST_BOT,
                        1L,
                        -1L,
                        new UUID(0L, 9L),
                        SkillSignalType.ACTION,
                        SkillSignalStatus.SUCCEEDED,
                        SkillFailureCode.NONE,
                        List.of(),
                        "",
                        1L));
    }

    @Test
    void signalFreezesBoundedUniqueActionEvidence() {
        List<ActionEvidence> mutable = new ArrayList<>();
        mutable.add(new ActionEvidence(
                "item.after_count", "1"));
        SkillSignal signal = new SkillSignal(
                new UUID(0L, 1L),
                FIRST_RUN,
                FIRST_BOT,
                1L,
                0L,
                new UUID(0L, 9L),
                SkillSignalType.ACTION,
                SkillSignalStatus.SUCCEEDED,
                SkillFailureCode.NONE,
                mutable,
                "",
                1L);

        mutable.clear();
        Assertions.assertEquals(1, signal.evidence().size());
        Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> signal.evidence().clear());
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new SkillSignal(
                        new UUID(0L, 2L),
                        FIRST_RUN,
                        FIRST_BOT,
                        1L,
                        0L,
                        new UUID(0L, 9L),
                        SkillSignalType.ACTION,
                        SkillSignalStatus.SUCCEEDED,
                        SkillFailureCode.NONE,
                        List.of(
                                new ActionEvidence("item.count", "2"),
                                new ActionEvidence("item.count", "1")),
                        "",
                        1L));
    }

    private static SkillSignal signal(
            UUID runId,
            UUID botId,
            long generation,
            long sequence) {
        return new SkillSignal(
                new UUID(0L, sequence),
                runId,
                botId,
                generation,
                sequence,
                new UUID(1L, sequence),
                SkillSignalType.ACTION,
                SkillSignalStatus.SUCCEEDED,
                SkillFailureCode.NONE,
                List.of(),
                "",
                sequence);
    }
}
