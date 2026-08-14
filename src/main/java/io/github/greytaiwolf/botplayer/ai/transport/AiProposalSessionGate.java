package io.github.greytaiwolf.botplayer.ai.transport;

import io.github.greytaiwolf.botplayer.ai.AiRawToolCall;
import io.github.greytaiwolf.botplayer.ai.tool.ProposedSkillPlan;
import io.github.greytaiwolf.botplayer.ai.tool.ToolCallCodec;
import io.github.greytaiwolf.botplayer.ai.tool.ToolCallCodecException;
import io.github.greytaiwolf.botplayer.ai.tool.ToolFirewall;
import io.github.greytaiwolf.botplayer.ai.tool.ToolFirewallPolicy;
import io.github.greytaiwolf.botplayer.ai.tool.ToolFirewallResult;
import io.github.greytaiwolf.botplayer.network.payload.AiProposalPayload;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 服务端唯一持有的 P6 client proposal 会话 gate。
 *
 * <p>调用者必须在同一服务器线程调用本类。{@link #open(UUID, UUID, UUID, long, long, long, int,
 * ToolFirewallPolicy)} 生成 requestId 和 nonce；客户端无法选择它们。每 bot 最多一个活跃请求，
 * 每个完全关联的回传都会单次消费，无论工具语法或 Firewall 随后是否接受。过期、解绑和 bot
 * 卸载也会丢弃会话。
 *
 * <p>本类只构造 {@link ProposedSkillPlan} 并做静态 Firewall 审核。它不读取世界、不写入世界、
 * 不调用 Skill runtime，也不记录或回显模型原文。
 */
public final class AiProposalSessionGate {
    /** 一次 client-sponsored 请求最多存在 60 秒，避免迟到响应长期占用关联状态。 */
    public static final int MAX_REQUEST_TTL_TICKS =
            AiProposalSessionLimits.MAX_REQUEST_TTL_TICKS;

    private final Map<UUID, AiProposalRequestEnvelope> requestsById =
            new LinkedHashMap<>();
    private final Map<UUID, UUID> requestIdByBot = new LinkedHashMap<>();
    private final ToolCallCodec codec;

    public AiProposalSessionGate() {
        this(ToolCallCodec.strictDefaults());
    }

