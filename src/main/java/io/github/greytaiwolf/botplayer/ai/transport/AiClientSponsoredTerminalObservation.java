package io.github.greytaiwolf.botplayer.ai.transport;

import java.util.Objects;

/**
 * Bounded mailbox value for a future foreign-thread terminal report.
 *
 * <p>The receipt is the only correlation carried across the thread boundary. In particular this
 * DTO deliberately excludes a nonce, owner, prompt, schema, provider response, throwable and
 * cancellation handle. Receiving an observation does not close the gate or ledger.
 */
public record AiClientSponsoredTerminalObservation(
        AiRequestDispatchReceipt receipt,
        AiClientSponsoredTerminalStatus status) {
    public AiClientSponsoredTerminalObservation {
        receipt = Objects.requireNonNull(receipt, "receipt");
        status = Objects.requireNonNull(status, "status");
    }

    @Override
    public String toString() {
        return "AiClientSponsoredTerminalObservation[receipt=" + receipt
                + ", status=" + status + "]";
    }
}
