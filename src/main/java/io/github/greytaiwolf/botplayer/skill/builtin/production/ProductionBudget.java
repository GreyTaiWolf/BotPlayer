package io.github.greytaiwolf.botplayer.skill.builtin.production;

/**
 * 一个计划在进入游戏运行时前必须满足的固定资源上限。
 */
public record ProductionBudget(
        int maxNodes,
        int maxEdges,
        int maxMenuTransactions,
        int maxMenuClicks,
        int maxFurnaceInputItems) {
    public static final int ABSOLUTE_MAX_NODES = 64;
    public static final int ABSOLUTE_MAX_EDGES = 192;
    public static final int ABSOLUTE_MAX_MENU_TRANSACTIONS = 128;
    public static final int ABSOLUTE_MAX_MENU_CLICKS = 2_048;
    public static final int ABSOLUTE_MAX_FURNACE_INPUT_ITEMS = 512;

    public ProductionBudget {
        requireBounded(maxNodes, 1, ABSOLUTE_MAX_NODES, "maxNodes");
        requireBounded(maxEdges, 0, ABSOLUTE_MAX_EDGES, "maxEdges");
        requireBounded(maxMenuTransactions, 0,
                ABSOLUTE_MAX_MENU_TRANSACTIONS,
                "maxMenuTransactions");
        requireBounded(maxMenuClicks, 0, ABSOLUTE_MAX_MENU_CLICKS,
                "maxMenuClicks");
        requireBounded(maxFurnaceInputItems, 0,
                ABSOLUTE_MAX_FURNACE_INPUT_ITEMS,
                "maxFurnaceInputItems");
    }

    public boolean allows(ProductionBudgetUsage usage) {
        return usage != null
                && usage.nodes() <= maxNodes
                && usage.edges() <= maxEdges
                && usage.menuTransactions() <= maxMenuTransactions
                && usage.menuClicks() <= maxMenuClicks
                && usage.furnaceInputItems() <= maxFurnaceInputItems;
    }

    private static void requireBounded(
            int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    name + " must be within " + minimum + ".." + maximum);
        }
    }
}