    public AiProposalSessionGate(ToolCallCodec codec) {
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    /**
     * Opens exactly one server-generated proposal request for the active agent.
     *
     * <p>This method intentionally accepts neither client nonce nor client request ID. The caller is
     * responsible for sending a minimal S2C request DTO to the already authorized owner. The
     * lifecycle-owned review-only bridge performs that handoff; this low-level gate intentionally
     * remains transport-free.
     */
    public AiProposalRequestEnvelope open(
            UUID botId,
            UUID ownerId,
            UUID agentId,
            long generation,
            long revision,
            long currentTick,
            int ttlTicks,
            ToolFirewallPolicy firewallPolicy) {
        return open(
                botId,
                ownerId,
                agentId,
                generation,
                revision,
                currentTick,
                ttlTicks,
                AiRequestPurpose.UNSPECIFIED_V1,
                true,
                firewallPolicy);
    }

    /**
     * Opens one request while retaining the trusted request response policy for its later C2S
     * review. The client-visible options are never treated as authority at review time.
     */
    public AiProposalRequestEnvelope open(
            UUID botId,
            UUID ownerId,
            UUID agentId,
            long generation,
            long revision,
            long currentTick,
            int ttlTicks,
            boolean toolCallsAllowed,
            ToolFirewallPolicy firewallPolicy) {
        return open(
                botId,
                ownerId,
                agentId,
                generation,
                revision,
                currentTick,
                ttlTicks,
                AiRequestPurpose.UNSPECIFIED_V1,
                toolCallsAllowed,
                firewallPolicy);
    }

    /**
     * Opens a purpose-bound request. The purpose is server-selected and retained next to the
     * trusted Firewall policy so an eventual C2S proposal cannot reinterpret one correlation as a
     * different product flow.
     */
    public AiProposalRequestEnvelope open(
            UUID botId,
            UUID ownerId,
            UUID agentId,
            long generation,
            long revision,
            long currentTick,
            int ttlTicks,
            AiRequestPurpose purpose,
            boolean toolCallsAllowed,
            ToolFirewallPolicy firewallPolicy) {
        return openInternal(
                botId,
                ownerId,
                agentId,
                generation,
                revision,
                currentTick,
                ttlTicks,
                purpose,
                toolCallsAllowed,
                firewallPolicy,
                false).opened();
    }

    /**
     * 显式替换同一 Bot 的未终态请求，并返回必须精确清理的旧关联。
     *
     * <p>仅 client-sponsored 协调器可使用此方法。它必须在向新 owner-client 分发之前，先
     * 取消 {@link AiProposalOpenResult#replaced()} 对应的客户端 HTTP、Scheduler 和
     * 生命周期 ticket；不得按 botId 宽泛清理，因为旧回调不能误伤新的 requestId。
     */
    public AiProposalOpenResult openReplacing(
            UUID botId,
            UUID ownerId,
            UUID agentId,
            long generation,
            long revision,
            long currentTick,
            int ttlTicks,
            AiRequestPurpose purpose,
            boolean toolCallsAllowed,
            ToolFirewallPolicy firewallPolicy) {
        return openInternal(
                botId,
                ownerId,
                agentId,
                generation,
                revision,
                currentTick,
                ttlTicks,
                purpose,
                toolCallsAllowed,
                firewallPolicy,
                true);
    }

    private AiProposalOpenResult openInternal(
            UUID botId,
            UUID ownerId,
            UUID agentId,
            long generation,
            long revision,
            long currentTick,
            int ttlTicks,
            AiRequestPurpose purpose,
            boolean toolCallsAllowed,
            ToolFirewallPolicy firewallPolicy,
            boolean allowReplacement) {
        requireNonZero(botId, "botId");
        requireNonZero(ownerId, "ownerId");
        requireNonZero(agentId, "agentId");
        if (generation <= 0L) {
            throw new IllegalArgumentException("generation must be positive");
        }
        if (revision <= 0L) {
            throw new IllegalArgumentException("revision must be positive");
        }
        if (currentTick < 0L) {
            throw new IllegalArgumentException("currentTick must not be negative");
        }
        if (ttlTicks < 1 || ttlTicks > MAX_REQUEST_TTL_TICKS) {
            throw new IllegalArgumentException(
                    "ttlTicks must be between 1 and " + MAX_REQUEST_TTL_TICKS);
        }
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(firewallPolicy, "firewallPolicy");
        long expiresAtTick;
        try {
            expiresAtTick = Math.addExact(currentTick, ttlTicks);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("request expiry overflow", exception);
        }

        AiProposalRequestEnvelope envelope = new AiProposalRequestEnvelope(
                botId,
                ownerId,
                agentId,
                generation,
                nextRequestId(),
                nextNonce(),
                revision,
                currentTick,
                expiresAtTick,
                purpose,
                toolCallsAllowed,
                firewallPolicy);
        UUID previousRequestId = requestIdByBot.get(botId);
        if (previousRequestId != null && !allowReplacement) {
            throw new IllegalStateException(
                    "an active proposal request already exists for this bot");
        }
        AiProposalRequestEnvelope previous = previousRequestId == null
                ? null
                : requestsById.get(previousRequestId);
        if (previousRequestId != null && previous == null) {
            throw new IllegalStateException(
                    "proposal gate request index is inconsistent");
        }
        Optional<AiProposalRequestEnvelope> replaced = Optional.ofNullable(previous);
        if (previous != null) {
            requestsById.remove(previousRequestId, previous);
        }
        requestIdByBot.put(botId, envelope.requestId());
        requestsById.put(envelope.requestId(), envelope);
        return new AiProposalOpenResult(envelope, replaced);
    }

    /**
     * Checks server-derived authority and the complete request binding, then performs syntax and
     * static Firewall review.
     *
     * <p>Model output text is deliberately ignored. No result from this method is an execution
     * authorization.
     */
    public AiProposalReview review(
            AiProposalPayload payload,
            AiProposalAuthority authority,
            long currentTick) {
        return reviewWithReceipt(payload, authority, currentTick).review();
    }

    /**
     * Reviews one proposal and exposes a safe receipt only when this invocation consumed the
     * exact gate envelope. Lifecycle code uses that receipt to remove a purpose-specific ticket
     * without guessing from a bot id or accepting a stale/partial correlation.
     */
    public AiProposalReviewReceipt reviewWithReceipt(
            AiProposalPayload payload,
            AiProposalAuthority authority,
            long currentTick) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(authority, "authority");
        if (currentTick < 0L) {
            throw new IllegalArgumentException("currentTick must not be negative");
        }
        if (!authority.botActive()) {
            return nonTerminal(AiProposalReviewStatus.BOT_NOT_ACTIVE);
        }
        if (!authority.senderIsPersistentOwner()) {
            return nonTerminal(AiProposalReviewStatus.NOT_OWNER);
        }
        if (authority.persistentOwnerId().isEmpty()) {
            return nonTerminal(AiProposalReviewStatus.OWNER_CHANGED);
        }
        Optional<UUID> activeAgentId = authority.activeAgentId();
        if (activeAgentId.isEmpty()
                || !activeAgentId.orElseThrow().equals(payload.agentId())) {
            return nonTerminal(AiProposalReviewStatus.AGENT_NOT_BOUND);
        }
        if (authority.activeGeneration().isEmpty()) {
            return nonTerminal(
                    AiProposalReviewStatus.GENERATION_MISMATCH);
        }

        AiProposalRequestEnvelope envelope = requestsById.get(payload.requestId());
        if (envelope == null || !envelope.requestId().equals(payload.requestId())) {
            return nonTerminal(AiProposalReviewStatus.NO_ACTIVE_REQUEST);
        }
        if (currentTick >= envelope.expiresAtTick()) {
            return terminal(envelope, AiProposalReview.rejected(
                    AiProposalReviewStatus.EXPIRED));
        }
        if (!envelope.botId().equals(payload.botId())) {
            return nonTerminal(AiProposalReviewStatus.BOT_MISMATCH);
        }
        if (!envelope.ownerId().equals(
                authority.persistentOwnerId().orElseThrow())) {
            return terminal(envelope, AiProposalReview.rejected(
                    AiProposalReviewStatus.OWNER_CHANGED));
        }
        if (!envelope.agentId().equals(payload.agentId())) {
            return nonTerminal(AiProposalReviewStatus.AGENT_MISMATCH);
        }
        if (envelope.generation()
                != authority.activeGeneration().getAsLong()) {
            /* A body generation never becomes current again, so retain no stale nonce. */
            return terminal(envelope, AiProposalReview.rejected(
                    AiProposalReviewStatus.GENERATION_MISMATCH));
        }
        if (envelope.generation() != payload.generation()) {
            return nonTerminal(
                    AiProposalReviewStatus.GENERATION_MISMATCH);
        }
        if (!envelope.nonce().equals(payload.nonce())) {
            return nonTerminal(AiProposalReviewStatus.NONCE_MISMATCH);
        }
        if (envelope.revision() != payload.revision()) {
            return nonTerminal(AiProposalReviewStatus.REVISION_MISMATCH);
        }

        /*
         * REVIEW_ONLY_V1 is not a generic ToolCallCodec/Firewall request. Validate its complete
         * wire shape while the exact correlation is still live, then terminally consume it on any
         * mismatch. This prevents free prose, a second tool, or even a syntactically valid but
         * different tool from reaching the generic proposal path or leaving the R1 ticket live.
         */
        if (envelope.purpose() == AiRequestPurpose.REVIEW_ONLY_V1
                && !AiReviewOnlyProposalShape.matches(payload)) {
            return terminal(envelope, AiProposalReview.rejected(
                    AiProposalReviewStatus.REVIEW_CONTRACT_REJECTED));
        }

        // A complete matching response is final even if it is malformed or rejected by policy.
        if (!envelope.toolCallsAllowed() && !payload.toolCalls().isEmpty()) {
            return terminal(envelope, AiProposalReview.rejected(
                    AiProposalReviewStatus.TOOL_CALLS_NOT_ALLOWED));
        }
        if (payload.toolCalls().isEmpty()) {
            return terminal(envelope, AiProposalReview.rejected(
                    AiProposalReviewStatus.NO_TOOL_CALLS));
        }

        List<AiRawToolCall> rawToolCalls = new ArrayList<>(payload.toolCalls().size());
        payload.toolCalls().forEach(toolCall -> rawToolCalls.add(toolCall.toRawToolCall()));
        final ProposedSkillPlan proposal;
        try {
            proposal = codec.decode(payload.requestId(), rawToolCalls);
        } catch (ToolCallCodecException exception) {
            return terminal(envelope, AiProposalReview.rejected(
                    AiProposalReviewStatus.MALFORMED_TOOL_CALL));
        }

        ToolFirewallResult firewallResult = new ToolFirewall(
                envelope.firewallPolicy()).review(proposal);
        if (!firewallResult.acceptedByStaticRules()) {
            return terminal(envelope, AiProposalReview.toolRejected(
                    firewallResult.rejection().orElseThrow()));
        }
        return terminal(envelope, AiProposalReview.acceptedNoExecution(proposal));
    }

