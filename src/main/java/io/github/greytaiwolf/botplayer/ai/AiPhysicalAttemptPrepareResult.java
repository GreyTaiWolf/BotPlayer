package io.github.greytaiwolf.botplayer.ai;

import java.util.Objects;
import java.util.Optional;

/** Bounded result of one exact prepare acknowledgement. */
public record AiPhysicalAttemptPrepareResult(
        AiPhysicalAttemptPrepareStatus status,
        Optional<AiPhysicalAttemptStartGrant> grant,
        Optional<AiTokenBudgetOperationStatus> settlementStatus) {
    public AiPhysicalAttemptPrepareResult {
        status = Objects.requireNonNull(status, "status");
        grant = Objects.requireNonNull(grant, "grant");
        settlementStatus = Objects.requireNonNull(settlementStatus, "settlementStatus");
        if (status == AiPhysicalAttemptPrepareStatus.START_GRANTED) {
            if (grant.isEmpty()
                    || settlementStatus.orElseThrow() != AiTokenBudgetOperationStatus.SETTLED) {
                throw new IllegalArgumentException(
                        "a START_GRANTED result must carry one SETTLED grant");
            }
        } else if (grant.isPresent() || (status
                == AiPhysicalAttemptPrepareStatus.SETTLEMENT_REJECTED)
                != settlementStatus.isPresent()
                || settlementStatus.isPresent()
                && !isSettlementFailure(settlementStatus.orElseThrow())) {
            throw new IllegalArgumentException(
                    "only a settlement rejection may carry settlement status");
        }
    }

    static AiPhysicalAttemptPrepareResult granted(
            AiPhysicalAttemptStartGrant grant) {
        return new AiPhysicalAttemptPrepareResult(
                AiPhysicalAttemptPrepareStatus.START_GRANTED,
                Optional.of(Objects.requireNonNull(grant, "grant")),
                Optional.of(AiTokenBudgetOperationStatus.SETTLED));
    }

    static AiPhysicalAttemptPrepareResult rejected(
            AiPhysicalAttemptPrepareStatus status) {
        if (status == AiPhysicalAttemptPrepareStatus.START_GRANTED
                || status == AiPhysicalAttemptPrepareStatus.SETTLEMENT_REJECTED) {
            throw new IllegalArgumentException("status is not a non-settlement rejection");
        }
        return new AiPhysicalAttemptPrepareResult(status, Optional.empty(), Optional.empty());
    }

    static AiPhysicalAttemptPrepareResult settlementRejected(
            AiTokenBudgetOperationStatus status) {
        AiTokenBudgetOperationStatus checked = Objects.requireNonNull(status, "status");
        if (checked == AiTokenBudgetOperationStatus.SETTLED) {
            throw new IllegalArgumentException("a settled attempt must carry a grant");
        }
        return new AiPhysicalAttemptPrepareResult(
                AiPhysicalAttemptPrepareStatus.SETTLEMENT_REJECTED,
                Optional.empty(), Optional.of(checked));
    }

    public boolean granted() {
        return grant.isPresent();
    }

    private static boolean isSettlementFailure(
            AiTokenBudgetOperationStatus status) {
        return status == AiTokenBudgetOperationStatus.CLOCK_ROLLBACK
                || status == AiTokenBudgetOperationStatus.NOT_FOUND
                || status == AiTokenBudgetOperationStatus.STALE_RESERVATION
                || status == AiTokenBudgetOperationStatus.EXPIRED;
    }
}
