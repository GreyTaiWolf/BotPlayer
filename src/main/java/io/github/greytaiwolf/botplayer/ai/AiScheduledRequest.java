package io.github.greytaiwolf.botplayer.ai;

import java.util.Objects;
import java.util.UUID;

/**
 * 服务端调度层绑定的不可变请求关联信息。
 *
 * <p>owner、bot、agent 和 revision 必须由服务器会话/快照层提供；模型和客户端都不能借
 * Provider 请求改变这些字段。本 DTO 不包含 credential 或世界引用。</p>
 */
public record AiScheduledRequest(
        UUID ownerId, UUID botId, UUID agentId, long revision, AiRequest request) {
    public AiScheduledRequest {
        AiChecks.requireNonZero(ownerId, "ownerId");
        AiChecks.requireNonZero(botId, "botId");
        AiChecks.requireNonZero(agentId, "agentId");
        if (revision <= 0L) {
            throw new IllegalArgumentException("revision must be positive");
        }
        Objects.requireNonNull(request, "request");
    }

    /** 不递归输出 prompt 或 schema。 */
    @Override
    public String toString() {
        return "AiScheduledRequest[botId=" + botId
                + ", agentId=" + agentId
                + ", revision=" + revision
                + ", requestId=" + request.requestId()
                + ", model=" + request.model() + "]";
    }
}
