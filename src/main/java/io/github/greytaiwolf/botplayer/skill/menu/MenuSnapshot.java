package io.github.greytaiwolf.botplayer.skill.menu;

import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 某一时刻原版菜单的完整、不可变服务端快照。
 *
 * <p>槽位内容、cursor、containerId 与 stateId 都是事务证据。任何适配器都不得只上报
 * 计划会触碰的局部槽位。
 */
public record MenuSnapshot(
        MenuFamily family,
        int containerId,
        int stateId,
        ItemStackFingerprint carried,
        List<ItemStackFingerprint> slots) {

    public MenuSnapshot {
        Objects.requireNonNull(family, "family");
        if (containerId < 0) {
            throw new IllegalArgumentException(
                    "containerId must not be negative");
        }
        if (stateId < 0) {
            throw new IllegalArgumentException(
                    "stateId must not be negative");
        }
        Objects.requireNonNull(carried, "carried");
        Objects.requireNonNull(slots, "slots");
        if (slots.size() != family.slotCount()) {
            throw new IllegalArgumentException(
                    "snapshot slot count does not match the exact menu family");
        }
        List<ItemStackFingerprint> copied = new ArrayList<>(slots.size());
        for (ItemStackFingerprint slot : slots) {
            copied.add(Objects.requireNonNull(
                    slot, "slot fingerprint"));
        }
        slots = List.copyOf(copied);
    }

    public ItemStackFingerprint itemAt(int slot) {
        family.requireSlot(slot);
        return slots.get(slot);
    }

    /**
     * 比较固定会话和全部物品布局，但允许 stateId 由原版重新编号。
     */
    public boolean layoutEqualsIgnoringState(MenuSnapshot other) {
        return other != null
                && family == other.family
                && containerId == other.containerId
                && carried.equals(other.carried)
                && slots.equals(other.slots);
    }

    public boolean sameMenu(MenuSnapshot other) {
        return other != null
                && family == other.family
                && containerId == other.containerId;
    }
}
