package io.github.greytaiwolf.botplayer.action.interaction;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class InventoryContentsSnapshotTest {
    private static final String COMPONENTS =
            "0000000000000000000000000000000000000000000000000000000000000000";
    private static final String OTHER_COMPONENTS =
            "1111111111111111111111111111111111111111111111111111111111111111";

    @Test
    void combinesSplitStacksIndependentlyOfSlotOrder() {
        ItemStackFingerprint one = item(
                1, COMPONENTS);
        ItemStackFingerprint two = item(
                2, COMPONENTS);

        InventoryContentsSnapshot first =
                new InventoryContentsSnapshot(
                        List.of(
                                one,
                                ItemStackFingerprint.empty(),
                                two));
        InventoryContentsSnapshot second =
                new InventoryContentsSnapshot(
                        List.of(two, one));

        Assertions.assertEquals(first, second);
        Assertions.assertEquals(
                3, first.matchingCount(one));
        Assertions.assertEquals(
                1, first.itemTotals().size());
    }

    @Test
    void keepsDifferentComponentIdentitiesSeparate() {
        ItemStackFingerprint normal = item(
                2, COMPONENTS);
        ItemStackFingerprint changed = item(
                3, OTHER_COMPONENTS);
        InventoryContentsSnapshot snapshot =
                new InventoryContentsSnapshot(
                        List.of(normal, changed));

        Assertions.assertEquals(
                2, snapshot.matchingCount(normal));
        Assertions.assertEquals(
                3, snapshot.matchingCount(changed));
        Assertions.assertEquals(
                2, snapshot.itemTotals().size());
    }

    @Test
    void rejectsMoreThanTheBoundedPlayerInventory() {
        List<ItemStackFingerprint> stacks =
                new ArrayList<>();
        for (int index = 0;
                index
                        <= InventoryContentsSnapshot
                                .MAX_INPUT_STACKS;
                index++) {
            stacks.add(ItemStackFingerprint.empty());
        }

        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new InventoryContentsSnapshot(
                        stacks));
    }

    private static ItemStackFingerprint item(
            int count, String components) {
        return ItemStackFingerprint.of(
                new ResourceId("minecraft:melon_slice"),
                count,
                0,
                components);
    }
}
