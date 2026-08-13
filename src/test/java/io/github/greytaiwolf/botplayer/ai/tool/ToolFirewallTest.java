package io.github.greytaiwolf.botplayer.ai.tool;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ToolFirewallTest {
    private static final UUID REQUEST_ID = UUID.fromString(
            "87654321-4321-4321-4321-cba987654321");

    @Test
    void acceptsOnlyWhitelistedLowRiskCallWithACompleteSchema() {
        ToolFirewall firewall = firewall(ToolRiskLevel.LOW, 2,
                Set.of("collect_resource"));

        ToolFirewallResult result = firewall.review(plan(
                "collect_resource",
                Map.of(
                        "count", new ToolValue.IntegerValue(32L),
                        "itemId", new ToolValue.StringValue(
                                "minecraft:oak_log"))));

        Assertions.assertAll(
                () -> Assertions.assertTrue(result.acceptedByStaticRules()),
                () -> Assertions.assertEquals(ToolFirewallStatus.ACCEPTED,
                        result.status()),
                () -> Assertions.assertTrue(result.rejection().isEmpty()));
    }

    @Test
    void rejectsUnknownMissingWrongTypeAndOutOfRangeParameters() {
        ToolFirewall firewall = firewall(ToolRiskLevel.LOW, 2,
                Set.of("collect_resource"));

        assertRejected(firewall, plan("collect_resource", Map.of(
                "count", new ToolValue.IntegerValue(1L),
                "itemId", new ToolValue.StringValue("minecraft:oak_log"),
                "extra", new ToolValue.BooleanValue(true))),
                ToolFirewallRejectionCode.UNKNOWN_PARAMETER);
        assertRejected(firewall, plan("collect_resource", Map.of(
                "count", new ToolValue.StringValue("32"),
                "itemId", new ToolValue.StringValue("minecraft:oak_log"))),
                ToolFirewallRejectionCode.TYPE_MISMATCH);
        assertRejected(firewall, plan("collect_resource", Map.of(
                "count", new ToolValue.IntegerValue(65L),
                "itemId", new ToolValue.StringValue("minecraft:oak_log"))),
                ToolFirewallRejectionCode.INTEGER_OUT_OF_RANGE);
        assertRejected(firewall, plan("collect_resource", Map.of(
                "count", new ToolValue.IntegerValue(1L),
                "itemId", new ToolValue.StringValue("Minecraft:oak_log"))),
                ToolFirewallRejectionCode.STRING_VALUE_NOT_ALLOWED);
        assertRejected(firewall, plan("collect_resource", Map.of(
                "itemId", new ToolValue.StringValue("minecraft:oak_log"))),
                ToolFirewallRejectionCode.MISSING_PARAMETER);
    }

    @Test
    void rejectsRequestWhitelistRiskAndCallCountEscalation() {
        ToolFirewall noClearArea = firewall(ToolRiskLevel.HIGH, 2,
                Set.of("collect_resource"));
        assertRejected(noClearArea, plan("clear_area", Map.of()),
                ToolFirewallRejectionCode.TOOL_NOT_WHITELISTED);

        ToolFirewall lowRiskOnly = firewall(ToolRiskLevel.LOW, 2,
                Set.of("collect_resource", "clear_area"));
        assertRejected(lowRiskOnly, plan("clear_area", Map.of()),
                ToolFirewallRejectionCode.RISK_TOO_HIGH);

        ToolFirewall forbidForbidden = firewall(ToolRiskLevel.FORBIDDEN, 2,
                Set.of("collect_resource", "forbidden_tool"));
        assertRejected(forbidForbidden, plan("forbidden_tool", Map.of()),
                ToolFirewallRejectionCode.RISK_TOO_HIGH);

        ToolFirewall oneCall = firewall(ToolRiskLevel.LOW, 1,
                Set.of("collect_resource"));
        ProposedToolCall first = call("call_1", "collect_resource", Map.of(
                "count", new ToolValue.IntegerValue(1L),
                "itemId", new ToolValue.StringValue("minecraft:oak_log")));
        ProposedToolCall second = call("call_2", "collect_resource", Map.of(
                "count", new ToolValue.IntegerValue(2L),
                "itemId", new ToolValue.StringValue("minecraft:oak_log")));
        assertRejected(oneCall, new ProposedSkillPlan(REQUEST_ID, List.of(first, second)),
                ToolFirewallRejectionCode.CALL_COUNT_EXCEEDED,
                Optional.empty());
    }

    @Test
    void rejectsCommandFileHttpAndScriptLikeParametersBeforeAnyExecution() {
        ToolFirewall firewall = firewall(ToolRiskLevel.LOW, 2,
                Set.of("collect_resource"));

        assertRejected(firewall, plan("collect_resource", Map.of(
                "command", new ToolValue.StringValue("say hi"),
                "count", new ToolValue.IntegerValue(1L),
                "itemId", new ToolValue.StringValue("minecraft:oak_log"))),
                ToolFirewallRejectionCode.FORBIDDEN_PARAMETER_NAME);
        assertRejected(firewall, plan("collect_resource", Map.of(
                "count", new ToolValue.IntegerValue(1L),
                "itemId", new ToolValue.StringValue("minecraft:oak_log"),
                "path", new ToolValue.StringValue("./server.properties"))),
                ToolFirewallRejectionCode.FORBIDDEN_PARAMETER_NAME);
        assertRejected(firewall, plan("collect_resource", Map.of(
                "count", new ToolValue.IntegerValue(1L),
                "itemId", new ToolValue.StringValue("https://example.invalid"))),
                ToolFirewallRejectionCode.SCRIPT_LIKE_VALUE);
        assertRejected(firewall, plan("collect_resource", Map.of(
                "count", new ToolValue.IntegerValue(1L),
                "itemId", new ToolValue.StringValue("minecraft:oak_log"),
                "note", new ToolValue.StringValue("curl https://example.invalid"))),
                ToolFirewallRejectionCode.SCRIPT_LIKE_VALUE);
        assertRejected(firewall, plan("collect_resource", Map.of(
                "count", new ToolValue.IntegerValue(1L),
                "itemId", new ToolValue.StringValue("../server.properties"))),
                ToolFirewallRejectionCode.SCRIPT_LIKE_VALUE);
        assertRejected(firewall, plan("collect_resource", Map.of(
                "count", new ToolValue.IntegerValue(1L),
                "itemId", new ToolValue.StringValue(".\\server.properties"))),
                ToolFirewallRejectionCode.SCRIPT_LIKE_VALUE);
        assertRejected(firewall, plan("collect_resource", Map.of(
                "count", new ToolValue.IntegerValue(1L),
                "itemId", new ToolValue.StringValue("javascript:alert(1)"))),
                ToolFirewallRejectionCode.SCRIPT_LIKE_VALUE);
        assertRejected(firewall, plan("collect_resource", Map.of(
                "count", new ToolValue.IntegerValue(1L),
                "itemId", new ToolValue.StringValue("file:server.properties"))),
                ToolFirewallRejectionCode.SCRIPT_LIKE_VALUE);
        assertRejected(firewall, plan("collect_resource", Map.of(
                "count", new ToolValue.IntegerValue(1L),
                "itemId", new ToolValue.StringValue("http:evil"))),
                ToolFirewallRejectionCode.SCRIPT_LIKE_VALUE);
        assertRejected(firewall, plan("collect_resource", Map.of(
                "count", new ToolValue.IntegerValue(1L),
                "itemId", new ToolValue.StringValue("C:server.properties"))),
                ToolFirewallRejectionCode.SCRIPT_LIKE_VALUE);
    }

    @Test
    void recursivelyRejectsNestedUnknownAndForbiddenObjectFields() {
        ToolFirewall firewall = firewall(ToolRiskLevel.LOW, 2,
                Set.of("collect_resource"));

        assertRejected(firewall, plan("collect_resource", Map.of(
                "count", new ToolValue.IntegerValue(1L),
                "itemId", new ToolValue.StringValue("minecraft:oak_log"),
                "metadata", new ToolValue.ObjectValue(Map.of(
                        "extra", new ToolValue.StringValue("not allowed"))))),
                ToolFirewallRejectionCode.UNKNOWN_PARAMETER);
        assertRejected(firewall, plan("collect_resource", Map.of(
                "count", new ToolValue.IntegerValue(1L),
                "itemId", new ToolValue.StringValue("minecraft:oak_log"),
                "metadata", new ToolValue.ObjectValue(Map.of(
                        "url", new ToolValue.StringValue("value"))))),
                ToolFirewallRejectionCode.FORBIDDEN_PARAMETER_NAME);
    }

    private static ToolFirewall firewall(
            ToolRiskLevel maximumRisk,
            int maximumCalls,
            Set<String> whitelist) {
        ToolParameterRule metadata = ToolParameterRule.optionalObject(2, Map.of(
                "label", ToolParameterRule.requiredString(24),
                "tags", ToolParameterRule.optionalArray(2,
                        ToolParameterRule.requiredStringOneOf(
                                8, Set.of("base", "resource")))));
        ToolDefinition collectResource = new ToolDefinition(
                "collect_resource",
                ToolRiskLevel.LOW,
                Map.of(
                        "count", ToolParameterRule.requiredInteger(1L, 64L),
                        "itemId", ToolParameterRule.requiredResourceIdentifier(64),
                        "metadata", metadata,
                        "note", ToolParameterRule.optionalString(64)));
        ToolDefinition clearArea = new ToolDefinition(
                "clear_area", ToolRiskLevel.HIGH, Map.of());
        ToolDefinition forbidden = new ToolDefinition(
                "forbidden_tool", ToolRiskLevel.FORBIDDEN, Map.of());
        return new ToolFirewall(new ToolFirewallPolicy(
                Map.of(
                        "collect_resource", collectResource,
                        "clear_area", clearArea,
                        "forbidden_tool", forbidden),
                whitelist,
                maximumRisk,
                maximumCalls));
    }

    private static ProposedSkillPlan plan(
            String toolName, Map<String, ToolValue> arguments) {
        return new ProposedSkillPlan(REQUEST_ID,
                List.of(call("call_1", toolName, arguments)));
    }

    private static ProposedToolCall call(
            String callId, String toolName, Map<String, ToolValue> arguments) {
        return new ProposedToolCall(callId, toolName, arguments);
    }

    private static void assertRejected(
            ToolFirewall firewall,
            ProposedSkillPlan proposal,
            ToolFirewallRejectionCode expectedCode) {
        assertRejected(firewall, proposal, expectedCode, Optional.of("call_1"));
    }

    private static void assertRejected(
            ToolFirewall firewall,
            ProposedSkillPlan proposal,
            ToolFirewallRejectionCode expectedCode,
            Optional<String> expectedCallId) {
        ToolFirewallResult result = firewall.review(proposal);
        Assertions.assertAll(
                () -> Assertions.assertFalse(result.acceptedByStaticRules()),
                () -> Assertions.assertEquals(ToolFirewallStatus.REJECTED,
                        result.status()),
                () -> Assertions.assertEquals(expectedCode,
                        result.rejection().orElseThrow().code()),
                () -> Assertions.assertEquals(expectedCallId,
                        result.rejection().orElseThrow().callId()));
    }
}
