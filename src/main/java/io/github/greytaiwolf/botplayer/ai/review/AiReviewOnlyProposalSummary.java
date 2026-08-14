package io.github.greytaiwolf.botplayer.ai.review;

/** Prompt-free summary retained after a review-only proposal has been inspected and discarded. */
public record AiReviewOnlyProposalSummary(
        long snapshotId,
        long snapshotTick,
        int threatCount,
        int toolCallCount) {
    public AiReviewOnlyProposalSummary {
        if (snapshotId <= 0L || snapshotTick < 0L || threatCount < 0 || toolCallCount != 1) {
            throw new IllegalArgumentException("review summary is invalid");
        }
    }
}
