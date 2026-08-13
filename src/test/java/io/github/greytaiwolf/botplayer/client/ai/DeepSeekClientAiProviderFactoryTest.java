package io.github.greytaiwolf.botplayer.client.ai;

import io.github.greytaiwolf.botplayer.ai.AiCapability;
import io.github.greytaiwolf.botplayer.ai.AiMessage;
import io.github.greytaiwolf.botplayer.ai.AiMessageRole;
import io.github.greytaiwolf.botplayer.ai.AiModelCapabilities;
import io.github.greytaiwolf.botplayer.ai.AiProvider;
import io.github.greytaiwolf.botplayer.ai.AiRequestOptions;
import io.github.greytaiwolf.botplayer.ai.AiResponseFormat;
import io.github.greytaiwolf.botplayer.ai.transport.AiClientRequestDispatch;
import io.github.greytaiwolf.botplayer.client.credential.ClientCredentialStore;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeepSeekClientAiProviderFactoryTest {
    private static final UUID SERVER_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000001");
    private static final UUID BOT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000101");
    private static final UUID OWNER_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000151");
    private static final UUID AGENT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000201");

    @TempDir
    Path temporaryDirectory;

    @Test
    void createsOnlyForTheLocallyConfiguredDeepSeekProviderAndModel()
            throws Exception {
        DeepSeekClientAiProviderFactory factory = new DeepSeekClientAiProviderFactory(
                new DeepSeekProviderConfig(
                        List.of(new AiModelCapabilities(
                                "deepseek-chat",
                                8_192L,
                                1_024,
                                Set.of(AiCapability.CHAT))),
                        List.of(),
                        false),
                ignored -> CompletableFuture.failedFuture(
                        new AssertionError("factory construction must not issue HTTP")),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        try (ClientCredentialStore store = new ClientCredentialStore(temporaryDirectory)) {
            store.bind(
                    SERVER_ID,
                    OWNER_ID,
                    BOT_ID,
                    "private",
                    "sk-fake-test-key",
                    Optional.of(AGENT_ID));

            AiProvider provider = factory.create(
                    dispatch("deepseek", "deepseek-chat"), store);

            Assertions.assertInstanceOf(DeepSeekProvider.class, provider);
            Assertions.assertThrows(IllegalArgumentException.class,
                    () -> factory.create(dispatch("other", "deepseek-chat"), store));
            Assertions.assertThrows(IllegalArgumentException.class,
                    () -> factory.create(dispatch("deepseek", "unconfigured-model"), store));
        }
    }

    private static AiClientRequestDispatch dispatch(String providerId, String model) {
        return new AiClientRequestDispatch(
                SERVER_ID,
                BOT_ID,
                OWNER_ID,
                AGENT_ID,
                1L,
                UUID.fromString("00000000-0000-0000-0000-000000000301"),
                UUID.fromString("00000000-0000-0000-0000-000000000401"),
                1L,
                100L,
                120L,
                1_000L,
                2_000L,
                providerId,
                model,
                List.of(new AiMessage(AiMessageRole.USER, "safe request")),
                new AiRequestOptions(
                        256,
                        500L,
                        AiResponseFormat.TEXT,
                        false,
                        false,
                        Optional.empty()),
                Optional.empty());
    }
}
