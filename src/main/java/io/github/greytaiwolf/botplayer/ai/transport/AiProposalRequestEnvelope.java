package io.github.greytaiwolf.botplayer.ai.transport;

import io.github.greytaiwolf.botplayer.ai.tool.ToolFirewallPolicy;
import java.util.Objects;
import java.util.UUID;

/**
 * 由服务端为一次 client-sponsored AI 请求创建的、不可变的关联信封。
 *
 * <p>此对象只保存在服务端会话 gate 内。它不是网络 payload，也绝不包含 prompt、模型输出、
 * credential profile 或任何 secret。{@link #firewallPolicy()} 同样只由可信 Java 侧创建；客户端
 * 不能选择白名单、风险上限或 schema。
 */
public final class AiProposalRequestEnvelope {
    private final UUID botId;
    private final UUID ownerId;
    private final UUID agentId;
    private final long generation;
    private final UUID requestId;
    private final UUID nonce;
    private final long revision;
    private final long issuedAtTick;
    private final long expiresAtTick;
    private final AiRequestPurpose purpose;
    private final boolean toolCallsAllowed;
    private final ToolFirewallPolicy firewallPolicy;

    /**
     * Deliberately package-private: only {@link AiProposalSessionGate} may place an envelope in the
     * active server session map, and that gate generates both requestId and nonce itself.
     */
    AiProposalRequestEnvelope(
            UUID botId,
            UUID ownerId,
            UUID agentId,
            long generation,
            UUID requestId,
            UUID nonce,
            long revision,
            long issuedAtTick,
            long expiresAtTick,
            ToolFirewallPolicy firewallPolicy) {
        this(
                botId,
                ownerId,
                agentId,
                generation,
                requestId,
                nonce,
                revision,
                issuedAtTick,
                expiresAtTick,
                AiRequestPurpose.UNSPECIFIED_V1,
                true,
                firewallPolicy);
    }

    /** Compatibility constructor for pre-purpose server-only gate tests. */
    AiProposalRequestEnvelope(
            UUID botId,
            UUID ownerId,
            UUID agentId,
            long generation,
            UUID requestId,
            UUID nonce,
            long revision,
            long issuedAtTick,
            long expiresAtTick,
            boolean toolCallsAllowed,
            ToolFirewallPolicy firewallPolicy) {
        this(
                botId,
                ownerId,
                agentId,
                generation,
                requestId,
                nonce,
                revision,
                issuedAtTick,
                expiresAtTick,
                AiRequestPurpose.UNSPECIFIED_V1,
                toolCallsAllowed,
                firewallPolicy);
    }

    AiProposalRequestEnvelope(
            UUID botId,
            UUID ownerId,
            UUID agentId,
            long generation,
            UUID requestId,
            UUID nonce,
            long revision,
            long issuedAtTick,
            long expiresAtTick,
            AiRequestPurpose purpose,
            boolean toolCallsAllowed,
            ToolFirewallPolicy firewallPolicy) {
        requireNonZero(botId, "botId");
        requireNonZero(ownerId, "ownerId");
        requireNonZero(agentId, "agentId");
        if (generation <= 0L) {
            throw new IllegalArgumentException("generation must be positive");
        }
        requireNonZero(requestId, "requestId");
        requireNonZero(nonce, "nonce");
        if (revision <= 0L) {
            throw new IllegalArgumentException("revision must be positive");
        }
        if (issuedAtTick < 0L) {
            throw new IllegalArgumentException("issuedAtTick must not be negative");
        }
        if (expiresAtTick <= issuedAtTick) {
            throw new IllegalArgumentException(
                    "expiresAtTick must be after issuedAtTick");
        }
        this.botId = botId;
        this.ownerId = ownerId;
        this.agentId = agentId;
        this.generation = generation;
        this.requestId = requestId;
        this.nonce = nonce;
        this.revision = revision;
        this.issuedAtTick = issuedAtTick;
        this.expiresAtTick = expiresAtTick;
        this.purpose = Objects.requireNonNull(purpose, "purpose");
        this.toolCallsAllowed = toolCallsAllowed;
        this.firewallPolicy = Objects.requireNonNull(firewallPolicy, "firewallPolicy");
    }

    public UUID botId() {
        return botId;
    }

    /** Persistent roster owner that was authoritative when this request was issued. */
    public UUID ownerId() {
        return ownerId;
    }

    public UUID agentId() {
        return agentId;
    }

    /** Server-observed BotPlayer body generation at request issuance. */
    public long generation() {
        return generation;
    }

    public UUID requestId() {
        return requestId;
    }

    /** Never include this correlation secret in logs or a generic diagnostic. */
    public UUID nonce() {
        return nonce;
    }

    public long revision() {
        return revision;
    }

    public long issuedAtTick() {
        return issuedAtTick;
    }

    public long expiresAtTick() {
        return expiresAtTick;
    }

    /** Fixed server-selected request purpose; never chosen by a C2S proposal. */
    public AiRequestPurpose purpose() {
        return purpose;
    }

    /** Trusted response policy captured from the server-approved request template. */
    public boolean toolCallsAllowed() {
        return toolCallsAllowed;
    }

    /** Trusted server-only policy; never serialize it as part of a payload. */
    public ToolFirewallPolicy firewallPolicy() {
        return firewallPolicy;
    }

    private static void requireNonZero(UUID value, String name) {
        Objects.requireNonNull(value, name);
        if (value.getMostSignificantBits() == 0L
                && value.getLeastSignificantBits() == 0L) {
            throw new IllegalArgumentException(name + " must not be zero");
        }
    }

    /** 不输出 nonce 或 policy 内容，避免诊断日志意外扩大会话攻击面。 */
    @Override
    public String toString() {
        return "AiProposalRequestEnvelope[botId=" + botId
                + ", agentId=" + agentId
                + ", generation=" + generation
                + ", requestId=" + requestId
                + ", revision=" + revision
                + ", issuedAtTick=" + issuedAtTick
                + ", expiresAtTick=" + expiresAtTick
                + ", purpose=" + purpose
                + ", toolCallsAllowed=" + toolCallsAllowed
                + ", policyToolCount=" + firewallPolicy.knownTools().size()
                + "]";
    }
}
