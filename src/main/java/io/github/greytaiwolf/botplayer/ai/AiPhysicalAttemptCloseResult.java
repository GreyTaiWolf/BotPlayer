package io.github.greytaiwolf.botplayer.ai;

import java.util.Objects;
import java.util.Optional;

/** Exact-close result; only an unstarted close can report a reservation release outcome. */
public record AiPhysicalAttemptCloseResult(
        AiPhysicalAttemptCloseStatus status,
        Optional<AiTokenBudgetOperationStatus> releaseStatus) {
    public AiPhysicalAttemptCloseResult {
        status = Objects.requireNonNull(status, "status");
        releaseStatus = Objects.requireNonNull(releaseStatus, "releaseStatus");
        if (status == AiPhysicalAttemptCloseStatus.CLOSED_UNSTARTED
                != releaseStatus.isPresent()
                || releaseStatus.isPresent()
                && !isReleaseOutcome(releaseStatus.orElseThrow())) {
            throw new IllegalArgumentException(
                    "only an unstarted close may carry a release status");
        }
    }

    static AiPhysicalAttemptCloseResult closedUnstarted(
            AiTokenBudgetOperationStatus releaseStatus) {
        return new AiPhysicalAttemptCloseResult(
                AiPhysicalAttemptCloseStatus.CLOSED_UNSTARTED,
                Optional.of(Objects.requireNonNull(releaseStatus, "releaseStatus")));
    }

    static AiPhysicalAttemptCloseResult closedCommitted() {
        return new AiPhysicalAttemptCloseResult(
                AiPhysicalAttemptCloseStatus.CLOSED_COMMITTED,
                Optional.empty());
    }

    static AiPhysicalAttemptCloseResult rejected(
            AiPhysicalAttemptCloseStatus status) {
        if (status == AiPhysicalAttemptCloseStatus.CLOSED_UNSTARTED
                || status == AiPhysicalAttemptCloseStatus.CLOSED_COMMITTED) {
            throw new IllegalArgumentException("status is not a close rejection");
        }
        return new AiPhysicalAttemptCloseResult(status, Optional.empty());
    }

    private static boolean isReleaseOutcome(AiTokenBudgetOperationStatus status) {
        return status == AiTokenBudgetOperationStatus.RELEASED
                || status == AiTokenBudgetOperationStatus.NOT_FOUND
                || status == AiTokenBudgetOperationStatus.STALE_RESERVATION;
    }
}
