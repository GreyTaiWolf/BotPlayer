package io.github.greytaiwolf.botplayer.skill.builtin.production;

import java.util.Objects;
import java.util.Optional;

/**
 * 一个白名单配方的完整、静态描述。
 */
public record ProductionRecipe(
        ProductionRecipeId id,
        ProductionRecipeKind kind,
        ProductionDelta delta,
        int maximumClicksPerBatch,
        Optional<FurnaceBatchRequirement> furnaceBatch) {
    public ProductionRecipe {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(delta, "delta");
        if (maximumClicksPerBatch < 1
                || maximumClicksPerBatch
                > ProductionMenuContract.ABSOLUTE_MAX_CLICKS) {
            throw new IllegalArgumentException(
                    "maximum clicks per batch is outside the bounded range");
        }
        furnaceBatch = Objects.requireNonNull(furnaceBatch, "furnaceBatch");
        if (kind == ProductionRecipeKind.FURNACE_SMELTING) {
            FurnaceBatchRequirement batch = furnaceBatch.orElseThrow(() ->
                    new IllegalArgumentException(
                            "furnace recipe requires a furnace batch"));
            if (!batch.playerDelta().equals(delta)) {
                throw new IllegalArgumentException(
                        "furnace batch and recipe delta must agree exactly");
            }
        } else if (furnaceBatch.isPresent()) {
            throw new IllegalArgumentException(
                    "non-furnace recipe must not declare furnace requirements");
        }
    }

    public ProductionMenuContract menuContract(int batches) {
        if (batches < 1) {
            throw new IllegalArgumentException(
                    "recipe batches must be positive");
        }
        int clicks;
        try {
            clicks = Math.multiplyExact(maximumClicksPerBatch, batches);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "recipe click budget overflow", exception);
        }
        return new ProductionMenuContract(
                kind.menuFamily(), clicks, true, delta.multipliedBy(batches));
    }

    public Optional<FurnaceBatchRequirement> furnaceRequirement(int batches) {
        if (batches < 1) {
            throw new IllegalArgumentException(
                    "recipe batches must be positive");
        }
        return furnaceBatch.map(batch -> batch.multipliedBy(batches));
    }
}
