package io.github.greytaiwolf.botplayer.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.greytaiwolf.botplayer.ai.tool.ProposedSkillPlan;
import io.github.greytaiwolf.botplayer.ai.tool.ProposedToolCall;
import io.github.greytaiwolf.botplayer.ai.tool.ToolFirewallRejection;
import io.github.greytaiwolf.botplayer.ai.tool.ToolFirewallRejectionCode;
import io.github.greytaiwolf.botplayer.ai.tool.ToolValue;
import io.github.greytaiwolf.botplayer.client.ai.DeepSeekProviderConfig;
import io.github.greytaiwolf.botplayer.client.ai.DeepSeekToolDefinition;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AiPrivacyToStringTest {
    private static final String SECRET = "should-never-appear-in-a-log";
    private static final UUID REQUEST_ID = UUID.fromString(
            "11111111-1111-1111-1111-111111111111");

    @Test
    void modelContextAndUntrustedToolValuesNeverUseRecordDefaultToString() {
        AiMessage system = new AiMessage(AiMessageRole.SYSTEM, SECRET);
        AiMessage user = new AiMessage(AiMessageRole.USER, SECRET);
        AiRequestOptions options = AiRequestOptions.defaults();
        AiRequest request = new AiRequest(
                REQUEST_ID, "deepseek-chat", List.of(system, user), options,
                Optional.empty());
        AiRawToolCall rawCall = new AiRawToolCall(
                "TOPSECRET_CALL_ID", "collect_resource",
                "{\"itemId\":\"" + SECRET + "\"}");
        AiResponse response = new AiResponse(
                REQUEST_ID, "deepseek", "deepseek-chat", AiFinishReason.TOOL_CALLS,
                SECRET, Optional.of(SECRET), Optional.empty(), List.of(rawCall),
                AiTokenUsage.empty());
        AiMemory memory = new AiMemory("memory.source", 0.75D, SECRET);
        MemoryQuery query = new MemoryQuery(SECRET, 1, 512, 512);
        ContextPolicy policy = ContextPolicy.fixedRules(List.of(SECRET));
        AiConversationWindow window = new AiConversationWindow(
                ContextBudget.defaults());
        window.appendUser(user);
        AiConversationHistory history = window.snapshot();
        ContextAssemblyInput input = new ContextAssemblyInput(
                policy, history, user, Optional.of(SECRET));
        ContextAssembly assembly = new ContextAssembly(
                List.of(system, user),
                ContextBudget.estimateTextTokens(SECRET) * 2,
                SECRET.length() * 2,
                0, 0, MemoryRetrievalStatus.NOT_REQUESTED);
        RedactionResult redaction = new RedactionResult(SECRET, 0);
        ToolValue value = new ToolValue.ObjectValue(Map.of(
                "content", new ToolValue.StringValue(SECRET)));
        ProposedToolCall proposal = new ProposedToolCall(
                "TOPSECRET_CALL_ID", "collect_resource", Map.of("item", value));
        ProposedSkillPlan plan = new ProposedSkillPlan(REQUEST_ID, List.of(proposal));
        ToolFirewallRejection rejection = new ToolFirewallRejection(
                ToolFirewallRejectionCode.UNKNOWN_PARAMETER,
                Optional.of("TOPSECRET_CALL_ID"));

        assertDoesNotContainSecret(
                system, user, request, rawCall, response, memory, query, input,
                policy, history, assembly, redaction, value, proposal, plan, rejection);
    }

    @Test
    void providerToolPromptMetadataUsesSafeSummaries() {
        DeepSeekToolDefinition tool = new DeepSeekToolDefinition(
                new io.github.greytaiwolf.botplayer.ai.tool.ToolDefinition(
                        "collect_resource",
                        io.github.greytaiwolf.botplayer.ai.tool.ToolRiskLevel.LOW,
                        Map.of()),
                SECRET);
        DeepSeekProviderConfig config = new DeepSeekProviderConfig(
                List.of(new AiModelCapabilities(
                        "deepseek-chat", 32_768L, 512,
                        Set.of(AiCapability.CHAT))),
                List.of(tool), false);

        assertDoesNotContainSecret(tool, config);
    }

    private static void assertDoesNotContainSecret(Object... values) {
        for (Object value : values) {
            assertFalse(value.toString().contains(SECRET),
                    () -> "unsafe diagnostic summary for "
                            + value.getClass().getSimpleName());
        }
    }
}
