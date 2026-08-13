package io.github.greytaiwolf.botplayer.ai.transport;

import io.github.greytaiwolf.botplayer.ai.AiRequest;
import io.github.greytaiwolf.botplayer.ai.AiScheduledRequest;
import io.github.greytaiwolf.botplayer.network.payload.AiProposalPayload;
import io.github.greytaiwolf.botplayer.network.payload.AiRequestCancellationPayload;
import java.util.Objects;
import java.util.UUID;

/**
 * 客户端赞助 AI 请求的唯一服务端关联绑定。
 *
 * <p>提案 gate 先生成 {@code requestId + nonce}，本类随后把同一份身份同时写入发给
 * 客户端的 dispatch 与交给调度器的 {@link AiScheduledRequest}。请求模板自身的 ID 绝不
 * 参与关联，避免调度器健康记录、客户端回传与 gate 分别使用三个不相同的 ID。
 *
 * <p>本类只固定纯数据关联，不发送网络包、不启动 Provider、不读取 Minecraft 对象，也不
 * 授予 Tool、Skill、Action 或世界执行权限。生命周期集成必须先创建 gate envelope，再通过
 * {@link #bind(UUID, AiProposalRequestEnvelope, long, long, String, AiRequest)} 取得两个
 * 下游 DTO；不得绕过此绑定分别构造调度请求和客户端 dispatch。
 */
public final class AiClientSponsoredRequest {
    private final AiClientRequestDispatch dispatch;
    private final AiScheduledRequest scheduledRequest;

    private AiClientSponsoredRequest(
            AiClientRequestDispatch dispatch,
            AiScheduledRequest scheduledRequest) {
        this.dispatch = Objects.requireNonNull(dispatch, "dispatch");
        this.scheduledRequest = Objects.requireNonNull(
                scheduledRequest, "scheduledRequest");
        verifySharedIdentity(dispatch, scheduledRequest);
    }

    /**
     * 用 gate 已签发的精确关联绑定一个经过预校验的请求模板。
     *
     * <p>模板只贡献模型、消息、选项和可选 schema；其 {@code requestId} 会被 gate 的
     * {@code requestId} 替换。这样即便调用者重用了一个模板，也不可能让旧请求 ID 进入
     * Scheduler 的健康、取消或完成记录。
     */
    public static AiClientSponsoredRequest bind(
            UUID serverInstanceId,
            AiProposalRequestEnvelope envelope,
            long issuedAtEpochMillis,
            long expiresAtEpochMillis,
            String providerId,
            AiRequest requestTemplate) {
        AiProposalRequestEnvelope checkedEnvelope = Objects.requireNonNull(
                envelope, "envelope");
        AiClientRequestDispatch dispatch = AiClientRequestDispatch.fromEnvelope(
                requireNonZero(serverInstanceId, "serverInstanceId"),
                checkedEnvelope,
                issuedAtEpochMillis,
                expiresAtEpochMillis,
                providerId,
                Objects.requireNonNull(requestTemplate, "requestTemplate"));
        AiScheduledRequest scheduled = new AiScheduledRequest(
                checkedEnvelope.ownerId(),
                checkedEnvelope.botId(),
                checkedEnvelope.agentId(),
                checkedEnvelope.revision(),
                dispatch.toAiRequest());
        return new AiClientSponsoredRequest(dispatch, scheduled);
    }

    /** 只读客户端 dispatch；它仍不包含 credential profile 或 Authorization。 */
    public AiClientRequestDispatch dispatch() {
        return dispatch;
    }

    /** 只读 Scheduler 请求；其 requestId 必须与 gate/dispatch 完全相同。 */
    public AiScheduledRequest scheduledRequest() {
        return scheduledRequest;
    }

