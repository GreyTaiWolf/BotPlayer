package io.github.greytaiwolf.botplayer.ai.transport;

import java.util.Objects;
import java.util.Optional;

/**
 * Proposal gate 审核的服务端关联回执。
 *
 * <p>当且仅当 gate 已经消费了一个精确关联时，{@link #terminalDispatch()} 才存在。调用方可
 * 据此删除自己在 gate 外保存的同一 request ticket，而无需根据 botId 做宽泛清理。回执不暴露
 * nonce、模型正文、工具参数或 {@code ProposedSkillPlan}。
 */
public record AiProposalReviewReceipt(
        AiProposalReview review,
        Optional<AiRequestDispatchReceipt> terminalDispatch) {
    public AiProposalReviewReceipt {
        review = Objects.requireNonNull(review, "review");
        terminalDispatch = Objects.requireNonNull(
                terminalDispatch, "terminalDispatch");
    }

    public static AiProposalReviewReceipt nonTerminal(AiProposalReview review) {
        return new AiProposalReviewReceipt(
                Objects.requireNonNull(review, "review"), Optional.empty());
    }

    public static AiProposalReviewReceipt terminal(
            AiProposalReview review, AiRequestDispatchReceipt dispatch) {
        return new AiProposalReviewReceipt(
                Objects.requireNonNull(review, "review"),
                Optional.of(Objects.requireNonNull(dispatch, "dispatch")));
    }

    @Override
    public String toString() {
        return "AiProposalReviewReceipt[status=" + review.status()
                + ", terminalDispatch=" + terminalDispatch.isPresent() + "]";
    }
}
