package io.github.greytaiwolf.botplayer.technique.bridge;

import io.github.greytaiwolf.botplayer.action.ActionKind;
import io.github.greytaiwolf.botplayer.action.ActionOutcome;
import java.util.Objects;

/**
 * Terminal Action evidence retained for one exact Technique child handle.
 *
 * <p>This is intentionally terminal-only.  A Technique advances from exact
 * child acknowledgements at the lifecycle tick boundary; it does not treat a
 * live Action state or a caller completion callback as a child result.
 */
public record TechniqueActionTerminal(
        TechniqueActionPermit permit,
        ActionKind kind,
        ActionOutcome outcome) {
    public TechniqueActionTerminal {
        permit = Objects.requireNonNull(permit, "permit");
        kind = Objects.requireNonNull(kind, "kind");
        outcome = Objects.requireNonNull(outcome, "outcome");
        if (kind != permit.kind()
                || !permit.actionId().equals(outcome.actionId())
                || !outcome.state().isTerminal()) {
            throw new IllegalArgumentException(
                    "Technique Action terminal did not match its exact action");
        }
    }
}
