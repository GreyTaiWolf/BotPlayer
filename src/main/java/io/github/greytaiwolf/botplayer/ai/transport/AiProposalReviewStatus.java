package io.github.greytaiwolf.botplayer.ai.transport;

/**
 * AI 提案 server gate 的稳定、无敏感原文结果码。
 *
 * <p>所有接受结果都明确表示“仅静态审核，未执行”。当前 P6 基础切片不会把任何结果提交给
 * Skill runtime 或世界动作。
 */
public enum AiProposalReviewStatus {
    BOT_NOT_ACTIVE,
    NOT_OWNER,
    OWNER_CHANGED,
    AGENT_NOT_BOUND,
    NO_ACTIVE_REQUEST,
    EXPIRED,
    BOT_MISMATCH,
    AGENT_MISMATCH,
    GENERATION_MISMATCH,
    NONCE_MISMATCH,
    REVISION_MISMATCH,
    /** A current R1 proposal arrived before its exact server-side physical attempt was granted. */
    PHYSICAL_ATTEMPT_NOT_GRANTED,
    /** The server-selected REVIEW_ONLY_V1 reply shape was not the one fixed acknowledgement. */
    REVIEW_CONTRACT_REJECTED,
    TOOL_CALLS_NOT_ALLOWED,
    NO_TOOL_CALLS,
    MALFORMED_TOOL_CALL,
    TOOL_REJECTED,
    ACCEPTED_NO_EXECUTION
}
