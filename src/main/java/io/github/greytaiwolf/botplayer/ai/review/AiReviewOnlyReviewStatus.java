package io.github.greytaiwolf.botplayer.ai.review;

/** Safe terminal disposition of an inbound proposal for the P6-R1 review ticket. */
public enum AiReviewOnlyReviewStatus {
    GATE_REJECTED,
    UNTRACKED_TERMINAL_DROPPED,
    REVIEW_CONTRACT_REJECTED_DROPPED,
    ACCEPTED_AND_DROPPED
}
