package io.github.greytaiwolf.botplayer.ai;

import java.util.Objects;
import java.util.UUID;

/**
 * Minimal trusted request identity frozen into one token reservation.
 *
 * <p>A later scheduler adapter must derive this value from its already-authoritative request
 * binding. This DTO intentionally contains no prompt, model, response, credential, nonce or
 * transport/session authority.</p>
 */
public record AiTokenBudgetRequestBinding(
        AiTokenBudgetScope scope, UUID requestId, long revision) {
    public AiTokenBudgetRequestBinding {
        scope = Objects.requireNonNull(scope, "scope");
        AiChecks.requireNonZero(requestId, "requestId");
        if (revision <= 0L) {
            throw new IllegalArgumentException("revision must be positive");
        }
    }

    /** Request and owner identifiers are deliberately omitted from ordinary diagnostics. */
    @Override
    public String toString() {
        return "AiTokenBudgetRequestBinding[bound=true]";
    }
}
