package io.github.greytaiwolf.botplayer.skill.menu;

import io.github.greytaiwolf.botplayer.action.ActionKind;
import io.github.greytaiwolf.botplayer.action.WorldInteractionAction;
import io.github.greytaiwolf.botplayer.action.interaction.InventoryContentsSnapshot;
import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import io.github.greytaiwolf.botplayer.action.interaction.WorldInteractionActionSpec;
import io.github.greytaiwolf.botplayer.action.interaction.menu.P5ARecipe;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class P5ARecipeMenuPlanBuilderTest {
    @Test
    void buildsExactInventoryTwoByTwoCraftingPlanWithPreviewAndConsumption() {
        P5ARecipe recipe = P5ARecipe.OAK_PLANKS_TO_STICKS;
        Map<ResourceId, ItemStackFingerprint> prototypes = prototypes(recipe);
        List<ItemStackFingerprint> slots = emptySlots(recipe.family());
        slots.set(9, stack("minecraft:oak_planks", 2, 'a'));
        MenuSnapshot opened = snapshot(recipe.family(), 7, 11, slots);

        MenuTransactionPlan plan = P5ACraftingMenuPlanBuilder.build(
                opened, recipe, prototypes).orElseThrow();

        Assertions.assertAll(
                () -> Assertions.assertEquals(5,
                        plan.orderedSteps().size()),
                () -> Assertions.assertEquals(ItemStackFingerprint.empty(),
                        plan.finalSnapshot().carried()),
                () -> Assertions.assertEquals(ItemStackFingerprint.empty(),
                        plan.finalSnapshot().itemAt(0)),
                () -> Assertions.assertEquals(ItemStackFingerprint.empty(),
                        plan.finalSnapshot().itemAt(1)),
                () -> Assertions.assertEquals(ItemStackFingerprint.empty(),
                        plan.finalSnapshot().itemAt(3)),
                () -> Assertions.assertEquals(stack("minecraft:stick", 4, 'c'),
                        plan.finalSnapshot().itemAt(10)),
                () -> Assertions.assertEquals(ItemStackFingerprint.empty(),
                        plan.finalSnapshot().itemAt(9)));
    }

    @Test
    void rejectsCustomComponentSourceInsteadOfCopyingItIntoRecipe() {
        P5ARecipe recipe = P5ARecipe.OAK_PLANKS_TO_STICKS;
        Map<ResourceId, ItemStackFingerprint> prototypes = prototypes(recipe);
        List<ItemStackFingerprint> slots = emptySlots(recipe.family());
        slots.set(9, stack("minecraft:oak_planks", 2, 'f'));
        MenuSnapshot opened = snapshot(recipe.family(), 7, 11, slots);

        Assertions.assertTrue(P5ACraftingMenuPlanBuilder.build(
                opened, recipe, prototypes).isEmpty());
    }

    @Test
    void buildsTheCanonicalFourBatchPlankPlanAndExactAggregateDelta() {
        P5ARecipe recipe = P5ARecipe.OAK_LOG_TO_PLANKS;
        Map<ResourceId, ItemStackFingerprint> prototypes = prototypes(recipe);
        List<ItemStackFingerprint> slots = emptySlots(recipe.family());
        slots.set(9, stack("minecraft:oak_log", 4, 'e'));
        MenuSnapshot opened = snapshot(recipe.family(), 7, 11, slots);

        MenuTransactionPlan plan = P5ACraftingMenuPlanBuilder.build(
                opened, recipe, 4, prototypes).orElseThrow();
        List<ItemStackFingerprint> beforeInventory = inventorySlots();
        beforeInventory.set(0, stack("minecraft:oak_log", 4, 'e'));
        List<ItemStackFingerprint> afterInventory = inventorySlots();
        afterInventory.set(0, stack("minecraft:oak_planks", 16, 'a'));

        Assertions.assertAll(
                () -> Assertions.assertEquals(19,
                        plan.orderedSteps().size()),
                () -> Assertions.assertEquals(stack("minecraft:oak_planks", 16, 'a'),
                        plan.finalSnapshot().itemAt(10)),
                () -> Assertions.assertTrue(
                        P5ACraftingMenuPlanBuilder.matchesPlayerDelta(
                                new InventoryContentsSnapshot(beforeInventory),
                                new InventoryContentsSnapshot(afterInventory),
                                recipe,
                                4,
                                prototypes)),
                () -> Assertions.assertTrue(P5ACraftingMenuPlanBuilder.build(
                        opened, recipe, 5, prototypes).isEmpty()));
    }

    @Test
    void furnaceDepositWaitAndCollectionUseBoundedSeparateNativeMenus() {
        P5ARecipe recipe = P5ARecipe.RAW_IRON_TO_IRON_INGOTS;
        Map<ResourceId, ItemStackFingerprint> prototypes = prototypes(recipe);
        List<ItemStackFingerprint> slots = emptySlots(MenuFamily.FURNACE);
        slots.set(3, stack("minecraft:raw_iron", 3, 'a'));
        slots.set(4, stack("minecraft:coal", 1, 'b'));
        MenuSnapshot opened = snapshot(MenuFamily.FURNACE, 12, 30, slots);

        P5AFurnaceMenuPlanBuilder.Deposit deposit =
                P5AFurnaceMenuPlanBuilder.deposit(
                        opened, recipe, prototypes).orElseThrow();
        MenuSnapshot afterDeposit = deposit.plan().finalSnapshot();
        List<ItemStackFingerprint> smeltedSlots = new ArrayList<>(
                afterDeposit.slots());
        smeltedSlots.set(0, ItemStackFingerprint.empty());
        smeltedSlots.set(1, ItemStackFingerprint.empty());
        smeltedSlots.set(2, stack("minecraft:iron_ingot", 3, 'c'));
        MenuSnapshot reopenedReady = snapshot(MenuFamily.FURNACE,
                13, 4, smeltedSlots);

        MenuTransactionPlan collection = P5AFurnaceMenuPlanBuilder.collect(
                reopenedReady, deposit.expectation()).orElseThrow();

        Assertions.assertAll(
                () -> Assertions.assertEquals(6,
                        deposit.plan().orderedSteps().size()),
                () -> Assertions.assertTrue(deposit.expectation()
                        .readyToCollect(reopenedReady)),
                () -> Assertions.assertEquals(2,
                        collection.orderedSteps().size()),
                () -> Assertions.assertEquals(stack("minecraft:iron_ingot", 3, 'c'),
                        collection.finalSnapshot().itemAt(5)),
                () -> Assertions.assertEquals(ItemStackFingerprint.empty(),
                        collection.finalSnapshot().itemAt(2)));
    }

    @Test
    void playerDeltaRejectsUnrelatedInventoryMutation() {
        P5ARecipe recipe = P5ARecipe.OAK_PLANKS_TO_STICKS;
        Map<ResourceId, ItemStackFingerprint> prototypes = prototypes(recipe);
        List<ItemStackFingerprint> beforeSlots = inventorySlots();
        beforeSlots.set(0, stack("minecraft:oak_planks", 2, 'a'));
        beforeSlots.set(1, stack("minecraft:diamond", 1, 'd'));
        List<ItemStackFingerprint> expectedAfterSlots = inventorySlots();
        expectedAfterSlots.set(0, stack("minecraft:stick", 4, 'c'));
        expectedAfterSlots.set(1, stack("minecraft:diamond", 1, 'd'));
        List<ItemStackFingerprint> driftedAfterSlots = new ArrayList<>(
                expectedAfterSlots);
        driftedAfterSlots.set(1, stack("minecraft:diamond", 2, 'd'));

        Assertions.assertAll(
                () -> Assertions.assertTrue(
                        P5ACraftingMenuPlanBuilder.matchesPlayerDelta(
                                new InventoryContentsSnapshot(beforeSlots),
                                new InventoryContentsSnapshot(
                                        expectedAfterSlots),
                                recipe,
                                prototypes)),
                () -> Assertions.assertFalse(
                        P5ACraftingMenuPlanBuilder.matchesPlayerDelta(
                                new InventoryContentsSnapshot(beforeSlots),
                                new InventoryContentsSnapshot(
                                        driftedAfterSlots),
                                recipe,
                                prototypes)));
    }

    @Test
    void actionSpecRequiresTheExactMenuFamilyAndFurnaceTimeBudget() {
        WorldInteractionActionSpec.WorldMenuRecipe inventoryRecipe =
                new WorldInteractionActionSpec.WorldMenuRecipe(
                        WorldInteractionActionSpec.Hand.MAIN_HAND,
                        Optional.empty(),
                        ItemStackFingerprint.empty(),
                        P5ARecipe.OAK_LOG_TO_PLANKS,
                        1,
                        MenuTransactionLimits.defaults());

        Assertions.assertAll(
                () -> Assertions.assertEquals(
                        WorldInteractionActionSpec.Kind.WORLD_MENU_RECIPE,
                        inventoryRecipe.kind()),
                () -> Assertions.assertEquals(ActionKind.WORLD_MENU_RECIPE,
                        new WorldInteractionAction(inventoryRecipe).kind()),
                () -> Assertions.assertThrows(IllegalArgumentException.class,
                        () -> new WorldInteractionActionSpec.WorldMenuRecipe(
                                WorldInteractionActionSpec.Hand.MAIN_HAND,
                                Optional.empty(),
                                ItemStackFingerprint.empty(),
                                P5ARecipe.RAW_IRON_TO_IRON_INGOTS,
                                1,
                                MenuTransactionLimits.defaults())));
    }

    private static Map<ResourceId, ItemStackFingerprint> prototypes(
            P5ARecipe recipe) {
        Map<ResourceId, ItemStackFingerprint> result =
                new LinkedHashMap<>();
        for (ResourceId material : recipe.materialIds()) {
            char digest = switch (material.value()) {
                case "minecraft:oak_planks",
                        "minecraft:raw_iron" -> 'a';
                case "minecraft:coal" -> 'b';
                case "minecraft:stick",
                        "minecraft:iron_ingot" -> 'c';
                default -> 'e';
            };
            result.put(material, stack(material.value(), 1, digest));
        }
        return Map.copyOf(result);
    }

    private static MenuSnapshot snapshot(
            MenuFamily family,
            int containerId,
            int stateId,
            List<ItemStackFingerprint> slots) {
        return new MenuSnapshot(family, containerId, stateId,
                ItemStackFingerprint.empty(), slots);
    }

    private static List<ItemStackFingerprint> emptySlots(MenuFamily family) {
        List<ItemStackFingerprint> slots = new ArrayList<>();
        for (int slot = 0; slot < family.slotCount(); slot++) {
            slots.add(ItemStackFingerprint.empty());
        }
        return slots;
    }

    private static List<ItemStackFingerprint> inventorySlots() {
        List<ItemStackFingerprint> slots = new ArrayList<>();
        for (int slot = 0;
                slot < InventoryContentsSnapshot.MAX_INPUT_STACKS;
                slot++) {
            slots.add(ItemStackFingerprint.empty());
        }
        return slots;
    }

    private static ItemStackFingerprint stack(
            String itemId, int count, char digestCharacter) {
        return ItemStackFingerprint.of(new ResourceId(itemId), count, 0,
                String.valueOf(digestCharacter).repeat(64));
    }
}
