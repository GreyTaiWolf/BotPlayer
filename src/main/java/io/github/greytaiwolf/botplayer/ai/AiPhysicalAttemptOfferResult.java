package io.github.greytaiwolf.botplayer.ai;

import java.util.Objects;
import java.util.Optional;

/** Bounded result of offering one client-prepared physical attempt. */
public record AiPhysicalAttemptOfferResult(
        AiPhysicalAttemptOfferStatus status,
        Optional<AiPhysicalAttemptOffer> offer,
        Optional<AiTokenBudgetOperationStatus> budgetStatus) {
    public AiPhysicalAttemptOfferResult {
        status = Objects.requireNonNull(status, "status");
        offer = Objects.requireNonNull(offer, "offer");
        budgetStatus = Objects.requireNonNull(budgetStatus, "budgetStatus");
        if (status == AiPhysicalAttemptOfferStatus.OFFERED) {
            if (offer.isEmpty()
                    || budgetStatus.orElseThrow() != AiTokenBudgetOperationStatus.RESERVED) {
                throw new IllegalArgumentException(
                        "an OFFERED result must carry one RESERVED offer");
            }
        } else if (offer.isPresent() || (status
                == AiPhysicalAttemptOfferStatus.BUDGET_REJECTED)
                != budgetStatus.isPresent()
                || budgetStatus.isPresent()
                && !isReservationRejection(budgetStatus.orElseThrow())) {
            throw new IllegalArgumentException(
                    "only a BUDGET_REJECTED result may carry a budget status");
        }
    }

    static AiPhysicalAttemptOfferResult offered(
            AiPhysicalAttemptOffer offer) {
        return new AiPhysicalAttemptOfferResult(
                AiPhysicalAttemptOfferStatus.OFFERED,
                Optional.of(Objects.requireNonNull(offer, "offer")),
                Optional.of(AiTokenBudgetOperationStatus.RESERVED));
    }

    static AiPhysicalAttemptOfferResult rejected(
            AiPhysicalAttemptOfferStatus status) {
        if (status == AiPhysicalAttemptOfferStatus.OFFERED
                || status == AiPhysicalAttemptOfferStatus.BUDGET_REJECTED) {
            throw new IllegalArgumentException("status is not a non-budget rejection");
        }
        return new AiPhysicalAttemptOfferResult(status, Optional.empty(), Optional.empty());
    }

    static AiPhysicalAttemptOfferResult budgetRejected(
            AiTokenBudgetOperationStatus budgetStatus) {
        AiTokenBudgetOperationStatus checked = Objects.requireNonNull(
                budgetStatus, "budgetStatus");
        if (checked == AiTokenBudgetOperationStatus.RESERVED) {
            throw new IllegalArgumentException("a reservation is not a budget rejection");
        }
        return new AiPhysicalAttemptOfferResult(
                AiPhysicalAttemptOfferStatus.BUDGET_REJECTED,
                Optional.empty(),
                Optional.of(checked));
    }

    public boolean offered() {
        return offer.isPresent();
    }

    private static boolean isReservationRejection(
            AiTokenBudgetOperationStatus status) {
        return switch (status) {
            case ADMISSION_REJECTED, INVALID_ADMISSION, SCOPE_MISMATCH,
                    INVALID_EXPIRATION, TOKEN_BUDGET_EXHAUSTED,
                    ACTIVE_RESERVATION_LIMIT, RESERVATION_ID_EXHAUSTED,
                    LEDGER_CLOSED, CLOCK_ROLLBACK -> true;
            case RESERVED, RELEASED, SETTLED, EXPIRED, NOT_FOUND,
                    STALE_RESERVATION -> false;
        };
    }
}
