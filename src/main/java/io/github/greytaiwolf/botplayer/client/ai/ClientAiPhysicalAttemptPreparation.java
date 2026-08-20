package io.github.greytaiwolf.botplayer.client.ai;

import io.github.greytaiwolf.botplayer.ai.AiPhysicalAttemptPrepareAck;
import java.util.Objects;
import java.util.Optional;

/**
 * Result of locally staging one exact physical-attempt offer before any Provider start.
 *
 * <p>Only {@link ClientAiRequestDispatchStatus#PREPARED} carries the exact prepare ACK. Every
 * other status deliberately carries no C2S capability, so a failed local binding, deadline,
 * factory, replacement, or connection check cannot cause server settlement.
 */
public record ClientAiPhysicalAttemptPreparation(
        ClientAiRequestDispatchStatus status,
        Optional<AiPhysicalAttemptPrepareAck> prepareAck) {
    public ClientAiPhysicalAttemptPreparation {
        status = Objects.requireNonNull(status, "status");
        prepareAck = Objects.requireNonNull(prepareAck, "prepareAck");
        if ((status == ClientAiRequestDispatchStatus.PREPARED) != prepareAck.isPresent()) {
            throw new IllegalArgumentException(
                    "only a prepared local attempt may carry an ACK");
        }
    }

    static ClientAiPhysicalAttemptPreparation prepared(
            AiPhysicalAttemptPrepareAck prepareAck) {
        return new ClientAiPhysicalAttemptPreparation(
                ClientAiRequestDispatchStatus.PREPARED,
                Optional.of(Objects.requireNonNull(prepareAck, "prepareAck")));
    }

    static ClientAiPhysicalAttemptPreparation rejected(
            ClientAiRequestDispatchStatus status) {
        ClientAiRequestDispatchStatus checked = Objects.requireNonNull(status, "status");
        if (checked == ClientAiRequestDispatchStatus.PREPARED) {
            throw new IllegalArgumentException("prepared requires an ACK");
        }
        return new ClientAiPhysicalAttemptPreparation(checked, Optional.empty());
    }
}
