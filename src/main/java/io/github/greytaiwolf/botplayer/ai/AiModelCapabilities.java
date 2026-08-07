package io.github.greytaiwolf.botplayer.ai;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * 单个配置化模型的有界能力快照。
 */
public record AiModelCapabilities(
        String model,
        long contextWindowTokens,
        int maximumOutputTokens,
        Set<AiCapability> capabilities) {
    public static final long MAX_CONTEXT_WINDOW_TOKENS = 10_000_000L;
    public static final int MAX_OUTPUT_TOKENS = 1_000_000;

    public AiModelCapabilities {
        model = AiChecks.modelId(model, "model");
        if (contextWindowTokens < 1L
                || contextWindowTokens > MAX_CONTEXT_WINDOW_TOKENS) {
            throw new IllegalArgumentException(
                    "contextWindowTokens must be between 1 and "
                            + MAX_CONTEXT_WINDOW_TOKENS);
        }
        if (maximumOutputTokens < 1
                || maximumOutputTokens > MAX_OUTPUT_TOKENS) {
            throw new IllegalArgumentException(
                    "maximumOutputTokens must be between 1 and "
                            + MAX_OUTPUT_TOKENS);
        }
        if (maximumOutputTokens > contextWindowTokens) {
            throw new IllegalArgumentException(
                    "maximumOutputTokens must not exceed contextWindowTokens");
        }
        Objects.requireNonNull(capabilities, "capabilities");
        EnumSet<AiCapability> copied =
                EnumSet.noneOf(AiCapability.class);
        for (AiCapability capability : capabilities) {
            copied.add(Objects.requireNonNull(
                    capability, "capability"));
        }
        if (!copied.contains(AiCapability.CHAT)) {
            throw new IllegalArgumentException(
                    "completion models must declare CHAT capability");
        }
        capabilities = Collections.unmodifiableSet(copied);
    }

    public boolean supports(AiCapability capability) {
        return capabilities.contains(
                Objects.requireNonNull(capability, "capability"));
    }
}
