package io.github.greytaiwolf.botplayer.skill.menu;

import java.util.Objects;

/**
 * 一次、且只能在一个服务器 Tick 中发出的原版菜单点击。
 */
public record MenuClick(int slot, MenuClickType type, int button) {
    public MenuClick {
        Objects.requireNonNull(type, "type");
        if (!type.acceptsButton(button)) {
            throw new IllegalArgumentException(
                    "button is not allowed for this click type");
        }
    }

    void validateFor(MenuFamily family) {
        Objects.requireNonNull(family, "family").requireSlot(slot);
    }
}
