package io.github.greytaiwolf.botplayer.ai;

import java.util.Objects;

/**
 * Client-to-server acknowledgement that the client is prepared to receive a start grant.
 *
 * <p>This is not proof that a local Provider exists, that HTTP has begun, or that the request
 * will eventually produce a response. It can only cause the server-owned coordinator to settle
 * one exact pre-committed reservation and return a grant.
 */
public record AiPhysicalAttemptPrepareAck(AiPhysicalAttemptIdentity identity) {
    public AiPhysicalAttemptPrepareAck {
        identity = Objects.requireNonNull(identity, "identity");
    }

    @Override
    public String toString() {
        return "AiPhysicalAttemptPrepareAck[identityBound=true]";
    }
}
