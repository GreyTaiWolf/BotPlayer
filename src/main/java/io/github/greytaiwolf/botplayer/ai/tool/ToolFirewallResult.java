package io.github.greytaiwolf.botplayer.ai.tool;

import java.util.Objects;
import java.util.Optional;

/** Firewall 的 fail-closed 结果。 */
public record ToolFirewallResult(
        ToolFirewallStatus status,
        Optional<ToolFirewallRejection> rejection) {
    public ToolFirewallResult {
        Objects.requireNonNull(status, "status");
        rejection = Objects.requireNonNull(rejection, "rejection");
        if (status == ToolFirewallStatus.ACCEPTED && rejection.isPresent()) {
            throw new IllegalArgumentException(
                    "accepted result must not contain a rejection");
        }
        if (status == ToolFirewallStatus.REJECTED && rejection.isEmpty()) {
            throw new IllegalArgumentException(
                    "rejected result must contain a rejection");
        }
    }

    public static ToolFirewallResult accepted() {
        return new ToolFirewallResult(
                ToolFirewallStatus.ACCEPTED, Optional.empty());
    }

    public static ToolFirewallResult rejected(
            ToolFirewallRejectionCode code, Optional<String> callId) {
        return new ToolFirewallResult(
                ToolFirewallStatus.REJECTED,
                Optional.of(new ToolFirewallRejection(code, callId)));
    }

    public boolean acceptedByStaticRules() {
        return status == ToolFirewallStatus.ACCEPTED;
    }
}