    /**
     * Expires all requests whose half-open TTL no longer contains
     * {@code currentTick} and returns their exact correlations.
     *
     * <p>The lifecycle uses the returned envelopes to stop owner-client HTTP
     * work as soon as the server gate closes. Callers must never reconstruct a
     * broad cancellation from only a bot id or request id.
     */
    public List<AiProposalRequestEnvelope> closeExpiredThrough(long currentTick) {
        if (currentTick < 0L) {
            throw new IllegalArgumentException("currentTick must not be negative");
        }
        List<AiProposalRequestEnvelope> expired = requestsById.values().stream()
                .filter(envelope -> currentTick >= envelope.expiresAtTick())
                .toList();
        expired.forEach(this::removeRequest);
        return List.copyOf(expired);
    }

    /**
     * Compatibility count view of {@link #closeExpiredThrough(long)}.
     * New lifecycle code should use the returned correlations to notify the
     * client immediately.
     */
    public int expireThrough(long currentTick) {
        return closeExpiredThrough(currentTick).size();
    }

    /**
     * Invalidates every outstanding proposal for one bot, including a replaced active agent.
     *
     * <p>The returned envelope lets the lifecycle send an exact client cancellation without
     * retaining a separate, potentially stale correlation cache.
     */
    public Optional<AiProposalRequestEnvelope> closeBot(UUID botId) {
        requireNonZero(botId, "botId");
        UUID requestId = requestIdByBot.remove(botId);
        if (requestId != null) {
            return Optional.ofNullable(requestsById.remove(requestId));
        }
        return Optional.empty();
    }

