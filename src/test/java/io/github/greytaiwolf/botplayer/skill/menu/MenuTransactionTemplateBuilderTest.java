package io.github.greytaiwolf.botplayer.skill.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MenuTransactionTemplateBuilderTest {
    private static final String DIGEST_A =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String DIGEST_B =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @Test
    void movesWholeStackToEmptyTargetWithTwoStrictClicks() {
        MenuSnapshot snapshot = snapshot(7, 18, 10, item("stone", 4, DIGEST_A),
                ItemStackFingerprint.empty());

        MenuTransactionTemplate template = MenuTransactionTemplateBuilder
                .moveOrSwap(snapshot, 10, 11)
                .orElseThrow();

        assertEquals(2, template.orderedSteps().size());
        assertTrue(template.initialLayout().carried().isEmpty());
        assertTrue(template.finalLayout().slots().get(10).isEmpty());
        assertEquals(item("stone", 4, DIGEST_A),
                template.finalLayout().slots().get(11));
        assertTrue(template.finalLayout().carried().isEmpty());
    }

    @Test
    void swapsWholeStacksWithThreeStrictClicks() {
        MenuSnapshot snapshot = snapshot(7, 18, 10, item("stone", 4, DIGEST_A),
                item("dirt", 2, DIGEST_B));

        MenuTransactionTemplate template = MenuTransactionTemplateBuilder
                .moveOrSwap(snapshot, 10, 11)
                .orElseThrow();

        assertEquals(3, template.orderedSteps().size());
        assertEquals(item("dirt", 2, DIGEST_B),
                template.finalLayout().slots().get(10));
        assertEquals(item("stone", 4, DIGEST_A),
                template.finalLayout().slots().get(11));
        assertTrue(template.finalLayout().carried().isEmpty());
    }

    @Test
    void movesRequestedAmountWithStrictRightClickPrefixes() {
        MenuSnapshot snapshot = snapshot(7, 18, 10, item("stone", 16, DIGEST_A),
                ItemStackFingerprint.empty());

        MenuTransactionTemplate template = MenuTransactionTemplateBuilder
                .moveExactAmount(snapshot, 10, 11, 5)
                .orElseThrow();

        assertEquals(7, template.orderedSteps().size());
        assertEquals(0, template.orderedSteps().get(0).click().button());
        assertEquals(item("stone", 16, DIGEST_A),
                template.orderedSteps().get(0).expectedAfter().carried());
        for (int index = 1; index <= 5; index++) {
            MenuTemplateStep step = template.orderedSteps().get(index);
            assertEquals(MenuClickType.PICKUP, step.click().type());
            assertEquals(1, step.click().button());
            assertEquals(item("stone", index, DIGEST_A),
                    step.expectedAfter().slots().get(11));
            assertEquals(item("stone", 16 - index, DIGEST_A),
                    step.expectedAfter().carried());
        }
        MenuTemplateStep settle = template.orderedSteps().get(6);
        assertEquals(0, settle.click().button());
        assertEquals(item("stone", 11, DIGEST_A),
                template.finalLayout().slots().get(10));
        assertEquals(item("stone", 5, DIGEST_A),
                template.finalLayout().slots().get(11));
        assertTrue(template.finalLayout().carried().isEmpty());
    }

    @Test
    void exactAmountRejectsAFullTargetAndInsufficientSource() {
        MenuSnapshot base = snapshot(7, 18, 10, item("stone", 4, DIGEST_A),
                ItemStackFingerprint.empty());

        assertFalse(MenuTransactionTemplateBuilder.moveExactAmount(
                base, 10, 11, 5).isPresent());
        assertFalse(MenuTransactionTemplateBuilder.moveExactAmount(
                snapshot(7, 18, 10, item("stone", 4, DIGEST_A),
                        item("dirt", 1, DIGEST_B)),
                10, 11, 1).isPresent());
        assertFalse(MenuTransactionTemplateBuilder.moveExactAmount(
                base, 10, 11, 0).isPresent());
    }

    @Test
    void exactAmountUsesWholeStackPathWhenAmountMatchesSource() {
        MenuSnapshot snapshot = snapshot(7, 18, 10, item("stone", 4, DIGEST_A),
                ItemStackFingerprint.empty());

        MenuTransactionTemplate template = MenuTransactionTemplateBuilder
                .moveExactAmount(snapshot, 10, 11, 4)
                .orElseThrow();

        assertEquals(2, template.orderedSteps().size());
        assertTrue(template.finalLayout().slots().get(10).isEmpty());
        assertEquals(item("stone", 4, DIGEST_A),
                template.finalLayout().slots().get(11));
    }

    @Test
    void bindsDoubleChestBoundarySlotsToTheSameStrictTemplate() {
        MenuSnapshot snapshot = snapshot(
                MenuFamily.CHEST_6X9,
                7,
                18,
                53,
                54,
                item("stone", 16, DIGEST_A),
                ItemStackFingerprint.empty());

        MenuTransactionTemplate template = MenuTransactionTemplateBuilder
                .moveExactAmount(snapshot, 53, 54, 5)
                .orElseThrow();

        assertEquals(MenuFamily.CHEST_6X9, template.family());
        assertEquals(7, template.orderedSteps().size());
        assertEquals(item("stone", 11, DIGEST_A),
                template.finalLayout().slots().get(53));
        assertEquals(item("stone", 5, DIGEST_A),
                template.finalLayout().slots().get(54));
        assertTrue(template.finalLayout().carried().isEmpty());
    }

    @Test
    void rejectsNonEmptyCursorEmptySourceAndIdenticalSlots() {
        MenuSnapshot base = snapshot(7, 18, 10, item("stone", 4, DIGEST_A),
                ItemStackFingerprint.empty());
        assertFalse(MenuTransactionTemplateBuilder.moveOrSwap(
                base, 10, 10).isPresent());
        assertFalse(MenuTransactionTemplateBuilder.moveOrSwap(
                snapshot(7, 18, 10, ItemStackFingerprint.empty(),
                        ItemStackFingerprint.empty()), 10, 11).isPresent());
        MenuSnapshot cursor = new MenuSnapshot(
                base.family(), base.containerId(), base.stateId(),
                item("dirt", 1, DIGEST_B), base.slots());
        assertFalse(MenuTransactionTemplateBuilder.moveOrSwap(
                cursor, 10, 11).isPresent());
    }

    private static MenuSnapshot snapshot(
            int containerId,
            int stateId,
            int source,
            ItemStackFingerprint sourceItem,
            ItemStackFingerprint targetItem) {
        return snapshot(
                MenuFamily.INVENTORY_2X2,
                containerId,
                stateId,
                source,
                source + 1,
                sourceItem,
                targetItem);
    }

    private static MenuSnapshot snapshot(
            MenuFamily family,
            int containerId,
            int stateId,
            int source,
            int target,
            ItemStackFingerprint sourceItem,
            ItemStackFingerprint targetItem) {
        List<ItemStackFingerprint> slots = new ArrayList<>();
        for (int index = 0; index < family.slotCount(); index++) {
            slots.add(ItemStackFingerprint.empty());
        }
        slots.set(source, sourceItem);
        slots.set(target, targetItem);
        return new MenuSnapshot(family,
                containerId, stateId, ItemStackFingerprint.empty(), slots);
    }

    private static ItemStackFingerprint item(
            String path, int count, String digest) {
        return ItemStackFingerprint.of(
                new ResourceId("minecraft:" + path), count, 0, digest);
    }
}
