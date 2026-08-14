package io.github.greytaiwolf.botplayer.ai.tool;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 对 {@link ProposedSkillPlan} 执行不依赖 P5 的静态 fail-closed 审核。
 *
 * <p>通过本类只代表工具名、参数 schema、数量和静态风险符合当前请求策略。它<strong>不</strong>
 * 授予 owner、ACL、位置、区块、世界 revision、幂等或实际执行权限。
 */
public final class ToolFirewall {
    private static final Set<String> FORBIDDEN_PARAMETER_NAMES = Set.of(
            "apikey",
            "authorization",
            "cmd",
            "code",
            "command",
            "env",
            "environment",
            "endpoint",
            "file",
            "filepath",
            "headers",
            "http",
            "https",
            "path",
            "script",
            "secret",
            "shell",
            "token",
            "uri",
            "url");
    private static final Pattern URL_SCHEME = Pattern.compile(
            "(?i)^[a-z][a-z0-9+.-]{0,15}://.*");
    private static final Pattern DANGEROUS_URI_SCHEME = Pattern.compile(
            "(?i)^(?:data|file|ftp|gopher|http|https|jar|javascript|mailto|"
                    + "ssh|tel|ws|wss):.*");
    private static final Pattern WINDOWS_PATH = Pattern.compile(
            "(?i)^[a-z]:[\\\\/].*");
    private static final Pattern WINDOWS_DRIVE_RELATIVE_PATH = Pattern.compile(
            "(?i)^[a-z]:.*");
    private static final Pattern RELATIVE_PATH_SEGMENT = Pattern.compile(
            "(?:^|[\\\\/])\\.{1,2}(?:[\\\\/]|$)");
    private static final Pattern RESOURCE_IDENTIFIER = Pattern.compile(
            "[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,128}");
    private static final Pattern SCRIPT_TOKEN = Pattern.compile(
            "(?i)(?:^|\\W)(?:bash|cat|chmod|cmd(?:\\.exe)?|cp|curl|del|dir|"
                    + "eval|exec|find|fish|function|git|import|java|kill|ls|mkdir|mv|"
                    + "node|perl|powershell|python(?:3)?|require|rm|ruby|sed|sh|tee|"
                    + "touch|type|wget|zsh)(?:\\W|$)");

    private final ToolFirewallPolicy policy;

