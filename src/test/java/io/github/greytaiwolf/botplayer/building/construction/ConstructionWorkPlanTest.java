package io.github.greytaiwolf.botplayer.building.construction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.greytaiwolf.botplayer.action.interaction.BlockStateFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import io.github.greytaiwolf.botplayer.building.blueprint.Blueprint;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintCell;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintContentHash;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintMaterialClass;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintOffset;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintPlacementRole;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintReplacePolicy;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ConstructionWorkPlanTest {
    private static final UUID BLUEPRINT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000801");
    private static final UUID OTHER_BLUEPRINT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000802");
    private static final ResourceId STONE = new ResourceId("minecraft:stone");
    private static final ResourceId OAK_PLANKS = new ResourceId("minecraft:oak_planks");

    @Test
    void partitionBindsEveryPackageToExactBlueprintContentAndCoversEachCellOnce() {
        Blueprint blueprint = blueprint(65);

        ConstructionWorkPlan plan = ConstructionWorkPlan.partition(blueprint);

        assertEquals(List.of(33, 32), plan.workPackages().stream()
                .map(workPackage -> workPackage.cells().size())
                .toList());
        assertEquals(List.of(0, 1), plan.workPackages().stream()
                .map(workPackage -> workPackage.key().ordinal())
                .toList());
        for (ConstructionWorkPackage workPackage : plan.workPackages()) {
            assertEquals(BLUEPRINT_ID, workPackage.key().blueprintId());
            assertEquals(blueprint.revision(), workPackage.key().blueprintRevision());
            assertEquals(blueprint.contentHash(), workPackage.key().blueprintContentHash());
        }
        assertTrue(plan.workPackages().getFirst().prerequisites().isEmpty());
        assertEquals(List.of(plan.workPackages().getFirst().key()),
                plan.workPackages().get(1).prerequisites());
        assertEquals(plan.workPackages(), plan.topologicallyOrderedPackages());
        assertEquals(65, plan.workPackages().stream()
                .mapToInt(workPackage -> workPackage.plannedBlockRequirements().totalBlocks())
                .sum());
        assertEquals(65, plan.workPackages().stream()
                .flatMap(workPackage -> workPackage.cells().stream())
                .map(BlueprintCell::offset)
                .distinct()
                .count());
        assertThrows(UnsupportedOperationException.class,
                () -> plan.workPackages().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> plan.workPackages().getFirst().cells().clear());
    }

    @Test
    void partitionKeepsSmallBlueprintAsOnePackageAndBoundsLargePartitions() {
        ConstructionWorkPlan small = ConstructionWorkPlan.partition(blueprint(3));
        ConstructionWorkPlan maximum = ConstructionWorkPlan.partition(blueprint(256));
        List<BlueprintCell> maximumCells = maximum.blueprint().cells();
        ConstructionWorkPlan minimumSizedMaximum = new ConstructionWorkPlan(
                maximum.blueprint(), IntStream.range(0,
                        ConstructionWorkPlan.MAX_WORK_PACKAGES)
                        .mapToObj(ordinal -> packageOf(maximum.blueprint(), ordinal,
                                maximumCells.subList(ordinal * 16, (ordinal + 1) * 16),
                                List.of()))
                        .toList());

        assertEquals(1, small.workPackages().size());
        assertEquals(3, small.workPackages().getFirst().cells().size());
        assertEquals(4, maximum.workPackages().size());
        assertEquals(List.of(64, 64, 64, 64), maximum.workPackages().stream()
                .map(workPackage -> workPackage.cells().size())
                .toList());
        assertTrue(maximum.workPackages().size()
                <= ConstructionWorkPlan.MAX_WORK_PACKAGES);
        assertTrue(maximum.workPackages().stream().allMatch(workPackage ->
                workPackage.cells().size() >= ConstructionWorkPlan.MIN_CELLS_PER_LARGE_PACKAGE
                        && workPackage.cells().size()
                        <= ConstructionWorkPlan.MAX_CELLS_PER_PACKAGE));
        assertEquals(ConstructionWorkPlan.MAX_WORK_PACKAGES,
                minimumSizedMaximum.workPackages().size());
    }

    @Test
    void validatesExactCoverMinimumPackageSizeAndFullImmutableBinding() {
        Blueprint large = blueprint(65);
        List<ConstructionWorkPackage> partition = ConstructionWorkPlan.partition(large)
                .workPackages();
        ConstructionWorkPackage first = partition.getFirst();
        ConstructionWorkPackage second = partition.get(1);

        List<BlueprintCell> duplicateAcrossPackages = new ArrayList<>(second.cells());
        duplicateAcrossPackages.set(duplicateAcrossPackages.size() - 1,
                first.cells().getFirst());
        assertThrows(IllegalArgumentException.class,
                () -> new ConstructionWorkPlan(large, List.of(first,
                        new ConstructionWorkPackage(second.key(), duplicateAcrossPackages,
                                second.prerequisites()))));

        BlueprintCell firstCell = first.cells().getFirst();
        BlueprintCell changedCell = new BlueprintCell(firstCell.offset(),
                new BlockStateFingerprint(STONE, Map.of()), firstCell.role(),
                firstCell.replacePolicy(), firstCell.materialClass());
        List<BlueprintCell> changedContent = new ArrayList<>(first.cells());
        changedContent.set(0, changedCell);
        assertThrows(IllegalArgumentException.class,
                () -> new ConstructionWorkPlan(large, List.of(
                        new ConstructionWorkPackage(first.key(), changedContent,
                                first.prerequisites()), second)));

        ConstructionWorkPackageKey foreignKey = new ConstructionWorkPackageKey(
                OTHER_BLUEPRINT_ID, large.revision(), large.contentHash(), 0);
        assertThrows(IllegalArgumentException.class,
                () -> new ConstructionWorkPlan(large, List.of(
                        new ConstructionWorkPackage(foreignKey, first.cells(),
                                List.of()), second)));
        ConstructionWorkPackageKey staleHash = new ConstructionWorkPackageKey(
                large.blueprintId(), large.revision(), new BlueprintContentHash(
                        "0".repeat(BlueprintContentHash.HEX_LENGTH)), 0);
        assertThrows(IllegalArgumentException.class,
                () -> new ConstructionWorkPlan(large, List.of(
                        new ConstructionWorkPackage(staleHash, first.cells(),
                                List.of()), second)));

        Blueprint small = blueprint(2);
        List<BlueprintCell> smallCells = small.cells();
        assertThrows(IllegalArgumentException.class,
                () -> new ConstructionWorkPlan(small, List.of(
                        packageOf(small, 0, List.of(smallCells.getFirst()), List.of()),
                        packageOf(small, 1, List.of(smallCells.get(1)), List.of()))));

        List<BlueprintCell> cells = large.cells();
        assertThrows(IllegalArgumentException.class,
                () -> new ConstructionWorkPlan(large, List.of(
                        packageOf(large, 0, cells.subList(0, 16), List.of()),
                        packageOf(large, 1, cells.subList(16, 32), List.of()),
                        packageOf(large, 2, cells.subList(32, 48), List.of()),
                        packageOf(large, 3, cells.subList(48, 63), List.of()),
                        packageOf(large, 4, cells.subList(63, 65), List.of()))));
    }

    @Test
    void rejectsUnknownAndCyclicPrerequisitesAndProducesStableTopologicalOrder() {
        Blueprint blueprint = blueprint(80);
        List<BlueprintCell> cells = blueprint.cells();
        List<ConstructionWorkPackageKey> keys = IntStream.range(0, 5)
                .mapToObj(ordinal -> ConstructionWorkPackageKey.forBlueprint(blueprint, ordinal))
                .toList();
        List<ConstructionWorkPackage> graph = List.of(
                new ConstructionWorkPackage(keys.get(0), cells.subList(0, 16),
                        List.of(keys.get(3))),
                new ConstructionWorkPackage(keys.get(1), cells.subList(16, 32), List.of()),
                new ConstructionWorkPackage(keys.get(2), cells.subList(32, 48), List.of()),
                new ConstructionWorkPackage(keys.get(3), cells.subList(48, 64), List.of()),
                new ConstructionWorkPackage(keys.get(4), cells.subList(64, 80),
                        List.of(keys.get(1), keys.get(2))));

        ConstructionWorkPlan plan = new ConstructionWorkPlan(blueprint, List.of(
                graph.get(4), graph.get(2), graph.get(0), graph.get(3), graph.get(1)));
        assertEquals(List.of(1, 2, 3, 0, 4), plan.topologicallyOrderedPackages().stream()
                .map(workPackage -> workPackage.key().ordinal())
                .toList());

        ConstructionWorkPackageKey unknown = ConstructionWorkPackageKey.forBlueprint(blueprint,
                15);
        assertThrows(IllegalArgumentException.class,
                () -> new ConstructionWorkPlan(blueprint, List.of(
                        packageOf(blueprint, 0, cells.subList(0, 16), List.of(unknown)),
                        packageOf(blueprint, 1, cells.subList(16, 32), List.of()),
                        packageOf(blueprint, 2, cells.subList(32, 48), List.of()),
                        packageOf(blueprint, 3, cells.subList(48, 64), List.of()),
                        packageOf(blueprint, 4, cells.subList(64, 80), List.of()))));
        assertThrows(IllegalArgumentException.class,
                () -> new ConstructionWorkPlan(blueprint, List.of(
                        packageOf(blueprint, 0, cells.subList(0, 16), List.of(keys.get(1))),
                        packageOf(blueprint, 1, cells.subList(16, 32), List.of(keys.get(0))),
                        packageOf(blueprint, 2, cells.subList(32, 48), List.of()),
                        packageOf(blueprint, 3, cells.subList(48, 64), List.of()),
                        packageOf(blueprint, 4, cells.subList(64, 80), List.of()))));
    }

    @Test
    void packageRejectsUnboundedDuplicateAndAmbiguousFields() {
        Blueprint blueprint = blueprint(65);
        ConstructionWorkPackageKey key = ConstructionWorkPackageKey.forBlueprint(blueprint, 0);
        List<BlueprintCell> cells = blueprint.cells();

        assertThrows(IllegalArgumentException.class,
                () -> new ConstructionWorkPackage(key, List.of(), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ConstructionWorkPackage(key, List.of(cells.getFirst(),
                        cells.getFirst()), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ConstructionWorkPackage(key, cells, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ConstructionWorkPackage(key, cells.subList(0, 16),
                        List.of(key)));
        ConstructionWorkPackageKey other = ConstructionWorkPackageKey.forBlueprint(blueprint, 1);
        assertThrows(IllegalArgumentException.class,
                () -> new ConstructionWorkPackage(key, cells.subList(0, 16),
                        List.of(other, other)));
        assertThrows(IllegalArgumentException.class,
                () -> new ConstructionWorkPackageKey(BLUEPRINT_ID, 0L,
                        blueprint.contentHash(), 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ConstructionWorkPackageKey(BLUEPRINT_ID, 1L,
                        blueprint.contentHash(), ConstructionWorkPackageKey.MAX_WORK_PACKAGES));
    }

    @Test
    void publicConstructionDtosStayPureAndDoNotExposeWorldOrExecutionAuthority() {
        List<Class<?>> dtoTypes = List.of(
                ConstructionWorkPackageKey.class,
                ConstructionWorkPackage.class,
                ConstructionWorkPlan.class);

        for (Class<?> dtoType : dtoTypes) {
            assertTrue(dtoType.isRecord(), () -> dtoType.getName() + " must remain a record");
            for (RecordComponent component : dtoType.getRecordComponents()) {
                String typeName = component.getGenericType().getTypeName();
                assertFalse(typeName.contains("net.minecraft"),
                        () -> dtoType.getName() + "." + component.getName()
                                + " contains a Minecraft runtime type");
                assertFalse(typeName.contains("ItemStack"),
                        () -> dtoType.getName() + "." + component.getName()
                                + " contains inventory runtime data");
                assertFalse(typeName.contains("BotActionRuntime"),
                        () -> dtoType.getName() + "." + component.getName()
                                + " contains Action execution authority");
                assertFalse(typeName.contains("TechniqueActionPermit"),
                        () -> dtoType.getName() + "." + component.getName()
                                + " contains Technique execution authority");
            }
        }
    }

    private static ConstructionWorkPackage packageOf(Blueprint blueprint, int ordinal,
            List<BlueprintCell> cells, List<ConstructionWorkPackageKey> prerequisites) {
        return new ConstructionWorkPackage(ConstructionWorkPackageKey.forBlueprint(blueprint,
                ordinal), cells, prerequisites);
    }

    private static Blueprint blueprint(int cellCount) {
        return new Blueprint(BLUEPRINT_ID, Blueprint.CURRENT_SCHEMA_VERSION, 7L,
                IntStream.range(0, cellCount)
                        .mapToObj(ConstructionWorkPlanTest::cell)
                        .toList());
    }

    private static BlueprintCell cell(int index) {
        ResourceId blockId = index % 3 == 0 ? OAK_PLANKS : STONE;
        return new BlueprintCell(new BlueprintOffset(index % 16, index / 16, 0),
                new BlockStateFingerprint(blockId, Map.of()),
                index % 3 == 0 ? BlueprintPlacementRole.STRUCTURE
                        : BlueprintPlacementRole.FOUNDATION,
                BlueprintReplacePolicy.PRESERVE_EXISTING,
                index % 5 == 0 ? BlueprintMaterialClass.TEMPORARY
                        : BlueprintMaterialClass.PERMANENT);
    }
}
