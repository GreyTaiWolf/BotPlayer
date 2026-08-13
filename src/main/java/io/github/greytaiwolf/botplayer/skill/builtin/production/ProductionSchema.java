package io.github.greytaiwolf.botplayer.skill.builtin.production;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 管理员/pack 层可引用的生产计划白名单和上限。
 */
public record ProductionSchema(
        String schemaId,
        int version,
        Set<ProductionRecipeId> allowedRecipes,
        Set<ProductionMaterial> allowedAcquisitionMaterials,
        ProductionLedger requiredFinalOutput,
        ProductionBudget maximumBudget) {
    public static final int MAX_SCHEMA_ID_LENGTH = 80;
    private static final Pattern SCHEMA_ID =
            Pattern.compile("[a-z][a-z0-9_.-]{0,79}");

    public ProductionSchema {
        Objects.requireNonNull(schemaId, "schemaId");
        if (schemaId.length() > MAX_SCHEMA_ID_LENGTH
                || !SCHEMA_ID.matcher(schemaId).matches()) {
            throw new IllegalArgumentException(
                    "production schema id must be a bounded lower-case identifier");
        }
        if (version < 1) {
            throw new IllegalArgumentException(
                    "production schema version must be positive");
        }
        allowedRecipes = immutableNonEmptySet(
                allowedRecipes, "allowedRecipes");
        allowedAcquisitionMaterials = immutableNonEmptySet(
                allowedAcquisitionMaterials,
                "allowedAcquisitionMaterials");
        Objects.requireNonNull(requiredFinalOutput, "requiredFinalOutput");
        if (requiredFinalOutput.isEmpty()) {
            throw new IllegalArgumentException(
                    "production schema must require a final output");
        }
        Objects.requireNonNull(maximumBudget, "maximumBudget");
    }

    public boolean allowsRecipe(ProductionRecipeId recipeId) {
        return allowedRecipes.contains(Objects.requireNonNull(
                recipeId, "recipeId"));
    }

    public boolean allowsAcquisition(ProductionLedger gain) {
        Objects.requireNonNull(gain, "gain");
        return allowedAcquisitionMaterials.containsAll(
                gain.quantities().keySet());
    }

    private static <T> Set<T> immutableNonEmptySet(
            Set<T> values, String name) {
        Objects.requireNonNull(values, name);
        Set<T> copied = new LinkedHashSet<>();
        for (T value : values) {
            copied.add(Objects.requireNonNull(value, name + " entry"));
        }
        if (copied.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return Set.copyOf(copied);
    }
}
