package io.github.greytaiwolf.botplayer.building.material;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.greytaiwolf.botplayer.action.interaction.BlockStateFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import io.github.greytaiwolf.botplayer.building.blueprint.Blueprint;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintCell;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintMaterialClass;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintOffset;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintPlacementRole;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintReplacePolicy;
import io.github.greytaiwolf.botplayer.building.construction.ConstructionWorkPackageKey;
import io.github.greytaiwolf.botplayer.building.construction.ConstructionWorkPlan;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ConstructionWorkPackageMaterialDemandTest {
    private static final UUID BLUEPRINT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000a81");
    private static final UUID OTHER_BLUEPRINT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000a82");
    private static final ResourceId STONE = new ResourceId("minecraft:stone");
    private static final ResourceId OAK_STAIRS = new ResourceId("minecraft:oak_stairs");
    private static final BlockStateFingerprint STONE_STATE = new BlockStateFingerprint(STONE, Map.of());
    private static final BlockStateFingerprint OAK_STAIRS_NORTH = new BlockStateFingerprint(
            OAK_STAIRS, Map.of(
                    "facing", "north",
                    "half", "bottom",
                    "shape", "straight",
                    "waterlogged", "false"));

    @Test
    void derivesCanonicalPackageDemandAndKeepsMaterialClassesSeparate() {
        Blueprint blueprint = new Blueprint(BLUEPRINT_ID, Blueprint.CURRENT_SCHEMA_VERSION, 1L,
                List.of(
                        cell(new BlueprintOffset(2, 0, 0), STONE_STATE,
                                BlueprintMaterialClass.PERMANENT),
                        cell(new BlueprintOffset(-1, 0, 0), STONE_STATE,
                                BlueprintMaterialClass.PERMANENT),
                        cell(new BlueprintOffset(0, 0, 0), OAK_STAIRS_NORTH,
                                BlueprintMaterialClass.TEMPORARY),
                        cell(new BlueprintOffset(1, 0, 0), OAK_STAIRS_NORTH,
                                BlueprintMaterialClass.PERMANENT),
                        cell(new BlueprintOffset(3, 0, 0), STONE_STATE,
                                BlueprintMaterialClass.TEMPORARY))));
        ConstructionWorkPlan plan = ConstructionWorkPlan.partition(blueprint);
        BlueprintPlaceableItemEvidence evidence = evidence(blueprint);

        ConstructionWorkPackageMaterialDemand demand =
                ConstructionWorkPackageMaterialDemand.derive(plan,
                        plan.workPackages().getFirst().key(), evidence);

        assertEquals(plan, demand.workPlan());
        assertEquals(plan.workPackages().getFirst().key(), demand.workPackageKey());
        assertEquals(evidence, demand.declaredEvidence());
        assertEquals(List.of(
                        new BlueprintPlaceableItemRequirement(OAK_STAIRS,
                                BlueprintMaterialClass.PERMANENT, 1),
                        new BlueprintPlaceableItemRequirement(OAK_STAIRS,
                                BlueprintMaterialClass.TEMPORARY, 1),
                        new BlueprintPlaceableItemRequirement(STONE,
                                BlueprintMaterialClass.PERMANENT, 2),
                        new BlueprintPlaceableItemRequirement(STONE,
                                BlueprintMaterialClass.TEMPORARY, 1)),
                demand.declaredRequirements().entries());
        assertEquals(5, demand.totalDeclaredItems());
        assertThrows(UnsupportedOperationException.class,
                () -> demand.declaredRequirements().entries().clear());
    }

    @Test
    void derivesEveryActualPackageFor65And256CellPlansWithoutCreatingAllocationAuthority() {
        for (int cellCount : List.of(65, 256)) {
            Blueprint blueprint = patternedBlueprint(cellCount, cellCount);
            ConstructionWorkPlan plan = ConstructionWorkPlan.partition(blueprint);
            BlueprintPlaceableItemEvidence evidence = evidence(blueprint);
            List<ConstructionWorkPackageMaterialDemand> demands = plan.workPackages().stream()
                    .map(workPackage -> ConstructionWorkPackageMaterialDemand.derive(
                            plan, workPackage.key(), evidence))
                    .toList();

            assertEquals(cellCount, demands.stream()
                    .mapToInt(ConstructionWorkPackageMaterialDemand::totalDeclaredItems)
                    .sum());
            assertEquals(plan.workPackages().stream().map(workPackage -> workPackage.key()).toList(),
                    demands.stream().map(ConstructionWorkPackageMaterialDemand::workPackageKey)
                            .toList());
            assertTrue(demands.stream().allMatch(demand ->
                    demand.declaredRequirements().totalDeclaredItems()
                            == plan.workPackages().stream()
                                    .filter(workPackage -> workPackage.key().equals(
                                            demand.workPackageKey()))
                                    .findFirst()
                                    .orElseThrow()
                                    .cells()
                                    .size()));
        }
    }

    @Test
    void rejectsEvidenceDriftAndKeysOutsideTheExactPlan() {
        Blueprint blueprint = patternedBlueprint(65, 3L);
        ConstructionWorkPlan plan = ConstructionWorkPlan.partition(blueprint);
        ConstructionWorkPackageKey firstKey = plan.workPackages().getFirst().key();

        Blueprint revisionDrift = new Blueprint(BLUEPRINT_ID,
                Blueprint.CURRENT_SCHEMA_VERSION, 4L, blueprint.cells());
        Blueprint foreignIdentity = new Blueprint(OTHER_BLUEPRINT_ID,
                Blueprint.CURRENT_SCHEMA_VERSION, blueprint.revision(), blueprint.cells());
        List<BlueprintCell> contentDriftCells = new ArrayList<>(blueprint.cells());
        BlueprintCell firstCell = contentDriftCells.getFirst();
        contentDriftCells.set(0, cell(firstCell.offset(),
                new BlockStateFingerprint(STONE, Map.of("variant", "drift")),
                firstCell.materialClass()));
        Blueprint contentDrift = new Blueprint(BLUEPRINT_ID,
                Blueprint.CURRENT_SCHEMA_VERSION, blueprint.revision(), contentDriftCells);
        ConstructionWorkPackageKey foreignKey = ConstructionWorkPackageKey.forBlueprint(
                foreignIdentity, firstKey.ordinal());
        ConstructionWorkPackageKey missingKey = ConstructionWorkPackageKey.forBlueprint(blueprint,
                plan.workPackages().size());

        assertThrows(IllegalArgumentException.class,
                () -> ConstructionWorkPackageMaterialDemand.derive(plan, firstKey,
                        evidence(revisionDrift)));
        assertThrows(IllegalArgumentException.class,
                () -> ConstructionWorkPackageMaterialDemand.derive(plan, firstKey,
                        evidence(foreignIdentity)));
        assertThrows(IllegalArgumentException.class,
                () -> ConstructionWorkPackageMaterialDemand.derive(plan, firstKey,
                        evidence(contentDrift)));
        assertThrows(IllegalArgumentException.class,
                () -> ConstructionWorkPackageMaterialDemand.derive(plan, foreignKey,
                        evidence(blueprint)));
        assertThrows(IllegalArgumentException.class,
                () -> ConstructionWorkPackageMaterialDemand.derive(plan, missingKey,
                        evidence(blueprint)));
    }

    @Test
    void rejectsForgedPackageRequirementsEvenWhenTheyAreIndividuallyWellFormed() {
        Blueprint blueprint = patternedBlueprint(3, 5L);
        ConstructionWorkPlan plan = ConstructionWorkPlan.partition(blueprint);
        ConstructionWorkPackageKey key = plan.workPackages().getFirst().key();
        BlueprintPlaceableItemEvidence evidence = evidence(blueprint);
        BlueprintPlaceableItemRequirements forged = new BlueprintPlaceableItemRequirements(List.of(
                new BlueprintPlaceableItemRequirement(STONE,
                        BlueprintMaterialClass.PERMANENT, 3)));

        assertThrows(IllegalArgumentException.class,
                () -> new ConstructionWorkPackageMaterialDemand(plan, key, evidence, forged));
    }

    @Test
    void publicDemandDtoStaysPureAndExposesNoRuntimeOrReservationAuthority() {
        assertTrue(ConstructionWorkPackageMaterialDemand.class.isRecord());
        for (RecordComponent component : ConstructionWorkPackageMaterialDemand.class
                .getRecordComponents()) {
            assertPureType(component.getName(), component.getGenericType().getTypeName());
        }
        for (Method method : ConstructionWorkPackageMaterialDemand.class.getDeclaredMethods()) {
            assertPureType(method.getName(), method.getGenericReturnType().getTypeName());
            for (Class<?> parameterType : method.getParameterTypes()) {
                assertPureType(method.getName(), parameterType.getTypeName());
            }
        }
    }

    private static void assertPureType(String member, String typeName) {
        assertFalse(typeName.contains("net.minecraft"),
                () -> member + " contains a Minecraft runtime type");
        assertFalse(typeName.contains("ItemStack"),
                () -> member + " contains an item runtime type");
        assertFalse(typeName.contains("Reservation"),
                () -> member + " contains reservation authority");
        assertFalse(typeName.contains("ConstructionSite"),
                () -> member + " contains a site authority");
        assertFalse(typeName.contains("Level"),
                () -> member + " contains world authority");
        assertFalse(typeName.contains("BotActionRuntime"),
                () -> member + " contains action authority");
        assertFalse(typeName.contains("Technique"),
                () -> member + " contains technique authority");
        assertFalse(typeName.contains("Skill"),
                () -> member + " contains skill authority");
    }

    private static Blueprint patternedBlueprint(int cellCount, long revision) {
        return new Blueprint(BLUEPRINT_ID, Blueprint.CURRENT_SCHEMA_VERSION, revision,
                IntStream.range(0, cellCount)
                        .mapToObj(index -> cell(new BlueprintOffset(index % 16, index / 16, 0),
                                index % 3 == 0 ? STONE_STATE : OAK_STAIRS_NORTH,
                                index % 5 == 0 ? BlueprintMaterialClass.TEMPORARY
                                        : BlueprintMaterialClass.PERMANENT))
                        .toList());
    }

    private static BlueprintPlaceableItemEvidence evidence(Blueprint blueprint) {
        Map<BlockStateFingerprint, PlaceableItemEvidence> entries = new LinkedHashMap<>();
        for (BlueprintCell cell : blueprint.cells()) {
            entries.putIfAbsent(cell.expectedState(), new PlaceableItemEvidence(
                    cell.expectedState(), cell.expectedState().blockId().equals(STONE)
                            ? STONE
                            : OAK_STAIRS));
        }
        return new BlueprintPlaceableItemEvidence(blueprint, List.copyOf(entries.values()));
    }

    private static BlueprintCell cell(
            BlueprintOffset offset,
            BlockStateFingerprint state,
            BlueprintMaterialClass materialClass) {
        return new BlueprintCell(offset, state, BlueprintPlacementRole.STRUCTURE,
                BlueprintReplacePolicy.PRESERVE_EXISTING, materialClass);
    }
}
