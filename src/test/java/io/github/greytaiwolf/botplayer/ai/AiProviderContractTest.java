package io.github.greytaiwolf.botplayer.ai;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AiProviderContractTest {
    private static final UUID REQUEST_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Test
    void providerBoundaryUsesOnlyImmutableCoreDtos() {
        AiProvider provider = new TestProvider();
        AiRequest request = new AiRequest(
                REQUEST_ID,
                "test-model",
                List.of(new AiMessage(AiMessageRole.USER, "hello")),
                AiRequestOptions.defaults(),
                Optional.empty());

        AiResponse response = provider.complete(
                request, CancellationToken.none())
                .toCompletableFuture()
                .join();
        Assertions.assertEquals(REQUEST_ID, response.requestId());
        Assertions.assertEquals(
                "test-model",
                provider.probeCapabilities()
                        .toCompletableFuture()
                        .join()
                        .models()
                        .getFirst()
                        .model());
        Assertions.assertTrue(provider.health().acceptingRequests());
    }

    private static final class TestProvider implements AiProvider {
        @Override
        public CompletionStage<AiResponse> complete(
                AiRequest request, CancellationToken token) {
            token.throwIfCancellationRequested();
            return CompletableFuture.completedFuture(
                    new AiResponse(
                            request.requestId(),
                            "scripted",
                            request.model(),
                            AiFinishReason.STOP,
                            "ok",
                            Optional.empty(),
                            Optional.empty(),
                            List.of(),
                            new AiTokenUsage(1L, 1L, 0L)));
        }

        @Override
        public CompletionStage<AiCapabilities> probeCapabilities() {
            return CompletableFuture.completedFuture(
                    new AiCapabilities(
                            "scripted",
                            Instant.EPOCH,
                            List.of(new AiModelCapabilities(
                                    "test-model",
                                    8_192L,
                                    1_024,
                                    Set.of(AiCapability.CHAT)))));
        }

        @Override
        public ProviderHealth health() {
            return ProviderHealth.healthy(
                    "scripted", Instant.EPOCH);
        }
    }
}