    /**
     * 返回 gate 终态或异步清理必须携带的完整、安全 receipt。
     *
     * <p>Scheduler 只以 requestId 维护运行状态，不能把 {@link AiScheduledRequest} 当作 gate
     * 关闭授权。协调器必须保留本绑定，并以该 receipt 调用
     * {@link AiProposalSessionGate#closeExact(AiRequestDispatchReceipt)}。
     */
    public AiRequestDispatchReceipt dispatchReceipt() {
        return new AiRequestDispatchReceipt(
                dispatch.botId(),
                dispatch.agentId(),
                dispatch.generation(),
                dispatch.requestId(),
                dispatch.revision(),
                dispatch.expiresAtTick(),
                dispatch.purpose());
    }

    /** 为同一精确关联生成取消 payload，禁止按 botId 做宽泛取消。 */
    public AiRequestCancellationPayload cancellationPayload() {
        return new AiRequestCancellationPayload(
                dispatch.serverInstanceId(),
                dispatch.botId(),
                dispatch.ownerId(),
                dispatch.agentId(),
                dispatch.generation(),
                dispatch.requestId(),
                dispatch.nonce(),
                dispatch.revision());
    }

    /**
     * 仅比较 C2S payload 的完整、不可信关联元组。
     *
     * <p>返回 {@code true} 不等于 payload 已获授权；生命周期仍必须调用
     * {@link AiProposalSessionGate#reviewWithReceipt} 重新核对 owner、活动 agent、
     * generation、TTL 和 Firewall。
     */
    public boolean matches(AiProposalPayload payload) {
        AiProposalPayload checked = Objects.requireNonNull(payload, "payload");
        return dispatch.botId().equals(checked.botId())
                && dispatch.agentId().equals(checked.agentId())
                && dispatch.generation() == checked.generation()
                && dispatch.requestId().equals(checked.requestId())
                && dispatch.nonce().equals(checked.nonce())
                && dispatch.revision() == checked.revision();
    }

    /** 只接受同一 gate 消费后生成的精确安全 receipt。 */
    public boolean matches(AiRequestDispatchReceipt receipt) {
        AiRequestDispatchReceipt checked = Objects.requireNonNull(receipt, "receipt");
        return dispatch.botId().equals(checked.botId())
                && dispatch.agentId().equals(checked.agentId())
                && dispatch.generation() == checked.generation()
                && dispatch.requestId().equals(checked.requestId())
                && dispatch.revision() == checked.revision()
                && dispatch.expiresAtTick() == checked.expiresAtTick()
                && dispatch.purpose() == checked.purpose();
    }

    /** 使用 gate tick TTL 判断服务端关联是否已过期。 */
    public boolean expiresAtOrBefore(long currentTick) {
        if (currentTick < 0L) {
            throw new IllegalArgumentException("currentTick must not be negative");
        }
        return currentTick >= dispatch.expiresAtTick();
    }

    /** 不输出 nonce、owner、prompt、schema 或任何 credential 派生内容。 */
    @Override
    public String toString() {
        return "AiClientSponsoredRequest[botId=" + dispatch.botId()
                + ", agentId=" + dispatch.agentId()
                + ", generation=" + dispatch.generation()
                + ", requestId=" + dispatch.requestId()
                + ", revision=" + dispatch.revision()
                + ", purpose=" + dispatch.purpose()
                + ", providerId=" + dispatch.providerId()
                + ", model=" + dispatch.model()
                + "]";
    }

    private static void verifySharedIdentity(
            AiClientRequestDispatch dispatch,
            AiScheduledRequest scheduled) {
        if (!dispatch.ownerId().equals(scheduled.ownerId())
                || !dispatch.botId().equals(scheduled.botId())
                || !dispatch.agentId().equals(scheduled.agentId())
                || dispatch.revision() != scheduled.revision()
                || !dispatch.requestId().equals(
                        scheduled.request().requestId())) {
            throw new IllegalArgumentException(
                    "client dispatch and scheduled request must share one server correlation");
        }
    }

    private static UUID requireNonZero(UUID value, String name) {
        Objects.requireNonNull(value, name);
        if (value.getMostSignificantBits() == 0L
                && value.getLeastSignificantBits() == 0L) {
            throw new IllegalArgumentException(name + " must not be zero");
        }
        return value;
    }
}
