package io.github.greytaiwolf.botplayer.ai.tool;

import java.util.Objects;
import java.util.Optional;

/** 不回显模型参数内容的 Firewall 拒绝摘要。 */
public record ToolFirewallRejection(
        ToolFirewallRejectionCode code,
        Optional<String> callId) {
    public ToolFirewallRejection {
        Objects.requireNonNull(code, "code");
        callId = Objects.requireNonNull(callId, "callId");
        callId.ifPresent(value -> ToolChecks.callId(value, "callId"));
    }

    @Override
    public String toString() {
        return "ToolFirewallRejection[code=" + code
                + ", callIdPresent=" + callId.isPresent()
                + ", callIdLength=" + callId.map(String::length).orElse(0) + "]";
    }
}
