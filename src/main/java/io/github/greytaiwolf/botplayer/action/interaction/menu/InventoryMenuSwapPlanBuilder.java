package io.github.greytaiwolf.botplayer.action.interaction.menu;

import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 从一份权威初始快照生成 P5A 的稳定 SWAP 计划。
 *
 * <p>Builder 不猜测原版同步器会如何递增 stateId；合成的 after 快照保留初始
 * stateId，后端应先核对真实 stateId 栅栏，再用
 * {@link InventoryMenuSnapshot#layoutEqualsIgnoringState(InventoryMenuSnapshot)}
 * 验证每一步完整布局。
 */
public final class InventoryMenuSwapPlanBuilder {
    private InventoryMenuSwapPlanBuilder() {
    }

    public static InventoryMenuSwapPlan mainToHotbar(
            InventoryMenuSnapshot initialSnapshot,
            int sourceMainInventorySlot,
            int targetHotbarSlot) {
        return mainToHotbar(
                initialSnapshot,
                sourceMainInventorySlot,
                targetHotbarSlot,
                InventoryMenuTransactionLimits.defaults());
    }

    public static InventoryMenuSwapPlan mainToHotbar(
            InventoryMenuSnapshot initialSnapshot,
            int sourceMainInventorySlot,
            int targetHotbarSlot,
            InventoryMenuTransactionLimits limits) {
        requireInitial(initialSnapshot);
        requireMainSlot(sourceMainInventorySlot);
        requireHotbarSlot(targetHotbarSlot);
        requireNonEmptySource(
                initialSnapshot.itemAt(sourceMainInventorySlot));
        requireDifferent(
                initialSnapshot.itemAt(sourceMainInventorySlot),
                initialSnapshot.itemAt(targetHotbarSlot),
                "source and target");

        InventoryMenuClickStep step = step(
                initialSnapshot,
                sourceMainInventorySlot,
                targetHotbarSlot);
        return new InventoryMenuSwapPlan(
                InventoryMenuSwapPlan.Operation.MAIN_TO_HOTBAR,
                initialSnapshot,
                step.after(),
                List.of(step),
                Objects.requireNonNull(limits, "limits"));
    }

    public static InventoryMenuSwapPlan mainToEquipment(
            InventoryMenuSnapshot initialSnapshot,
            int sourceMainInventorySlot,
            int targetEquipmentInventorySlot,
            int temporaryHotbarSlot) {
        return mainToEquipment(
                initialSnapshot,
                sourceMainInventorySlot,
                targetEquipmentInventorySlot,
                temporaryHotbarSlot,
                InventoryMenuTransactionLimits.defaults());
    }

    public static InventoryMenuSwapPlan hotbarToEquipment(
            InventoryMenuSnapshot initialSnapshot,
            int sourceHotbarSlot,
            int targetEquipmentInventorySlot) {
        return hotbarToEquipment(
                initialSnapshot,
                sourceHotbarSlot,
                targetEquipmentInventorySlot,
                InventoryMenuTransactionLimits.defaults());
    }

    public static InventoryMenuSwapPlan hotbarToEquipment(
            InventoryMenuSnapshot initialSnapshot,
            int sourceHotbarSlot,
            int targetEquipmentInventorySlot,
            InventoryMenuTransactionLimits limits) {
        requireInitial(initialSnapshot);
        requireHotbarSlot(sourceHotbarSlot);
        if (!PlayerInventoryMenuLayout.isEquipmentInventorySlot(
                targetEquipmentInventorySlot)) {
            throw new IllegalArgumentException(
                    "target must be an armor or offhand inventory slot");
        }
        ItemStackFingerprint source =
                initialSnapshot.itemAt(sourceHotbarSlot);
        ItemStackFingerprint target =
                initialSnapshot.itemAt(
                        targetEquipmentInventorySlot);
        requireNonEmptySource(source);
        requireDifferent(source, target, "source and target");
        InventoryMenuClickStep step = step(
                initialSnapshot,
                targetEquipmentInventorySlot,
                sourceHotbarSlot);
        return new InventoryMenuSwapPlan(
                InventoryMenuSwapPlan.Operation
                        .HOTBAR_TO_EQUIPMENT,
                initialSnapshot,
                step.after(),
                List.of(step),
                Objects.requireNonNull(limits, "limits"));
    }

    public static InventoryMenuSwapPlan mainToEquipment(
            InventoryMenuSnapshot initialSnapshot,
            int sourceMainInventorySlot,
            int targetEquipmentInventorySlot,
            int temporaryHotbarSlot,
            InventoryMenuTransactionLimits limits) {
        requireInitial(initialSnapshot);
        requireMainSlot(sourceMainInventorySlot);
        if (!PlayerInventoryMenuLayout.isEquipmentInventorySlot(
                targetEquipmentInventorySlot)) {
            throw new IllegalArgumentException(
                    "target must be an armor or offhand inventory slot");
        }
        requireHotbarSlot(temporaryHotbarSlot);
        ItemStackFingerprint source =
                initialSnapshot.itemAt(sourceMainInventorySlot);
        ItemStackFingerprint target =
                initialSnapshot.itemAt(
                        targetEquipmentInventorySlot);
        ItemStackFingerprint temporary =
                initialSnapshot.itemAt(temporaryHotbarSlot);
        requireNonEmptySource(source);
        requireDifferent(source, target, "source and target");

        List<InventoryMenuClickStep> steps = new ArrayList<>(3);
        InventoryMenuSnapshot current = initialSnapshot;
        if (!source.equals(temporary)) {
            InventoryMenuClickStep sourceToTemporary = step(
                    current,
                    sourceMainInventorySlot,
                    temporaryHotbarSlot);
            steps.add(sourceToTemporary);
            current = sourceToTemporary.after();
        }
        InventoryMenuClickStep targetToTemporary = step(
                current,
                targetEquipmentInventorySlot,
                temporaryHotbarSlot);
        steps.add(targetToTemporary);
        current = targetToTemporary.after();
        if (!target.equals(temporary)) {
            InventoryMenuClickStep targetToSource = step(
                    current,
                    sourceMainInventorySlot,
                    temporaryHotbarSlot);
            steps.add(targetToSource);
            current = targetToSource.after();
        }

        return new InventoryMenuSwapPlan(
                InventoryMenuSwapPlan.Operation.MAIN_TO_EQUIPMENT,
                initialSnapshot,
                current,
                steps,
                Objects.requireNonNull(limits, "limits"));
    }

    private static InventoryMenuClickStep step(
            InventoryMenuSnapshot before,
            int clickedInventorySlot,
            int hotbarButton) {
        InventoryMenuSnapshot after = before.swapKeepingState(
                clickedInventorySlot, hotbarButton);
        return new InventoryMenuClickStep(
                PlayerInventoryMenuLayout.menuSlotForInventorySlot(
                        clickedInventorySlot),
                hotbarButton,
                before,
                after);
    }

    private static void requireInitial(
            InventoryMenuSnapshot initialSnapshot) {
        Objects.requireNonNull(initialSnapshot, "initialSnapshot");
        if (!initialSnapshot.cursor().isEmpty()) {
            throw new IllegalArgumentException(
                    "SWAP plan requires an empty initial cursor");
        }
    }

    private static void requireMainSlot(int inventorySlot) {
        if (!PlayerInventoryMenuLayout.isMainInventorySlot(
                inventorySlot)) {
            throw new IllegalArgumentException(
                    "source must be a main inventory slot");
        }
    }

    private static void requireHotbarSlot(int inventorySlot) {
        if (!PlayerInventoryMenuLayout.isHotbarInventorySlot(
                inventorySlot)) {
            throw new IllegalArgumentException(
                    "hotbar inventory slot must be in 0..8");
        }
    }

    private static void requireDifferent(
            ItemStackFingerprint first,
            ItemStackFingerprint second,
            String label) {
        if (first.equals(second)) {
            throw new IllegalArgumentException(
                    label + " fingerprints must differ");
        }
    }

    private static void requireNonEmptySource(
            ItemStackFingerprint source) {
        if (source.isEmpty()) {
            throw new IllegalArgumentException(
                    "source main inventory slot must not be empty");
        }
    }
}
