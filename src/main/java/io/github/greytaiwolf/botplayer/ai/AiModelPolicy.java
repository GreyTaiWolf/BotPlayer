package io.github.greytaiwolf.botplayer.ai;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Server/client configuration gate for model selection and per-request resource reservation.
 *
 * <p>This policy never selects a credential and never mutates a provider. It only admits an already
 * assembled request when its model is explicitly allowlisted, available in the latest capability
 * snapshot and inside bounded input/output/timeout/feature limits. The input count must be the
 * conservative {@link ContextBudget} estimate produced by the context assembler, not an untrusted
 * provider-reported usage value.
 */
public record AiModelPolicy(
        String providerId,
        Set<String> allowedModels,
        int maximumInputTokens,
        int maximumOutputTokens,
        long maximumTimeoutMillis,
        Set<AiResponseFormat> allowedResponseFormats,
        boolean reasoningAllowed,
        boolean toolCallsAllowed,
        Optional<Double> maximumTemperature) {
    public AiModelPolicy {
        providerId = AiChecks.providerId(providerId, "providerId");
        Objects.requireNonNull(allowedModels, "allowedModels");
        if (allowedModels.isEmpty()
                || allowedModels.size() > AiCapabilities.MAX_MODELS) {
            throw new IllegalArgumentException(
                    "allowedModels must contain between 1 and "
                            + AiCapabilities.MAX_MODELS + " entries");
        }
        Set<String> copiedModels = new LinkedHashSet<>();
        for (String model : allowedModels) {
            copiedModels.add(AiChecks.modelId(model, "allowed model"));
        }
        allowedModels = Collections.unmodifiableSet(copiedModels);
        if (maximumInputTokens < 1
                || maximumInputTokens > ContextBudget.MAX_INPUT_TOKENS) {
            throw new IllegalArgumentException(
                    "maximumInputTokens must be between 1 and "
                            + ContextBudget.MAX_INPUT_TOKENS);
        }
        if (maximumOutputTokens < 1
                || maximumOutputTokens > AiRequestOptions.MAX_OUTPUT_TOKENS) {
            throw new IllegalArgumentException(
                    "maximumOutputTokens must be between 1 and "
                            + AiRequestOptions.MAX_OUTPUT_TOKENS);
        }
        if (maximumTimeoutMillis < 1L
                || maximumTimeoutMillis > AiRequestOptions.MAX_TIMEOUT_MILLIS) {
            throw new IllegalArgumentException(
                    "maximumTimeoutMillis must be between 1 and "
                            + AiRequestOptions.MAX_TIMEOUT_MILLIS);
        }
        Objects.requireNonNull(allowedResponseFormats, "allowedResponseFormats");
        if (allowedResponseFormats.isEmpty()) {
            throw new IllegalArgumentException(
                    "allowedResponseFormats must not be empty");
        }
        EnumSet<AiResponseFormat> copiedFormats = EnumSet.noneOf(
                AiResponseFormat.class);
        for (AiResponseFormat format : allowedResponseFormats) {
            copiedFormats.add(Objects.requireNonNull(format, "response format"));
        }
        allowedResponseFormats = Collections.unmodifiableSet(copiedFormats);
        maximumTemperature = Objects.requireNonNull(
                maximumTemperature, "maximumTemperature");
        maximumTemperature.ifPresent(value -> {
            if (!Double.isFinite(value) || value < 0.0D
                    || value > AiRequestOptions.MAX_TEMPERATURE) {
                throw new IllegalArgumentException(
                        "maximumTemperature must be finite and between 0 and "
                                + AiRequestOptions.MAX_TEMPERATURE);
            }
        });
    }

    /** A conservative practical default; callers still supply the model/provider configuration. */
    public static AiModelPolicy strictDefaults(
            String providerId, String model) {
        return new AiModelPolicy(
                providerId,
                Set.of(model),
                ContextBudget.DEFAULT_MAXIMUM_INPUT_TOKENS,
                4_096,
                60_000L,
                Set.of(AiResponseFormat.TEXT, AiResponseFormat.JSON_OBJECT,
                        AiResponseFormat.JSON_SCHEMA),
                false,
                false,
                Optional.of(1.0D));
    }

    /**
     * Checks one request without performing provider I/O. Failure is deterministic and contains no
     * prompt, schema, tool arguments or credentials.
     */
    public AiModelAdmission admit(
            AiRequest request,
            long estimatedInputTokens,
            AiCapabilities capabilities) {
        AiRequest checkedRequest = Objects.requireNonNull(request, "request");
        AiCapabilities checkedCapabilities = Objects.requireNonNull(
                capabilities, "capabilities");
        if (estimatedInputTokens < 0L
                || estimatedInputTokens > ContextBudget.MAX_INPUT_TOKENS) {
            throw new IllegalArgumentException(
                    "estimatedInputTokens is outside the conservative hard bound");
        }
        long outputTokens = checkedRequest.options().maximumOutputTokens();
        long totalTokens;
        try {
            totalTokens = Math.addExact(estimatedInputTokens, outputTokens);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("request token reservation overflow", exception);
        }
        if (!providerId.equals(checkedCapabilities.providerId())) {
            return rejected(AiModelAdmissionStatus.PROVIDER_MISMATCH,
                    estimatedInputTokens, outputTokens, totalTokens);
        }
        if (!allowedModels.contains(checkedRequest.model())) {
            return rejected(AiModelAdmissionStatus.MODEL_NOT_ALLOWLISTED,
                    estimatedInputTokens, outputTokens, totalTokens);
        }
        AiModelCapabilities model = checkedCapabilities.findModel(
                checkedRequest.model()).orElse(null);
        if (model == null) {
            return rejected(AiModelAdmissionStatus.MODEL_NOT_AVAILABLE,
                    estimatedInputTokens, outputTokens, totalTokens);
        }
        if (estimatedInputTokens > maximumInputTokens) {
            return rejected(AiModelAdmissionStatus.INPUT_BUDGET_EXCEEDED,
                    estimatedInputTokens, outputTokens, totalTokens);
        }
        if (outputTokens > maximumOutputTokens
                || outputTokens > model.maximumOutputTokens()) {
            return rejected(AiModelAdmissionStatus.OUTPUT_BUDGET_EXCEEDED,
                    estimatedInputTokens, outputTokens, totalTokens);
        }
        if (totalTokens > model.contextWindowTokens()) {
            return rejected(AiModelAdmissionStatus.CONTEXT_WINDOW_EXCEEDED,
                    estimatedInputTokens, outputTokens, totalTokens);
        }
        if (checkedRequest.options().timeoutMillis() > maximumTimeoutMillis) {
            return rejected(AiModelAdmissionStatus.TIMEOUT_EXCEEDED,
                    estimatedInputTokens, outputTokens, totalTokens);
        }
        AiResponseFormat format = checkedRequest.options().responseFormat();
        if (!allowedResponseFormats.contains(format)) {
            return rejected(AiModelAdmissionStatus.RESPONSE_FORMAT_NOT_ALLOWED,
                    estimatedInputTokens, outputTokens, totalTokens);
        }
        AiCapability requiredFormatCapability = capabilityFor(format);
        if (requiredFormatCapability != null
                && !model.supports(requiredFormatCapability)) {
            return rejected(AiModelAdmissionStatus.RESPONSE_FORMAT_UNSUPPORTED,
                    estimatedInputTokens, outputTokens, totalTokens);
        }
        if (checkedRequest.options().reasoningAllowed()) {
            if (!reasoningAllowed) {
                return rejected(AiModelAdmissionStatus.REASONING_NOT_ALLOWED,
                        estimatedInputTokens, outputTokens, totalTokens);
            }
            if (!model.supports(AiCapability.REASONING)) {
                return rejected(AiModelAdmissionStatus.REASONING_UNSUPPORTED,
                        estimatedInputTokens, outputTokens, totalTokens);
            }
        }
        if (checkedRequest.options().toolCallsAllowed()) {
            if (!toolCallsAllowed) {
                return rejected(AiModelAdmissionStatus.TOOL_CALLS_NOT_ALLOWED,
                        estimatedInputTokens, outputTokens, totalTokens);
            }
            if (!model.supports(AiCapability.TOOL_CALLS)) {
                return rejected(AiModelAdmissionStatus.TOOL_CALLS_UNSUPPORTED,
                        estimatedInputTokens, outputTokens, totalTokens);
            }
        }
        Optional<Double> requestedTemperature = checkedRequest.options().temperature();
        if (requestedTemperature.isPresent() && maximumTemperature.isEmpty()) {
            return rejected(AiModelAdmissionStatus.TEMPERATURE_NOT_ALLOWED,
                    estimatedInputTokens, outputTokens, totalTokens);
        }
        if (requestedTemperature.isPresent()
                && requestedTemperature.orElseThrow()
                        > maximumTemperature.orElseThrow()) {
            return rejected(AiModelAdmissionStatus.TEMPERATURE_EXCEEDED,
                    estimatedInputTokens, outputTokens, totalTokens);
        }
        return new AiModelAdmission(AiModelAdmissionStatus.ACCEPTED,
                estimatedInputTokens, outputTokens, totalTokens);
    }

    private static AiModelAdmission rejected(
            AiModelAdmissionStatus status,
            long estimatedInputTokens,
            long outputTokens,
            long totalTokens) {
        return new AiModelAdmission(
                status, estimatedInputTokens, outputTokens, totalTokens);
    }

    private static AiCapability capabilityFor(AiResponseFormat format) {
        return switch (format) {
            case TEXT -> null;
            case JSON_OBJECT -> AiCapability.JSON_OBJECT;
            case JSON_SCHEMA -> AiCapability.JSON_SCHEMA;
        };
    }

    /** Do not reveal model allowlists or policy feature choices through accidental logging. */
    @Override
    public String toString() {
        return "AiModelPolicy[providerId=" + providerId
                + ", allowedModelCount=" + allowedModels.size()
                + ", maximumInputTokens=" + maximumInputTokens
                + ", maximumOutputTokens=" + maximumOutputTokens
                + ", maximumTimeoutMillis=" + maximumTimeoutMillis
                + ", allowedResponseFormatCount=" + allowedResponseFormats.size()
                + ", reasoningAllowed=" + reasoningAllowed
                + ", toolCallsAllowed=" + toolCallsAllowed
                + ", maximumTemperaturePresent=" + maximumTemperature.isPresent()
                + "]";
    }
}
