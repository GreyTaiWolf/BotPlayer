package io.github.greytaiwolf.botplayer.ai;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AiModelPolicyTest {
    @Test
    void admitsOnlyAConfiguredAvailableModelInsideAllReservations() {
        AiModelPolicy policy = new AiModelPolicy(
                "deepseek", Set.of("deepseek-chat"), 100, 40, 2_000L,
                Set.of(AiResponseFormat.TEXT, AiResponseFormat.JSON_OBJECT),
                true, true, Optional.of(1.0D));
        AiRequest request = request("deepseek-chat", new AiRequestOptions(
                32, 1_000L, AiResponseFormat.JSON_OBJECT, true, true,
                Optional.of(0.7D)));

        AiModelAdmission admission = policy.admit(
                request, 60L, capabilities("deepseek", "deepseek-chat",
                        128L, 64, Set.of(AiCapability.CHAT,
                                AiCapability.JSON_OBJECT,
                                AiCapability.REASONING,
                                AiCapability.TOOL_CALLS)));

        Assertions.assertEquals(AiModelAdmissionStatus.ACCEPTED,
                admission.status());
        Assertions.assertEquals(92L, admission.reservedTotalTokens());
        Assertions.assertTrue(admission.accepted());
    }

    @Test
    void rejectsPolicyCapabilityAndBudgetMismatchesWithoutPromptLeakage() {
        AiModelPolicy policy = new AiModelPolicy(
                "deepseek", Set.of("deepseek-chat"), 100, 40, 2_000L,
                Set.of(AiResponseFormat.TEXT), false, false, Optional.empty());
        AiCapabilities capable = capabilities("deepseek", "deepseek-chat",
                128L, 64, Set.of(AiCapability.CHAT));

        Assertions.assertEquals(AiModelAdmissionStatus.MODEL_NOT_ALLOWLISTED,
                policy.admit(request("other", AiRequestOptions.defaults()), 1L,
                        capable).status());
        Assertions.assertEquals(AiModelAdmissionStatus.INPUT_BUDGET_EXCEEDED,
                policy.admit(request("deepseek-chat", AiRequestOptions.defaults()),
                        101L, capable).status());
        Assertions.assertEquals(AiModelAdmissionStatus.OUTPUT_BUDGET_EXCEEDED,
                policy.admit(request("deepseek-chat", new AiRequestOptions(
                        41, 1_000L, AiResponseFormat.TEXT, false, false,
                        Optional.empty())), 1L, capable).status());
        Assertions.assertEquals(AiModelAdmissionStatus.RESPONSE_FORMAT_NOT_ALLOWED,
                policy.admit(request("deepseek-chat", new AiRequestOptions(
                        1, 1_000L, AiResponseFormat.JSON_OBJECT, false, false,
                        Optional.empty())), 1L, capable).status());
        Assertions.assertEquals(AiModelAdmissionStatus.TOOL_CALLS_NOT_ALLOWED,
                policy.admit(request("deepseek-chat", new AiRequestOptions(
                        1, 1_000L, AiResponseFormat.TEXT, false, true,
                        Optional.empty())), 1L, capable).status());
        Assertions.assertFalse(policy.toString().contains("deepseek-chat"));
    }

    @Test
    void failsClosedWhenTheProviderCannotSupportTheRequestedFormatOrContext() {
        AiModelPolicy policy = new AiModelPolicy(
                "deepseek", Set.of("deepseek-chat"), 100, 50, 2_000L,
                Set.of(AiResponseFormat.JSON_SCHEMA), false, false,
                Optional.of(0.5D));
        AiRequest schemaRequest = request("deepseek-chat", new AiRequestOptions(
                50, 1_000L, AiResponseFormat.JSON_SCHEMA, false, false,
                Optional.of(0.6D)));
        AiCapabilities noSchema = capabilities("deepseek", "deepseek-chat",
                100L, 50, Set.of(AiCapability.CHAT));
        AiCapabilities schema = capabilities("deepseek", "deepseek-chat",
                100L, 50, Set.of(AiCapability.CHAT, AiCapability.JSON_SCHEMA));

        Assertions.assertEquals(AiModelAdmissionStatus.RESPONSE_FORMAT_UNSUPPORTED,
                policy.admit(schemaRequest, 1L, noSchema).status());
        Assertions.assertEquals(AiModelAdmissionStatus.CONTEXT_WINDOW_EXCEEDED,
                policy.admit(schemaRequest, 51L, schema).status());
        Assertions.assertEquals(AiModelAdmissionStatus.TEMPERATURE_EXCEEDED,
                policy.admit(schemaRequest, 1L, schema).status());
    }

    private static AiRequest request(String model, AiRequestOptions options) {
        return new AiRequest(UUID.randomUUID(), model,
                List.of(new AiMessage(AiMessageRole.USER,
                        "Private prompt body must not appear in admissions.")),
                options,
                options.responseFormat() == AiResponseFormat.JSON_SCHEMA
                        ? Optional.of("{\\\"type\\\":\\\"object\\\"}")
                        : Optional.empty());
    }

    private static AiCapabilities capabilities(
            String providerId,
            String model,
            long contextWindow,
            int maximumOutput,
            Set<AiCapability> capabilities) {
        return new AiCapabilities(providerId, Instant.EPOCH,
                List.of(new AiModelCapabilities(model, contextWindow,
                        maximumOutput, capabilities)));
    }
}
