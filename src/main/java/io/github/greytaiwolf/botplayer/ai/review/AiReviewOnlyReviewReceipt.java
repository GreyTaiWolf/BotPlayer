package io.github.greytaiwolf.botplayer.ai.review;

import io.github.greytaiwolf.botplayer.ai.transport.AiProposalReviewStatus;
import java.util.Objects;
import java.util.Optional;

/**
 * Network-handler-safe result of reviewing a P6-R1 proposal. It deliberately has no raw plan,
 * model text, tool call id, nonce, or arguments field.
 */
public record AiReviewOnlyReviewReceipt(
        AiReviewOnlyReviewStatus status,
        AiProposalReviewStatus gateStatus,
        Optional<AiReviewOnlyProposalSummary> summary) {
    public AiReviewOnlyReviewReceipt {
        status = Objects.requireNonNull(status, "status");
        gateStatus = Objects.requireNonNull(gateStatus, "gateStatus");
        summary = Objects.requireNonNull(summary, "summary");
        if (status == AiReviewOnlyReviewStatus.ACCEPTED_AND_DROPPED
                != summary.isPresent()) {
            throw new IllegalArgumentException(
                    "only an accepted-and-dropped receipt may contain a summary");
        }
    }

    public static AiReviewOnlyReviewReceipt rejected(
            AiReviewOnlyReviewStatus status, AiProposalReviewStatus gateStatus) {
        if (status == AiReviewOnlyReviewStatus.ACCEPTED_AND_DROPPED) {
            throw new IllegalArgumentException("use accepted receipt factory");
        }
        return new AiReviewOnlyReviewReceipt(status, gateStatus, Optional.empty());
    }

    public static AiReviewOnlyReviewReceipt accepted(
            AiProposalReviewStatus gateStatus,
            AiReviewOnlyProposalSummary summary) {
        return new AiReviewOnlyReviewReceipt(
                AiReviewOnlyReviewStatus.ACCEPTED_AND_DROPPED,
                gateStatus,
                Optional.of(Objects.requireNonNull(summary, "summary")));
    }

    @Override
    public String toString() {
        return "AiReviewOnlyReviewReceipt[status=" + status
                + ", gateStatus=" + gateStatus
                + ", hasSummary=" + summary.isPresent() + "]";
    }
}
