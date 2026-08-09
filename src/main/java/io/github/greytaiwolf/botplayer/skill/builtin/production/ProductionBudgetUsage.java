package io.github.greytaiwolf.botplayer.skill.builtin.production;

/**
 * 已解析计划的保守运行成本，不是实际游戏执行计数。
 */
public record ProductionBudgetUsage(
        int nodes,
        int edges,
        int menuTransactions,
        int menuClicks,
        int furnaceInputItems) {
    public ProductionBudgetUsage {
        requireNonNegative(nodes, "nodes");
        requireNonNegative(edges, "edges");
        requireNonNegative(menuTransactions, "menuTransactions");
        requireNonNegative(menuClicks, "menuClicks");
        requireNonNegative(furnaceInputItems, "furnaceInputItems");
    }

    public ProductionBudgetUsage plus(ProductionBudgetUsage other) {
        if (other == null) {
            throw new IllegalArgumentException("other usage is required");
        }
        try {
            return new ProductionBudgetUsage(
                    Math.addExact(nodes, other.nodes),
                    Math.addExact(edges, other.edges),
                    Math.addExact(menuTransactions, other.menuTransactions),
                    Math.addExact(menuClicks, other.menuClicks),
                    Math.addExact(furnaceInputItems,
                            other.furnaceInputItems));
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "production budget usage overflow", exception);
        }
    }

    private static void requireNonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
