package io.github.greytaiwolf.botplayer.client.ai;

import io.github.greytaiwolf.botplayer.ai.AiCapability;
import io.github.greytaiwolf.botplayer.ai.AiModelCapabilities;
import io.github.greytaiwolf.botplayer.ai.AiProvider;
import io.github.greytaiwolf.botplayer.ai.review.AiReviewOnlyContract;
import io.github.greytaiwolf.botplayer.ai.review.AiReviewOnlySnapshotProjection;
import io.github.greytaiwolf.botplayer.ai.transport.AiClientRequestDispatch;
import io.github.greytaiwolf.botplayer.ai.transport.AiRequestPurpose;
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

class ReviewOnlyDeepSeekClientAiProviderFactoryTest {
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
    void acceptsOnlyTheFixedReviewContractAndBuildsAClientBoundProvider()
            throws Exception {
        ReviewOnlyDeepSeekClientAiProviderFactory factory = factory();
        AiClientRequestDispatch canonical = canonicalDispatch(AiRequestPurpose.REVIEW_ONLY_V1);
        Assertions.assertTrue(factory.accepts(canonical));
        Assertions.assertFalse(factory.accepts(
                canonicalDispatch(AiRequestPurpose.UNSPECIFIED_V1)));

        try (ClientCredentialStore store = new ClientCredentialStore(temporaryDirectory)) {
            store.bind(
                    SERVER_ID,
                    OWNER_ID,
                    BOT_ID,
                    ClientCredentialStore.DEFAULT_PROFILE_ID,
                    "fake-local-key",
                    Optional.of(AGENT_ID));
            AiProvider provider = factory.create(canonical, store);
            Assertions.assertInstanceOf(DeepSeekProvider.class, provider);
            Assertions.assertThrows(IllegalArgumentException.class,
                    () -> factory.create(
                            canonicalDispatch(AiRequestPurpose.UNSPECIFIED_V1), store));
        }
    }

    @Test
    void refusesModelsWithoutTheSharedReviewToolCapability() {
        AiModelCapabilities chatOnly = new AiModelCapabilities(
                AiReviewOnlyContract.MODEL,
                8_192L,
                1_024,
                Set.of(AiCapability.CHAT));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new ReviewOnlyDeepSeekClientAiProviderFactory(
                        chatOnly,
                        false,
                        ignored -> CompletableFuture.failedFuture(
                                new AssertionError("no HTTP expected")),
                        Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)));
    }

    private static ReviewOnlyDeepSeekClientAiProviderFactory factory() {
        return new ReviewOnlyDeepSeekClientAiProviderFactory(
                new AiModelCapabilities(
                        AiReviewOnlyContract.MODEL,
                        8_192L,
                        1_024,
                        Set.of(AiCapability.CHAT, AiCapability.TOOL_CALLS)),
                false,
                ignored -> CompletableFuture.failedFuture(
                        new AssertionError("factory construction must not issue HTTP")),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    }

    private static AiClientRequestDispatch canonicalDispatch(AiRequestPurpose purpose) {
        AiReviewOnlySnapshotProjection projection = new AiReviewOnlySnapshotProjection(
                BOT_ID,
                2L,
                7L,
                100L,
                "minecraft:overworld",
                19.5F,
                18,
                294,
                0);
        var template = AiReviewOnlyContract.requestTemplate(projection);
        return new AiClientRequestDispatch(
                SERVER_ID,
                BOT_ID,
                OWNER_ID,
                AGENT_ID,
                2L,
                UUID.fromString("00000000-0000-0000-0000-000000000301"),
                UUID.fromString("00000000-0000-0000-0000-000000000401"),
                projection.snapshotId(),
                purpose,
                100L,
                140L,
                1_000L,
                3_000L,
                AiReviewOnlyContract.PROVIDER_ID,
                AiReviewOnlyContract.MODEL,
                template.messages(),
                template.options(),
                Optional.empty());
    }
}
