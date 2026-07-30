package io.github.greytaiwolf.botplayer.skill.builtin.survival;

/**
 * P5 基础装备策略允许处理的有限目标槽位。
 */
public enum EquipmentSlotKind {
    HEAD,
    CHEST,
    LEGS,
    FEET,
    OFFHAND,
    MAIN_HAND_TOOL;

    public boolean isArmorSlot() {
        return this == HEAD
                || this == CHEST
                || this == LEGS
                || this == FEET;
    }
}
