package io.github.greytaiwolf.botplayer.action.minecraft;

import io.github.greytaiwolf.botplayer.action.ActionEvidence;
import io.github.greytaiwolf.botplayer.action.ActionOutcome;
import io.github.greytaiwolf.botplayer.action.interaction.BlockCoordinates;
import io.github.greytaiwolf.botplayer.action.interaction.BlockTargetFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.neoforged.neoforge.event.level.BlockDropsEvent;

/**
 * Captures the exact vanilla item entities produced by one synchronous block-break packet.
 *
 * <p>The capture is armed around the one dispatch packet that may complete the break
 * ({@code START_DESTROY_BLOCK} for {@code instabreak()} blocks, otherwise
 * {@code STOP_DESTROY_BLOCK}); it never retains a live player, level, or item entity after
 * that packet returns. {@link BlockDropsEvent} supplies the still-unspawned entity UUID,
 * item id, and count, which can subsequently be carried as immutable action evidence. Any
 * missing, duplicate, or non-exact event leaves no receipt.
 */
public final class BreakDropProvenanceCapture {
    private static final UUID ZERO_UUID = new UUID(0L, 0L);
    /**
     * Obsolete bare multi-drop receipt key. It is retained only so consumers can
     * explicitly reject the ambiguous pre-index protocol; the backend never
     * emits it.
     */
    public static final String COMPACT_RECEIPT_EVIDENCE_KEY =
            "block.drop.receipt";
    /** One compact receipt is emitted for each multi-drop entity at a unique index. */
    public static final String COMPACT_RECEIPT_EVIDENCE_KEY_PREFIX =
            COMPACT_RECEIPT_EVIDENCE_KEY + ".";
    /* verifyBreak always emits position, post-state and inventory-change first. */
    private static final int VERIFY_BREAK_BASE_EVIDENCE_ITEMS = 3;
    /**
     * A normal vanilla block may legitimately emit several independent item entities
     * (mature wheat is the smallest example).  Keep the synchronous receipt bounded
     * by the global ActionOutcome evidence ceiling: callers that need an unbounded
     * loot-table expansion must not reuse this action-level provenance channel.
     */
    private static final int MAX_DROPPED_ITEM_ENTITIES =
            ActionOutcome.MAX_EVIDENCE_ITEMS
                    - VERIFY_BREAK_BASE_EVIDENCE_ITEMS;
    private static final ThreadLocal<ActiveCapture> ACTIVE = new ThreadLocal<>();

    private BreakDropProvenanceCapture() {
    }

    /**
     * Returns the canonical, bounded evidence key for one multi-drop receipt.
     * The index preserves the original vanilla drop-event order without relying
     * on duplicate evidence keys across the ActionOutcome-to-SkillSignal boundary.
     */
    public static String compactReceiptEvidenceKey(int index) {
        if (index < 0 || index >= MAX_DROPPED_ITEM_ENTITIES) {
            throw new IllegalArgumentException(
                    "compact receipt index exceeds the action evidence bound");
        }
        return COMPACT_RECEIPT_EVIDENCE_KEY_PREFIX + index;
    }

    /**
     * Decodes only a canonical, bounded compact receipt index. Bare, signed,
     * zero-padded, malformed and out-of-budget keys are rejected.
     */
    public static OptionalInt compactReceiptEvidenceIndex(String key) {
        Objects.requireNonNull(key, "key");
        if (!key.startsWith(COMPACT_RECEIPT_EVIDENCE_KEY_PREFIX)) {
            return OptionalInt.empty();
        }
        String encoded = key.substring(COMPACT_RECEIPT_EVIDENCE_KEY_PREFIX
                .length());
        try {
            int index = Integer.parseInt(encoded);
            return index >= 0 && index < MAX_DROPPED_ITEM_ENTITIES
                    && Integer.toString(index).equals(encoded)
                            ? OptionalInt.of(index) : OptionalInt.empty();
        } catch (NumberFormatException exception) {
            return OptionalInt.empty();
        }
    }

