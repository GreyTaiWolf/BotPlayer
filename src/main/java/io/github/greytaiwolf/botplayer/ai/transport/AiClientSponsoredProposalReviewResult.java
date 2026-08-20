package io.github.greytaiwolf.botplayer.ai.transport;

import java.util.Objects;
import java.util.Optional;

/**
 * Owner-thread result of reviewing one already-decoded client-sponsored proposal.
 *
 * <p>An empty {@link #review()} means the immutable ledger correlation did not match, so the
 * coordinator deliberately did not invoke the gate. A terminal binding exists only after the gate
 * consumed the same exact correlation and the coordinator removed the corresponding ledger entry.
 * The proposal, if accepted, remains an unexecuted DTO inside {@link AiProposalReview}; this result
 * does not authorize a packet, Scheduler, Skill, Action, or world effect.
 */
public final class AiClientSponsoredProposalReviewResult {
    private final Optional<AiProposalReview> review;
    private final Optional<AiClientSponsoredRequest> terminalBinding;

    private AiClientSponsoredProposalReviewResult(
            Optional<AiProposalReview> review,
            Optional<AiClientSponsoredRequest> terminalBinding) {
        this.review = Objects.requireNonNull(review, "review");
        this.terminalBinding = Objects.requireNonNull(terminalBinding,
                "terminalBinding");
        if (review.isEmpty() && terminalBinding.isPresent()) {
            throw new IllegalArgumentException(
                    "an unreviewed proposal cannot close a client-sponsored binding");
        }
    }

    /** The untrusted correlation did not match an active immutable binding. */
    static AiClientSponsoredProposalReviewResult droppedBeforeGate() {
        return new AiClientSponsoredProposalReviewResult(Optional.empty(),
                Optional.empty());
    }

    /** The gate reviewed the proposal but retained its exact request session. */
    static AiClientSponsoredProposalReviewResult nonTerminal(
            AiProposalReview review) {
        return new AiClientSponsoredProposalReviewResult(
                Optional.of(Objects.requireNonNull(review, "review")),
                Optional.empty());
    }

    /** The gate consumed the request and the coordinator exact-closed its ledger binding. */
    static AiClientSponsoredProposalReviewResult terminal(
            AiProposalReview review, AiClientSponsoredRequest binding) {
        return new AiClientSponsoredProposalReviewResult(
                Optional.of(Objects.requireNonNull(review, "review")),
                Optional.of(Objects.requireNonNull(binding, "binding")));
    }

    /** The gate review, or empty when correlation was dropped before review. */
    public Optional<AiProposalReview> review() {
        return review;
    }

    /** The exact binding removed after a terminal gate review, if any. */
    public Optional<AiClientSponsoredRequest> terminalBinding() {
        return terminalBinding;
    }

    /** No nonce, owner, prompt, raw tool arguments, or binding correlation is rendered. */
    @Override
    public String toString() {
        return "AiClientSponsoredProposalReviewResult[reviewed="
                + review.isPresent()
                + ", terminalBinding=" + terminalBinding.isPresent() + "]";
    }
}
