package io.github.greytaiwolf.botplayer.perception;

import java.util.List;
import java.util.Objects;

public record InventoryObservation(
        String digest,
        int selectedSlot,
        int occupiedSlots,
        int totalSlots,
        List<ItemObservation> items,
        boolean truncated) {
    public static final int MAX_ITEMS = 512;

    public InventoryObservation {
        Objects.requireNonNull(digest, "digest");
        if (digest.isBlank() || digest.length() > 128) {
            throw new IllegalArgumentException("digest must contain 1-128 characters");
        }
        if (selectedSlot < 0 || selectedSlot > 8) {
            throw new IllegalArgumentException("selectedSlot must be between 0 and 8");
        }
        if (totalSlots < 1 || occupiedSlots < 0 || occupiedSlots > totalSlots) {
            throw new IllegalArgumentException("inventory slot counters are invalid");
        }
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        if (items.size() > MAX_ITEMS) {
            throw new IllegalArgumentException(
                    "items exceeds maximum size " + MAX_ITEMS);
        }
    }
}
