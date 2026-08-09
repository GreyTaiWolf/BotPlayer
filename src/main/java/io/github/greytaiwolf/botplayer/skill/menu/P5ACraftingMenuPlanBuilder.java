package io.github.greytaiwolf.botplayer.skill.menu;

import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.InventoryContentsSnapshot;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import io.github.greytaiwolf.botplayer.action.interaction.menu.P5ARecipe;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 从刚打开的原版 crafting 菜单完整快照编译一批 P5A 白名单配方点击。
 *
 * <p>该编译器故意只选择一个足量的原版默认组件 source stack，并且要求一个空的玩家目标
 * 槽。它不会把部分、带自定义组件或分散在多个 source stack 的材料“凑合”起来；调用方
 * 应将空结果视为可恢复的前置条件失败，而不是直接写背包。
 */
public final class P5ACraftingMenuPlanBuilder {
    private P5ACraftingMenuPlanBuilder() {
    }

    /**
     * 以完整权威快照和每种默认原版材料的精确组件指纹构造逐 Tick 点击计划。
     */
    public static Optional<MenuTransactionPlan> build(
            MenuSnapshot opened,
            P5ARecipe recipe,
            Map<ResourceId, ItemStackFingerprint> vanillaPrototypes) {
        return build(opened, recipe, 1, vanillaPrototypes);
    }

