package io.github.greytaiwolf.botplayer.ai.transport;

import io.github.greytaiwolf.botplayer.ai.tool.ProposedSkillPlan;
import io.github.greytaiwolf.botplayer.ai.tool.ToolFirewallRejection;
import java.util.Objects;
import java.util.Optional;

/**
 * 不含模型原文的 AI 提案审核结果。
 *
 * <p>{@link #proposal()} 只在 {@link AiProposalReviewStatus#ACCEPTED_NO_EXECUTION} 时存在，且仍是
 * 未授权的不可执行 DTO。调用方必须在未来再做 snapshot、world revision、ACL 和计划校验，才可能
 * 交给任何确定性执行层。
 */
public record AiProposalReview(
        AiProposalReviewStatus status,
        Optional<ProposedSkillPlan> proposal,
        Optional<ToolFirewallRejection> firewallRejection) {
    public AiProposalReview {
        Objects.requireNonNull(status, "status");
        proposal = Objects.requireNonNull(proposal, "proposal");
        firewallRejection = Objects.requireNonNull(
                firewallRejection, "firewallRejection");
        if (status == AiProposalReviewStatus.ACCEPTED_NO_EXECUTION
                && proposal.isEmpty()) {
            throw new IllegalArgumentException(
                    "accepted review requires an unexecuted proposal");
        }
        if (status != AiProposalReviewStatus.ACCEPTED_NO_EXECUTION
                && proposal.isPresent()) {
            throw new IllegalArgumentException(
                    "rejected review must not expose a proposal");
        }
        if (status == AiProposalReviewStatus.TOOL_REJECTED
                && firewallRejection.isEmpty()) {
            throw new IllegalArgumentException(
                    "tool rejection requires a firewall rejection code");
        }
        if (status != AiProposalReviewStatus.TOOL_REJECTED
                && firewallRejection.isPresent()) {
            throw new IllegalArgumentException(
                    "only tool rejection may expose a firewall rejection code");
        }
    }

    public static AiProposalReview rejected(AiProposalReviewStatus status) {
        if (status == AiProposalReviewStatus.ACCEPTED_NO_EXECUTION
                || status == AiProposalReviewStatus.TOOL_REJECTED) {
            throw new IllegalArgumentException("use a dedicated review factory");
        }
        return new AiProposalReview(status, Optional.empty(), Optional.empty());
    }

    public static AiProposalReview toolRejected(
            ToolFirewallRejection rejection) {
        return new AiProposalReview(
                AiProposalReviewStatus.TOOL_REJECTED,
                Optional.empty(),
                Optional.of(Objects.requireNonNull(rejection, "rejection")));
    }

    public static AiProposalReview acceptedNoExecution(
            ProposedSkillPlan proposal) {
        return new AiProposalReview(
                AiProposalReviewStatus.ACCEPTED_NO_EXECUTION,
                Optional.of(Objects.requireNonNull(proposal, "proposal")),
                Optional.empty());
    }

    public boolean acceptedNoExecution() {
        return status == AiProposalReviewStatus.ACCEPTED_NO_EXECUTION;
    }

    /** 审核结果不回显模型原文、工具参数或 tool call id。 */
    @Override
    public String toString() {
        return "AiProposalReview[status=" + status
                + ", hasProposal=" + proposal.isPresent()
                + ", hasFirewallRejection=" + firewallRejection.isPresent()
                + "]";
    }
}
