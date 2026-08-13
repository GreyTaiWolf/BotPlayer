package io.github.greytaiwolf.botplayer.skill.menu;

import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class MenuTransactionTemplateTest {
    @Test
    void bindsOnlyTheExactOpenedLayoutAndUsesItsWindowIdentity() {
        MenuLayout before = layout(1, 0);
        MenuLayout after = layout(0, 1);
        MenuTransactionTemplate template = new MenuTransactionTemplate(
                MenuFamily.INVENTORY_2X2,
                before,
                List.of(new MenuTemplateStep(
                        new MenuClick(9, MenuClickType.PICKUP, 0),
                        before,
                        after,
                        MenuConservationRule.strict())),
                after);
        MenuSnapshot opened = before.bind(17, 9);

        MenuTransactionPlan bound = template.bind(opened).orElseThrow();
        Assertions.assertAll(
                () -> Assertions.assertEquals(17,
                        bound.initialSnapshot().containerId()),
                () -> Assertions.assertEquals(9,
                        bound.initialSnapshot().stateId()),
                () -> Assertions.assertEquals(10,
                        bound.finalSnapshot().stateId()),
                () -> Assertions.assertTrue(template.bind(
                        layout(2, 0).bind(17, 9)).isEmpty()));
    }

    private static MenuLayout layout(int firstCount, int secondCount) {
        List<ItemStackFingerprint> slots = new java.util.ArrayList<>();
        for (int index = 0;
                index < MenuFamily.INVENTORY_2X2.slotCount();
                index++) {
            slots.add(index == 9
                    ? fingerprint(firstCount)
                    : index == 10
                            ? fingerprint(secondCount)
                            : ItemStackFingerprint.empty());
        }
        return new MenuLayout(
                MenuFamily.INVENTORY_2X2,
                ItemStackFingerprint.empty(),
                slots);
    }

    private static ItemStackFingerprint fingerprint(int count) {
        return count == 0
                ? ItemStackFingerprint.empty()
                : ItemStackFingerprint.of(
                        new io.github.greytaiwolf.botplayer
                                .action.interaction.ResourceId(
                                        "minecraft:stone"),
                        count,
                        0,
                        "0000000000000000000000000000000000000000000000000000000000000000");
    }
}