    /**
     * 构造经过 recipe 白名单审核的有限多批计划。每一批都重新从上一个精确 prefix 选择
     * source，完成 preview、领取和空 grid 收口；不会把多个 batch 折叠成一次虚假的库存
     * delta。
     */
    public static Optional<MenuTransactionPlan> build(
            MenuSnapshot opened,
            P5ARecipe recipe,
            int batches,
            Map<ResourceId, ItemStackFingerprint> vanillaPrototypes) {
        Objects.requireNonNull(opened, "opened");
        Objects.requireNonNull(recipe, "recipe");
        Objects.requireNonNull(vanillaPrototypes, "vanillaPrototypes");
        if (!recipe.isCrafting()
                || batches < 1
                || batches > recipe.maximumBatches()
                || opened.family() != recipe.family()
                || !opened.carried().isEmpty()
                || !craftingAreaEmpty(opened)) {
            return Optional.empty();
        }

        try {
            Map<ResourceId, ItemStackFingerprint> prototypes =
                    validatedPrototypes(recipe, vanillaPrototypes);
            ItemStackFingerprint output = withCount(
                    prototypes.get(recipe.output()), recipe.outputCount());
            int totalOutput = Math.multiplyExact(
                    recipe.outputCount(), batches);
            if (totalOutput > recipe.outputMaxStackSize()) {
                return Optional.empty();
            }
            int outputTarget = emptyPlayerStorageSlot(opened)
                    .orElse(-1);
            if (outputTarget < 0) {
                return Optional.empty();
            }

            List<MenuClickStep> steps = new ArrayList<>();
            MenuSnapshot current = opened;
            for (int batch = 0; batch < batches; batch++) {
                for (Map.Entry<ResourceId, List<P5ARecipe.Ingredient>> entry
                        : ingredientsByItem(recipe).entrySet()) {
                    ResourceId material = entry.getKey();
                    List<P5ARecipe.Ingredient> placements = entry.getValue();
                    int required = totalCount(placements);
                    int source = sourceSlot(
                            current, prototypes.get(material), required)
                            .orElse(-1);
                    if (source < 0) {
                        return Optional.empty();
                    }
                    current = addPickup(
                            steps, current, source, 0,
                            MenuConservationRule.strict());
                    for (P5ARecipe.Ingredient placement : placements) {
                        for (int count = 0;
                                count < placement.count();
                                count++) {
                            current = addOneToCraftingInput(
                                    steps,
                                    current,
                                    placement.slot(),
                                    prototypes,
                                    recipe,
                                    output);
                        }
                    }
                    if (!current.carried().isEmpty()) {
                        current = addPickup(
                                steps, current, source, 0,
                                MenuConservationRule.strict());
                    }
                }
                if (!current.itemAt(0).equals(output)
                        || !gridMatchesRecipe(current, recipe, prototypes)
                        || !current.carried().isEmpty()) {
                    return Optional.empty();
                }
                current = collectCraftingResult(
                        steps, current, recipe, prototypes, output);
                current = placeCraftingOutput(
                        steps, current, outputTarget, output,
                        recipe.outputMaxStackSize());
                if (!current.carried().isEmpty()
                        || !craftingAreaEmpty(current)) {
                    return Optional.empty();
                }
            }
            if (!current.carried().isEmpty()
                    || !current.itemAt(outputTarget).equals(withCount(
                            output, totalOutput))
                    || !craftingAreaEmpty(current)) {
                return Optional.empty();
            }
            return Optional.of(new MenuTransactionPlan(
                    recipe.family(), opened, steps, current));
        } catch (ArithmeticException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    /**
     * 在完整玩家背包域上核对这一个 crafting batch 的输入扣款、产物入账和其他身份不变。
     */
    public static boolean matchesPlayerDelta(
            InventoryContentsSnapshot before,
            InventoryContentsSnapshot after,
            P5ARecipe recipe,
            int batches,
            Map<ResourceId, ItemStackFingerprint> vanillaPrototypes) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        Objects.requireNonNull(recipe, "recipe");
        if (!recipe.isCrafting()) {
            return false;
        }
        if (batches < 1 || batches > recipe.maximumBatches()) {
            return false;
        }
        try {
            Map<ResourceId, ItemStackFingerprint> prototypes =
                    validatedPrototypes(recipe, vanillaPrototypes);
            Map<MenuItemKey, Long> observed = totals(after);
            mergeTotals(observed, before, -1L);
            Map<MenuItemKey, Long> expected = new LinkedHashMap<>();
            for (P5ARecipe.Ingredient ingredient : recipe.ingredients()) {
                MenuItemKey key = MenuItemKey.from(
                        prototypes.get(ingredient.item())).orElseThrow();
                expected.merge(key, Math.multiplyExact(
                        (long) -ingredient.count(), batches),
                        Math::addExact);
            }
            expected.merge(MenuItemKey.from(
                    withCount(prototypes.get(recipe.output()),
                            recipe.outputCount())).orElseThrow(),
                    Math.multiplyExact((long) recipe.outputCount(),
                            batches), Math::addExact);
            observed.entrySet().removeIf(entry -> entry.getValue() == 0L);
            expected.entrySet().removeIf(entry -> entry.getValue() == 0L);
            return observed.equals(expected);
        } catch (ArithmeticException | IllegalArgumentException exception) {
            return false;
        }
    }

    /** 单批兼容入口。 */
    public static boolean matchesPlayerDelta(
            InventoryContentsSnapshot before,
            InventoryContentsSnapshot after,
            P5ARecipe recipe,
            Map<ResourceId, ItemStackFingerprint> vanillaPrototypes) {
        return matchesPlayerDelta(
                before, after, recipe, 1, vanillaPrototypes);
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
                        "recipe prototype is not one default vanilla item");
            }
            result.put(material, prototype);
        }
        return Map.copyOf(result);
    }

    private static Map<ResourceId, List<P5ARecipe.Ingredient>>
            ingredientsByItem(P5ARecipe recipe) {
        Map<ResourceId, List<P5ARecipe.Ingredient>> groups =
                new LinkedHashMap<>();
        for (P5ARecipe.Ingredient ingredient : recipe.ingredients()) {
            groups.computeIfAbsent(ingredient.item(), ignored ->
                    new ArrayList<>()).add(ingredient);
        }
        return groups;
    }

    private static int totalCount(
            List<P5ARecipe.Ingredient> placements) {
        int total = 0;
        for (P5ARecipe.Ingredient placement : placements) {
            total = Math.addExact(total, placement.count());
        }
        return total;
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

    private static boolean craftingAreaEmpty(MenuSnapshot snapshot) {
        if (!snapshot.itemAt(0).isEmpty()) {
            return false;
        }
        for (int slot = 0; slot < snapshot.slots().size(); slot++) {
            if (snapshot.family().roleAt(slot)
                    == MenuSlotRole.CRAFTING_INPUT
                    && !snapshot.itemAt(slot).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static MenuSnapshot addPickup(
            List<MenuClickStep> steps,
            MenuSnapshot before,
            int slot,
            int button,
            MenuConservationRule conservation) {
        ItemStackFingerprint clicked = before.itemAt(slot);
        ItemStackFingerprint carried = before.carried();
        if (button != 0 || (clicked.isEmpty() && carried.isEmpty())) {
            throw new IllegalArgumentException(
                    "recipe plan only emits observable left pickup clicks");
        }
        List<ItemStackFingerprint> slots = new ArrayList<>(before.slots());
        slots.set(slot, carried);
        MenuSnapshot after = next(
                before, clicked, slots);
        add(steps, before, after,
                new MenuClick(slot, MenuClickType.PICKUP, button),
                conservation);
        return after;
    }

    private static MenuSnapshot addOneToCraftingInput(
            List<MenuClickStep> steps,
            MenuSnapshot before,
            int target,
            Map<ResourceId, ItemStackFingerprint> prototypes,
            P5ARecipe recipe,
            ItemStackFingerprint output) {
        if (before.family().roleAt(target)
                        != MenuSlotRole.CRAFTING_INPUT
                || !before.itemAt(target).isEmpty()
                || before.carried().isEmpty()
                || before.carried().count() < 1) {
            throw new IllegalArgumentException(
                    "crafting input click no longer has an empty target and carried item");
        }
        ItemStackFingerprint placed = withCount(before.carried(), 1);
        List<ItemStackFingerprint> slots = new ArrayList<>(before.slots());
        slots.set(target, placed);
        ItemStackFingerprint carried = decrement(before.carried());
        MenuSnapshot shape = next(before, carried, slots);
        boolean completesRecipe = gridMatchesRecipe(shape, recipe, prototypes);
        if (completesRecipe) {
            slots = new ArrayList<>(shape.slots());
            slots.set(0, output);
            shape = next(before, carried, slots);
        }
        MenuConservationRule conservation = completesRecipe
                ? outputPreviewRule(output)
                : MenuConservationRule.strict();
        add(steps, before, shape,
                new MenuClick(target, MenuClickType.PICKUP, 1),
                conservation);
        return shape;
    }

    private static MenuSnapshot collectCraftingResult(
            List<MenuClickStep> steps,
            MenuSnapshot before,
            P5ARecipe recipe,
            Map<ResourceId, ItemStackFingerprint> prototypes,
            ItemStackFingerprint output) {
        if (!before.carried().isEmpty()
                || !before.itemAt(0).equals(output)
                || !gridMatchesRecipe(before, recipe, prototypes)) {
            throw new IllegalArgumentException(
                    "crafting result is not the contracted preview");
        }
        List<ItemStackFingerprint> slots = new ArrayList<>(before.slots());
        slots.set(0, ItemStackFingerprint.empty());
        for (int slot = 0; slot < slots.size(); slot++) {
            if (before.family().roleAt(slot)
                    == MenuSlotRole.CRAFTING_INPUT) {
                slots.set(slot, ItemStackFingerprint.empty());
            }
        }
        MenuSnapshot after = next(before, output, slots);
        add(steps, before, after,
                new MenuClick(0, MenuClickType.PICKUP, 0),
                consumedInputRule(recipe, prototypes));
        return after;
    }

    private static MenuSnapshot placeCraftingOutput(
            List<MenuClickStep> steps,
            MenuSnapshot before,
            int outputTarget,
            ItemStackFingerprint output,
            int maximumStackSize) {
        if (!before.carried().equals(output)
                || !isPlayerStorage(before.family().roleAt(outputTarget))) {
            throw new IllegalArgumentException(
                    "crafted output cursor or target no longer matches the contract");
        }
        ItemStackFingerprint target = before.itemAt(outputTarget);
        if (target.isEmpty()) {
            return addPickup(steps, before, outputTarget, 0,
                    MenuConservationRule.strict());
        }
        if (!target.sameItemAndComponents(output)
                || target.count() > maximumStackSize - output.count()) {
            throw new IllegalArgumentException(
                    "crafted output target cannot merge within its vanilla stack limit");
        }
        List<ItemStackFingerprint> slots = new ArrayList<>(before.slots());
        slots.set(outputTarget, withCount(output,
                Math.addExact(target.count(), output.count())));
        MenuSnapshot after = next(before, ItemStackFingerprint.empty(), slots);
        add(steps, before, after,
                new MenuClick(outputTarget, MenuClickType.PICKUP, 0),
                MenuConservationRule.strict());
        return after;
    }

    private static MenuConservationRule outputPreviewRule(
            ItemStackFingerprint output) {
        return rule(Map.of(
                MenuItemKey.from(output).orElseThrow(),
                (long) output.count()));
    }

    private static MenuConservationRule consumedInputRule(
            P5ARecipe recipe,
            Map<ResourceId, ItemStackFingerprint> prototypes) {
        Map<MenuItemKey, Long> delta = new LinkedHashMap<>();
        for (P5ARecipe.Ingredient ingredient : recipe.ingredients()) {
            MenuItemKey key = MenuItemKey.from(
                    prototypes.get(ingredient.item())).orElseThrow();
            delta.merge(key, (long) -ingredient.count(), Math::addExact);
        }
        return rule(delta);
    }

    private static MenuConservationRule rule(
            Map<MenuItemKey, Long> expectedDelta) {
        return new MenuConservationRule(expectedDelta);
    }

    private static boolean gridMatchesRecipe(
            MenuSnapshot snapshot,
            P5ARecipe recipe,
            Map<ResourceId, ItemStackFingerprint> prototypes) {
        if (snapshot.family() != recipe.family()) {
            return false;
        }
        Map<Integer, P5ARecipe.Ingredient> expected =
                new LinkedHashMap<>();
        for (P5ARecipe.Ingredient ingredient : recipe.ingredients()) {
            expected.put(ingredient.slot(), ingredient);
        }
        for (int slot = 0; slot < snapshot.slots().size(); slot++) {
            if (snapshot.family().roleAt(slot)
                    != MenuSlotRole.CRAFTING_INPUT) {
                continue;
            }
            P5ARecipe.Ingredient ingredient = expected.get(slot);
            ItemStackFingerprint actual = snapshot.itemAt(slot);
            if (ingredient == null) {
                if (!actual.isEmpty()) {
                    return false;
                }
            } else if (!actual.equals(withCount(
                    prototypes.get(ingredient.item()), ingredient.count()))) {
                return false;
            }
        }
        return true;
    }

    private static ItemStackFingerprint decrement(
            ItemStackFingerprint source) {
        if (source.isEmpty() || source.count() < 1) {
            throw new IllegalArgumentException(
                    "cannot decrement empty crafting cursor");
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
                    "recipe stack count is outside the bounded range");
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
                before.family(),
                before.containerId(),
                Math.addExact(before.stateId(), 1),
                carried,
                slots);
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
}
