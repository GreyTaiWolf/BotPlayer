package io.github.greytaiwolf.botplayer.ai.review;

import io.github.greytaiwolf.botplayer.ai.transport.AiClientRequestDispatch;
import io.github.greytaiwolf.botplayer.ai.transport.AiProposalAuthority;
import io.github.greytaiwolf.botplayer.ai.transport.AiProposalRequestEnvelope;
import io.github.greytaiwolf.botplayer.ai.transport.AiProposalReviewReceipt;
import io.github.greytaiwolf.botplayer.ai.transport.AiProposalReviewStatus;
import io.github.greytaiwolf.botplayer.ai.transport.AiProposalSessionGate;
import io.github.greytaiwolf.botplayer.ai.transport.AiRequestDispatchReceipt;
import io.github.greytaiwolf.botplayer.network.payload.AiProposalPayload;
import io.github.greytaiwolf.botplayer.network.payload.AiProposalToolCallPayload;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AiReviewOnlyLoopbackTest {
    private static final UUID SERVER_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000001");
    private static final UUID BOT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000101");
    private static final UUID OWNER_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000151");
    private static final UUID AGENT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000201");

    @Test
    void fixedProposalConsumesTheExactTicketThenLeavesOnlyASafeSummary() {
        AiReviewOnlySnapshotProjection projection = new AiReviewOnlySnapshotProjection(
                BOT_ID,
                2L,
                7L,
                100L,
                "minecraft:overworld",
                19.5F,
                18,
                294,
                2);
        AiProposalSessionGate gate = new AiProposalSessionGate();
        AiProposalRequestEnvelope envelope = gate.open(
                BOT_ID,
                OWNER_ID,
                AGENT_ID,
                projection.generation(),
                projection.snapshotId(),
                projection.gameTick(),
                AiReviewOnlyContract.REQUEST_TTL_TICKS,
                AiReviewOnlyContract.PURPOSE,
                true,
                AiReviewOnlyContract.firewallPolicy());
        AiClientRequestDispatch dispatch = AiClientRequestDispatch.fromEnvelope(
                SERVER_ID,
                envelope,
                1_000L,
                3_000L,
                AiReviewOnlyContract.PROVIDER_ID,
                AiReviewOnlyContract.requestTemplate(projection));
        Assertions.assertTrue(AiReviewOnlyContract.isCanonicalDispatch(dispatch));

        AiReviewOnlyTicketBook tickets = new AiReviewOnlyTicketBook();
        tickets.open(new AiReviewOnlyTicket(
                AiRequestDispatchReceipt.fromEnvelope(envelope), projection));
        AiProposalPayload proposal = new AiProposalPayload(
                BOT_ID,
                AGENT_ID,
                projection.generation(),
                envelope.requestId(),
                envelope.nonce(),
                projection.snapshotId(),
                "",
                List.of(new AiProposalToolCallPayload(
                        "call_1", AiReviewOnlyContract.TOOL_NAME, "{}")));

        AiProposalReviewReceipt reviewed = gate.reviewWithReceipt(
                proposal,
                new AiProposalAuthority(
                        true,
                        true,
                        Optional.of(OWNER_ID),
                        Optional.of(AGENT_ID),
                        OptionalLong.of(projection.generation())),
                101L);

        Assertions.assertEquals(AiProposalReviewStatus.ACCEPTED_NO_EXECUTION,
                reviewed.review().status());
        Assertions.assertTrue(AiReviewOnlyContract.isCanonicalProposal(
                reviewed.review().proposal().orElseThrow()));
        var terminalDispatch = reviewed.terminalDispatch().orElseThrow();
        AiReviewOnlyTicket consumed = tickets.close(terminalDispatch).orElseThrow();
        Assertions.assertTrue(tickets.close(terminalDispatch).isEmpty(),
                "the exact terminal receipt may close its ticket only once");
        AiReviewOnlyProposalSummary summary = new AiReviewOnlyProposalSummary(
                consumed.projection().snapshotId(),
                consumed.projection().gameTick(),
                consumed.projection().threatCount(),
                1);
        Assertions.assertEquals(7L, summary.snapshotId());
        Assertions.assertEquals(2, summary.threatCount());
        Assertions.assertEquals(0, tickets.activeTicketCount());
        Assertions.assertEquals(0, gate.activeRequestCount());
    }

    @Test
    void malformedReviewReplyConsumesItsExactTicketOnceAndReplayCannotReachIt() {
        AiReviewOnlySnapshotProjection projection = new AiReviewOnlySnapshotProjection(
                BOT_ID,
                2L,
                7L,
                100L,
                "minecraft:overworld",
                19.5F,
                18,
                294,
                2);
        AiProposalSessionGate gate = new AiProposalSessionGate();
        AiProposalRequestEnvelope envelope = gate.open(
                BOT_ID,
                OWNER_ID,
                AGENT_ID,
                projection.generation(),
                projection.snapshotId(),
                projection.gameTick(),
                AiReviewOnlyContract.REQUEST_TTL_TICKS,
                AiReviewOnlyContract.PURPOSE,
                true,
                AiReviewOnlyContract.firewallPolicy());
        AiReviewOnlyTicketBook tickets = new AiReviewOnlyTicketBook();
        tickets.open(new AiReviewOnlyTicket(
                AiRequestDispatchReceipt.fromEnvelope(envelope), projection));
        AiProposalPayload malformed = new AiProposalPayload(
                BOT_ID,
                AGENT_ID,
                projection.generation(),
                envelope.requestId(),
                envelope.nonce(),
                projection.snapshotId(),
                "forged review prose",
                List.of(new AiProposalToolCallPayload(
                        "call_1", AiReviewOnlyContract.TOOL_NAME, "{}")));

        AiProposalReviewReceipt rejected = gate.reviewWithReceipt(
                malformed,
                new AiProposalAuthority(
                        true,
                        true,
                        Optional.of(OWNER_ID),
                        Optional.of(AGENT_ID),
                        OptionalLong.of(projection.generation())),
                101L);
        Assertions.assertEquals(AiProposalReviewStatus.REVIEW_CONTRACT_REJECTED,
                rejected.review().status());
        Assertions.assertTrue(rejected.review().proposal().isEmpty());
        AiRequestDispatchReceipt receipt = rejected.terminalDispatch().orElseThrow();
        Assertions.assertTrue(tickets.close(receipt).isPresent());
        Assertions.assertTrue(tickets.close(receipt).isEmpty());
        Assertions.assertEquals(0, tickets.activeTicketCount());

        AiProposalReviewReceipt replay = gate.reviewWithReceipt(
                malformed,
                new AiProposalAuthority(
                        true,
                        true,
                        Optional.of(OWNER_ID),
                        Optional.of(AGENT_ID),
                        OptionalLong.of(projection.generation())),
                101L);
        Assertions.assertEquals(AiProposalReviewStatus.NO_ACTIVE_REQUEST,
                replay.review().status());
        Assertions.assertTrue(replay.terminalDispatch().isEmpty());
    }
}
