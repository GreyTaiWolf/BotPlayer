package io.github.greytaiwolf.botplayer.action.interaction.menu;

/**
 * 单个玩家背包 menu 事务的硬边界。
 */
public record InventoryMenuTransactionLimits(
        int maxClicks, int maxUniqueInventorySlots) {
    public static final int HARD_MAX_CLICKS = 16;
    public static final int HARD_MAX_UNIQUE_INVENTORY_SLOTS = 8;

    public InventoryMenuTransactionLimits {
        if (maxClicks < 1 || maxClicks > HARD_MAX_CLICKS) {
            throw new IllegalArgumentException(
                    "maxClicks must be in 1..16");
        }
        if (maxUniqueInventorySlots < 2
                || maxUniqueInventorySlots
                        > HARD_MAX_UNIQUE_INVENTORY_SLOTS) {
            throw new IllegalArgumentException(
                    "maxUniqueInventorySlots must be in 2..8");
        }
    }

    public static InventoryMenuTransactionLimits defaults() {
        return new InventoryMenuTransactionLimits(3, 3);
    }

    /**
     * 通用玩家背包 SWAP 事务的冻结绝对上限。
     */
    public static InventoryMenuTransactionLimits hardMaximum() {
        return new InventoryMenuTransactionLimits(
                HARD_MAX_CLICKS,
                HARD_MAX_UNIQUE_INVENTORY_SLOTS);
    }
}
