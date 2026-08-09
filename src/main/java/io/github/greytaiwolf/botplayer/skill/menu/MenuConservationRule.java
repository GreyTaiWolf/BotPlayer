package io.github.greytaiwolf.botplayer.skill.menu;

import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 菜单点击前后精确物品总数差的白名单。
 *
 * <p>空差值代表严格守恒，适用于移动、交换与箱子存取。制作或取出熔炼结果必须由
 * 上层配方/炉子验证器显式提供输入负数、输出正数；本类绝不把摘要字符串当成守恒
 * 证据。
 */
public record MenuConservationRule(
        Map<MenuItemKey, Long> expectedDelta) {
    public MenuConservationRule {
        Objects.requireNonNull(expectedDelta, "expectedDelta");
        Map<MenuItemKey, Long> copied = new HashMap<>();
        for (Map.Entry<MenuItemKey, Long> entry :
                expectedDelta.entrySet()) {
            MenuItemKey key = Objects.requireNonNull(
                    entry.getKey(), "expected delta key");
            long value = Objects.requireNonNull(
                    entry.getValue(), "expected delta value");
            if (value == 0L) {
                throw new IllegalArgumentException(
                        "expected delta must omit zero entries");
            }
            copied.merge(key, value, Math::addExact);
        }
        copied.entrySet().removeIf(entry -> entry.getValue() == 0L);
        expectedDelta = Map.copyOf(copied);
    }

    public static MenuConservationRule strict() {
        return new MenuConservationRule(Map.of());
    }

    public boolean matches(MenuSnapshot before, MenuSnapshot after) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        return expectedDelta.equals(delta(before, after));
    }

    /**
     * 返回 {@code after - before} 的非零物品数量差，cursor 也作为守恒域的一部分。
     */
    public static Map<MenuItemKey, Long> delta(
            MenuSnapshot before, MenuSnapshot after) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        Map<MenuItemKey, Long> result = new HashMap<>();
        addSnapshot(result, before, -1L);
        addSnapshot(result, after, 1L);
        result.entrySet().removeIf(entry -> entry.getValue() == 0L);
        return Map.copyOf(result);
    }

    private static void addSnapshot(
            Map<MenuItemKey, Long> totals,
            MenuSnapshot snapshot,
            long direction) {
        for (ItemStackFingerprint fingerprint : snapshot.slots()) {
            addFingerprint(totals, fingerprint, direction);
        }
        addFingerprint(totals, snapshot.carried(), direction);
    }

    private static void addFingerprint(
            Map<MenuItemKey, Long> totals,
            ItemStackFingerprint fingerprint,
            long direction) {
        MenuItemKey.from(fingerprint).ifPresent(key -> totals.merge(
                key,
                Math.multiplyExact(direction, fingerprint.count()),
                Math::addExact));
    }
}
