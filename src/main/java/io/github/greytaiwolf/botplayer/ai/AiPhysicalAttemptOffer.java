package io.github.greytaiwolf.botplayer.ai;

import java.util.Objects;

/**
 * Server-to-client invitation to prepare one already-reserved physical attempt.
 *
 * <p>Receiving an offer does not start HTTP and does not consume a token reservation. The client
 * must return the exact {@link #prepareAck()} before the server may settle the attempt.
 */
public record AiPhysicalAttemptOffer(AiPhysicalAttemptIdentity identity) {
    public AiPhysicalAttemptOffer {
        identity = Objects.requireNonNull(identity, "identity");
    }

    /** Creates the exact C2S prepare acknowledgement for this one offer. */
    public AiPhysicalAttemptPrepareAck prepareAck() {
        return new AiPhysicalAttemptPrepareAck(identity);
    }

    @Override
    public String toString() {
        return "AiPhysicalAttemptOffer[identityBound=true]";
    }
}
