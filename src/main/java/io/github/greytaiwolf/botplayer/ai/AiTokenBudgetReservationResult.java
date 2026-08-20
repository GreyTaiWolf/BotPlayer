package io.github.greytaiwolf.botplayer.ai;

import java.util.Objects;
import java.util.Optional;

/** The result of attempting to create one exact token reservation. */
public record AiTokenBudgetReservationResult(
        AiTokenBudgetOperationStatus status,
        Optional<AiTokenReservation> reservation) {
    public AiTokenBudgetReservationResult {
        status = Objects.requireNonNull(status, "status");
        reservation = Objects.requireNonNull(reservation, "reservation");
        if ((status == AiTokenBudgetOperationStatus.RESERVED)
                != reservation.isPresent()) {
            throw new IllegalArgumentException(
                    "only a RESERVED result may carry a reservation");
        }
    }

    public boolean reserved() {
        return reservation.isPresent();
    }
}
