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
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class BlueprintPlaceableItemEvidenceTest {
    private static final UUID BLUEPRINT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000b01");
    private static final ResourceId STONE = new ResourceId("minecraft:stone");
    private static final ResourceId OAK_STAIRS = new ResourceId("minecraft:oak_stairs");
    private static final ResourceId OAK_SLAB = new ResourceId("minecraft:oak_slab");
    private static final ResourceId TORCH = new ResourceId("minecraft:torch");
    private static final ResourceId OAK_PLANKS = new ResourceId("minecraft:oak_planks");
    private static final ResourceId SCAFFOLDING = new ResourceId("minecraft:scaffolding");
    private static final BlockStateFingerprint STONE_STATE = new BlockStateFingerprint(STONE, Map.of());
    private static final BlockStateFingerprint OAK_STAIRS_NORTH = new BlockStateFingerprint(OAK_STAIRS,
            Map.of("facing", "north", "half", "bottom"));
    private static final BlockStateFingerprint OAK_STAIRS_SOUTH = new BlockStateFingerprint(OAK_STAIRS,
            Map.of("facing", "south", "half", "bottom"));
    private static final BlockStateFingerprint OAK_SLAB_TOP = new BlockStateFingerprint(OAK_SLAB,
            Map.of("type", "top"));

    @Test
    void canonicalizesExactStateEvidenceDefensivelyAndNeverInfersItemIds() {
        Blueprint blueprint = policyBlueprint();
        List<PlaceableItemEvidence> supplied = new ArrayList<>(List.of(
                new PlaceableItemEvidence(OAK_STAIRS_SOUTH, OAK_PLANKS),
                new PlaceableItemEvidence(STONE_STATE, TORCH),
                new PlaceableItemEvidence(OAK_STAIRS_NORTH, OAK_PLANKS)));

        BlueprintPlaceableItemEvidence declared = new BlueprintPlaceableItemEvidence(blueprint,
                supplied);
        supplied.clear();

        assertEquals(List.of(OAK_STAIRS_NORTH, STONE_STATE, OAK_STAIRS_SOUTH),
                declared.evidence().stream().map(PlaceableItemEvidence::targetState).toList());
        assertEquals(List.of(OAK_PLANKS, TORCH, OAK_PLANKS),
                declared.evidence().stream().map(PlaceableItemEvidence::placeableItemId).toList());
        assertThrows(UnsupportedOperationException.class,
                () -> declared.evidence().clear());

        assertEquals(OAK_PLANKS, declared.placeableItemFor(new BlueprintOffset(-1, 0, 0)));
        assertEquals(TORCH, declared.placeableItemFor(new BlueprintOffset(0, 0, 0)));
        assertEquals(OAK_PLANKS, declared.placeableItemFor(new BlueprintOffset(1, 0, 0)));
        assertFalse(STONE_STATE.blockId().equals(TORCH),
                () -> "an explicit non-equal state-to-item declaration must remain valid");
    }

    @Test
    void distinguishesEveryFullStateBeforeAggregatingExplicitItemsByMaterialClass() {
        Blueprint blueprint = new Blueprint(BLUEPRINT_ID, Blueprint.CURRENT_SCHEMA_VERSION, 2L,
                List.of(
                        cell(new BlueprintOffset(2, 0, 0), OAK_STAIRS_NORTH,
                                BlueprintMaterialClass.PERMANENT),
                        cell(new BlueprintOffset(-1, 0, 0), OAK_STAIRS_SOUTH,
                                BlueprintMaterialClass.PERMANENT),
                        cell(new BlueprintOffset(0, 0, 0), OAK_SLAB_TOP,
                                BlueprintMaterialClass.TEMPORARY),
                        cell(new BlueprintOffset(1, 0, 0), OAK_STAIRS_NORTH,
                                BlueprintMaterialClass.TEMPORARY)));
        BlueprintPlaceableItemEvidence declared = new BlueprintPlaceableItemEvidence(blueprint,
                List.of(
                        new PlaceableItemEvidence(OAK_STAIRS_SOUTH, SCAFFOLDING),
                        new PlaceableItemEvidence(OAK_SLAB_TOP, SCAFFOLDING),
                        new PlaceableItemEvidence(OAK_STAIRS_NORTH, OAK_PLANKS)));

        assertEquals(List.of(OAK_STAIRS_SOUTH, OAK_SLAB_TOP, OAK_STAIRS_NORTH),
                declared.evidence().stream().map(PlaceableItemEvidence::targetState).toList());
        assertEquals(SCAFFOLDING, declared.placeableItemFor(new BlueprintOffset(-1, 0, 0)));
        assertEquals(OAK_PLANKS, declared.placeableItemFor(new BlueprintOffset(1, 0, 0)));
        assertEquals(List.of(
                        new BlueprintPlaceableItemRequirement(OAK_PLANKS,
                                BlueprintMaterialClass.PERMANENT, 1),
                        new BlueprintPlaceableItemRequirement(OAK_PLANKS,
                                BlueprintMaterialClass.TEMPORARY, 1),
                        new BlueprintPlaceableItemRequirement(SCAFFOLDING,
                                BlueprintMaterialClass.PERMANENT, 1),
                        new BlueprintPlaceableItemRequirement(SCAFFOLDING,
                                BlueprintMaterialClass.TEMPORARY, 1)),
                declared.declaredItemRequirements().entries());
        assertEquals(4, declared.declaredItemRequirements().totalDeclaredItems());
    }

    @Test
    void rejectsMissingForeignDuplicateNullAndUnboundedEvidence() {
        Blueprint blueprint = policyBlueprint();
        List<PlaceableItemEvidence> complete = List.of(
                new PlaceableItemEvidence(OAK_STAIRS_NORTH, OAK_PLANKS),
                new PlaceableItemEvidence(STONE_STATE, TORCH),
                new PlaceableItemEvidence(OAK_STAIRS_SOUTH, OAK_PLANKS));

        assertThrows(NullPointerException.class,
                () -> new BlueprintPlaceableItemEvidence(null, complete));
        assertThrows(NullPointerException.class,
                () -> new BlueprintPlaceableItemEvidence(blueprint, null));
        assertThrows(NullPointerException.class,
                () -> new BlueprintPlaceableItemEvidence(blueprint,
                        Arrays.asList(complete.get(0), null, complete.get(2))));
        assertThrows(IllegalArgumentException.class,
                () -> new BlueprintPlaceableItemEvidence(blueprint, complete.subList(0, 2)));
        assertThrows(IllegalArgumentException.class,
                () -> new BlueprintPlaceableItemEvidence(blueprint, List.of(
                        complete.get(0), complete.get(1), complete.get(0))));
        assertThrows(IllegalArgumentException.class,
                () -> new BlueprintPlaceableItemEvidence(blueprint, List.of(
                        complete.get(0), complete.get(1),
                        new PlaceableItemEvidence(OAK_SLAB_TOP, SCAFFOLDING))));
        assertThrows(NullPointerException.class,
                () -> new PlaceableItemEvidence(null, OAK_PLANKS));
        assertThrows(NullPointerException.class,
                () -> new PlaceableItemEvidence(OAK_STAIRS_NORTH, null));

        Blueprint maxDistinctBlueprint = fullyDistinctBlueprint(Blueprint.MAX_CELLS);
        List<PlaceableItemEvidence> exactMaxEvidence = maxDistinctBlueprint.cells().stream()
                .map(cell -> new PlaceableItemEvidence(cell.expectedState(), OAK_PLANKS))
                .toList();
        List<PlaceableItemEvidence> tooMany = new ArrayList<>(exactMaxEvidence);
        tooMany.add(exactMaxEvidence.get(0));
        List<PlaceableItemEvidence> misreporting = new AbstractList<>() {
            @Override
            public PlaceableItemEvidence get(int index) {
                return tooMany.get(index);
            }

            @Override
            public int size() {
                return 1;
            }

            @Override
            public java.util.Iterator<PlaceableItemEvidence> iterator() {
                return tooMany.iterator();
            }
        };
        assertThrows(IllegalArgumentException.class,
                () -> new BlueprintPlaceableItemEvidence(maxDistinctBlueprint, misreporting));
    }

    @Test
    void fencesLookupsToExactBlueprintOffsets() {
        BlueprintPlaceableItemEvidence declared = new BlueprintPlaceableItemEvidence(policyBlueprint(),
                List.of(
                        new PlaceableItemEvidence(OAK_STAIRS_NORTH, OAK_PLANKS),
                        new PlaceableItemEvidence(STONE_STATE, TORCH),
                        new PlaceableItemEvidence(OAK_STAIRS_SOUTH, OAK_PLANKS)));

        assertThrows(NullPointerException.class, () -> declared.placeableItemFor(null));
        assertThrows(IllegalArgumentException.class,
                () -> declared.placeableItemFor(new BlueprintOffset(2, 0, 0)));
    }

    @Test
    void exactCoversAndAggregatesA65CellBlueprintWithinTheBoundedTotal() {
        Blueprint blueprint = regularBlueprint(65);
        List<PlaceableItemEvidence> supplied = new ArrayList<>(List.of(
                new PlaceableItemEvidence(OAK_STAIRS_NORTH, OAK_PLANKS),
                new PlaceableItemEvidence(OAK_STAIRS_SOUTH, OAK_PLANKS)));
        Collections.reverse(supplied);

        BlueprintPlaceableItemEvidence declared = new BlueprintPlaceableItemEvidence(blueprint,
                supplied);
        BlueprintPlaceableItemRequirements requirements = declared.declaredItemRequirements();

        assertEquals(65, blueprint.cells().size());
        assertEquals(2, declared.evidence().size());
        assertEquals(List.of(new BlueprintPlaceableItemRequirement(OAK_PLANKS,
                        BlueprintMaterialClass.PERMANENT, 65)), requirements.entries());
        assertEquals(65, requirements.totalDeclaredItems());
    }

    @Test
    void canonicalizesAndBoundsDeclaredRequirements() {
        List<BlueprintPlaceableItemRequirement> supplied = new ArrayList<>(List.of(
                new BlueprintPlaceableItemRequirement(SCAFFOLDING,
                        BlueprintMaterialClass.TEMPORARY, 3),
                new BlueprintPlaceableItemRequirement(OAK_PLANKS,
                        BlueprintMaterialClass.PERMANENT, 2)));
        BlueprintPlaceableItemRequirements requirements = new BlueprintPlaceableItemRequirements(supplied);
        supplied.clear();

        assertEquals(List.of(
                        new BlueprintPlaceableItemRequirement(OAK_PLANKS,
                                BlueprintMaterialClass.PERMANENT, 2),
                        new BlueprintPlaceableItemRequirement(SCAFFOLDING,
                                BlueprintMaterialClass.TEMPORARY, 3)),
                requirements.entries());
        assertEquals(5, requirements.totalDeclaredItems());
        assertThrows(UnsupportedOperationException.class, () -> requirements.entries().clear());

        assertThrows(NullPointerException.class,
                () -> new BlueprintPlaceableItemRequirement(null,
                        BlueprintMaterialClass.PERMANENT, 1));
        assertThrows(NullPointerException.class,
                () -> new BlueprintPlaceableItemRequirement(OAK_PLANKS, null, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new BlueprintPlaceableItemRequirement(OAK_PLANKS,
                        BlueprintMaterialClass.PERMANENT, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new BlueprintPlaceableItemRequirement(OAK_PLANKS,
                        BlueprintMaterialClass.PERMANENT, Blueprint.MAX_CELLS + 1));
        assertThrows(NullPointerException.class,
                () -> new BlueprintPlaceableItemRequirements(null));
        assertThrows(IllegalArgumentException.class,
                () -> new BlueprintPlaceableItemRequirements(List.of()));
        assertThrows(NullPointerException.class,
                () -> new BlueprintPlaceableItemRequirements(Arrays.asList(
                        new BlueprintPlaceableItemRequirement(OAK_PLANKS,
                                BlueprintMaterialClass.PERMANENT, 1), null)));
        assertThrows(IllegalArgumentException.class,
                () -> new BlueprintPlaceableItemRequirements(List.of(
                        new BlueprintPlaceableItemRequirement(OAK_PLANKS,
                                BlueprintMaterialClass.PERMANENT, 1),
                        new BlueprintPlaceableItemRequirement(OAK_PLANKS,
                                BlueprintMaterialClass.PERMANENT, 1))));
        assertThrows(IllegalArgumentException.class,
                () -> new BlueprintPlaceableItemRequirements(List.of(
                        new BlueprintPlaceableItemRequirement(OAK_PLANKS,
                                BlueprintMaterialClass.PERMANENT, Blueprint.MAX_CELLS),
                        new BlueprintPlaceableItemRequirement(SCAFFOLDING,
                                BlueprintMaterialClass.TEMPORARY, 1))));

        List<BlueprintPlaceableItemRequirement> tooManyEntries = IntStream.range(0,
                        Blueprint.MAX_CELLS + 1)
                .mapToObj(index -> new BlueprintPlaceableItemRequirement(
                        new ResourceId("test:item-" + index),
                        BlueprintMaterialClass.PERMANENT, 1))
                .toList();
        assertThrows(IllegalArgumentException.class,
                () -> new BlueprintPlaceableItemRequirements(tooManyEntries));
    }

    @Test
    void publicMaterialDtosStayPureAndExposeNoRuntimeAuthority() {
        List<Class<?>> dtoTypes = List.of(
                PlaceableItemEvidence.class,
                BlueprintPlaceableItemEvidence.class,
                BlueprintPlaceableItemRequirement.class,
                BlueprintPlaceableItemRequirements.class);

        for (Class<?> dtoType : dtoTypes) {
            assertTrue(dtoType.isRecord(), () -> dtoType.getName() + " must remain a record");
            for (RecordComponent component : dtoType.getRecordComponents()) {
                assertPureType(dtoType.getName() + "." + component.getName(),
                        component.getGenericType().getTypeName());
            }
            for (Method method : dtoType.getDeclaredMethods()) {
                assertPureType(dtoType.getName() + "." + method.getName(),
                        method.getGenericReturnType().getTypeName());
                for (Class<?> parameterType : method.getParameterTypes()) {
                    assertPureType(dtoType.getName() + "." + method.getName(),
                            parameterType.getTypeName());
                }
            }
        }
    }

    private static void assertPureType(String member, String typeName) {
        assertFalse(typeName.contains("net.minecraft"),
                () -> member + " contains a runtime type");
        assertFalse(typeName.contains("ItemStack"),
                () -> member + " contains an item runtime type");
        assertFalse(typeName.contains("Reservation"),
                () -> member + " contains reservation authority");
        assertFalse(typeName.contains("ConstructionSite"),
                () -> member + " contains a site authority");
        assertFalse(typeName.contains("Level"),
                () -> member + " contains a world authority");
        assertFalse(typeName.contains("BotActionRuntime"),
                () -> member + " contains action authority");
        assertFalse(typeName.contains("Technique"),
                () -> member + " contains technique authority");
        assertFalse(typeName.contains("Skill"),
                () -> member + " contains skill authority");
    }

    private static Blueprint policyBlueprint() {
        return new Blueprint(BLUEPRINT_ID, Blueprint.CURRENT_SCHEMA_VERSION, 1L,
                List.of(
                        cell(new BlueprintOffset(1, 0, 0), OAK_STAIRS_SOUTH,
                                BlueprintMaterialClass.PERMANENT),
                        cell(new BlueprintOffset(-1, 0, 0), OAK_STAIRS_NORTH,
                                BlueprintMaterialClass.PERMANENT),
                        cell(new BlueprintOffset(0, 0, 0), STONE_STATE,
                                BlueprintMaterialClass.TEMPORARY)));
    }

    private static Blueprint regularBlueprint(int cellCount) {
        return new Blueprint(BLUEPRINT_ID, Blueprint.CURRENT_SCHEMA_VERSION, 3L,
                IntStream.range(0, cellCount)
                        .mapToObj(index -> cell(new BlueprintOffset(index % 16, index / 16, 0),
                                index % 2 == 0 ? OAK_STAIRS_NORTH : OAK_STAIRS_SOUTH,
                                BlueprintMaterialClass.PERMANENT))
                        .toList());
    }

    private static Blueprint fullyDistinctBlueprint(int cellCount) {
        return new Blueprint(BLUEPRINT_ID, Blueprint.CURRENT_SCHEMA_VERSION, 4L,
                IntStream.range(0, cellCount)
                        .mapToObj(index -> cell(new BlueprintOffset(index % 16, index / 16, 0),
                                new BlockStateFingerprint(STONE, Map.of("variant",
                                        Integer.toString(index))),
                                BlueprintMaterialClass.PERMANENT))
                        .toList());
    }

    private static BlueprintCell cell(BlueprintOffset offset, BlockStateFingerprint expectedState,
            BlueprintMaterialClass materialClass) {
        return new BlueprintCell(offset, expectedState, BlueprintPlacementRole.STRUCTURE,
                BlueprintReplacePolicy.PRESERVE_EXISTING, materialClass);
    }
}
