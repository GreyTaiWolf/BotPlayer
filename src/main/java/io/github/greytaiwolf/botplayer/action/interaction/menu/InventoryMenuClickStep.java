package io.github.greytaiwolf.botplayer.action.interaction.menu;

import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * 一次原版 {@code ClickType.SWAP} 的完整前后状态。
 *
 * <p>{@code hotbarButton} 同时是点击按钮和玩家库存热栏索引。除了 menu 指向的
 * 库存槽与该热栏槽互换外，任何 cursor、选中槽或其他库存槽变化都会使步骤无效。
 */
public record InventoryMenuClickStep(
        int menuSlot,
        int hotbarButton,
        InventoryMenuSnapshot before,
        InventoryMenuSnapshot after) {

    public InventoryMenuClickStep {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        if (!PlayerInventoryMenuLayout.isHotbarInventorySlot(
                hotbarButton)) {
            throw new IllegalArgumentException(
                    "hotbarButton must be in 0..8");
        }
        OptionalInt clickedInventorySlot =
                PlayerInventoryMenuLayout.inventorySlotForMenuSlot(
                        menuSlot);
        if (clickedInventorySlot.isEmpty()) {
            throw new IllegalArgumentException(
                    "craft menu slots are read-only");
        }
        int clicked = clickedInventorySlot.orElseThrow();
        if (clicked == hotbarButton) {
            throw new IllegalArgumentException(
                    "SWAP sides must be different inventory slots");
        }
        if (before.containerId() != after.containerId()) {
            throw new IllegalArgumentException(
                    "containerId cannot change inside one click");
        }
        if (before.selectedHotbar() != after.selectedHotbar()) {
            throw new IllegalArgumentException(
                    "SWAP cannot change the selected hotbar slot");
        }
        if (!before.cursor().isEmpty()
                || !after.cursor().isEmpty()) {
            throw new IllegalArgumentException(
                    "SWAP plan requires an empty cursor");
        }

        ItemStackFingerprint clickedBefore =
                before.itemAt(clicked);
        ItemStackFingerprint hotbarBefore =
                before.itemAt(hotbarButton);
        if (clickedBefore.equals(hotbarBefore)) {
            throw new IllegalArgumentException(
                    "same-fingerprint SWAP is not observable");
        }
        for (int inventorySlot = 0;
                inventorySlot
                        < PlayerInventoryMenuLayout
                                .INVENTORY_SLOT_COUNT;
                inventorySlot++) {
            ItemStackFingerprint expected =
                    inventorySlot == clicked
                            ? hotbarBefore
                            : inventorySlot == hotbarButton
                                    ? clickedBefore
                                    : before.itemAt(inventorySlot);
            if (!expected.equals(after.itemAt(inventorySlot))) {
                throw new IllegalArgumentException(
                        "after snapshot is not the exact SWAP result");
            }
        }
    }

    public int clickedInventorySlot() {
        return PlayerInventoryMenuLayout
                .inventorySlotForMenuSlot(menuSlot)
                .orElseThrow();
    }
}
