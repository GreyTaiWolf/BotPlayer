package io.github.greytaiwolf.botplayer.skill.menu;

import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 不绑定具体 containerId/stateId 的完整菜单布局模板。
 *
 * <p>世界菜单只能在原版真正打开后得知 window id；计划作者因此只能提交这种完整布局，
 * 由适配器以刚读到的权威快照绑定会话标识，不能猜测客户端窗口编号。
 */
public record MenuLayout(
        MenuFamily family,
        ItemStackFingerprint carried,
        List<ItemStackFingerprint> slots) {
    public MenuLayout {
        Objects.requireNonNull(family, "family");
        Objects.requireNonNull(carried, "carried");
        Objects.requireNonNull(slots, "slots");
        if (slots.size() != family.slotCount()) {
            throw new IllegalArgumentException(
                    "layout slot count does not match exact menu family");
        }
        List<ItemStackFingerprint> copied = new ArrayList<>(slots.size());
        for (ItemStackFingerprint slot : slots) {
            copied.add(Objects.requireNonNull(
                    slot, "slot fingerprint"));
        }
        slots = List.copyOf(copied);
    }

    public static MenuLayout from(MenuSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return new MenuLayout(
                snapshot.family(), snapshot.carried(), snapshot.slots());
    }

    public MenuSnapshot bind(int containerId, int stateId) {
        return new MenuSnapshot(family, containerId, stateId, carried, slots);
    }

    public boolean matches(MenuSnapshot snapshot) {
        return snapshot != null
                && family == snapshot.family()
                && carried.equals(snapshot.carried())
                && slots.equals(snapshot.slots());
    }
}