    public ToolFirewall(ToolFirewallPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public ToolFirewallPolicy policy() {
        return policy;
    }

    /** 审核整批提案；遇到第一个拒绝即停止，且不产生任何副作用。 */
    public ToolFirewallResult review(ProposedSkillPlan proposal) {
        Objects.requireNonNull(proposal, "proposal");
        if (proposal.toolCalls().size() > policy.maximumCalls()) {
            return rejected(ToolFirewallRejectionCode.CALL_COUNT_EXCEEDED,
                    Optional.empty());
        }
        for (ProposedToolCall call : proposal.toolCalls()) {
            ToolFirewallResult result = reviewCall(call);
            if (!result.acceptedByStaticRules()) {
                return result;
            }
        }
        return ToolFirewallResult.accepted();
    }

    private ToolFirewallResult reviewCall(ProposedToolCall call) {
        String toolName = call.toolName();
        if (!policy.requestWhitelist().contains(toolName)) {
            return rejected(ToolFirewallRejectionCode.TOOL_NOT_WHITELISTED,
                    Optional.of(call.callId()));
        }
        ToolDefinition definition = policy.knownTools().get(toolName);
        if (definition == null) {
            return rejected(ToolFirewallRejectionCode.TOOL_NOT_REGISTERED,
                    Optional.of(call.callId()));
        }
        if (!definition.riskLevel().isAtMost(policy.maximumRisk())) {
            return rejected(ToolFirewallRejectionCode.RISK_TOO_HIGH,
                    Optional.of(call.callId()));
        }

        for (Map.Entry<String, ToolValue> entry : call.arguments().entrySet()) {
            ToolFirewallRejectionCode unsafeCode = unsafeValueCode(
                    entry.getKey(), entry.getValue());
            if (unsafeCode != null) {
                return rejected(unsafeCode, Optional.of(call.callId()));
            }
            ToolParameterRule rule = definition.parameters().get(entry.getKey());
            if (rule == null) {
                return rejected(ToolFirewallRejectionCode.UNKNOWN_PARAMETER,
                        Optional.of(call.callId()));
            }
            ToolFirewallRejectionCode ruleCode = validateRule(
                    rule, entry.getValue());
            if (ruleCode != null) {
                return rejected(ruleCode, Optional.of(call.callId()));
            }
        }

        for (Map.Entry<String, ToolParameterRule> entry
                : definition.parameters().entrySet()) {
            if (entry.getValue().required()
                    && !call.arguments().containsKey(entry.getKey())) {
                return rejected(ToolFirewallRejectionCode.MISSING_PARAMETER,
                        Optional.of(call.callId()));
            }
        }
        return ToolFirewallResult.accepted();
    }

    private static ToolFirewallRejectionCode validateRule(
            ToolParameterRule rule, ToolValue value) {
        if (value.type() != rule.type()) {
            return ToolFirewallRejectionCode.TYPE_MISMATCH;
        }
        if (value instanceof ToolValue.IntegerValue integerValue) {
            long integer = integerValue.value();
            if (integer < rule.minimumInteger().orElseThrow()
                    || integer > rule.maximumInteger().orElseThrow()) {
                return ToolFirewallRejectionCode.INTEGER_OUT_OF_RANGE;
            }
        } else if (value instanceof ToolValue.StringValue stringValue) {
            String text = stringValue.value();
            if (text.length() > rule.maximumStringCharacters()) {
                return ToolFirewallRejectionCode.STRING_TOO_LONG;
            }
            if (rule.stringSemantics()
                    == ToolStringSemantics.RESOURCE_IDENTIFIER
                    && !RESOURCE_IDENTIFIER.matcher(text).matches()) {
                return ToolFirewallRejectionCode.STRING_VALUE_NOT_ALLOWED;
            }
            if (!rule.allowedStringValues().isEmpty()
                    && !rule.allowedStringValues().contains(text)) {
                return ToolFirewallRejectionCode.STRING_VALUE_NOT_ALLOWED;
            }
        } else if (value instanceof ToolValue.ArrayValue arrayValue) {
            if (arrayValue.values().size() > rule.maximumCollectionEntries()) {
                return ToolFirewallRejectionCode.COLLECTION_TOO_LARGE;
            }
            ToolParameterRule elementRule = rule.arrayElementRule().orElseThrow();
            for (ToolValue child : arrayValue.values()) {
                ToolFirewallRejectionCode childCode = validateRule(elementRule, child);
                if (childCode != null) {
                    return childCode;
                }
            }
        } else if (value instanceof ToolValue.ObjectValue objectValue) {
            if (objectValue.values().size() > rule.maximumCollectionEntries()) {
                return ToolFirewallRejectionCode.COLLECTION_TOO_LARGE;
            }
            for (Map.Entry<String, ToolValue> entry : objectValue.values().entrySet()) {
                ToolParameterRule childRule = rule.objectFields().get(entry.getKey());
                if (childRule == null) {
                    return ToolFirewallRejectionCode.UNKNOWN_PARAMETER;
                }
                ToolFirewallRejectionCode childCode = validateRule(
                        childRule, entry.getValue());
                if (childCode != null) {
                    return childCode;
                }
            }
            for (Map.Entry<String, ToolParameterRule> entry
                    : rule.objectFields().entrySet()) {
                if (entry.getValue().required()
                        && !objectValue.values().containsKey(entry.getKey())) {
                    return ToolFirewallRejectionCode.MISSING_PARAMETER;
                }
            }
        }
        return null;
    }

    private static ToolFirewallRejectionCode unsafeValueCode(
            String parameterName, ToolValue value) {
        if (isForbiddenParameterName(parameterName)) {
            return ToolFirewallRejectionCode.FORBIDDEN_PARAMETER_NAME;
        }
        if (value instanceof ToolValue.StringValue stringValue) {
            return looksScriptLikeOrExternal(stringValue.value())
                    ? ToolFirewallRejectionCode.SCRIPT_LIKE_VALUE
                    : null;
        }
        if (value instanceof ToolValue.ArrayValue arrayValue) {
            for (ToolValue child : arrayValue.values()) {
                ToolFirewallRejectionCode childCode = unsafeValueCode(
                        "value", child);
                if (childCode != null) {
                    return childCode;
                }
            }
        } else if (value instanceof ToolValue.ObjectValue objectValue) {
            for (Map.Entry<String, ToolValue> entry : objectValue.values().entrySet()) {
                ToolFirewallRejectionCode childCode = unsafeValueCode(
                        entry.getKey(), entry.getValue());
                if (childCode != null) {
                    return childCode;
                }
            }
        }
        return null;
    }

    private static boolean isForbiddenParameterName(String parameterName) {
        String normalized = parameterName.toLowerCase(Locale.ROOT)
                .replace("_", "");
        return FORBIDDEN_PARAMETER_NAMES.contains(normalized);
    }

    private static boolean looksScriptLikeOrExternal(String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("/") || trimmed.startsWith("\\\\")
                || WINDOWS_PATH.matcher(trimmed).matches()
                || WINDOWS_DRIVE_RELATIVE_PATH.matcher(trimmed).matches()
                || trimmed.startsWith("~/") || trimmed.startsWith("~\\")
                || RELATIVE_PATH_SEGMENT.matcher(trimmed).find()
                || URL_SCHEME.matcher(trimmed).matches()
                || DANGEROUS_URI_SCHEME.matcher(trimmed).matches()
                || trimmed.regionMatches(true, 0, "file:", 0, 5)) {
            return true;
        }
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0
                || value.contains("&&") || value.contains("||")
                || value.contains("$(") || value.indexOf('`') >= 0
                || value.indexOf(';') >= 0 || value.contains("<script")) {
            return true;
        }
        return SCRIPT_TOKEN.matcher(value).find();
    }

    private static ToolFirewallResult rejected(
            ToolFirewallRejectionCode code, Optional<String> callId) {
        return ToolFirewallResult.rejected(code, callId);
    }
}
