package io.github.greytaiwolf.botplayer.ai;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AiCapabilitiesContractTest {
    private static final Instant OBSERVED_AT =
            Instant.parse("2026-08-07T00:00:00Z");

    @Test
    void normalizesModelsAndCapabilitiesDeterministically() {
        AiModelCapabilities reasoner = model(
                "deepseek-reasoner",
                Set.of(AiCapability.CHAT, AiCapability.REASONING));
        AiModelCapabilities chat = model(
                "deepseek-chat",
                Set.of(
                        AiCapability.CHAT,
                        AiCapability.TOOL_CALLS,
                        AiCapability.JSON_OBJECT));
        AiCapabilities capabilities = new AiCapabilities(
                "deepseek",
                OBSERVED_AT,
                List.of(reasoner, chat));

        Assertions.assertEquals(
                "deepseek-chat", capabilities.models().getFirst().model());
        Assertions.assertTrue(capabilities
                .findModel("deepseek-reasoner")
                .orElseThrow()
                .supports(AiCapability.REASONING));
        Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> capabilities.models().clear());
        Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> chat.capabilities().clear());
    }

    @Test
    void rejectsDuplicateModelsAndInvalidCapabilityRanges() {
        AiModelCapabilities model = model(
                "deepseek-chat", Set.of(AiCapability.CHAT));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new AiCapabilities(
                        "deepseek",
                        OBSERVED_AT,
                        List.of(model, model)));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new AiModelCapabilities(
                        "deepseek-chat",
                        1_000L,
                        2_000,
                        Set.of(AiCapability.CHAT)));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new AiModelCapabilities(
                        "deepseek-chat",
                        8_000L,
                        1_000,
                        Set.of(AiCapability.JSON_OBJECT)));
    }

    @Test
    void validatesProviderHealthWithoutRawDetails() {
        ProviderHealth healthy = ProviderHealth.healthy(
                "deepseek", OBSERVED_AT);
        Assertions.assertTrue(healthy.acceptingRequests());
        ProviderHealth limited = new ProviderHealth(
                "deepseek",
                ProviderHealthState.RATE_LIMITED,
                OBSERVED_AT,
                Optional.of(OBSERVED_AT.plusSeconds(30L)),
                Optional.of(AiReasonCode.RATE_LIMITED.wireCode()));
        Assertions.assertFalse(limited.acceptingRequests());

        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderHealth(
                        "DeepSeek",
                        ProviderHealthState.HEALTHY,
                        OBSERVED_AT,
                        Optional.empty(),
                        Optional.empty()));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderHealth(
                        "deepseek",
                        ProviderHealthState.RATE_LIMITED,
                        OBSERVED_AT,
                        Optional.of(OBSERVED_AT.minusSeconds(1L)),
                        Optional.of(AiReasonCode.RATE_LIMITED.wireCode())));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderHealth(
                        "deepseek",
                        ProviderHealthState.UNAVAILABLE,
                        OBSERVED_AT,
                        Optional.empty(),
                        Optional.of("sk-live-unsafe-detail")));

        ProviderHealth clientFailure = new ProviderHealth(
                "deepseek",
                ProviderHealthState.UNAVAILABLE,
                OBSERVED_AT,
                Optional.empty(),
                Optional.of(AiReasonCode.NETWORK_FAILURE.wireCode()));
        Assertions.assertEquals(AiReasonCode.NETWORK_FAILURE.wireCode(),
                clientFailure.reasonCode().orElseThrow());
    }

    private static AiModelCapabilities model(
            String model, Set<AiCapability> capabilities) {
        return new AiModelCapabilities(
                model, 128_000L, 8_192, capabilities);
    }
}
