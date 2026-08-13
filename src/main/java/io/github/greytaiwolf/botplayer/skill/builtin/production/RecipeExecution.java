package io.github.greytaiwolf.botplayer.skill.builtin.production;

import java.util.Objects;

/**
 * 请求执行某个白名单配方若干完整批次。
 *
 * <p>构造阶段允许一个语法正确但未知的 id，以便外部输入能得到结构化拒绝；真正解析必须
 * 经过 {@link ProductionPlanValidator} 和 {@link ProductionRecipeCatalog}。
 */
public record RecipeExecution(ProductionRecipeId recipeId, int batches)
        implements ProductionOperation {
    public static final int ABSOLUTE_MAX_BATCHES = 256;

    public RecipeExecution {
        Objects.requireNonNull(recipeId, "recipeId");
        if (batches < 1 || batches > ABSOLUTE_MAX_BATCHES) {
            throw new IllegalArgumentException(
                    "recipe batches must be within 1.."
                            + ABSOLUTE_MAX_BATCHES);
        }
    }

    @Override
    public ProductionOperationKind kind() {
        return ProductionOperationKind.EXECUTE_RECIPE;
    }
}
