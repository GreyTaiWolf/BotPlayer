package io.github.greytaiwolf.botplayer.ai;

import java.util.Objects;
import java.util.UUID;

/**
 * 显式取消的异步安全回执。
 *
 * <p>调度器只会在相应请求的终态清理已实际确认，或者该清理已被显式 quarantine 后完成本回执。
 * 因此它可以作为会话层释放本地请求引用的确认点，但不能据此假设被 quarantine 的 Provider
 * 已停止物理执行。特别是，当 cancellation 在 Provider-start 提交之后获胜时，
 * {@link AiRequestCancellationDisposition#CANCELLED} 仍只表示逻辑请求已撤销；Provider 可能已
 * 发出远端请求并持续占用受限 lane，直到诊断中的 quarantine 清零。</p>
 */
public record AiRequestCancellationReceipt(
        UUID requestId, AiRequestCancellationDisposition disposition) {
    public AiRequestCancellationReceipt {
        AiChecks.requireNonZero(requestId, "requestId");
        Objects.requireNonNull(disposition, "disposition");
    }
}
