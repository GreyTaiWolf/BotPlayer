package io.github.greytaiwolf.botplayer.perception;

import java.util.Objects;

public record ItemObservation(
        int slot,
        String itemId,
        int count,
        int damage,
        int maximumDamage) {
    public ItemObservation {
        if (slot < 0 || slot > 4095) {
            throw new IllegalArgumentException("slot must be between 0 and 4095");
        }
        Objects.requireNonNull(itemId, "itemId");
        if (itemId.isBlank() || itemId.length() > 128) {
            throw new IllegalArgumentException("itemId must contain 1-128 characters");
        }
        if (count < 1 || count > 99_999) {
            throw new IllegalArgumentException("count must be between 1 and 99999");
        }
        if (damage < 0 || maximumDamage < 0 || damage > maximumDamage) {
            throw new IllegalArgumentException("item damage range is invalid");
        }
    }
}
