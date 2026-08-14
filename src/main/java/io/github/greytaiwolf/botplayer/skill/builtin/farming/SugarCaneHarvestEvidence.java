package io.github.greytaiwolf.botplayer.skill.builtin.farming;

import io.github.greytaiwolf.botplayer.action.ActionEvidence;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Pure decoder for the one-item legacy break-drop receipt used by this narrow
 * sugar-cane slice.
 *
 * <p>The generic action backend emits the legacy triple for exactly one captured
 * drop and compact receipts only for multiple entities. A permitted top sugar-cane
 * break has exactly one native {@code minecraft:sugar_cane} entity with count one;
 * any compact, duplicate, partial, non-canonical, or otherwise widened proof is
 * rejected rather than inferred from an inventory change.
 */
public final class SugarCaneHarvestEvidence {
    private static final UUID ZERO_UUID = new UUID(0L, 0L);
    private static final ResourceId SUGAR_CANE_ID =
            new ResourceId("minecraft:sugar_cane");

    private SugarCaneHarvestEvidence() {
    }

    public static Optional<Receipt> exactSingleDrop(
            List<ActionEvidence> evidence) {
        Objects.requireNonNull(evidence, "evidence");
        String entityId = null;
        String itemId = null;
        String count = null;
        for (ActionEvidence item : evidence) {
            if (item == null) {
                return Optional.empty();
            }
            switch (item.key()) {
                case "block.drop.entity.id" -> {
                    if (entityId != null) {
                        return Optional.empty();
                    }
                    entityId = item.value();
                }
                case "block.drop.item" -> {
                    if (itemId != null) {
                        return Optional.empty();
                    }
                    itemId = item.value();
                }
                case "block.drop.count" -> {
                    if (count != null) {
                        return Optional.empty();
                    }
                    count = item.value();
                }
                default -> {
                    if (item.key().startsWith("block.drop.")) {
                        return Optional.empty();
                    }
                }
            }
        }
        if (entityId == null || itemId == null || count == null) {
            return Optional.empty();
        }
        try {
            UUID parsedEntityId = UUID.fromString(entityId);
            ResourceId parsedItemId = new ResourceId(itemId);
            int parsedCount = Integer.parseInt(count);
            if (ZERO_UUID.equals(parsedEntityId)
                    || !parsedEntityId.toString().equals(entityId)
                    || !parsedItemId.equals(SUGAR_CANE_ID)
                    || parsedCount != 1
                    || !Integer.toString(parsedCount).equals(count)) {
                return Optional.empty();
            }
            return Optional.of(new Receipt(parsedEntityId));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    /** Immutable receipt identity after the strict item/count contract is decoded. */
    public record Receipt(UUID entityId) {
        public Receipt {
            if (ZERO_UUID.equals(Objects.requireNonNull(entityId,
                    "entityId"))) {
                throw new IllegalArgumentException(
                        "sugar cane receipt entity id must be non-zero");
            }
        }
    }
}
