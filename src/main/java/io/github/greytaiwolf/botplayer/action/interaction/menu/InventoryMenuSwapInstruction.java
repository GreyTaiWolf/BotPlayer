package io.github.greytaiwolf.botplayer.action.interaction.menu;

/**
 * 一次通用 {@code ClickType.SWAP} 的纯数据指令。
 *
 * <p>{@code clickedInventorySlot} 使用玩家库存索引 {@code 0..40}；
 * {@code hotbarButton} 同时是点击按钮与热栏库存索引 {@code 0..8}。
 */
public record InventoryMenuSwapInstruction(
        int clickedInventorySlot, int hotbarButton) {

    public InventoryMenuSwapInstruction {
        PlayerInventoryMenuLayout.requireInventorySlot(
                clickedInventorySlot);
        if (!PlayerInventoryMenuLayout.isHotbarInventorySlot(
                hotbarButton)) {
            throw new IllegalArgumentException(
                    "hotbarButton must be in 0..8");
        }
        if (clickedInventorySlot == hotbarButton) {
            throw new IllegalArgumentException(
                    "SWAP sides must be different inventory slots");
        }
    }
}
