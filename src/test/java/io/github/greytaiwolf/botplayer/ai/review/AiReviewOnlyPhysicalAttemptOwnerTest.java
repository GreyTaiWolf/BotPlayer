package io.github.greytaiwolf.botplayer.ai.review;

import io.github.greytaiwolf.botplayer.ai.AiPhysicalAttemptCloseStatus;
import io.github.greytaiwolf.botplayer.ai.AiPhysicalAttemptIdentity;
import io.github.greytaiwolf.botplayer.ai.AiPhysicalAttemptOffer;
import io.github.greytaiwolf.botplayer.ai.AiPhysicalAttemptPrepareStatus;
import io.github.greytaiwolf.botplayer.ai.transport.AiClientRequestDispatch;
import io.github.greytaiwolf.botplayer.ai.transport.AiRequestDispatchReceipt;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AiReviewOnlyPhysicalAttemptOwnerTest {
    private static final UUID SERVER_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000001");
    private static final UUID BOT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000101");
    private static final UUID OWNER_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000151");
    private static final UUID AGENT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000201");

    @Test
    void serverOwnedCanonicalOfferSettlesOnlyFromItsExactPrepareAck() {
        AiReviewOnlyPhysicalAttemptOwner owner = new AiReviewOnlyPhysicalAttemptOwner(OWNER_ID);
        try {
            AiPhysicalAttemptOffer offered = owner.offer(dispatch()).offer().orElseThrow();
            AiRequestDispatchReceipt receipt = offered.identity().dispatchReceipt();

            var granted = owner.acknowledge(offered.prepareAck());
            var duplicate = owner.acknowledge(offered.prepareAck());

            Assertions.assertAll(
                    () -> Assertions.assertEquals(AiPhysicalAttemptPrepareStatus.START_GRANTED,
                            granted.status()),
                    () -> Assertions.assertSame(granted.grant().orElseThrow(),
                            duplicate.grant().orElseThrow()),
                    () -> Assertions.assertEquals(offered.identity(),
                            owner.findIdentity(receipt).orElseThrow()),
                    () -> Assertions.assertEquals(1, owner.indexedAttemptCount()),
                    () -> Assertions.assertEquals(AiPhysicalAttemptCloseStatus.CLOSED_COMMITTED,
                            owner.closeReceipt(receipt).orElseThrow().status()),
                    () -> Assertions.assertTrue(owner.findIdentity(receipt).isEmpty()),
                    () -> Assertions.assertEquals(0, owner.indexedAttemptCount()));
        } finally {
            owner.close();
        }
    }

    @Test
    void closeBeforeAckReleasesOnlyTheExactIndexedOffer() {
        AiReviewOnlyPhysicalAttemptOwner owner = new AiReviewOnlyPhysicalAttemptOwner(OWNER_ID);
        try {
            AiPhysicalAttemptOffer offered = owner.offer(dispatch()).offer().orElseThrow();
            AiRequestDispatchReceipt receipt = offered.identity().dispatchReceipt();

            Assertions.assertEquals(AiPhysicalAttemptCloseStatus.CLOSED_UNSTARTED,
                    owner.closeReceipt(receipt).orElseThrow().status());
            Assertions.assertTrue(owner.closeReceipt(receipt).isEmpty());
            Assertions.assertEquals(AiPhysicalAttemptPrepareStatus.ATTEMPT_TOMBSTONED,
                    owner.acknowledge(offered.prepareAck()).status());
        } finally {
            owner.close();
        }
    }

    @Test
    void oldExactCloseCannotRemoveAReplacementForTheSameReceipt() {
        AiReviewOnlyPhysicalAttemptOwner owner = new AiReviewOnlyPhysicalAttemptOwner(OWNER_ID);
        try {
            AiClientRequestDispatch dispatch = dispatch();
            AiPhysicalAttemptIdentity original = owner.offer(dispatch).offer().orElseThrow()
                    .identity();
            owner.closeExact(original).orElseThrow();
            AiPhysicalAttemptIdentity replacement = owner.offer(dispatch).offer().orElseThrow()
                    .identity();

            Assertions.assertAll(
                    () -> Assertions.assertNotEquals(original.attemptId(),
                            replacement.attemptId()),
                    () -> Assertions.assertTrue(owner.closeExact(original).isEmpty()),
                    () -> Assertions.assertEquals(replacement,
                            owner.findIdentity(replacement.dispatchReceipt()).orElseThrow()),
                    () -> Assertions.assertEquals(1, owner.indexedAttemptCount()),
                    () -> Assertions.assertEquals(AiPhysicalAttemptPrepareStatus.START_GRANTED,
                            owner.acknowledge(replacement.prepareAck()).status()),
                    () -> Assertions.assertEquals(AiPhysicalAttemptCloseStatus.CLOSED_COMMITTED,
                            owner.closeExact(replacement).orElseThrow().status()),
                    () -> Assertions.assertEquals(0, owner.indexedAttemptCount()));
        } finally {
            owner.close();
        }
    }

    @Test
    void wrongThreadCannotMutateTheExactReceiptIndex() throws InterruptedException {
        AiReviewOnlyPhysicalAttemptOwner owner = new AiReviewOnlyPhysicalAttemptOwner(OWNER_ID);
        try {
            AiPhysicalAttemptOffer offered = owner.offer(dispatch()).offer().orElseThrow();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread worker = new Thread(
                    () -> {
                        try {
                            owner.closeExact(offered.identity());
                        } catch (Throwable throwable) {
                            failure.set(throwable);
                        }
                    },
                    "wrong-owner-thread");
            worker.start();
            worker.join();

            Assertions.assertAll(
                    () -> Assertions.assertInstanceOf(IllegalStateException.class, failure.get()),
                    () -> Assertions.assertEquals(offered.identity(), owner.findIdentity(
                            offered.identity().dispatchReceipt()).orElseThrow()),
                    () -> Assertions.assertEquals(AiPhysicalAttemptCloseStatus.CLOSED_UNSTARTED,
                            owner.closeExact(offered.identity()).orElseThrow().status()));
        } finally {
            owner.close();
        }
    }

    @Test
    void ticketCarriesOnlyTheSameExactPhysicalAttemptReceipt() {
        AiReviewOnlyPhysicalAttemptOwner owner = new AiReviewOnlyPhysicalAttemptOwner(OWNER_ID);
        try {
            AiClientRequestDispatch dispatch = dispatch();
            AiPhysicalAttemptOffer offered = owner.offer(dispatch).offer().orElseThrow();
            AiRequestDispatchReceipt receipt = offered.identity().dispatchReceipt();
            AiReviewOnlySnapshotProjection projection = projection();

            AiReviewOnlyTicket ticket = new AiReviewOnlyTicket(
                    receipt, projection, offered.identity());
            Assertions.assertEquals(Optional.of(offered.identity()),
                    ticket.physicalAttemptIdentity());

            AiRequestDispatchReceipt drifted = new AiRequestDispatchReceipt(
                    receipt.botId(),
                    receipt.agentId(),
                    receipt.generation(),
                    receipt.requestId(),
                    receipt.revision() + 1L,
                    receipt.expiresAtTick(),
                    receipt.purpose());
            Assertions.assertThrows(IllegalArgumentException.class,
                    () -> new AiReviewOnlyTicket(drifted, projection, offered.identity()));
        } finally {
            owner.close();
        }
    }

    private static AiClientRequestDispatch dispatch() {
        AiReviewOnlySnapshotProjection projection = projection();
        long issuedAtEpochMillis = System.currentTimeMillis();
        return new AiClientRequestDispatch(
                SERVER_ID,
                BOT_ID,
                OWNER_ID,
                AGENT_ID,
                projection.generation(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                projection.snapshotId(),
                AiReviewOnlyContract.PURPOSE,
                projection.gameTick(),
                projection.gameTick() + AiReviewOnlyContract.REQUEST_TTL_TICKS,
                issuedAtEpochMillis,
                issuedAtEpochMillis + AiReviewOnlyContract.REQUEST_TTL_TICKS * 50L,
                AiReviewOnlyContract.PROVIDER_ID,
                AiReviewOnlyContract.requestTemplate(projection).model(),
                AiReviewOnlyContract.requestTemplate(projection).messages(),
                AiReviewOnlyContract.requestTemplate(projection).options(),
                Optional.empty());
    }

    private static AiReviewOnlySnapshotProjection projection() {
        return new AiReviewOnlySnapshotProjection(
                BOT_ID,
                2L,
                7L,
                100L,
                "minecraft:overworld",
                19.5F,
                18,
                294,
                2);
    }
}