    /**
     * 只关闭指定 requestId 仍代表的活跃 gate 会话。
     *
     * <p>异步 Scheduler、mailbox 或客户端终态回调必须使用此方法，而不是依据 botId 关闭；
     * 旧请求的迟到终态因而无法移除同一 Bot 的后续请求。
     */
    public Optional<AiProposalRequestEnvelope> closeExact(
            AiRequestDispatchReceipt expected) {
        AiRequestDispatchReceipt checkedExpected = Objects.requireNonNull(
                expected, "expected");
        AiProposalRequestEnvelope envelope = requestsById.get(checkedExpected.requestId());
        if (envelope == null
                || !AiRequestDispatchReceipt.fromEnvelope(envelope).equals(checkedExpected)) {
            return Optional.empty();
        }
        removeRequest(envelope);
        return Optional.of(envelope);
    }

    /**
     * Drops all transient request associations during server shutdown and returns the exact
     * correlations that may still have client HTTP work in flight.
     */
    public List<AiProposalRequestEnvelope> closeAll() {
        List<AiProposalRequestEnvelope> closed = List.copyOf(requestsById.values());
        requestsById.clear();
        requestIdByBot.clear();
        return closed;
    }

    public int activeRequestCount() {
        return requestsById.size();
    }

    private void removeRequest(AiProposalRequestEnvelope envelope) {
        requestsById.remove(envelope.requestId(), envelope);
        requestIdByBot.remove(envelope.botId(), envelope.requestId());
    }

    private AiProposalReviewReceipt nonTerminal(AiProposalReviewStatus status) {
        return AiProposalReviewReceipt.nonTerminal(AiProposalReview.rejected(status));
    }

    private AiProposalReviewReceipt terminal(
            AiProposalRequestEnvelope envelope, AiProposalReview review) {
        removeRequest(envelope);
        return AiProposalReviewReceipt.terminal(
                review, AiRequestDispatchReceipt.fromEnvelope(envelope));
    }

    private UUID nextRequestId() {
        UUID requestId;
        do {
            requestId = nextNonZeroUuid();
        } while (requestsById.containsKey(requestId));
        return requestId;
    }

    private static UUID nextNonce() {
        return nextNonZeroUuid();
    }

    private static UUID nextNonZeroUuid() {
        UUID value;
        do {
            value = UUID.randomUUID();
        } while (value.getMostSignificantBits() == 0L
                && value.getLeastSignificantBits() == 0L);
        return value;
    }

    private static void requireNonZero(UUID value, String name) {
        Objects.requireNonNull(value, name);
        if (value.getMostSignificantBits() == 0L
                && value.getLeastSignificantBits() == 0L) {
            throw new IllegalArgumentException(name + " must not be zero");
        }
    }
}
