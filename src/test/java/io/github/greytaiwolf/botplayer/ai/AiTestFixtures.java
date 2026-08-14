package io.github.greytaiwolf.botplayer.ai;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * P6 纯 Java 单测的无凭据 fixture。
 */
final class AiTestFixtures {
    private AiTestFixtures() {
        throw new AssertionError("No instances");
    }

    static AiRequest request(UUID requestId) {
        return new AiRequest(
                requestId,
                "test-model",
                List.of(new AiMessage(AiMessageRole.USER, "测试请求")),
                AiRequestOptions.defaults(),
                Optional.empty());
    }

    static AiResponse response(AiRequest request, String providerId) {
        return new AiResponse(
                request.requestId(),
                providerId,
                request.model(),
                AiFinishReason.STOP,
                "测试响应",
                Optional.empty(),
                Optional.empty(),
                List.of(),
                AiTokenUsage.empty());
    }

    static AiCapabilities capabilities(String providerId) {
        return new AiCapabilities(
                providerId,
                Instant.EPOCH,
                List.of(new AiModelCapabilities(
                        "test-model",
                        8_192L,
                        1_024,
                        Set.of(AiCapability.CHAT))));
    }

    static AiProvider fixedProvider(String providerId, AiResponse response) {
        return new AiProvider() {
            @Override
            public CompletionStage<AiResponse> complete(
                    AiRequest request, CancellationToken token) {
                if (token.isCancellationRequested()) {
                    return CompletableFuture.failedFuture(
                            AiProviderException.of(AiFailureKind.CANCELLED));
                }
                return CompletableFuture.completedFuture(response);
            }

            @Override
            public CompletionStage<AiCapabilities> probeCapabilities() {
                return CompletableFuture.completedFuture(
                        capabilities(providerId));
            }

            @Override
            public ProviderHealth health() {
                return ProviderHealth.healthy(providerId, Instant.EPOCH);
            }
        };
    }
}
