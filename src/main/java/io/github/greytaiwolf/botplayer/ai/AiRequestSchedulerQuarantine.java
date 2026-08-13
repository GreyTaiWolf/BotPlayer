package io.github.greytaiwolf.botplayer.ai;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 有界诊断历史中的一项逻辑槽位 quarantine。
 *
 * <p>不携带 prompt、模型输出、凭据、异常正文或工具参数。失败原因与
 * {@link AiScheduledRequestHealth} 保持同一终态来源，便于上层将请求身份和退化原因关联起来。</p>
 */
public record AiRequestSchedulerQuarantine(
        UUID requestId,
        AiFailureKind failureKind,
        AiReasonCode reasonCode,
        AiRequestSchedulerQuarantineKind kind,
        Instant observedAt) {
    public AiRequestSchedulerQuarantine {
        AiChecks.requireNonZero(requestId, "requestId");
        Objects.requireNonNull(failureKind, "failureKind");
        Objects.requireNonNull(reasonCode, "reasonCode");
        Objects.requireNonNull(kind, "kind");
        observedAt = AiChecks.instant(observedAt, "observedAt");
    }
}
