package io.github.greytaiwolf.botplayer.skill.task;

import io.github.greytaiwolf.botplayer.action.interaction.menu.FurnaceKind;
import java.util.HashSet;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.BlastFurnaceMenu;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.inventory.SmokerMenu;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TaskSensorResourceFilterTest {
    private static final TaskSensorRunIdentity IDENTITY =
            new TaskSensorRunIdentity(new UUID(3L, 1L), 1L,
                    new UUID(3L, 2L), 1L);
    private static final TaskSensorScope SCOPE = new TaskSensorScope(
            "minecraft:overworld", 0, 64, 0, 8, 1L);
    private static final TaskSensorBudget RESOURCE_BUDGET =
            new TaskSensorBudget(24, 0, 256, 0, 24, 0L);

    @Test
    void exactP5AFilterRejectsOrdinaryStone() {
        TaskSensorResourceFilter filter = TaskSensorResourceFilter.OAK_LOG;

        Assertions.assertAll(
                () -> Assertions.assertFalse(filter.accepts("minecraft:stone")),
                () -> Assertions.assertFalse(filter.accepts("minecraft:oak_wood")),
                () -> Assertions.assertTrue(filter.accepts("minecraft:oak_log")));
    }

    @Test
    void taskSensorDoesNotBroadenFurnaceEvidenceToAbstractMenuSubclasses() {
        Assertions.assertAll(
                () -> Assertions.assertEquals(FurnaceKind.FURNACE,
                        MinecraftTaskSensorAdapter.exactVanillaFurnaceKind(
                                FurnaceMenu.class).orElseThrow()),
                () -> Assertions.assertEquals(FurnaceKind.BLAST_FURNACE,
                        MinecraftTaskSensorAdapter.exactVanillaFurnaceKind(
                                BlastFurnaceMenu.class).orElseThrow()),
                () -> Assertions.assertEquals(FurnaceKind.SMOKER,
                        MinecraftTaskSensorAdapter.exactVanillaFurnaceKind(
                                SmokerMenu.class).orElseThrow()),
                () -> Assertions.assertTrue(MinecraftTaskSensorAdapter
                        .exactVanillaFurnaceKind(
                                AbstractContainerMenu.class).isEmpty()),
                () -> Assertions.assertTrue(MinecraftTaskSensorAdapter
                        .exactVanillaFurnaceKind(
                                AbstractFurnaceMenu.class).isEmpty()));
    }

    @Test
    void legacyQueryRemainsUnfilteredAndFilteredQueriesAreScoped() {
        TaskSensorQuery legacy = new TaskSensorQuery(
                IDENTITY,
                TaskSensorQueryType.RESOURCE_CANDIDATES,
                SCOPE,
                RESOURCE_BUDGET);
        TaskSensorQuery filtered = new TaskSensorQuery(
                IDENTITY,
                TaskSensorQueryType.RESOURCE_CANDIDATES,
                SCOPE,
                RESOURCE_BUDGET,
                TaskSensorResourceFilter.IRON_ORE);

        Assertions.assertAll(
                () -> Assertions.assertEquals(
                        TaskSensorResourceFilter.UNFILTERED,
                        legacy.resourceFilter()),
                () -> Assertions.assertNotEquals(legacy, filtered),
                () -> Assertions.assertTrue(TaskSensorResourceFilter.UNFILTERED
                        .accepts("minecraft:stone")),
                () -> Assertions.assertThrows(IllegalArgumentException.class,
                        () -> new TaskSensorQuery(
                                IDENTITY,
                                TaskSensorQueryType.SELF_INVENTORY,
                                SCOPE,
                                new TaskSensorBudget(0, 1, 0, 0, 1, 0L),
                                TaskSensorResourceFilter.IRON_ORE)));
    }

    @Test
    void exactFilteredScanPrioritizesSameLayerCandidatesBeyondThe3dPrefix() {
        MinecraftTaskSensorAdapter.ResourceScanPlan plan =
                MinecraftTaskSensorAdapter.resourceScanPlan(
                        SCOPE,
                        RESOURCE_BUDGET.maximumBlocks(),
                        TaskSensorResourceFilter.COAL_ORE);
        BlockPos center = new BlockPos(SCOPE.centerX(), SCOPE.centerY(),
                SCOPE.centerZ());

        Assertions.assertAll(
                () -> Assertions.assertEquals(256, plan.positions().size()),
                () -> Assertions.assertEquals(plan.positions().size(),
                        new HashSet<>(plan.positions()).size(),
                        "one bounded resource scan must never revisit a block"),
                () -> Assertions.assertTrue(plan.truncatedByBudget()),
                () -> Assertions.assertTrue(plan.positions().contains(
                        center.offset(5, 0, 5)),
                        "the reviewed 256-read cap must still cover a same-layer "
                                + "resource at horizontal Chebyshev distance five"),
                () -> Assertions.assertTrue(plan.positions().contains(
                        center.offset(0, 2, 0)),
                        "the complete nearby 3-D cube remains covered before "
                                + "the scan prioritizes the current body layer"));
    }

    @Test
    void exactFilteredScanPrioritizesTheTargetLayerBelowAResourceTop() {
        MinecraftTaskSensorAdapter.ResourceScanPlan plan =
                MinecraftTaskSensorAdapter
                        .resourceScanPlanPrioritizingLayerBelow(
                                SCOPE,
                                RESOURCE_BUDGET.maximumBlocks(),
                                TaskSensorResourceFilter.COBBLESTONE);
        BlockPos center = new BlockPos(SCOPE.centerX(), SCOPE.centerY(),
                SCOPE.centerZ());

        Assertions.assertAll(
                () -> Assertions.assertEquals(256, plan.positions().size()),
                () -> Assertions.assertEquals(plan.positions().size(),
                        new HashSet<>(plan.positions()).size(),
                        "a below-layer priority plan must not revisit a block"),
                () -> Assertions.assertTrue(plan.positions().contains(
                        center.offset(5, -1, 5)),
                        "a Bot standing on the exact resource must still observe "
                                + "the target layer through horizontal distance five"),
                () -> Assertions.assertTrue(plan.positions().stream().allMatch(
                        position -> Math.abs(position.getX() - center.getX())
                                        <= SCOPE.radius()
                                && Math.abs(position.getY() - center.getY())
                                        <= SCOPE.radius()
                                && Math.abs(position.getZ() - center.getZ())
                                        <= SCOPE.radius()),
                        "the lower-layer priority must remain inside the declared scope"),
                () -> Assertions.assertTrue(MinecraftTaskSensorAdapter
                        .shouldPrioritizeLayerBelow(
                                TaskSensorResourceFilter.COBBLESTONE,
                                "minecraft:cobblestone")),
                () -> Assertions.assertFalse(MinecraftTaskSensorAdapter
                        .shouldPrioritizeLayerBelow(
                                TaskSensorResourceFilter.COBBLESTONE,
                                "minecraft:iron_ore")),
                () -> Assertions.assertFalse(MinecraftTaskSensorAdapter
                        .shouldPrioritizeLayerBelow(
                                TaskSensorResourceFilter.CRAFTING_TABLE,
                                "minecraft:crafting_table")),
                () -> Assertions.assertFalse(MinecraftTaskSensorAdapter
                        .shouldPrioritizeLayerBelow(
                                TaskSensorResourceFilter.UNFILTERED,
                                "minecraft:cobblestone")));
    }

    @Test
    void unfilteredResourceScanRetainsTheHistoricalThreeDimensionalPrefix() {
        MinecraftTaskSensorAdapter.ResourceScanPlan plan =
                MinecraftTaskSensorAdapter.resourceScanPlan(
                        SCOPE,
                        RESOURCE_BUDGET.maximumBlocks(),
                        TaskSensorResourceFilter.UNFILTERED);
        BlockPos center = new BlockPos(SCOPE.centerX(), SCOPE.centerY(),
                SCOPE.centerZ());

        Assertions.assertEquals(
                java.util.List.of(center, center.offset(-1, -1, -1)),
                plan.positions().subList(0, 2));
    }
}
