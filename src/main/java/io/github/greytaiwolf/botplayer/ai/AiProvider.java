package io.github.greytaiwolf.botplayer.ai;

import java.util.concurrent.CompletionStage;

/**
 * 异步 AI Provider 边界。
 *
 * <p>实现不得阻塞 Minecraft 服务器线程，不得接收或返回活动 Minecraft 对象。Provider
 * 响应始终是不可信输入；本接口不授予世界动作、权限或 Skill 执行权。
 */
public interface AiProvider {
    CompletionStage<AiResponse> complete(
            AiRequest request, CancellationToken token);

    CompletionStage<AiCapabilities> probeCapabilities();

    ProviderHealth health();
}
