package io.github.greytaiwolf.botplayer.action.interaction.menu;

import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 原生玩家 {@code InventoryMenu} 的不可变完整事务快照。
 *
 * <p>槽位列表始终按玩家库存索引 0..40 排列。stateId 是观察时的同步栅栏；
 * {@link #layoutEqualsIgnoringState(InventoryMenuSnapshot)} 只忽略该栅栏，仍会比较
 * containerId、选中热栏、cursor 和全部 41 个槽。
 */
public record InventoryMenuSnapshot(
        int containerId,
        int stateId,
        int selectedHotbar,
        ItemStackFingerprint cursor,
        List<ItemStackFingerprint> inventorySlots) {

    public InventoryMenuSnapshot {
        if (containerId < 0) {
            throw new IllegalArgumentException(
                    "containerId must not be negative");
        }
        if (stateId < 0) {
            throw new IllegalArgumentException(
                    "stateId must not be negative");
        }
        if (!PlayerInventoryMenuLayout.isHotbarInventorySlot(
                selectedHotbar)) {
            throw new IllegalArgumentException(
                    "selectedHotbar must be in 0..8");
        }
        Objects.requireNonNull(cursor, "cursor");
        Objects.requireNonNull(inventorySlots, "inventorySlots");
        if (inventorySlots.size()
                != PlayerInventoryMenuLayout.INVENTORY_SLOT_COUNT) {
            throw new IllegalArgumentException(
                    "inventory snapshot must contain exactly 41 slots");
        }
        List<ItemStackFingerprint> copied =
                new ArrayList<>(inventorySlots.size());
        for (ItemStackFingerprint fingerprint : inventorySlots) {
            copied.add(Objects.requireNonNull(
                    fingerprint, "inventory slot fingerprint"));
        }
        inventorySlots = List.copyOf(copied);
    }

    public ItemStackFingerprint itemAt(int inventorySlot) {
        PlayerInventoryMenuLayout.requireInventorySlot(
                inventorySlot);
        return inventorySlots.get(inventorySlot);
    }

    public boolean layoutEqualsIgnoringState(
            InventoryMenuSnapshot other) {
        return other != null
                && containerId == other.containerId
                && selectedHotbar == other.selectedHotbar
                && cursor.equals(other.cursor)
                && inventorySlots.equals(other.inventorySlots);
    }

    /**
     * 严格比较 41 槽中的结构化堆叠多重集，不把摘要字符串当作守恒证明。
     *
     * <p>该比较有意忽略槽位、stateId、containerId、选中槽和 cursor；调用方必须
     * 独立核对这些控制面条件。
     */
    public boolean inventoryMultisetEquals(
            InventoryMenuSnapshot other) {
        return other != null
                && inventoryMultiset(inventorySlots)
                        .equals(inventoryMultiset(
                                other.inventorySlots));
    }

    private static Map<ItemStackFingerprint, Integer>
            inventoryMultiset(
                    List<ItemStackFingerprint> slots) {
        Map<ItemStackFingerprint, Integer> counts =
                new HashMap<>();
        for (ItemStackFingerprint fingerprint : slots) {
            counts.merge(fingerprint, 1, Integer::sum);
        }
        return counts;
    }

    InventoryMenuSnapshot swapKeepingState(
            int firstInventorySlot, int secondInventorySlot) {
        PlayerInventoryMenuLayout.requireInventorySlot(
                firstInventorySlot);
        PlayerInventoryMenuLayout.requireInventorySlot(
                secondInventorySlot);
        if (firstInventorySlot == secondInventorySlot) {
            throw new IllegalArgumentException(
                    "snapshot swap requires two different slots");
        }
        List<ItemStackFingerprint> swapped =
                new ArrayList<>(inventorySlots);
        ItemStackFingerprint first = swapped.get(firstInventorySlot);
        swapped.set(
                firstInventorySlot,
                swapped.get(secondInventorySlot));
        swapped.set(secondInventorySlot, first);
        return new InventoryMenuSnapshot(
                containerId,
                stateId,
                selectedHotbar,
                cursor,
                swapped);
    }
}
