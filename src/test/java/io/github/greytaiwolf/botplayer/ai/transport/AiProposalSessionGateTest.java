package io.github.greytaiwolf.botplayer.ai.transport;

import io.github.greytaiwolf.botplayer.ai.tool.ToolDefinition;
import io.github.greytaiwolf.botplayer.ai.tool.ToolFirewallPolicy;
import io.github.greytaiwolf.botplayer.ai.tool.ToolFirewallRejectionCode;
import io.github.greytaiwolf.botplayer.ai.tool.ToolRiskLevel;
import io.github.greytaiwolf.botplayer.network.payload.AiProposalPayload;
import io.github.greytaiwolf.botplayer.network.payload.AiProposalToolCallPayload;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AiProposalSessionGateTest {
    private static final UUID BOT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000101");
    private static final UUID OWNER_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000151");
    private static final UUID OTHER_BOT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000102");
    private static final UUID AGENT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000201");
    private static final UUID OTHER_AGENT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000202");

    @Test
    void exactAuthorizedResponseIsReviewedButNeverExecuted() {
        AiProposalSessionGate gate = new AiProposalSessionGate();
        AiProposalRequestEnvelope request = gate.open(
                BOT_ID,
                OWNER_ID,
                AGENT_ID,
                1L,
                7L,
                100L,
                20,
                policy());
        AiProposalPayload payload = payload(
                request,
                "模型建议使用已注册工具。",
                List.of(safeToolCall()));
        Assertions.assertFalse(request.toString().contains(request.nonce().toString()));
        Assertions.assertFalse(request.toString().contains("safe_tool"));

        AiProposalReview review = gate.review(
                payload, authorized(AGENT_ID), 101L);

        Assertions.assertEquals(
                AiProposalReviewStatus.ACCEPTED_NO_EXECUTION,
                review.status());
        Assertions.assertTrue(review.acceptedNoExecution());
        Assertions.assertEquals(request.requestId(),
                review.proposal().orElseThrow().requestId());
        Assertions.assertEquals(0, gate.activeRequestCount());
        Assertions.assertFalse(review.toString().contains("模型建议"));
    }

    @Test
    void rejectsAuthorityAndActiveBindingBeforeInspectingAProposal() {
        AiProposalSessionGate gate = new AiProposalSessionGate();
        AiProposalRequestEnvelope request = gate.open(
                BOT_ID, OWNER_ID, AGENT_ID, 1L, 7L, 100L, 20, policy());
        AiProposalPayload payload = payload(request, "safe", List.of(safeToolCall()));

        Assertions.assertEquals(AiProposalReviewStatus.BOT_NOT_ACTIVE,
                gate.review(payload,
                        new AiProposalAuthority(
                                false,
                                true,
                                Optional.of(OWNER_ID),
                                Optional.of(AGENT_ID),
                                OptionalLong.empty()),
                        101L).status());
        Assertions.assertEquals(AiProposalReviewStatus.NOT_OWNER,
                gate.review(payload,
                        new AiProposalAuthority(
                                true,
                                false,
                                Optional.of(OWNER_ID),
                                Optional.of(AGENT_ID),
                                OptionalLong.of(1L)),
                        101L).status());
        Assertions.assertEquals(AiProposalReviewStatus.AGENT_NOT_BOUND,
                gate.review(payload,
                        new AiProposalAuthority(
                                true,
                                true,
                                Optional.of(OWNER_ID),
                                Optional.empty(),
                                OptionalLong.of(1L)),
                        101L).status());
        Assertions.assertEquals(AiProposalReviewStatus.AGENT_NOT_BOUND,
                gate.review(payload, authorized(OTHER_AGENT_ID), 101L).status());
        Assertions.assertEquals(1, gate.activeRequestCount());
    }

    @Test
    void requiresExactBotNonceGenerationAndRevisionWithoutLettingMismatchesConsumeRequest() {
        AiProposalSessionGate gate = new AiProposalSessionGate();
        AiProposalRequestEnvelope request = gate.open(
                BOT_ID, OWNER_ID, AGENT_ID, 1L, 7L, 100L, 20, policy());

        Assertions.assertEquals(AiProposalReviewStatus.NO_ACTIVE_REQUEST,
                gate.review(new AiProposalPayload(
                                BOT_ID,
                                AGENT_ID,
                                request.generation(),
                                UUID.fromString("00000000-0000-0000-0000-000000000398"),
                                request.nonce(),
                                request.revision(),
                                "safe",
                                List.of(safeToolCall())),
                        authorized(AGENT_ID),
                        101L).status());
        Assertions.assertEquals(AiProposalReviewStatus.BOT_MISMATCH,
                gate.review(new AiProposalPayload(
                                OTHER_BOT_ID,
                                AGENT_ID,
                                request.generation(),
                                request.requestId(),
                                request.nonce(),
                                request.revision(),
                                "safe",
                                List.of(safeToolCall())),
                        authorized(AGENT_ID),
                        101L).status());
        Assertions.assertEquals(AiProposalReviewStatus.AGENT_MISMATCH,
                gate.review(new AiProposalPayload(
                                BOT_ID,
                                OTHER_AGENT_ID,
                                request.generation(),
                                request.requestId(),
                                request.nonce(),
                                request.revision(),
                                "safe",
                                List.of(safeToolCall())),
                        authorized(OTHER_AGENT_ID),
                        101L).status());
        Assertions.assertEquals(AiProposalReviewStatus.NONCE_MISMATCH,
                gate.review(new AiProposalPayload(
                                BOT_ID,
                                AGENT_ID,
                                request.generation(),
                                request.requestId(),
                                UUID.fromString("00000000-0000-0000-0000-000000000399"),
                                request.revision(),
                                "safe",
                                List.of(safeToolCall())),
                        authorized(AGENT_ID),
                        101L).status());
        Assertions.assertEquals(AiProposalReviewStatus.REVISION_MISMATCH,
                gate.review(new AiProposalPayload(
                                BOT_ID,
                                AGENT_ID,
                                request.generation(),
                                request.requestId(),
                                request.nonce(),
                                request.revision() + 1L,
                                "safe",
                                List.of(safeToolCall())),
                        authorized(AGENT_ID),
                        101L).status());
        Assertions.assertEquals(1, gate.activeRequestCount());

        Assertions.assertTrue(gate.review(
                        payload(request, "safe", List.of(safeToolCall())),
                        authorized(AGENT_ID),
                        101L)
                .acceptedNoExecution());
    }

    @Test
    void expiresAndConsumesARequestExactlyOnce() {
        AiProposalSessionGate gate = new AiProposalSessionGate();
        AiProposalRequestEnvelope request = gate.open(
                BOT_ID, OWNER_ID, AGENT_ID, 1L, 7L, 100L, 5, policy());

        Assertions.assertEquals(AiProposalReviewStatus.EXPIRED,
                gate.review(payload(request, "safe", List.of(safeToolCall())),
                        authorized(AGENT_ID),
                        105L).status());
        Assertions.assertEquals(AiProposalReviewStatus.NO_ACTIVE_REQUEST,
                gate.review(payload(request, "safe", List.of(safeToolCall())),
                        authorized(AGENT_ID),
                        105L).status());
        Assertions.assertEquals(0, gate.activeRequestCount());

        AiProposalRequestEnvelope naturallyExpired = gate.open(
                BOT_ID, OWNER_ID, AGENT_ID, 1L, 8L, 200L, 1, policy());
        Assertions.assertEquals(List.of(naturallyExpired),
                gate.closeExpiredThrough(201L));
        Assertions.assertEquals(0, gate.activeRequestCount());
        Assertions.assertEquals(0, gate.expireThrough(201L));
    }

    @Test
    void consumesMatchingMalformedAndFirewallRejectedResponsesWithoutExposingText() {
        AiProposalSessionGate malformedGate = new AiProposalSessionGate();
        AiProposalRequestEnvelope malformedRequest = malformedGate.open(
                BOT_ID, OWNER_ID, AGENT_ID, 1L, 7L, 100L, 20, policy());
        AiProposalReview malformed = malformedGate.review(
                payload(malformedRequest,
                        "this model text is never returned",
                        List.of(new AiProposalToolCallPayload(
                                "call_1", "safe_tool", "{not-json"))),
                authorized(AGENT_ID),
                101L);
        Assertions.assertEquals(AiProposalReviewStatus.MALFORMED_TOOL_CALL,
                malformed.status());
        Assertions.assertTrue(malformed.proposal().isEmpty());
        Assertions.assertEquals(0, malformedGate.activeRequestCount());
        Assertions.assertFalse(malformed.toString().contains("never returned"));

        AiProposalSessionGate firewallGate = new AiProposalSessionGate();
        AiProposalRequestEnvelope firewallRequest = firewallGate.open(
                BOT_ID, OWNER_ID, AGENT_ID, 1L, 8L, 100L, 20, policy());
        AiProposalReview rejected = firewallGate.review(
                payload(firewallRequest,
                        "safe",
                        List.of(new AiProposalToolCallPayload(
                                "call_1", "unregistered_tool", "{}"))),
                authorized(AGENT_ID),
                101L);
        Assertions.assertEquals(AiProposalReviewStatus.TOOL_REJECTED,
                rejected.status());
        Assertions.assertEquals(ToolFirewallRejectionCode.TOOL_NOT_WHITELISTED,
                rejected.firewallRejection().orElseThrow().code());
        Assertions.assertTrue(rejected.proposal().isEmpty());
        Assertions.assertEquals(0, firewallGate.activeRequestCount());
    }

    @Test
    void terminalReceiptExistsOnlyAfterTheExactGateEnvelopeWasConsumed() {
        AiProposalSessionGate gate = new AiProposalSessionGate();
        AiProposalRequestEnvelope request = gate.open(
                BOT_ID,
                OWNER_ID,
                AGENT_ID,
                1L,
                7L,
                100L,
                20,
                policy());

        AiProposalReviewReceipt mismatched = gate.reviewWithReceipt(
                new AiProposalPayload(
                        BOT_ID,
                        AGENT_ID,
                        request.generation(),
                        request.requestId(),
                        UUID.fromString("00000000-0000-0000-0000-000000000499"),
                        request.revision(),
                        "safe",
                        List.of(safeToolCall())),
                authorized(AGENT_ID),
                101L);
        Assertions.assertEquals(AiProposalReviewStatus.NONCE_MISMATCH,
                mismatched.review().status());
        Assertions.assertTrue(mismatched.terminalDispatch().isEmpty());
        Assertions.assertEquals(1, gate.activeRequestCount());

        AiProposalReviewReceipt terminal = gate.reviewWithReceipt(
                payload(request, "safe", List.of(new AiProposalToolCallPayload(
                        "call_1", "safe_tool", "{not-json"))),
                authorized(AGENT_ID),
                101L);
        Assertions.assertEquals(AiProposalReviewStatus.MALFORMED_TOOL_CALL,
                terminal.review().status());
        Assertions.assertEquals(
                AiRequestDispatchReceipt.fromEnvelope(request),
                terminal.terminalDispatch().orElseThrow());
        Assertions.assertEquals(AiRequestPurpose.UNSPECIFIED_V1,
                terminal.terminalDispatch().orElseThrow().purpose());
        Assertions.assertEquals(0, gate.activeRequestCount());
        Assertions.assertTrue(gate.reviewWithReceipt(
                        payload(request, "safe", List.of(safeToolCall())),
                        authorized(AGENT_ID),
                        101L)
                .terminalDispatch().isEmpty());
    }

    @Test
    void reviewOnlyPurposeTerminallyRejectsFreeProseAndMalformedCallsBeforeGenericReview() {
        AiProposalSessionGate correlationGate = new AiProposalSessionGate();
        AiProposalRequestEnvelope correlationRequest = reviewOnlyRequest(correlationGate, 6L);
        AiProposalPayload staleCorrelation = new AiProposalPayload(
                correlationRequest.botId(),
                correlationRequest.agentId(),
                correlationRequest.generation(),
                correlationRequest.requestId(),
                UUID.fromString("00000000-0000-0000-0000-000000000499"),
                correlationRequest.revision(),
                "free prose cannot consume a different nonce",
                List.of(new AiProposalToolCallPayload(
                        "call_1", AiReviewOnlyProposalShape.TOOL_NAME, "{}")));
        Assertions.assertEquals(AiProposalReviewStatus.NONCE_MISMATCH,
                correlationGate.review(staleCorrelation, authorized(AGENT_ID), 101L).status());
        Assertions.assertEquals(1, correlationGate.activeRequestCount(),
                "purpose-shape validation must wait for exact correlation");

        AiProposalSessionGate proseGate = new AiProposalSessionGate();
        AiProposalRequestEnvelope proseRequest = reviewOnlyRequest(proseGate, 7L);
        AiProposalPayload freeProse = payload(
                proseRequest,
                "this free prose must never reach the generic tool path",
                List.of(new AiProposalToolCallPayload(
                        "call_1", AiReviewOnlyProposalShape.TOOL_NAME, "{}")));

        AiProposalReviewReceipt proseRejected = proseGate.reviewWithReceipt(
                freeProse, authorized(AGENT_ID), 101L);
        Assertions.assertEquals(AiProposalReviewStatus.REVIEW_CONTRACT_REJECTED,
                proseRejected.review().status());
        Assertions.assertTrue(proseRejected.review().proposal().isEmpty());
        Assertions.assertEquals(AiRequestDispatchReceipt.fromEnvelope(proseRequest),
                proseRejected.terminalDispatch().orElseThrow());
        Assertions.assertEquals(0, proseGate.activeRequestCount());
        Assertions.assertFalse(proseRejected.review().toString().contains("free prose"));

        AiProposalReviewReceipt replay = proseGate.reviewWithReceipt(
                freeProse, authorized(AGENT_ID), 101L);
        Assertions.assertEquals(AiProposalReviewStatus.NO_ACTIVE_REQUEST,
                replay.review().status());
        Assertions.assertTrue(replay.terminalDispatch().isEmpty());

        AiProposalSessionGate malformedGate = new AiProposalSessionGate();
        AiProposalRequestEnvelope malformedRequest = reviewOnlyRequest(malformedGate, 8L);
        AiProposalReview malformedRejected = malformedGate.review(
                payload(
                        malformedRequest,
                        "",
                        List.of(new AiProposalToolCallPayload(
                                "call_1",
                                AiReviewOnlyProposalShape.TOOL_NAME,
                                "{not-json"))),
                authorized(AGENT_ID),
                101L);
        Assertions.assertEquals(AiProposalReviewStatus.REVIEW_CONTRACT_REJECTED,
                malformedRejected.status());
        Assertions.assertTrue(malformedRejected.proposal().isEmpty());
        Assertions.assertEquals(0, malformedGate.activeRequestCount());

        AiProposalSessionGate extraToolGate = new AiProposalSessionGate();
        AiProposalRequestEnvelope extraToolRequest = reviewOnlyRequest(extraToolGate, 9L);
        AiProposalReview extraToolRejected = extraToolGate.review(
                payload(
                        extraToolRequest,
                        "",
                        List.of(
                                new AiProposalToolCallPayload(
                                        "call_1",
                                        AiReviewOnlyProposalShape.TOOL_NAME,
                                        "{}"),
                                new AiProposalToolCallPayload(
                                        "call_2", "safe_tool", "{}"))),
                authorized(AGENT_ID),
                101L);
        Assertions.assertEquals(AiProposalReviewStatus.REVIEW_CONTRACT_REJECTED,
                extraToolRejected.status());
        Assertions.assertTrue(extraToolRejected.proposal().isEmpty());
        Assertions.assertEquals(0, extraToolGate.activeRequestCount());
    }

    @Test
    void serverRetainsToolPolicyAndConsumesForbiddenToolResponsesBeforeCodecOrFirewall() {
        AiProposalSessionGate gate = new AiProposalSessionGate();
        AiProposalRequestEnvelope request = gate.open(
                BOT_ID,
                OWNER_ID,
                AGENT_ID,
                1L,
                9L,
                100L,
                20,
                false,
                policy());
        AiProposalPayload malformedButForbidden = payload(
                request,
                "safe",
                List.of(new AiProposalToolCallPayload(
                        "call_1", "safe_tool", "{not-json")));

        Assertions.assertEquals(AiProposalReviewStatus.TOOL_CALLS_NOT_ALLOWED,
                gate.review(malformedButForbidden, authorized(AGENT_ID), 101L).status());
        Assertions.assertEquals(0, gate.activeRequestCount());
        Assertions.assertEquals(AiProposalReviewStatus.NO_ACTIVE_REQUEST,
                gate.review(malformedButForbidden, authorized(AGENT_ID), 101L).status());
    }

    @Test
    void implicitReplacementIsRejectedWithoutOrphaningThePriorRequest() {
        AiProposalSessionGate gate = new AiProposalSessionGate();
        AiProposalRequestEnvelope first = gate.open(
                BOT_ID, OWNER_ID, AGENT_ID, 1L, 7L, 100L, 20, policy());

        Assertions.assertThrows(IllegalStateException.class,
                () -> gate.open(
                        BOT_ID, OWNER_ID, AGENT_ID, 1L, 8L, 100L, 20, policy()));
        Assertions.assertEquals(1, gate.activeRequestCount());
        Assertions.assertTrue(gate.review(
                        payload(first, "safe", List.of(safeToolCall())),
                        authorized(AGENT_ID),
                        101L)
                .acceptedNoExecution());
    }

    @Test
    void explicitReplacementReturnsOldEnvelopeAndLateCloseCannotRemoveNewRequest() {
        AiProposalSessionGate gate = new AiProposalSessionGate();
        AiProposalRequestEnvelope first = gate.open(
                BOT_ID, OWNER_ID, AGENT_ID, 1L, 7L, 100L, 20, policy());
        AiProposalOpenResult result = gate.openReplacing(
                BOT_ID,
                OWNER_ID,
                AGENT_ID,
                1L,
                8L,
                100L,
                20,
                AiRequestPurpose.UNSPECIFIED_V1,
                true,
                policy());
        AiProposalRequestEnvelope replacement = result.opened();

        Assertions.assertEquals(first, result.replaced().orElseThrow());
        Assertions.assertNotEquals(first.requestId(), replacement.requestId());
        Assertions.assertEquals(AiProposalReviewStatus.NO_ACTIVE_REQUEST,
                gate.review(payload(first, "safe", List.of(safeToolCall())),
                        authorized(AGENT_ID),
                        101L).status());
        Assertions.assertTrue(gate.closeExact(
                AiRequestDispatchReceipt.fromEnvelope(first)).isEmpty());
        Assertions.assertEquals(1, gate.activeRequestCount());
        Assertions.assertTrue(gate.closeExact(new AiRequestDispatchReceipt(
                replacement.botId(),
                replacement.agentId(),
                replacement.generation(),
                replacement.requestId(),
                replacement.revision(),
                replacement.expiresAtTick(),
                AiRequestPurpose.REVIEW_ONLY_V1)).isEmpty());
        Assertions.assertEquals(1, gate.activeRequestCount());
        Assertions.assertEquals(replacement,
                gate.closeExact(AiRequestDispatchReceipt.fromEnvelope(replacement)).orElseThrow());
        Assertions.assertEquals(AiProposalReviewStatus.NO_ACTIVE_REQUEST,
                gate.review(payload(replacement, "safe", List.of(safeToolCall())),
                        authorized(AGENT_ID),
                        101L).status());
    }

    @Test
    void exactLookupLeavesTheLiveGateOpenAndRejectsAReceiptPurposeDrift() {
        AiProposalSessionGate gate = new AiProposalSessionGate();
        AiProposalRequestEnvelope request = gate.open(
                BOT_ID,
                OWNER_ID,
                AGENT_ID,
                1L,
                7L,
                100L,
                20,
                AiRequestPurpose.REVIEW_ONLY_V1,
                true,
                policy());
        AiRequestDispatchReceipt exact = AiRequestDispatchReceipt.fromEnvelope(request);
        AiRequestDispatchReceipt drifted = new AiRequestDispatchReceipt(
                exact.botId(),
                exact.agentId(),
                exact.generation(),
                exact.requestId(),
                exact.revision(),
                exact.expiresAtTick(),
                AiRequestPurpose.UNSPECIFIED_V1);

        Assertions.assertAll(
                () -> Assertions.assertEquals(request,
                        gate.findExact(exact).orElseThrow()),
                () -> Assertions.assertTrue(gate.findExact(drifted).isEmpty()),
                () -> Assertions.assertEquals(1, gate.activeRequestCount()),
                () -> Assertions.assertEquals(request,
                        gate.closeExact(exact).orElseThrow()));
    }

    @Test
    void exactProposalLookupIsNonTerminalAndRequiresEveryPayloadCorrelationField() {
        AiProposalSessionGate gate = new AiProposalSessionGate();
        AiProposalRequestEnvelope request = reviewOnlyRequest(gate, 7L);
        AiProposalPayload exact = payload(request, "safe", List.of(safeToolCall()));
        AiProposalPayload driftedNonce = new AiProposalPayload(
                request.botId(),
                request.agentId(),
                request.generation(),
                request.requestId(),
                UUID.fromString("00000000-0000-0000-0000-000000000401"),
                request.revision(),
                "safe",
                List.of(safeToolCall()));
        AiProposalPayload driftedRevision = new AiProposalPayload(
                request.botId(),
                request.agentId(),
                request.generation(),
                request.requestId(),
                request.nonce(),
                request.revision() + 1L,
                "safe",
                List.of(safeToolCall()));

        Assertions.assertAll(
                () -> Assertions.assertEquals(request,
                        gate.findExactForProposal(exact).orElseThrow()),
                () -> Assertions.assertTrue(gate.findExactForProposal(driftedNonce).isEmpty()),
                () -> Assertions.assertTrue(gate.findExactForProposal(driftedRevision).isEmpty()),
                () -> Assertions.assertEquals(1, gate.activeRequestCount()),
                () -> Assertions.assertEquals(request,
                        gate.closeExact(AiRequestDispatchReceipt.fromEnvelope(request))
                                .orElseThrow()));
    }

    @Test
    void closeOperationsReturnTheExactOutstandingEnvelopeForClientCancellation() {
        AiProposalSessionGate gate = new AiProposalSessionGate();
        AiProposalRequestEnvelope first = gate.open(
                BOT_ID, OWNER_ID, AGENT_ID, 1L, 7L, 100L, 20, policy());

        Assertions.assertEquals(first, gate.closeBot(BOT_ID).orElseThrow());
        Assertions.assertTrue(gate.closeBot(BOT_ID).isEmpty());

        AiProposalRequestEnvelope second = gate.open(
                BOT_ID, OWNER_ID, AGENT_ID, 1L, 8L, 100L, 20, policy());
        Assertions.assertEquals(List.of(second), gate.closeAll());
        Assertions.assertEquals(0, gate.activeRequestCount());
    }

    @Test
    void rejectsAStaleBodyGenerationWithoutConsumingTheCurrentRequest() {
        AiProposalSessionGate gate = new AiProposalSessionGate();
        AiProposalRequestEnvelope request = gate.open(
                BOT_ID, OWNER_ID, AGENT_ID, 1L, 7L, 100L, 20, policy());
        AiProposalPayload stalePayload = new AiProposalPayload(
                request.botId(),
                request.agentId(),
                2L,
                request.requestId(),
                request.nonce(),
                request.revision(),
                "safe",
                List.of(safeToolCall()));

        Assertions.assertEquals(AiProposalReviewStatus.GENERATION_MISMATCH,
                gate.review(stalePayload, authorized(AGENT_ID), 101L).status());
        Assertions.assertEquals(1, gate.activeRequestCount());
        Assertions.assertTrue(gate.review(
                        payload(request, "safe", List.of(safeToolCall())),
                        authorized(AGENT_ID), 101L)
                .acceptedNoExecution());

        AiProposalRequestEnvelope nextRequest = gate.open(
                BOT_ID, OWNER_ID, AGENT_ID, 1L, 8L, 101L, 20, policy());
        Assertions.assertEquals(AiProposalReviewStatus.GENERATION_MISMATCH,
                gate.review(payload(nextRequest, "safe", List.of(safeToolCall())),
                        authorized(AGENT_ID, 2L), 101L).status());
        Assertions.assertEquals(0, gate.activeRequestCount());
    }

    @Test
    void rejectsARequestWhenThePersistentOwnerChangedAfterIssuance() {
        AiProposalSessionGate gate = new AiProposalSessionGate();
        AiProposalRequestEnvelope request = gate.open(
                BOT_ID, OWNER_ID, AGENT_ID, 1L, 7L, 100L, 20, policy());
        UUID replacementOwner = UUID.fromString(
                "00000000-0000-0000-0000-000000000152");
        AiProposalAuthority changedOwner = new AiProposalAuthority(
                true,
                true,
                Optional.of(replacementOwner),
                Optional.of(AGENT_ID),
                OptionalLong.of(1L));

        Assertions.assertEquals(AiProposalReviewStatus.OWNER_CHANGED,
                gate.review(payload(request, "safe", List.of(safeToolCall())),
                        changedOwner, 101L).status());
        Assertions.assertEquals(0, gate.activeRequestCount());
    }

    private static AiProposalAuthority authorized(UUID activeAgentId) {
        return authorized(activeAgentId, 1L);
    }

    private static AiProposalAuthority authorized(
            UUID activeAgentId, long generation) {
        return new AiProposalAuthority(
                true,
                true,
                Optional.of(OWNER_ID),
                Optional.of(activeAgentId),
                OptionalLong.of(generation));
    }

    private static AiProposalPayload payload(
            AiProposalRequestEnvelope request,
            String outputText,
            List<AiProposalToolCallPayload> toolCalls) {
        return new AiProposalPayload(
                request.botId(),
                request.agentId(),
                request.generation(),
                request.requestId(),
                request.nonce(),
                request.revision(),
                outputText,
                toolCalls);
    }

    private static AiProposalToolCallPayload safeToolCall() {
        return new AiProposalToolCallPayload("call_1", "safe_tool", "{}");
    }

    private static AiProposalRequestEnvelope reviewOnlyRequest(
            AiProposalSessionGate gate, long revision) {
        return gate.open(
                BOT_ID,
                OWNER_ID,
                AGENT_ID,
                1L,
                revision,
                100L,
                20,
                AiRequestPurpose.REVIEW_ONLY_V1,
                true,
                policy());
    }

    private static ToolFirewallPolicy policy() {
        return new ToolFirewallPolicy(
                Map.of("safe_tool", new ToolDefinition(
                        "safe_tool", ToolRiskLevel.SAFE, Map.of())),
                Set.of("safe_tool"),
                ToolRiskLevel.SAFE,
                1);
    }
}
