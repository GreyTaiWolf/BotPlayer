package io.github.greytaiwolf.botplayer.skill.builtin.production;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 封闭的配方白名单。请求中的名称只有在此目录和 schema 双重认可时才可执行。
 */
public final class ProductionRecipeCatalog {
    private final Map<ProductionRecipeId, ProductionRecipe> recipes;

    public ProductionRecipeCatalog(Map<ProductionRecipeId, ProductionRecipe> recipes) {
        Objects.requireNonNull(recipes, "recipes");
        Map<ProductionRecipeId, ProductionRecipe> copied =
                new LinkedHashMap<>();
        for (Map.Entry<ProductionRecipeId, ProductionRecipe> entry
                : recipes.entrySet()) {
            ProductionRecipeId id = Objects.requireNonNull(
                    entry.getKey(), "recipe id");
            ProductionRecipe recipe = Objects.requireNonNull(
                    entry.getValue(), "recipe");
            if (!id.equals(recipe.id()) || copied.put(id, recipe) != null) {
                throw new IllegalArgumentException(
                        "recipe catalog must have one matching recipe per id");
            }
        }
        if (copied.isEmpty()) {
            throw new IllegalArgumentException(
                    "production recipe catalog must not be empty");
        }
        this.recipes = Map.copyOf(copied);
    }

    public static ProductionRecipeCatalog p5aDefault() {
        return new ProductionRecipeCatalog(ProductionRecipes.all());
    }

    public Optional<ProductionRecipe> find(ProductionRecipeId id) {
        return Optional.ofNullable(recipes.get(
                Objects.requireNonNull(id, "id")));
    }

    public Map<ProductionRecipeId, ProductionRecipe> recipes() {
        return recipes;
    }
}
