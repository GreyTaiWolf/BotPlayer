package io.github.greytaiwolf.botplayer.skill.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * P5A 明确允许的四种原版菜单布局。
 *
 * <p>每个枚举值同时冻结菜单槽位总数和每一个槽位的职责。适配器不能只凭槽位数把
 * 任意模组菜单当成箱子；它必须先由受信任的原版类分派，再调用
 * {@link #resolveExact(String, int)} 进行第二道精确形状检查。
 */
public enum MenuFamily {
    INVENTORY_2X2("inventory_2x2", inventory2x2()),
    CRAFTING_3X3("crafting_3x3", crafting3x3()),
    FURNACE("furnace", furnace()),
    CHEST_3X9("chest_3x9", chest3x9());

    private final String stableId;
    private final List<MenuSlotRole> slotRoles;

    MenuFamily(String stableId, List<MenuSlotRole> slotRoles) {
        this.stableId = Objects.requireNonNull(stableId, "stableId");
        this.slotRoles = List.copyOf(
                Objects.requireNonNull(slotRoles, "slotRoles"));
        if (this.slotRoles.isEmpty()) {
            throw new IllegalArgumentException(
                    "menu family must expose at least one slot");
        }
    }

    public String stableId() {
        return stableId;
    }

    public int slotCount() {
        return slotRoles.size();
    }

    public MenuSlotRole roleAt(int slot) {
        requireSlot(slot);
        return slotRoles.get(slot);
    }

    public List<MenuSlotRole> slotRoles() {
        return slotRoles;
    }

    public boolean isPlayerInventorySlot(int slot) {
        return switch (roleAt(slot)) {
            case PLAYER_MAIN, PLAYER_HOTBAR, ARMOR_HEAD, ARMOR_CHEST,
                    ARMOR_LEGS, ARMOR_FEET, OFFHAND -> true;
            case RESULT, CRAFTING_INPUT, FURNACE_INPUT, FURNACE_FUEL,
                    CONTAINER -> false;
        };
    }

    public void requireSlot(int slot) {
        if (slot < 0 || slot >= slotRoles.size()) {
            throw new IllegalArgumentException(
                    "slot is outside the exact menu layout");
        }
    }

    /**
     * 由适配器提供的闭合 family 标识解析精确菜单形状。
     *
     * <p>未知标识、空标识或任意槽数偏差都返回空，调用方应立即失败关闭而不是回退到
     * “近似兼容”的布局。
     */
    public static Optional<MenuFamily> resolveExact(
            String stableId, int observedSlotCount) {
        if (stableId == null) {
            return Optional.empty();
        }
        for (MenuFamily family : values()) {
            if (family.stableId.equals(stableId)
                    && family.slotCount() == observedSlotCount) {
                return Optional.of(family);
            }
        }
        return Optional.empty();
    }

    private static List<MenuSlotRole> inventory2x2() {
        List<MenuSlotRole> roles = new ArrayList<>(46);
        roles.add(MenuSlotRole.RESULT);
        append(roles, MenuSlotRole.CRAFTING_INPUT, 4);
        roles.add(MenuSlotRole.ARMOR_HEAD);
        roles.add(MenuSlotRole.ARMOR_CHEST);
        roles.add(MenuSlotRole.ARMOR_LEGS);
        roles.add(MenuSlotRole.ARMOR_FEET);
        append(roles, MenuSlotRole.PLAYER_MAIN, 27);
        append(roles, MenuSlotRole.PLAYER_HOTBAR, 9);
        roles.add(MenuSlotRole.OFFHAND);
        return roles;
    }

    private static List<MenuSlotRole> crafting3x3() {
        List<MenuSlotRole> roles = new ArrayList<>(46);
        roles.add(MenuSlotRole.RESULT);
        append(roles, MenuSlotRole.CRAFTING_INPUT, 9);
        append(roles, MenuSlotRole.PLAYER_MAIN, 27);
        append(roles, MenuSlotRole.PLAYER_HOTBAR, 9);
        return roles;
    }

    private static List<MenuSlotRole> furnace() {
        List<MenuSlotRole> roles = new ArrayList<>(39);
        roles.add(MenuSlotRole.FURNACE_INPUT);
        roles.add(MenuSlotRole.FURNACE_FUEL);
        roles.add(MenuSlotRole.RESULT);
        append(roles, MenuSlotRole.PLAYER_MAIN, 27);
        append(roles, MenuSlotRole.PLAYER_HOTBAR, 9);
        return roles;
    }

    private static List<MenuSlotRole> chest3x9() {
        List<MenuSlotRole> roles = new ArrayList<>(63);
        append(roles, MenuSlotRole.CONTAINER, 27);
        append(roles, MenuSlotRole.PLAYER_MAIN, 27);
        append(roles, MenuSlotRole.PLAYER_HOTBAR, 9);
        return roles;
    }

    private static void append(
            List<MenuSlotRole> roles, MenuSlotRole role, int count) {
        for (int index = 0; index < count; index++) {
            roles.add(role);
        }
    }
}
