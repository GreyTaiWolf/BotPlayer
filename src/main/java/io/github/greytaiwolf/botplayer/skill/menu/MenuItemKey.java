package io.github.greytaiwolf.botplayer.skill.menu;

import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 与槽位位置和堆叠数量无关的物品身份，用于逐数量守恒核验。
 */
public record MenuItemKey(
        ResourceId itemId, int damage, String componentsDigest) {
    private static final Pattern SHA_256 =
            Pattern.compile("[0-9a-f]{64}");

    public MenuItemKey {
        Objects.requireNonNull(itemId, "itemId");
        if (damage < 0) {
            throw new IllegalArgumentException(
                    "damage must not be negative");
        }
        Objects.requireNonNull(componentsDigest, "componentsDigest");
        if (!SHA_256.matcher(componentsDigest).matches()) {
            throw new IllegalArgumentException(
                    "components digest must be a lower-case SHA-256 value");
        }
    }

    public static Optional<MenuItemKey> from(
            ItemStackFingerprint fingerprint) {
        Objects.requireNonNull(fingerprint, "fingerprint");
        if (fingerprint.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new MenuItemKey(
                fingerprint.itemId().orElseThrow(),
                fingerprint.damage(),
                fingerprint.componentsDigest().orElseThrow()));
    }
}
