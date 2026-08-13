package io.github.greytaiwolf.botplayer.ai;

/**
 * 安全请求句柄对一次显式取消请求的最终确认。
 *
 * <p>该值不包含 prompt、凭据、Provider 原始错误或模型输出。{@link #CANCELLED} 表示该句柄的
 * 取消调用在线性化竞态中获胜；{@link #ALREADY_TERMINAL} 表示请求在该调用前已由成功、失败、
 * deadline、关闭或外部 token 进入终态。</p>
 */
public enum AiRequestCancellationDisposition {
    CANCELLED,
    ALREADY_TERMINAL
}
