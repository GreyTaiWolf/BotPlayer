package io.github.greytaiwolf.botplayer.skill.menu;

import io.github.greytaiwolf.botplayer.action.interaction.InventoryContentsSnapshot;
import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import io.github.greytaiwolf.botplayer.action.interaction.menu.P5ARecipe;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * P5A 唯一白名单熔炼流程的两个真实菜单阶段。
 *
 * <p>先以一份严格、可关闭的原版点击计划投入 input/fuel；炉子在窗口关闭后仍按原版 Tick
 * 继续工作。适配器只能通过重新右键打开同一炉子观察完成状态，并以第二份严格计划取走
 * 结果。这里不读取 block entity，也不直接改炉子或玩家背包。
 */
public final class P5AFurnaceMenuPlanBuilder {
    private P5AFurnaceMenuPlanBuilder() {
    }

    /**
     * 构造投入阶段；炉子三个原生槽和 cursor 必须在打开时为空。
     */
    public static Optional<Deposit> deposit(
            MenuSnapshot opened,
            P5ARecipe recipe,
            Map<ResourceId, ItemStackFingerprint> vanillaPrototypes) {
        Objects.requireNonNull(opened, "opened");
        Objects.requireNonNull(recipe, "recipe");
        Objects.requireNonNull(vanillaPrototypes, "vanillaPrototypes");
        if (!recipe.isFurnace()
                || opened.family() != MenuFamily.FURNACE
                || !opened.carried().isEmpty()
                || !opened.itemAt(0).isEmpty()
                || !opened.itemAt(1).isEmpty()
                || !opened.itemAt(2).isEmpty()) {
            return Optional.empty();
        }
        try {
            Map<ResourceId, ItemStackFingerprint> prototypes =
                    validatedPrototypes(recipe, vanillaPrototypes);
            int outputTarget = emptyPlayerStorageSlot(opened)
                    .orElse(-1);
            if (outputTarget < 0) {
                return Optional.empty();
            }
            List<MenuClickStep> steps = new ArrayList<>();
            MenuSnapshot current = opened;
            current = placeFromSingleSource(
                    steps,
                    current,
                    0,
                    prototypes.get(recipe.furnaceInput()),
                    recipe.furnaceInputCount());
            current = placeFromSingleSource(
                    steps,
                    current,
                    1,
                    prototypes.get(recipe.furnaceFuel()),
                    recipe.furnaceFuelCount());
            if (!current.carried().isEmpty()
                    || !current.itemAt(0).equals(withCount(
                            prototypes.get(recipe.furnaceInput()),
                            recipe.furnaceInputCount()))
                    || !current.itemAt(1).equals(withCount(
                            prototypes.get(recipe.furnaceFuel()),
                            recipe.furnaceFuelCount()))
                    || !current.itemAt(2).isEmpty()
                    || !current.itemAt(outputTarget).isEmpty()) {
                return Optional.empty();
            }
            MenuTransactionPlan plan = new MenuTransactionPlan(
                    MenuFamily.FURNACE, opened, steps, current);
            return Optional.of(new Deposit(
                    plan,
                    new FurnaceExpectation(
                            recipe,
                            current.slots(),
                            outputTarget,
                            prototypes)));
        } catch (ArithmeticException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    /**
     * 仅在重新打开的炉子已完整产出本合同的 result 后构造取结果计划。
     */
    public static Optional<MenuTransactionPlan> collect(
            MenuSnapshot opened, FurnaceExpectation expectation) {
        Objects.requireNonNull(opened, "opened");
        Objects.requireNonNull(expectation, "expectation");
        if (!expectation.readyToCollect(opened)) {
            return Optional.empty();
        }
        try {
            ItemStackFingerprint output = expectation.outputPrototype();
            List<MenuClickStep> steps = new ArrayList<>(2);
            List<ItemStackFingerprint> afterResultSlots =
                    new ArrayList<>(opened.slots());
            afterResultSlots.set(2, ItemStackFingerprint.empty());
            MenuSnapshot afterResult = next(
                    opened, output, afterResultSlots);
            add(steps, opened, afterResult,
                    new MenuClick(2, MenuClickType.PICKUP, 0),
                    MenuConservationRule.strict());

            List<ItemStackFingerprint> afterTargetSlots =
                    new ArrayList<>(afterResult.slots());
            afterTargetSlots.set(
                    expectation.outputTargetSlot(), output);
            MenuSnapshot afterTarget = next(
                    afterResult, ItemStackFingerprint.empty(),
                    afterTargetSlots);
            add(steps, afterResult, afterTarget,
                    new MenuClick(expectation.outputTargetSlot(),
                            MenuClickType.PICKUP, 0),
                    MenuConservationRule.strict());
            return Optional.of(new MenuTransactionPlan(
                    MenuFamily.FURNACE, opened, steps, afterTarget));
        } catch (ArithmeticException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    /**
     * 在整个玩家背包域上验证 input/fuel 扣款、output 入账，其他任何物品身份变化都拒绝。
     */
    public static boolean matchesPlayerDelta(
            InventoryContentsSnapshot before,
            InventoryContentsSnapshot after,
            FurnaceExpectation expectation) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        Objects.requireNonNull(expectation, "expectation");
        Map<MenuItemKey, Long> observed = totals(after);
        mergeTotals(observed, before, -1L);

        P5ARecipe recipe = expectation.recipe();
        Map<MenuItemKey, Long> expected = new LinkedHashMap<>();
        expected.put(MenuItemKey.from(expectation.prototypeFor(
                recipe.furnaceInput())).orElseThrow(),
                (long) -recipe.furnaceInputCount());
        expected.put(MenuItemKey.from(expectation.prototypeFor(
                recipe.furnaceFuel())).orElseThrow(),
                (long) -recipe.furnaceFuelCount());
        expected.put(MenuItemKey.from(expectation.outputPrototype())
                .orElseThrow(), (long) recipe.outputCount());
        observed.entrySet().removeIf(entry -> entry.getValue() == 0L);
        expected.entrySet().removeIf(entry -> entry.getValue() == 0L);
        return observed.equals(expected);
    }

    private static MenuSnapshot placeFromSingleSource(
            List<MenuClickStep> steps,
            MenuSnapshot current,
            int targetSlot,
            ItemStackFingerprint expected,
            int amount) {
        int source = sourceSlot(current, expected, amount).orElse(-1);
        if (source < 0 || !current.itemAt(targetSlot).isEmpty()) {
            throw new IllegalArgumentException(
                    "furnace material source or target changed");
        }
        current = addPickup(steps, current, source, 0);
        for (int count = 0; count < amount; count++) {
            current = addOneToFurnaceSlot(steps, current, targetSlot);
        }
        if (!current.carried().isEmpty()) {
            current = addPickup(steps, current, source, 0);
        }
        return current;
    }

    private static MenuSnapshot addPickup(
            List<MenuClickStep> steps,
            MenuSnapshot before,
            int slot,
            int button) {
        ItemStackFingerprint clicked = before.itemAt(slot);
        ItemStackFingerprint carried = before.carried();
        if (button != 0 || (clicked.isEmpty() && carried.isEmpty())) {
            throw new IllegalArgumentException(
                    "furnace plan only emits observable left pickup clicks");
        }
        List<ItemStackFingerprint> slots = new ArrayList<>(before.slots());
        slots.set(slot, carried);
        MenuSnapshot after = next(before, clicked, slots);
        add(steps, before, after,
                new MenuClick(slot, MenuClickType.PICKUP, button),
                MenuConservationRule.strict());
        return after;
    }

    private static MenuSnapshot addOneToFurnaceSlot(
            List<MenuClickStep> steps,
            MenuSnapshot before,
            int targetSlot) {
        MenuSlotRole expectedRole = targetSlot == 0
                ? MenuSlotRole.FURNACE_INPUT
                : MenuSlotRole.FURNACE_FUEL;
        ItemStackFingerprint target = before.itemAt(targetSlot);
        if (before.family().roleAt(targetSlot) != expectedRole
                || before.carried().isEmpty()
                || !target.isEmpty()
                        && (!target.sameItemAndComponents(before.carried())
                                || target.count() >= 64)) {
            throw new IllegalArgumentException(
                    "furnace target cannot accept the contracted cursor item");
        }
        ItemStackFingerprint placed = withCount(before.carried(), 1);
        List<ItemStackFingerprint> slots = new ArrayList<>(before.slots());
        slots.set(targetSlot, target.isEmpty()
                ? placed
                : withCount(target, Math.addExact(target.count(), 1)));
        MenuSnapshot after = next(
                before, decrement(before.carried()), slots);
        add(steps, before, after,
                new MenuClick(targetSlot, MenuClickType.PICKUP, 1),
                MenuConservationRule.strict());
        return after;
    }

    private static Map<ResourceId, ItemStackFingerprint> validatedPrototypes(
            P5ARecipe recipe,
            Map<ResourceId, ItemStackFingerprint> provided) {
        Map<ResourceId, ItemStackFingerprint> result =
                new LinkedHashMap<>();
        for (ResourceId material : recipe.materialIds()) {
            ItemStackFingerprint prototype = provided.get(material);
            if (prototype == null
                    || prototype.isEmpty()
                    || prototype.count() != 1
                    || prototype.damage() != 0
                    || !prototype.itemId().orElseThrow().equals(material)) {
                throw new IllegalArgumentException(
                        "furnace prototype is not one default vanilla item");
            }
            result.put(material, prototype);
        }
        return Map.copyOf(result);
    }

    private static Optional<Integer> sourceSlot(
            MenuSnapshot snapshot,
            ItemStackFingerprint expected,
            int minimumCount) {
        for (int slot = 0; slot < snapshot.slots().size(); slot++) {
            if (!isPlayerStorage(snapshot.family().roleAt(slot))) {
                continue;
            }
            ItemStackFingerprint candidate = snapshot.itemAt(slot);
            if (candidate.sameItemAndComponents(expected)
                    && candidate.count() >= minimumCount) {
                return Optional.of(slot);
            }
        }
        return Optional.empty();
    }

    private static Optional<Integer> emptyPlayerStorageSlot(
            MenuSnapshot snapshot) {
        for (int slot = 0; slot < snapshot.slots().size(); slot++) {
            if (isPlayerStorage(snapshot.family().roleAt(slot))
                    && snapshot.itemAt(slot).isEmpty()) {
                return Optional.of(slot);
            }
        }
        return Optional.empty();
    }

    private static boolean isPlayerStorage(MenuSlotRole role) {
        return role == MenuSlotRole.PLAYER_MAIN
                || role == MenuSlotRole.PLAYER_HOTBAR;
    }

    private static ItemStackFingerprint decrement(
            ItemStackFingerprint source) {
        if (source.isEmpty() || source.count() < 1) {
            throw new IllegalArgumentException(
                    "cannot decrement empty furnace cursor");
        }
        return source.count() == 1
                ? ItemStackFingerprint.empty()
                : withCount(source, source.count() - 1);
    }

    private static ItemStackFingerprint withCount(
            ItemStackFingerprint prototype, int count) {
        Objects.requireNonNull(prototype, "prototype");
        if (count == 0) {
            return ItemStackFingerprint.empty();
        }
        if (prototype.isEmpty() || count < 1 || count > 64) {
            throw new IllegalArgumentException(
                    "furnace stack count is outside the bounded range");
        }
        return new ItemStackFingerprint(
                prototype.itemId(), count, prototype.damage(),
                prototype.componentsDigest());
    }

    private static MenuSnapshot next(
            MenuSnapshot before,
            ItemStackFingerprint carried,
            List<ItemStackFingerprint> slots) {
        return new MenuSnapshot(
                before.family(), before.containerId(),
                Math.addExact(before.stateId(), 1), carried, slots);
    }

    private static void add(
            List<MenuClickStep> steps,
            MenuSnapshot before,
            MenuSnapshot after,
            MenuClick click,
            MenuConservationRule conservation) {
        steps.add(new MenuClickStep(
                click, before, after, conservation));
    }

    private static Map<MenuItemKey, Long> totals(
            InventoryContentsSnapshot snapshot) {
        Map<MenuItemKey, Long> result = new LinkedHashMap<>();
        mergeTotals(result, snapshot, 1L);
        return result;
    }

    private static void mergeTotals(
            Map<MenuItemKey, Long> totals,
            InventoryContentsSnapshot snapshot,
            long direction) {
        for (ItemStackFingerprint stack : snapshot.itemTotals()) {
            MenuItemKey key = MenuItemKey.from(stack).orElseThrow();
            totals.merge(key,
                    Math.multiplyExact(direction, (long) stack.count()),
                    Math::addExact);
        }
    }

    /**
     * 已绑定的投入点击计划及其跨关闭窗口使用的观察合同。
     *
     * <p>两者必须一起保留：计划完成只证明原版接受了 input/fuel；后续是否可以领取只由
     * {@link FurnaceExpectation} 在重新打开的真实菜单快照上决定。
     */
    public record Deposit(
            MenuTransactionPlan plan, FurnaceExpectation expectation) {
        public Deposit {
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(expectation, "expectation");
            if (plan.family() != MenuFamily.FURNACE
                    || !expectation.recipe().isFurnace()) {
                throw new IllegalArgumentException(
                        "furnace deposit must bind one furnace menu contract");
            }
        }
    }

    /**
     * 已关闭投入窗口后可跨 window id 使用的炉子观察合同。
     */
    public record FurnaceExpectation(
            P5ARecipe recipe,
            List<ItemStackFingerprint> playerLayoutAfterDeposit,
            int outputTargetSlot,
            Map<ResourceId, ItemStackFingerprint> vanillaPrototypes) {
        public FurnaceExpectation {
            Objects.requireNonNull(recipe, "recipe");
            if (!recipe.isFurnace()) {
                throw new IllegalArgumentException(
                        "furnace expectation requires a furnace recipe");
            }
            Objects.requireNonNull(playerLayoutAfterDeposit,
                    "playerLayoutAfterDeposit");
            if (playerLayoutAfterDeposit.size()
                    != MenuFamily.FURNACE.slotCount()) {
                throw new IllegalArgumentException(
                        "furnace expectation must retain the exact menu layout");
            }
            List<ItemStackFingerprint> copied = new ArrayList<>(
                    playerLayoutAfterDeposit.size());
            for (ItemStackFingerprint fingerprint : playerLayoutAfterDeposit) {
                copied.add(Objects.requireNonNull(
                        fingerprint, "expected slot"));
            }
            playerLayoutAfterDeposit = List.copyOf(copied);
            MenuFamily.FURNACE.requireSlot(outputTargetSlot);
            if (MenuFamily.FURNACE.roleAt(outputTargetSlot)
                    != MenuSlotRole.PLAYER_MAIN
                    && MenuFamily.FURNACE.roleAt(outputTargetSlot)
                    != MenuSlotRole.PLAYER_HOTBAR) {
                throw new IllegalArgumentException(
                        "furnace result target must be player storage");
            }
            Objects.requireNonNull(vanillaPrototypes, "vanillaPrototypes");
            vanillaPrototypes = Map.copyOf(vanillaPrototypes);
            for (ResourceId material : recipe.materialIds()) {
                ItemStackFingerprint prototype = vanillaPrototypes.get(material);
                if (prototype == null
                        || prototype.isEmpty()
                        || prototype.count() != 1
                        || prototype.damage() != 0
                        || !prototype.itemId().orElseThrow().equals(material)) {
                    throw new IllegalArgumentException(
                            "furnace expectation has an invalid vanilla prototype");
                }
            }
            if (!playerLayoutAfterDeposit.get(outputTargetSlot).isEmpty()) {
                throw new IllegalArgumentException(
                        "furnace result target must remain empty after deposit");
            }
        }

        public boolean pollingSnapshotAllowed(MenuSnapshot snapshot) {
            if (snapshot == null
                    || snapshot.family() != MenuFamily.FURNACE
                    || !snapshot.carried().isEmpty()
                    || !samePlayerLayout(snapshot)) {
                return false;
            }
            ItemStackFingerprint input = snapshot.itemAt(0);
            ItemStackFingerprint fuel = snapshot.itemAt(1);
            ItemStackFingerprint output = snapshot.itemAt(2);
            return matchesBounded(input, prototypeFor(recipe.furnaceInput()),
                    recipe.furnaceInputCount())
                    && matchesBounded(fuel, prototypeFor(recipe.furnaceFuel()),
                            recipe.furnaceFuelCount())
                    && matchesBounded(output, outputPrototype(),
                            recipe.outputCount())
                    && snapshot.itemAt(outputTargetSlot).isEmpty();
        }

        public boolean readyToCollect(MenuSnapshot snapshot) {
            return pollingSnapshotAllowed(snapshot)
                    && snapshot.itemAt(0).isEmpty()
                    && snapshot.itemAt(1).isEmpty()
                    && snapshot.itemAt(2).equals(outputPrototype());
        }

        public ItemStackFingerprint outputPrototype() {
            return withCount(prototypeFor(recipe.output()),
                    recipe.outputCount());
        }

        ItemStackFingerprint prototypeFor(ResourceId material) {
            ItemStackFingerprint prototype = vanillaPrototypes.get(material);
            if (prototype == null) {
                throw new IllegalStateException(
                        "furnace expectation lacks a contracted material prototype");
            }
            return prototype;
        }

        private boolean samePlayerLayout(MenuSnapshot snapshot) {
            for (int slot = 0; slot < snapshot.slots().size(); slot++) {
                if (isPlayerStorage(MenuFamily.FURNACE.roleAt(slot))
                        && !snapshot.itemAt(slot).equals(
                                playerLayoutAfterDeposit.get(slot))) {
                    return false;
                }
            }
            return true;
        }

        private static boolean matchesBounded(
                ItemStackFingerprint actual,
                ItemStackFingerprint expected,
                int maximumCount) {
            return actual.isEmpty()
                    || actual.sameItemAndComponents(expected)
                    && actual.count() >= 1
                    && actual.count() <= maximumCount;
        }
    }
}
