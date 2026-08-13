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
import io.github.greytaiwolf.botplayer.network.payload.AiRequestCancellationPayload;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AiClientSponsoredRequestTest {
    private static final UUID SERVER_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000001");
    private static final UUID BOT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000101");
    private static final UUID OWNER_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000151");
    private static final UUID AGENT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000201");
    private static final UUID TEMPLATE_REQUEST_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000302");

    @Test
    void gateIdBecomesTheOnlySchedulerAndClientCorrelation() {
        AiClientSponsoredRequest binding = binding();

        Assertions.assertEquals(binding.dispatch().requestId(),
                binding.dispatch().toAiRequest().requestId());
        Assertions.assertEquals(binding.dispatch().requestId(),
                binding.scheduledRequest().request().requestId());
        Assertions.assertNotEquals(TEMPLATE_REQUEST_ID,
                binding.scheduledRequest().request().requestId());
        Assertions.assertEquals(BOT_ID, binding.scheduledRequest().botId());
        Assertions.assertEquals(OWNER_ID, binding.scheduledRequest().ownerId());
        Assertions.assertEquals(AGENT_ID, binding.scheduledRequest().agentId());
        Assertions.assertEquals(7L, binding.scheduledRequest().revision());

        String diagnostic = binding.toString();
        Assertions.assertFalse(diagnostic.contains(
                binding.dispatch().nonce().toString()));
        Assertions.assertFalse(diagnostic.contains(OWNER_ID.toString()));
        Assertions.assertFalse(diagnostic.contains("请求正文不能进入诊断"));
    }

    @Test
    void requiresTheCompleteProposalTupleButDoesNotAuthorizeIt() {
        AiClientSponsoredRequest binding = binding();
        AiProposalPayload matching = payload(
                BOT_ID,
                AGENT_ID,
                4L,
                binding.dispatch().requestId(),
                binding.dispatch().nonce(),
                7L);
        Assertions.assertTrue(binding.matches(matching));

        Assertions.assertFalse(binding.matches(payload(
                UUID.fromString("00000000-0000-0000-0000-000000000102"),
                AGENT_ID,
                4L,
                binding.dispatch().requestId(),
                binding.dispatch().nonce(),
                7L)));
        Assertions.assertFalse(binding.matches(payload(
                BOT_ID,
                UUID.fromString("00000000-0000-0000-0000-000000000202"),
                4L,
                binding.dispatch().requestId(),
                binding.dispatch().nonce(),
                7L)));
        Assertions.assertFalse(binding.matches(payload(
                BOT_ID,
                AGENT_ID,
                5L,
                binding.dispatch().requestId(),
                binding.dispatch().nonce(),
                7L)));
        Assertions.assertFalse(binding.matches(payload(
                BOT_ID,
                AGENT_ID,
                4L,
                TEMPLATE_REQUEST_ID,
                binding.dispatch().nonce(),
                7L)));
        Assertions.assertFalse(binding.matches(payload(
                BOT_ID,
                AGENT_ID,
                4L,
                binding.dispatch().requestId(),
                UUID.fromString("00000000-0000-0000-0000-000000000402"),
                7L)));
        Assertions.assertFalse(binding.matches(payload(
                BOT_ID,
                AGENT_ID,
                4L,
                binding.dispatch().requestId(),
                binding.dispatch().nonce(),
                8L)));
    }

    @Test
    void derivesCancellationAndTerminalReceiptFromTheSameGateIdentity() {
        AiClientSponsoredRequest binding = binding();
        AiRequestCancellationPayload cancellation = binding.cancellationPayload();
        Assertions.assertTrue(cancellation.matches(binding.dispatch()));
        Assertions.assertEquals(binding.dispatch().requestId(), cancellation.requestId());
        Assertions.assertEquals(binding.dispatch().nonce(), cancellation.nonce());

        AiRequestDispatchReceipt exact = new AiRequestDispatchReceipt(
                BOT_ID,
                AGENT_ID,
                4L,
                binding.dispatch().requestId(),
                7L,
                binding.dispatch().expiresAtTick(),
                AiRequestPurpose.UNSPECIFIED_V1);
        Assertions.assertEquals(exact, binding.dispatchReceipt());
        Assertions.assertTrue(binding.matches(exact));
        Assertions.assertFalse(binding.matches(new AiRequestDispatchReceipt(
                BOT_ID,
                AGENT_ID,
                4L,
                binding.dispatch().requestId(),
                7L,
                binding.dispatch().expiresAtTick(),
                AiRequestPurpose.REVIEW_ONLY_V1)));
        Assertions.assertFalse(binding.matches(new AiRequestDispatchReceipt(
                BOT_ID,
                AGENT_ID,
                4L,
                binding.dispatch().requestId(),
                7L,
                binding.dispatch().expiresAtTick() + 1L,
                AiRequestPurpose.UNSPECIFIED_V1)));
    }

    @Test
    void serverTickExpiryIsHalfOpenAndNeverUsesTheClientWallClock() {
        AiClientSponsoredRequest binding = binding();

        long expiresAtTick = binding.dispatch().expiresAtTick();
        Assertions.assertFalse(binding.expiresAtOrBefore(expiresAtTick - 1L));
        Assertions.assertTrue(binding.expiresAtOrBefore(expiresAtTick));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> binding.expiresAtOrBefore(-1L));
    }

    @Test
    void replacementCancellationNeverMatchesTheNewGateBinding() {
        AiProposalSessionGate gate = new AiProposalSessionGate();
        AiClientSponsoredRequest first = binding(gate.open(
                BOT_ID,
                OWNER_ID,
                AGENT_ID,
                4L,
                7L,
                100L,
                120,
                true,
                new ToolFirewallPolicy(
                        Map.of(), Set.of(), ToolRiskLevel.SAFE, 1)));
        AiProposalOpenResult replacement = gate.openReplacing(
                BOT_ID,
                OWNER_ID,
                AGENT_ID,
                4L,
                8L,
                100L,
                120,
                AiRequestPurpose.UNSPECIFIED_V1,
                true,
                new ToolFirewallPolicy(
                        Map.of(), Set.of(), ToolRiskLevel.SAFE, 1));
        AiClientSponsoredRequest next = binding(replacement.opened());

        Assertions.assertEquals(first.dispatch().requestId(),
                replacement.replaced().orElseThrow().requestId());
        Assertions.assertFalse(first.cancellationPayload().matches(next.dispatch()));
        Assertions.assertFalse(next.cancellationPayload().matches(first.dispatch()));
    }

    private static AiClientSponsoredRequest binding() {
        return binding(envelope());
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
                                "请求正文不能进入诊断")),
                        new AiRequestOptions(
                                512,
                                500L,
                                AiResponseFormat.TEXT,
                                false,
                                true,
                                Optional.empty()),
                        Optional.empty()));
    }

    private static AiProposalRequestEnvelope envelope() {
        return new AiProposalSessionGate().open(
                BOT_ID,
                OWNER_ID,
                AGENT_ID,
                4L,
                7L,
                100L,
                120,
                true,
                new ToolFirewallPolicy(
                        Map.of(), Set.of(), ToolRiskLevel.SAFE, 1));
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
                "不可信输出",
                List.of(new AiProposalToolCallPayload(
                        "call_1", "safe_tool", "{}")));
    }
}
