package io.github.greytaiwolf.botplayer.ai.review;

import io.github.greytaiwolf.botplayer.ai.transport.AiRequestDispatchReceipt;
import io.github.greytaiwolf.botplayer.ai.transport.AiRequestPurpose;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AiReviewOnlyTicketBookTest {
    private static final UUID BOT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000101");
    private static final UUID AGENT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000201");
    private static final UUID REQUEST_ONE = UUID.fromString(
            "00000000-0000-0000-0000-000000000301");
    private static final UUID REQUEST_TWO = UUID.fromString(
            "00000000-0000-0000-0000-000000000302");

    @Test
    void consumesOnlyAnExactTerminalDispatchReceipt() {
        AiReviewOnlyTicketBook book = new AiReviewOnlyTicketBook();
        AiReviewOnlyTicket ticket = ticket(REQUEST_ONE, 7L, 140L);
        book.open(ticket);

        AiRequestDispatchReceipt staleRevision = new AiRequestDispatchReceipt(
                BOT_ID,
                AGENT_ID,
                2L,
                REQUEST_ONE,
                8L,
                140L,
                AiRequestPurpose.REVIEW_ONLY_V1);
        Assertions.assertTrue(book.close(staleRevision).isEmpty());
        Assertions.assertEquals(1, book.activeTicketCount());

        Assertions.assertEquals(ticket, book.close(ticket.dispatch()).orElseThrow());
        Assertions.assertEquals(0, book.activeTicketCount());
        Assertions.assertTrue(book.close(ticket.dispatch()).isEmpty());
    }

    @Test
    void replacementAndExpiryCannotLeaveAnOldReviewTicketReachable() {
        AiReviewOnlyTicketBook book = new AiReviewOnlyTicketBook();
        AiReviewOnlyTicket first = ticket(REQUEST_ONE, 7L, 140L);
        AiReviewOnlyTicket replacement = ticket(REQUEST_TWO, 8L, 160L);

        Assertions.assertTrue(book.open(first).isEmpty());
        Assertions.assertEquals(first, book.open(replacement).orElseThrow());
        Assertions.assertEquals(1, book.activeTicketCount());
        Assertions.assertTrue(book.close(first.dispatch()).isEmpty());

        Assertions.assertEquals(
                java.util.List.of(replacement), book.closeExpiredThrough(160L));
        Assertions.assertEquals(0, book.activeTicketCount());
        Assertions.assertTrue(book.closeBot(BOT_ID).isEmpty());
    }

    private static AiReviewOnlyTicket ticket(
            UUID requestId, long snapshotId, long expiresAtTick) {
        AiRequestDispatchReceipt dispatch = new AiRequestDispatchReceipt(
                BOT_ID,
                AGENT_ID,
                2L,
                requestId,
                snapshotId,
                expiresAtTick,
                AiRequestPurpose.REVIEW_ONLY_V1);
        return new AiReviewOnlyTicket(dispatch, new AiReviewOnlySnapshotProjection(
                BOT_ID,
                2L,
                snapshotId,
                100L,
                "minecraft:overworld",
                20.0F,
                20,
                300,
                0));
    }
}
