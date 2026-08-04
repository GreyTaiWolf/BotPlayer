package io.github.greytaiwolf.botplayer.action.interaction.menu;

import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.TreeSet;

/**
 * 有界、不可变且每一步都携带完整 41 槽快照的 SWAP 事务计划。
 */
public final class InventoryMenuSwapPlan {
    public enum Operation {
        MAIN_TO_HOTBAR,
        HOTBAR_TO_EQUIPMENT,
        MAIN_TO_EQUIPMENT,
        SWAP_SEQUENCE
    }

    private final Operation operation;
    private final InventoryMenuSnapshot initialSnapshot;
    private final InventoryMenuSnapshot finalSnapshot;
    private final List<InventoryMenuClickStep> orderedSteps;
    private final InventoryMenuTransactionLimits limits;
    private final List<Integer> touchedInventorySlots;

    InventoryMenuSwapPlan(
            Operation operation,
            InventoryMenuSnapshot initialSnapshot,
            InventoryMenuSnapshot finalSnapshot,
            List<InventoryMenuClickStep> orderedSteps,
            InventoryMenuTransactionLimits limits) {
        this.operation = Objects.requireNonNull(
                operation, "operation");
        this.initialSnapshot = Objects.requireNonNull(
                initialSnapshot, "initialSnapshot");
        this.finalSnapshot = Objects.requireNonNull(
                finalSnapshot, "finalSnapshot");
        Objects.requireNonNull(orderedSteps, "orderedSteps");
        this.limits = Objects.requireNonNull(limits, "limits");
        if (!initialSnapshot.cursor().isEmpty()) {
            throw new IllegalArgumentException(
                    "SWAP plan requires an empty initial cursor");
        }
        if (orderedSteps.isEmpty()
                || orderedSteps.size() > limits.maxClicks()) {
            throw new IllegalArgumentException(
                    "SWAP plan click count is outside its limit");
        }

        this.orderedSteps = List.copyOf(orderedSteps);
        InventoryMenuSnapshot expectedBefore = initialSnapshot;
        TreeSet<Integer> touched = new TreeSet<>();
        for (InventoryMenuClickStep step : this.orderedSteps) {
            Objects.requireNonNull(step, "ordered step");
            if (!expectedBefore.equals(step.before())) {
                throw new IllegalArgumentException(
                        "SWAP step snapshots must form one exact chain");
            }
            expectedBefore = step.after();
            touched.add(step.clickedInventorySlot());
            touched.add(step.hotbarButton());
        }
        if (!expectedBefore.equals(finalSnapshot)) {
            throw new IllegalArgumentException(
                    "final snapshot must equal the last step result");
        }
        if (touched.size() > limits.maxUniqueInventorySlots()) {
            throw new IllegalArgumentException(
                    "SWAP plan exceeds unique inventory slot limit");
        }
        this.touchedInventorySlots = List.copyOf(touched);
        validateUniquePrefixes();
        validateOperationShape();
    }

    private void validateUniquePrefixes() {
        for (int first = 0;
                first <= orderedSteps.size();
                first++) {
            InventoryMenuSnapshot firstSnapshot =
                    snapshotAtPrefix(first);
            for (int second = first + 1;
                    second <= orderedSteps.size();
                    second++) {
                if (firstSnapshot.layoutEqualsIgnoringState(
                        snapshotAtPrefix(second))) {
                    throw new IllegalArgumentException(
                            "SWAP plan prefixes must have unique layouts");
                }
            }
        }
    }

    private void validateOperationShape() {
        switch (operation) {
            case MAIN_TO_HOTBAR -> {
                if (orderedSteps.size() != 1) {
                    throw new IllegalArgumentException(
                            "main-to-hotbar plan requires one click");
                }
                InventoryMenuClickStep step =
                        orderedSteps.getFirst();
                if (!PlayerInventoryMenuLayout.isMainInventorySlot(
                                step.clickedInventorySlot())
                        || !PlayerInventoryMenuLayout
                                .isHotbarInventorySlot(
                                        step.hotbarButton())) {
                    throw new IllegalArgumentException(
                            "main-to-hotbar slot roles are invalid");
                }
            }
            case HOTBAR_TO_EQUIPMENT -> {
                if (orderedSteps.size() != 1) {
                    throw new IllegalArgumentException(
                            "hotbar-to-equipment plan requires one click");
                }
                InventoryMenuClickStep step =
                        orderedSteps.getFirst();
                if (!PlayerInventoryMenuLayout
                                .isEquipmentInventorySlot(
                                        step.clickedInventorySlot())
                        || !PlayerInventoryMenuLayout
                                .isHotbarInventorySlot(
                                        step.hotbarButton())) {
                    throw new IllegalArgumentException(
                            "hotbar-to-equipment slot roles are invalid");
                }
            }
            case MAIN_TO_EQUIPMENT -> {
                if (orderedSteps.size() < 2
                        || orderedSteps.size() > 3) {
                    throw new IllegalArgumentException(
                            "main-to-equipment plan requires two or three clicks");
                }
                int hotbar = orderedSteps.getFirst().hotbarButton();
                List<InventoryMenuClickStep> equipmentSteps =
                        orderedSteps.stream()
                                .filter(step ->
                                        PlayerInventoryMenuLayout
                                                .isEquipmentInventorySlot(
                                                        step.clickedInventorySlot()))
                                .toList();
                List<InventoryMenuClickStep> mainSteps =
                        orderedSteps.stream()
                                .filter(step ->
                                        PlayerInventoryMenuLayout
                                                .isMainInventorySlot(
                                                        step.clickedInventorySlot()))
                                .toList();
                if (equipmentSteps.size() != 1
                        || mainSteps.size()
                                != orderedSteps.size() - 1
                        || orderedSteps.stream().anyMatch(step ->
                                step.hotbarButton() != hotbar)
                        || mainSteps.stream().anyMatch(step ->
                                step.clickedInventorySlot()
                                        != mainSteps.getFirst()
                                                .clickedInventorySlot())
                        || !finalSnapshot
                                .itemAt(
                                        equipmentSteps.getFirst()
                                                .clickedInventorySlot())
                                .equals(initialSnapshot.itemAt(
                                        mainSteps.getFirst()
                                                .clickedInventorySlot()))
                        || !finalSnapshot
                                .itemAt(
                                        mainSteps.getFirst()
                                                .clickedInventorySlot())
                                .equals(initialSnapshot.itemAt(
                                        equipmentSteps.getFirst()
                                                .clickedInventorySlot()))
                        || !finalSnapshot.itemAt(hotbar)
                                .equals(initialSnapshot.itemAt(hotbar))) {
                    throw new IllegalArgumentException(
                            "main-to-equipment plan shape is invalid");
                }
            }
            case SWAP_SEQUENCE -> {
                /*
                 * The constructor's exact snapshot chain, unique-prefix,
                 * click-count and touched-slot gates are the complete
                 * shape contract for a generic SWAP sequence.
                 */
            }
        }
    }

