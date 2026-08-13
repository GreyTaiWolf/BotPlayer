package io.github.greytaiwolf.botplayer.client.ai;

import java.util.concurrent.CompletionStage;

/**
 * 可替换的客户端 HTTP 边界，供本地无网络单测注入 fixture。
 *
 * <p>实现只能连接由 {@link DeepSeekProvider} 创建的固定 DeepSeek endpoint；它不得访问
 * Minecraft 活动对象或把请求 secret 交给任何自定义 payload。</p>
 */
@FunctionalInterface
public interface DeepSeekHttpExecutor {
    CompletionStage<DeepSeekHttpResponse> execute(DeepSeekHttpRequest request);

    /**
     * 尽力中止仍在运行的请求；fixture executor 可以保留默认无操作实现。
     *
     * <p>调用方只会传入由 {@link #execute(DeepSeekHttpRequest)} 接收的同一受控请求对象，
     * 因此实现不得把它转发给任何非 DeepSeek 网络边界。</p>
     */
    default void cancel(DeepSeekHttpRequest request) {
        // lambda fixture 不需要维护真实网络句柄。
    }
}
