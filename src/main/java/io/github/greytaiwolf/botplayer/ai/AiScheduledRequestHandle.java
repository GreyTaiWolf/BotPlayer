package io.github.greytaiwolf.botplayer.ai;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * 提交给 {@link AiRequestScheduler} 后返回的受限请求句柄。
 *
 * <p>{@link #response()} 和 {@link #requestCancellation()} 都只返回 minimal/read-only
 * CompletionStage 视图。调用者即使对 {@code toCompletableFuture()} 得到的副本调用
 * {@code cancel}、{@code complete} 或 {@code obtrude}，也不能改变调度器中的请求状态、
 * 清理顺序或其他观察者看到的真实结果。</p>
 *
 * <p>显式取消不直接完成 public response。它先在线性化点撤销请求，再等待真实终态清理 ACK
 * 或明确 quarantine；随后 response 与 {@link AiRequestCancellationReceipt} 才可见。若取消
 * 在线性化 Provider start <em>之前</em>获胜，Provider 不会被调用；若 Provider start 先提交，
 * Provider 可能已经开始，回执只确认逻辑取消和有界物理 quarantine，不声称远端工作已停止。
 * 句柄不承载请求文本、凭据、模型输出以外的可变控制面，也不会授予世界动作权限。</p>
 */
public final class AiScheduledRequestHandle {
    private final UUID requestId;
    private final CompletionStage<AiResponse> response;
    private final Supplier<CompletionStage<AiRequestCancellationReceipt>> cancellation;

    AiScheduledRequestHandle(
            UUID requestId,
            CompletionStage<AiResponse> response,
            Supplier<CompletionStage<AiRequestCancellationReceipt>> cancellation) {
        AiChecks.requireNonZero(requestId, "requestId");
        this.requestId = requestId;
        this.response = Objects.requireNonNull(response, "response");
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
    }

    /** 不可变的安全请求 ID，供会话层关联本地状态。 */
    public UUID requestId() {
        return requestId;
    }

    /**
     * 返回只读响应视图。
     *
     * <p>该视图永远不会把 caller 的 CompletableFuture 变异传播回调度器。正常终态会在清理
     * ACK 或 quarantine 后才发布。</p>
     */
    public CompletionStage<AiResponse> response() {
        return response;
    }

    /**
     * 请求取消并返回幂等的异步回执。
     *
     * <p>该方法本身只执行调度器状态线性化，不读取 token、不调用 Provider，也不执行 caller
     * continuation。重复调用返回同一请求的等价回执视图。</p>
     */
    public CompletionStage<AiRequestCancellationReceipt> requestCancellation() {
        return cancellation.get();
    }

    @Override
    public String toString() {
        return "AiScheduledRequestHandle[requestId=" + requestId + "]";
    }
}