    /**
     * Arms a same-thread capture around one already-validated break packet.
     */
    static Scope arm(
            BotServerPlayer player,
            BlockTargetFingerprint target,
            long generation) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(target, "target");
        if (generation <= 0L
                || player.runtimeHandle().generation() != generation
                || !target.dimension().value().equals(player.serverLevel()
                        .dimension().location().toString())) {
            throw new IllegalArgumentException(
                    "break-drop provenance arm does not match active bot");
        }
        if (ACTIVE.get() != null) {
            throw new IllegalStateException(
                    "nested block-drop provenance capture is not permitted");
        }
        ActiveCapture capture = new ActiveCapture(player, generation, target);
        ACTIVE.set(capture);
        return new Scope(capture);
    }

    /**
     * Receives the synchronous NeoForge drop event. Unrelated events are ignored; a second
     * event for the exact same captured source marks the receipt ambiguous.
     */
    public static void record(BlockDropsEvent event) {
        ActiveCapture capture = ACTIVE.get();
        if (capture != null) {
            capture.record(Objects.requireNonNull(event, "event"));
        }
    }

    /** Immutable proof that a particular ItemEntity was generated by the captured break. */
    public record Provenance(UUID entityId, String itemId, int count) {
        public Provenance {
            Objects.requireNonNull(entityId, "entityId");
            new ResourceId(Objects.requireNonNull(itemId, "itemId"));
            if (ZERO_UUID.equals(entityId) || count < 1 || count > 64) {
                throw new IllegalArgumentException(
                        "break-drop provenance is invalid");
            }
        }
    }

    /**
     * Parses the compact multi-drop protocol exactly.  UUID and count must be
     * canonical text, and ResourceId rejects separators or malformed syntax.
     */
    public static Optional<Provenance> parseCompactReceipt(String value) {
        Objects.requireNonNull(value, "value");
        if (value.length() > ActionEvidence.MAX_VALUE_LENGTH
                || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl)) {
            return Optional.empty();
        }
        String[] fields = value.split("\\|", -1);
        if (fields.length != 3) {
            return Optional.empty();
        }
        try {
            UUID entityId = UUID.fromString(fields[0]);
            ResourceId itemId = new ResourceId(fields[1]);
            int count = Integer.parseInt(fields[2]);
            if (!entityId.toString().equals(fields[0])
                    || !Integer.toString(count).equals(fields[2])) {
                return Optional.empty();
            }
            return Optional.of(new Provenance(entityId, itemId.value(),
                    count));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    /**
     * Encodes one multi-drop receipt only when its exact text fits the immutable
     * ActionEvidence value contract.  Callers must reject the entire receipt set
     * if any one member cannot be represented; never truncate individual drops.
     */
    static Optional<String> compactReceiptValue(Provenance provenance) {
        Provenance required = Objects.requireNonNull(provenance,
                "provenance");
        String value = required.entityId()
                + "|"
                + required.itemId()
                + "|"
                + required.count();
        return value.length() <= ActionEvidence.MAX_VALUE_LENGTH
                ? Optional.of(value)
                : Optional.empty();
    }

    /** Scope returned to the backend; it may expose a receipt only after being closed. */
    static final class Scope implements AutoCloseable {
        private final ActiveCapture capture;
        private boolean closed;

        private Scope(ActiveCapture capture) {
            this.capture = Objects.requireNonNull(capture, "capture");
        }

        @Override
        public void close() {
            if (!closed) {
                if (ACTIVE.get() != capture) {
                    throw new IllegalStateException(
                            "block-drop provenance capture ownership changed");
                }
                ACTIVE.remove();
                closed = true;
            }
        }

        Optional<Provenance> provenance() {
            if (!closed) {
                throw new IllegalStateException(
                        "block-drop provenance receipt read before close");
            }
            List<Provenance> values = capture.provenances().orElse(null);
            return values != null && values.size() == 1
                    ? Optional.of(values.getFirst())
                    : Optional.empty();
        }

        /**
         * Returns every exact item entity emitted by the one captured vanilla break.
         * The legacy {@link #provenance()} view deliberately remains singleton-only so
         * existing callers cannot silently start accepting multi-drop loot tables.
         */
        Optional<List<Provenance>> provenances() {
            if (!closed) {
                throw new IllegalStateException(
                        "block-drop provenance receipt read before close");
            }
            return capture.provenances();
        }
    }

    private static final class ActiveCapture {
        private final BotServerPlayer player;
        private final long generation;
        private final BlockTargetFingerprint target;
        private boolean matched;
        private boolean invalid;
        private List<Provenance> provenances = List.of();

        private ActiveCapture(
                BotServerPlayer player,
                long generation,
                BlockTargetFingerprint target) {
            this.player = Objects.requireNonNull(player, "player");
            this.generation = generation;
            this.target = Objects.requireNonNull(target, "target");
        }

        private void record(BlockDropsEvent event) {
            if (!(event.getLevel() instanceof ServerLevel level)
                    || event.getBreaker() != player
                    || player.runtimeHandle().generation() != generation
                    || !target.dimension().value().equals(level.dimension()
                            .location().toString())
                    || !event.getPos().equals(position(target.position()))
                    || !MinecraftInteractionView.blockStateFingerprint(
                            event.getState()).equals(target.state())) {
                return;
            }
            if (matched) {
                invalid = true;
                return;
            }
            matched = true;
            if (event.getDrops().isEmpty()
                    || event.getDrops().size()
                            > MAX_DROPPED_ITEM_ENTITIES) {
                invalid = true;
                return;
            }
            List<Provenance> receipts = new ArrayList<>(
                    event.getDrops().size());
            Set<UUID> entityIds = new HashSet<>(event.getDrops().size());
            for (ItemEntity item : event.getDrops()) {
                if (item == null || item.isRemoved()
                        || item.getItem().isEmpty()
                        || ZERO_UUID.equals(item.getUUID())
                        || !entityIds.add(item.getUUID())) {
                    invalid = true;
                    return;
                }
                int count = item.getItem().getCount();
                if (count < 1 || count > 64) {
                    invalid = true;
                    return;
                }
                receipts.add(new Provenance(item.getUUID(),
                        BuiltInRegistries.ITEM.getKey(item.getItem()
                                .getItem()).toString(), count));
            }
            provenances = List.copyOf(receipts);
        }

        private Optional<List<Provenance>> provenances() {
            return !matched || invalid || provenances.isEmpty()
                    ? Optional.empty()
                    : Optional.of(provenances);
        }
    }

    private static BlockPos position(BlockCoordinates coordinates) {
        return new BlockPos(coordinates.x(), coordinates.y(), coordinates.z());
    }
}
