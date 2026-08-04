package io.github.greytaiwolf.botplayer.action.interaction.menu;

import io.github.greytaiwolf.botplayer.action.ActionChannel;
import io.github.greytaiwolf.botplayer.action.ActionKind;
import io.github.greytaiwolf.botplayer.action.WorldInteractionAction;
import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import io.github.greytaiwolf.botplayer.action.interaction.WorldInteractionActionSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class InventoryMenuWorldActionSpecTest {
    @Test
    void actionOwnsInventoryAndBothHandsForTheWholePlan() {
        List<ItemStackFingerprint> slots = new ArrayList<>(41);
        for (int index = 0; index < 41; index++) {
            slots.add(ItemStackFingerprint.empty());
        }
        slots.set(10, ItemStackFingerprint.of(
                new ResourceId("minecraft:diamond_helmet"),
                1,
                0,
                "1".repeat(64)));
        slots.set(39, ItemStackFingerprint.of(
                new ResourceId("minecraft:iron_helmet"),
                1,
                0,
                "2".repeat(64)));
        slots.set(3, ItemStackFingerprint.of(
                new ResourceId("minecraft:torch"),
                16,
                0,
                "3".repeat(64)));
        InventoryMenuSwapPlan plan =
                InventoryMenuSwapPlanBuilder.mainToEquipment(
                        new InventoryMenuSnapshot(
                                0,
                                3,
                                0,
                                ItemStackFingerprint.empty(),
                                slots),
                        10,
                        39,
                        3);
        WorldInteractionActionSpec.InventoryMenuSwap spec =
                new WorldInteractionActionSpec.InventoryMenuSwap(plan);

        Assertions.assertEquals(
                WorldInteractionActionSpec.Kind.INVENTORY_MENU_SWAP,
                spec.kind());
        Assertions.assertEquals(
                ActionKind.INVENTORY_MENU_SWAP,
                new WorldInteractionAction(spec).kind());
        Assertions.assertEquals(
                Set.of(
                        ActionChannel.INVENTORY,
                        ActionChannel.MAIN_HAND,
                        ActionChannel.OFF_HAND),
                spec.channels());
        Assertions.assertSame(plan, spec.plan());
        Assertions.assertEquals(
                3, spec.plan().orderedSteps().size());
        Assertions.assertThrows(
                NullPointerException.class,
                () -> new WorldInteractionActionSpec
                        .InventoryMenuSwap(null));
    }
}
