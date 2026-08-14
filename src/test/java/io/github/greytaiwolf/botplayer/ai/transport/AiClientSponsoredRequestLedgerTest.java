package io.github.greytaiwolf.botplayer.ai.transport;

import io.github.greytaiwolf.botplayer.ai.AiMessage;
import io.github.greytaiwolf.botplayer.ai.AiMessageRole;
import io.github.greytaiwolf.botplayer.ai.AiRequest;
import io.github.greytaiwolf.botplayer.ai.AiRequestOptions;
import io.github.greytaiwolf.botplayer.ai.AiResponseFormat;
import io.github.greytaiwolf.botplayer.ai.tool.ToolFirewallPolicy;
import io.github.greytaiwolf.botplayer.ai.tool.ToolRiskLevel;
import io.github.greytaiwolf.botplayer.network.payload.AiProposalPayload;
import io.github.greytaiwolf.botplayer.network.payload.AiProposalToolCallPayload;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AiClientSponsoredRequestLedgerTest {
    private static final UUID SERVER_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000001");
    private static final UUID BOT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000101");
    private static final UUID OTHER_BOT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000102");
    private static final UUID OWNER_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000151");
    private static final UUID AGENT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000201");
    private static final UUID OTHER_AGENT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000202");
    private static final UUID TEMPLATE_REQUEST_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000301");

    @Test
    void openRejectsImplicitSameBotReplacementAndRetainsTheOriginalBinding() {
        AiProposalSessionGate gate = new AiProposalSessionGate();
        AiClientSponsoredRequest first = binding(gate.open(
                BOT_ID, OWNER_ID, AGENT_ID, 4L, 7L, 100L, 20,
                true, policy()));
        AiClientSponsoredRequestLedger ledger = new AiClientSponsoredRequestLedger();
        ledger.open(first);
        AiProposalOpenResult replacement = gate.openReplacing(
                BOT_ID, OWNER_ID, AGENT_ID, 4L, 8L, 100L, 20,
                AiRequestPurpose.UNSPECIFIED_V1, true, policy());
        AiClientSponsoredRequest next = binding(replacement.opened());

        Assertions.assertThrows(IllegalStateException.class,
                () -> ledger.open(next));
        Assertions.assertEquals(1, ledger.activeRequestCount());
        Assertions.assertEquals(first,
                ledger.findMatching(payload(first)).orElseThrow());
        Assertions.assertTrue(ledger.findMatching(payload(next)).isEmpty());
    }

    @Test
    void replaceRequiresTheExactGateDisplacementReceiptAndReturnsOnlyThatBinding() {
        AiProposalSessionGate gate = new AiProposalSessionGate();
        AiClientSponsoredRequest first = binding(gate.open(
                BOT_ID, OWNER_ID, AGENT_ID, 4L, 7L, 100L, 20,
                true, policy()));
        AiClientSponsoredRequestLedger ledger = new AiClientSponsoredRequestLedger();
        ledger.open(first);
        AiProposalOpenResult replacement = gate.openReplacing(
                BOT_ID, OWNER_ID, AGENT_ID, 4L, 8L, 100L, 20,
                AiRequestPurpose.UNSPECIFIED_V1, true, policy());
        AiClientSponsoredRequest next = binding(replacement.opened());

        Assertions.assertThrows(IllegalStateException.class,
                () -> ledger.replace(next, Optional.empty()));
        AiRequestDispatchReceipt wrongRevision = receipt(
                first, first.dispatch().revision() + 1L,
                first.dispatch().expiresAtTick(), first.dispatch().purpose());
        Assertions.assertThrows(IllegalStateException.class,
                () -> ledger.replace(next, Optional.of(wrongRevision)));
        Assertions.assertThrows(IllegalStateException.class,
                () -> ledger.replace(next, Optional.of(receipt(first,
                        first.dispatch().revision(),
                        first.dispatch().expiresAtTick() + 1L,
                        first.dispatch().purpose()))));
        Assertions.assertThrows(IllegalStateException.class,
                () -> ledger.replace(next, Optional.of(receipt(first,
                        first.dispatch().revision(),
                        first.dispatch().expiresAtTick(),
                        AiRequestPurpose.REVIEW_ONLY_V1))));
        Assertions.assertTrue(ledger.findMatching(payload(next)).isEmpty());
        Assertions.assertEquals(first,
                ledger.findMatching(payload(first)).orElseThrow());

        Assertions.assertEquals(first, ledger.replace(next, Optional.of(
                AiRequestDispatchReceipt.fromEnvelope(
                        replacement.replaced().orElseThrow()))).orElseThrow());
        Assertions.assertEquals(1, ledger.activeRequestCount());
        Assertions.assertTrue(ledger.findMatching(payload(first)).isEmpty());
        Assertions.assertEquals(next,
                ledger.findMatching(payload(next)).orElseThrow());
        Assertions.assertFalse(first.cancellationPayload().matches(next.dispatch()));
    }

    @Test
    void replaceUsesAnEmptyGateReceiptOnlyWhenTheLedgerHasNoCurrentBinding() {
        AiClientSponsoredRequest first = binding(new AiProposalSessionGate().open(
                BOT_ID, OWNER_ID, AGENT_ID, 4L, 7L, 100L, 20,
                true, policy()));
        AiClientSponsoredRequestLedger emptyLedger =
                new AiClientSponsoredRequestLedger();

        Assertions.assertTrue(emptyLedger.replace(first, Optional.empty()).isEmpty());
        Assertions.assertEquals(first,
                emptyLedger.findMatching(payload(first)).orElseThrow());

        AiClientSponsoredRequestLedger inconsistentLedger =
                new AiClientSponsoredRequestLedger();
        Assertions.assertThrows(IllegalStateException.class,
                () -> inconsistentLedger.replace(first, Optional.of(
                        first.dispatchReceipt())));
        Assertions.assertEquals(0, inconsistentLedger.activeRequestCount());
    }

    @Test
    void proposalMatchingRequiresTheFullUntrustedCorrelationTuple() {
        AiClientSponsoredRequest binding = binding(new AiProposalSessionGate().open(
                BOT_ID, OWNER_ID, AGENT_ID, 4L, 7L, 100L, 20,
                true, policy()));
        AiClientSponsoredRequestLedger ledger = new AiClientSponsoredRequestLedger();
        ledger.open(binding);

        Assertions.assertEquals(binding,
                ledger.findMatching(payload(binding)).orElseThrow());
        Assertions.assertTrue(ledger.findMatching(payload(
                OTHER_BOT_ID, AGENT_ID, binding.dispatch().generation(),
                binding.dispatch().requestId(), binding.dispatch().nonce(),
                binding.dispatch().revision())).isEmpty());
        Assertions.assertTrue(ledger.findMatching(payload(
                BOT_ID, OTHER_AGENT_ID, binding.dispatch().generation(),
                binding.dispatch().requestId(), binding.dispatch().nonce(),
                binding.dispatch().revision())).isEmpty());
        Assertions.assertTrue(ledger.findMatching(payload(
                BOT_ID, AGENT_ID, binding.dispatch().generation() + 1L,
                binding.dispatch().requestId(), binding.dispatch().nonce(),
                binding.dispatch().revision())).isEmpty());
        Assertions.assertTrue(ledger.findMatching(payload(
                BOT_ID, AGENT_ID, binding.dispatch().generation(),
                UUID.fromString("00000000-0000-0000-0000-000000000302"),
                binding.dispatch().nonce(), binding.dispatch().revision())).isEmpty());
        Assertions.assertTrue(ledger.findMatching(payload(
                BOT_ID, AGENT_ID, binding.dispatch().generation(),
                binding.dispatch().requestId(), UUID.fromString(
                        "00000000-0000-0000-0000-000000000402"),
                binding.dispatch().revision())).isEmpty());
        Assertions.assertTrue(ledger.findMatching(payload(
                BOT_ID, AGENT_ID, binding.dispatch().generation(),
                binding.dispatch().requestId(), binding.dispatch().nonce(),
                binding.dispatch().revision() + 1L)).isEmpty());
        Assertions.assertEquals(1, ledger.activeRequestCount());
    }

    @Test
    void exactCloseCannotLetAnOldOrDriftedReceiptRetireTheReplacement() {
        AiProposalSessionGate gate = new AiProposalSessionGate();
        AiClientSponsoredRequest first = binding(gate.open(
                BOT_ID, OWNER_ID, AGENT_ID, 4L, 7L, 100L, 20,
                true, policy()));
        AiClientSponsoredRequestLedger ledger = new AiClientSponsoredRequestLedger();
        ledger.open(first);
        AiProposalOpenResult replacement = gate.openReplacing(
                BOT_ID, OWNER_ID, AGENT_ID, 4L, 8L, 100L, 20,
                AiRequestPurpose.UNSPECIFIED_V1, true, policy());
        AiClientSponsoredRequest next = binding(replacement.opened());
        ledger.replace(next, Optional.of(AiRequestDispatchReceipt.fromEnvelope(
                replacement.replaced().orElseThrow())));

        Assertions.assertTrue(ledger.closeExact(first.dispatchReceipt()).isEmpty());
        Assertions.assertTrue(ledger.closeExact(new AiRequestDispatchReceipt(
                OTHER_BOT_ID,
                next.dispatch().agentId(),
                next.dispatch().generation(),
                next.dispatch().requestId(),
                next.dispatch().revision(),
                next.dispatch().expiresAtTick(),
                next.dispatch().purpose())).isEmpty());
        Assertions.assertTrue(ledger.closeExact(new AiRequestDispatchReceipt(
                next.dispatch().botId(),
                OTHER_AGENT_ID,
                next.dispatch().generation(),
                next.dispatch().requestId(),
                next.dispatch().revision(),
                next.dispatch().expiresAtTick(),
                next.dispatch().purpose())).isEmpty());
        Assertions.assertTrue(ledger.closeExact(new AiRequestDispatchReceipt(
                next.dispatch().botId(),
                next.dispatch().agentId(),
                next.dispatch().generation() + 1L,
                next.dispatch().requestId(),
                next.dispatch().revision(),
                next.dispatch().expiresAtTick(),
                next.dispatch().purpose())).isEmpty());
        Assertions.assertTrue(ledger.closeExact(receipt(next,
                next.dispatch().revision() + 1L,
                next.dispatch().expiresAtTick(),
                next.dispatch().purpose())).isEmpty());
        Assertions.assertTrue(ledger.closeExact(receipt(next,
                next.dispatch().revision(), next.dispatch().expiresAtTick() + 1L,
                next.dispatch().purpose())).isEmpty());
        Assertions.assertTrue(ledger.closeExact(receipt(next,
                next.dispatch().revision(), next.dispatch().expiresAtTick(),
                AiRequestPurpose.REVIEW_ONLY_V1)).isEmpty());
        Assertions.assertEquals(next,
                ledger.closeExact(next.dispatchReceipt()).orElseThrow());
        Assertions.assertTrue(ledger.closeExact(next.dispatchReceipt()).isEmpty());
        Assertions.assertEquals(0, ledger.activeRequestCount());
    }

    @Test
    void expiryBotCloseAndShutdownReturnOriginalExactBindings() {
        AiClientSponsoredRequest expiring = binding(new AiProposalSessionGate().open(
                BOT_ID, OWNER_ID, AGENT_ID, 4L, 7L, 100L, 20,
                true, policy()));
        AiClientSponsoredRequest retained = binding(new AiProposalSessionGate().open(
                OTHER_BOT_ID, OWNER_ID, OTHER_AGENT_ID, 5L, 9L, 100L, 40,
                true, policy()));
        AiClientSponsoredRequestLedger ledger = new AiClientSponsoredRequestLedger();
        ledger.open(expiring);
        ledger.open(retained);

        Assertions.assertTrue(ledger.closeExpiredThrough(
                expiring.dispatch().expiresAtTick() - 1L).isEmpty());
        Assertions.assertEquals(List.of(expiring), ledger.closeExpiredThrough(
                expiring.dispatch().expiresAtTick()));
        Assertions.assertEquals(retained, ledger.closeBot(OTHER_BOT_ID).orElseThrow());
        Assertions.assertTrue(ledger.closeBot(OTHER_BOT_ID).isEmpty());

        ledger.open(expiring);
        ledger.open(retained);
        Assertions.assertEquals(List.of(expiring, retained), ledger.closeAll());
        Assertions.assertEquals(0, ledger.activeRequestCount());
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> ledger.closeExpiredThrough(-1L));
    }

    @Test
    void diagnosticDoesNotRevealOwnerNonceOrPrompt() {
        AiClientSponsoredRequest binding = binding(new AiProposalSessionGate().open(
                BOT_ID, OWNER_ID, AGENT_ID, 4L, 7L, 100L, 20,
                true, policy()));
        AiClientSponsoredRequestLedger ledger = new AiClientSponsoredRequestLedger();
        ledger.open(binding);

        String diagnostic = ledger.toString();

        Assertions.assertFalse(diagnostic.contains(OWNER_ID.toString()));
        Assertions.assertFalse(diagnostic.contains(
                binding.dispatch().nonce().toString()));
        Assertions.assertFalse(diagnostic.contains("不可信请求正文"));
        Assertions.assertEquals("AiClientSponsoredRequestLedger[activeRequestCount=1]",
                diagnostic);
    }

    private static AiClientSponsoredRequest binding(
            AiProposalRequestEnvelope envelope) {
        return AiClientSponsoredRequest.bind(
                SERVER_ID,
                envelope,
                1_000L,
                2_000L,
                "deepseek",
                new AiRequest(
                        TEMPLATE_REQUEST_ID,
                        "deepseek-chat",
                        List.of(new AiMessage(AiMessageRole.USER,
                                "不可信请求正文")),
                        new AiRequestOptions(
                                512,
                                500L,
                                AiResponseFormat.TEXT,
                                false,
                                true,
                                Optional.empty()),
                        Optional.empty()));
    }

    private static AiProposalPayload payload(AiClientSponsoredRequest binding) {
        return payload(
                binding.dispatch().botId(),
                binding.dispatch().agentId(),
                binding.dispatch().generation(),
                binding.dispatch().requestId(),
                binding.dispatch().nonce(),
                binding.dispatch().revision());
    }

    private static AiProposalPayload payload(
            UUID botId,
            UUID agentId,
            long generation,
            UUID requestId,
            UUID nonce,
            long revision) {
        return new AiProposalPayload(
                botId,
                agentId,
                generation,
                requestId,
                nonce,
                revision,
                "untrusted",
                List.of(new AiProposalToolCallPayload("call_1", "safe_tool", "{}")));
    }

    private static AiRequestDispatchReceipt receipt(
            AiClientSponsoredRequest binding,
            long revision,
            long expiresAtTick,
            AiRequestPurpose purpose) {
        return new AiRequestDispatchReceipt(
                binding.dispatch().botId(),
                binding.dispatch().agentId(),
                binding.dispatch().generation(),
                binding.dispatch().requestId(),
                revision,
                expiresAtTick,
                purpose);
    }

    private static ToolFirewallPolicy policy() {
        return new ToolFirewallPolicy(
                Map.of(), Set.of(), ToolRiskLevel.SAFE, 1);
    }
}