    public Operation operation() {
        return operation;
    }

    public InventoryMenuSnapshot initialSnapshot() {
        return initialSnapshot;
    }

    public InventoryMenuSnapshot finalSnapshot() {
        return finalSnapshot;
    }

    public List<InventoryMenuClickStep> orderedSteps() {
        return orderedSteps;
    }

    public InventoryMenuTransactionLimits limits() {
        return limits;
    }

    public List<Integer> touchedInventorySlots() {
        return touchedInventorySlots;
    }

    /**
     * 返回恰好完成 {@code confirmedClicks} 次点击后的完整计划快照。
     */
    public InventoryMenuSnapshot snapshotAtPrefix(
            int confirmedClicks) {
        if (confirmedClicks < 0
                || confirmedClicks > orderedSteps.size()) {
            throw new IllegalArgumentException(
                    "confirmedClicks is outside the plan");
        }
        return confirmedClicks == 0
                ? initialSnapshot
                : orderedSteps.get(confirmedClicks - 1).after();
    }

    /**
     * 按完整布局识别当前快照对应的唯一计划前缀，只忽略 stateId。
     */
    public OptionalInt matchingPrefixIgnoringState(
            InventoryMenuSnapshot actual) {
        Objects.requireNonNull(actual, "actual");
        OptionalInt match = OptionalInt.empty();
        for (int prefix = 0;
                prefix <= orderedSteps.size();
                prefix++) {
            if (!snapshotAtPrefix(prefix)
                    .layoutEqualsIgnoringState(actual)) {
                continue;
            }
            if (match.isPresent()) {
                throw new IllegalStateException(
                        "SWAP plan prefix match is ambiguous");
            }
            match = OptionalInt.of(prefix);
        }
        return match;
    }

    /**
     * 只按本计划会触碰的库存槽识别唯一前缀。
     *
     * <p>该识别用于防止选中槽或无关槽的外部变化把一个临时事务前缀伪装成
     * “未知但安全”的布局。container、cursor、多重集与精确 stateId 仍由调用方
     * 独立核对。
     */
    OptionalInt matchingTouchedPrefix(
            InventoryMenuSnapshot actual) {
        Objects.requireNonNull(actual, "actual");
        OptionalInt match = OptionalInt.empty();
        for (int prefix = 0;
                prefix <= orderedSteps.size();
                prefix++) {
            InventoryMenuSnapshot expected =
                    snapshotAtPrefix(prefix);
            boolean equal = true;
            for (int inventorySlot :
                    touchedInventorySlots) {
                if (!expected.itemAt(inventorySlot)
                        .equals(actual.itemAt(
                                inventorySlot))) {
                    equal = false;
                    break;
                }
            }
            if (!equal) {
                continue;
            }
            if (match.isPresent()) {
                throw new IllegalStateException(
                        "SWAP plan touched-slot prefix match is ambiguous");
            }
            match = OptionalInt.of(prefix);
        }
        return match;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof InventoryMenuSwapPlan plan)) {
            return false;
        }
        return operation == plan.operation
                && initialSnapshot.equals(plan.initialSnapshot)
                && finalSnapshot.equals(plan.finalSnapshot)
                && orderedSteps.equals(plan.orderedSteps)
                && limits.equals(plan.limits);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                operation,
                initialSnapshot,
                finalSnapshot,
                orderedSteps,
                limits);
    }

    @Override
    public String toString() {
        return "InventoryMenuSwapPlan[operation="
                + operation
                + ", orderedSteps="
                + orderedSteps
                + ']';
    }
}
