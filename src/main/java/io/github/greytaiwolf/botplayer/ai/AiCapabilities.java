package io.github.greytaiwolf.botplayer.ai;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Provider 能力探测的确定性快照。
 */
public record AiCapabilities(
        String providerId,
        Instant observedAt,
        List<AiModelCapabilities> models) {
    public static final int MAX_MODELS = 128;

    public AiCapabilities {
        providerId = AiChecks.providerId(
                providerId, "providerId");
        observedAt = AiChecks.instant(observedAt, "observedAt");
        Objects.requireNonNull(models, "models");
        if (models.size() > MAX_MODELS) {
            throw new IllegalArgumentException(
                    "models exceeds maximum size " + MAX_MODELS);
        }
        List<AiModelCapabilities> copied =
                new ArrayList<>(models.size());
        Set<String> modelIds = new HashSet<>();
        for (AiModelCapabilities model : models) {
            AiModelCapabilities copiedModel = Objects.requireNonNull(
                    model, "model capabilities");
            if (!modelIds.add(copiedModel.model())) {
                throw new IllegalArgumentException(
                        "duplicate model capability " + copiedModel.model());
            }
            copied.add(copiedModel);
        }
        copied.sort(Comparator.comparing(
                AiModelCapabilities::model));
        models = List.copyOf(copied);
    }

    public Optional<AiModelCapabilities> findModel(String model) {
        String normalized = AiChecks.modelId(model, "model");
        return models.stream()
                .filter(candidate -> candidate.model().equals(normalized))
                .findFirst();
    }
}
