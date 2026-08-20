package io.github.greytaiwolf.botplayer.building.blueprint;

import io.github.greytaiwolf.botplayer.action.interaction.BlockStateFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import java.lang.reflect.RecordComponent;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class BlueprintTest {
    private static final UUID BLUEPRINT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000701");
    private static final UUID OTHER_BLUEPRINT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000702");
    private static final ResourceId OAK_PLANKS =
            new ResourceId("minecraft:oak_planks");
    private static final ResourceId SCAFFOLDING =
            new ResourceId("minecraft:scaffolding");
    private static final ResourceId STONE = new ResourceId("minecraft:stone");

    @Test
    void canonicalizesCellsAndDerivesStructuralRequirementsWithoutInventoryMapping() {
        List<BlueprintCell> supplied = new ArrayList<>(List.of(
                cell(3, 0, 0, OAK_PLANKS, Map.of(),
                        BlueprintPlacementRole.STRUCTURE,
                        BlueprintReplacePolicy.PRESERVE_EXISTING,
                        BlueprintMaterialClass.PERMANENT),
                cell(-1, 2, 1, SCAFFOLDING, Map.of("distance", "0"),
                        BlueprintPlacementRole.TEMPORARY_SUPPORT,
                        BlueprintReplacePolicy.REPLACE_OWNED_TEMPORARY,
                        BlueprintMaterialClass.TEMPORARY),
                cell(0, 0, 0, OAK_PLANKS, Map.of("axis", "y"),
                        BlueprintPlacementRole.FOUNDATION,
                        BlueprintReplacePolicy.PRESERVE_EXISTING,
                        BlueprintMaterialClass.PERMANENT)));

        Blueprint blueprint = blueprint(BLUEPRINT_ID, 1L, supplied);
        supplied.clear();

        Assertions.assertEquals(List.of(
                new BlueprintOffset(-1, 2, 1),
                new BlueprintOffset(0, 0, 0),
                new BlueprintOffset(3, 0, 0)), blueprint.cells().stream()
                .map(BlueprintCell::offset)
                .toList());
        Assertions.assertEquals(new BlueprintBounds(
                new BlueprintOffset(-1, 0, 0),
                new BlueprintOffset(3, 2, 1)), blueprint.bounds());
        Assertions.assertEquals(5, blueprint.bounds().width());
        Assertions.assertEquals(3, blueprint.bounds().height());
        Assertions.assertEquals(2, blueprint.bounds().depth());
        Assertions.assertEquals(30L, blueprint.bounds().volume());

        BlueprintBlockRequirements requirements =
                blueprint.plannedBlockRequirements();
        Assertions.assertEquals(List.of(
                new BlueprintBlockRequirement(OAK_PLANKS,
                        BlueprintMaterialClass.PERMANENT, 2),
                new BlueprintBlockRequirement(SCAFFOLDING,
                        BlueprintMaterialClass.TEMPORARY, 1)),
                requirements.entries());
        Assertions.assertEquals(3, requirements.totalBlocks());
        Assertions.assertThrows(UnsupportedOperationException.class,
                () -> blueprint.cells().clear());
        Assertions.assertThrows(UnsupportedOperationException.class,
                () -> requirements.entries().clear());
    }

    @Test
    void contentHashIsCanonicalAndIncludesEveryCellSemanticField() {
        LinkedHashMap<String, String> firstPropertyOrder = new LinkedHashMap<>();
        firstPropertyOrder.put("waterlogged", "false");
        firstPropertyOrder.put("axis", "y");
        LinkedHashMap<String, String> secondPropertyOrder = new LinkedHashMap<>();
        secondPropertyOrder.put("axis", "y");
        secondPropertyOrder.put("waterlogged", "false");

        Blueprint first = blueprint(BLUEPRINT_ID, 1L, List.of(
                cell(1, 0, 0, OAK_PLANKS, firstPropertyOrder,
                        BlueprintPlacementRole.STRUCTURE,
                        BlueprintReplacePolicy.PRESERVE_EXISTING,
                        BlueprintMaterialClass.PERMANENT),
                cell(0, 0, 0, STONE, Map.of(),
                        BlueprintPlacementRole.FOUNDATION,
                        BlueprintReplacePolicy.PRESERVE_EXISTING,
                        BlueprintMaterialClass.PERMANENT)));
        Blueprint sameContentDifferentIdentity = blueprint(OTHER_BLUEPRINT_ID,
                99L, List.of(
                        cell(0, 0, 0, STONE, Map.of(),
                                BlueprintPlacementRole.FOUNDATION,
                                BlueprintReplacePolicy.PRESERVE_EXISTING,
                                BlueprintMaterialClass.PERMANENT),
                        cell(1, 0, 0, OAK_PLANKS, secondPropertyOrder,
                                BlueprintPlacementRole.STRUCTURE,
                                BlueprintReplacePolicy.PRESERVE_EXISTING,
                                BlueprintMaterialClass.PERMANENT)));

        Assertions.assertEquals(first.contentHash(),
                sameContentDifferentIdentity.contentHash());
        Assertions.assertEquals(
                "33202dcdc300c00f3b801d32832a562171da0770cf13df75e9cda3a6fde4d32a",
                first.contentHash().value());
        Assertions.assertTrue(first.contentHash().value().matches("[0-9a-f]{64}"));

        assertHashChanges(first, blueprint(OTHER_BLUEPRINT_ID, 2L, List.of(
                cell(1, 0, 0, OAK_PLANKS, Map.of("axis", "x",
                        "waterlogged", "false"),
                        BlueprintPlacementRole.STRUCTURE,
                        BlueprintReplacePolicy.PRESERVE_EXISTING,
                        BlueprintMaterialClass.PERMANENT),
                cell(0, 0, 0, STONE, Map.of(),
                        BlueprintPlacementRole.FOUNDATION,
                        BlueprintReplacePolicy.PRESERVE_EXISTING,
                        BlueprintMaterialClass.PERMANENT))));
        assertHashChanges(first, blueprint(OTHER_BLUEPRINT_ID, 3L, List.of(
                cell(2, 0, 0, OAK_PLANKS, Map.of("axis", "y",
                        "waterlogged", "false"),
                        BlueprintPlacementRole.STRUCTURE,
                        BlueprintReplacePolicy.PRESERVE_EXISTING,
                        BlueprintMaterialClass.PERMANENT),
                cell(0, 0, 0, STONE, Map.of(),
                        BlueprintPlacementRole.FOUNDATION,
                        BlueprintReplacePolicy.PRESERVE_EXISTING,
                        BlueprintMaterialClass.PERMANENT))));
        assertHashChanges(first, blueprint(OTHER_BLUEPRINT_ID, 4L, List.of(
                cell(1, 0, 0, OAK_PLANKS, Map.of("axis", "y",
                        "waterlogged", "false"),
                        BlueprintPlacementRole.ENCLOSURE,
                        BlueprintReplacePolicy.PRESERVE_EXISTING,
                        BlueprintMaterialClass.PERMANENT),
                cell(0, 0, 0, STONE, Map.of(),
                        BlueprintPlacementRole.FOUNDATION,
                        BlueprintReplacePolicy.PRESERVE_EXISTING,
                        BlueprintMaterialClass.PERMANENT))));
        assertHashChanges(first, blueprint(OTHER_BLUEPRINT_ID, 5L, List.of(
                cell(1, 0, 0, OAK_PLANKS, Map.of("axis", "y",
                        "waterlogged", "false"),
                        BlueprintPlacementRole.STRUCTURE,
                        BlueprintReplacePolicy.REQUIRE_HUMAN_CONFIRMATION,
                        BlueprintMaterialClass.PERMANENT),
                cell(0, 0, 0, STONE, Map.of(),
                        BlueprintPlacementRole.FOUNDATION,
                        BlueprintReplacePolicy.PRESERVE_EXISTING,
                        BlueprintMaterialClass.PERMANENT))));
        assertHashChanges(first, blueprint(OTHER_BLUEPRINT_ID, 6L, List.of(
                cell(1, 0, 0, OAK_PLANKS, Map.of("axis", "y",
                        "waterlogged", "false"),
                        BlueprintPlacementRole.STRUCTURE,
                        BlueprintReplacePolicy.PRESERVE_EXISTING,
                        BlueprintMaterialClass.TEMPORARY),
                cell(0, 0, 0, STONE, Map.of(),
                        BlueprintPlacementRole.FOUNDATION,
                        BlueprintReplacePolicy.PRESERVE_EXISTING,
                        BlueprintMaterialClass.PERMANENT))));
    }

    @Test
    void rejectsUnboundedAmbiguousAndMalformedBlueprintData() {
        BlueprintCell single = cell(0, 0, 0, STONE, Map.of(),
                BlueprintPlacementRole.FOUNDATION,
                BlueprintReplacePolicy.PRESERVE_EXISTING,
                BlueprintMaterialClass.PERMANENT);

        Assertions.assertThrows(NullPointerException.class,
                () -> new Blueprint(null, Blueprint.CURRENT_SCHEMA_VERSION,
                        1L, List.of(single)));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new Blueprint(new UUID(0L, 0L),
                        Blueprint.CURRENT_SCHEMA_VERSION, 1L,
                        List.of(single)));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new Blueprint(BLUEPRINT_ID, 0, 1L, List.of(single)));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new Blueprint(BLUEPRINT_ID, 2, 1L, List.of(single)));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new Blueprint(BLUEPRINT_ID,
                        Blueprint.CURRENT_SCHEMA_VERSION, 0L, List.of(single)));
        Assertions.assertThrows(NullPointerException.class,
                () -> new Blueprint(BLUEPRINT_ID,
                        Blueprint.CURRENT_SCHEMA_VERSION, 1L, null));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> blueprint(BLUEPRINT_ID, 1L, List.of()));
        Assertions.assertThrows(NullPointerException.class,
                () -> blueprint(BLUEPRINT_ID, 1L,
                        Arrays.asList((BlueprintCell) null)));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> blueprint(BLUEPRINT_ID, 1L, List.of(single,
                        cell(0, 0, 0, OAK_PLANKS, Map.of(),
                                BlueprintPlacementRole.STRUCTURE,
                                BlueprintReplacePolicy.PRESERVE_EXISTING,
                                BlueprintMaterialClass.PERMANENT))));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new BlueprintOffset(65, 0, 0));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new BlueprintOffset(Integer.MIN_VALUE, 0, 0));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> blueprint(BLUEPRINT_ID, 1L, List.of(
                        cell(-16, 0, 0, STONE, Map.of(),
                                BlueprintPlacementRole.FOUNDATION,
                                BlueprintReplacePolicy.PRESERVE_EXISTING,
                                BlueprintMaterialClass.PERMANENT),
                        cell(16, 0, 0, OAK_PLANKS, Map.of(),
                                BlueprintPlacementRole.STRUCTURE,
                                BlueprintReplacePolicy.PRESERVE_EXISTING,
                                BlueprintMaterialClass.PERMANENT))));

        List<BlueprintCell> tooManyCells = IntStream.range(0,
                        Blueprint.MAX_CELLS + 1)
                .mapToObj(index -> cell(index % Blueprint.MAX_AXIS_SPAN,
                        index / Blueprint.MAX_AXIS_SPAN, 0, STONE, Map.of(),
                        BlueprintPlacementRole.STRUCTURE,
                        BlueprintReplacePolicy.PRESERVE_EXISTING,
                        BlueprintMaterialClass.PERMANENT))
                .toList();
        Blueprint maximumBoundary = blueprint(BLUEPRINT_ID, 1L,
                tooManyCells.subList(0, Blueprint.MAX_CELLS));
        Assertions.assertEquals(Blueprint.MAX_CELLS,
                maximumBoundary.cells().size());
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> blueprint(BLUEPRINT_ID, 1L, tooManyCells));
        List<BlueprintCell> misreportingCells = new AbstractList<>() {
            @Override
            public BlueprintCell get(int index) {
                return single;
            }

            @Override
            public int size() {
                return 1;
            }

            @Override
            public java.util.Iterator<BlueprintCell> iterator() {
                return tooManyCells.iterator();
            }
        };
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> blueprint(BLUEPRINT_ID, 1L, misreportingCells));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new BlueprintBlockRequirement(STONE,
                        BlueprintMaterialClass.PERMANENT, 0));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new BlueprintBlockRequirements(List.of(
                        new BlueprintBlockRequirement(STONE,
                                BlueprintMaterialClass.PERMANENT, 1),
                        new BlueprintBlockRequirement(STONE,
                                BlueprintMaterialClass.PERMANENT, 1))));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new BlueprintContentHash("A".repeat(
                        BlueprintContentHash.HEX_LENGTH)));
    }

    @Test
    void publicBlueprintDtosContainNoMinecraftNbtOrInventoryRuntimeTypes() {
        List<Class<?>> dtoTypes = List.of(
                Blueprint.class,
                BlueprintCell.class,
                BlueprintOffset.class,
                BlueprintBounds.class,
                BlueprintBlockRequirement.class,
                BlueprintBlockRequirements.class,
                BlueprintContentHash.class);

        for (Class<?> dtoType : dtoTypes) {
            Assertions.assertTrue(dtoType.isRecord(),
                    () -> dtoType.getName() + " must remain a record");
            for (RecordComponent component : dtoType.getRecordComponents()) {
                String typeName = component.getGenericType().getTypeName();
                Assertions.assertFalse(typeName.contains("net.minecraft"),
                        () -> dtoType.getName() + "." + component.getName()
                                + " contains a Minecraft runtime type");
                Assertions.assertFalse(typeName.contains("BlockEntity"),
                        () -> dtoType.getName() + "." + component.getName()
                                + " contains BlockEntity data");
                Assertions.assertFalse(typeName.contains("CompoundTag"),
                        () -> dtoType.getName() + "." + component.getName()
                                + " contains NBT data");
                Assertions.assertFalse(typeName.contains("ItemStack"),
                        () -> dtoType.getName() + "." + component.getName()
                                + " contains inventory runtime data");
            }
        }
    }

    private static Blueprint blueprint(UUID id, long revision,
            List<BlueprintCell> cells) {
        return new Blueprint(id, Blueprint.CURRENT_SCHEMA_VERSION, revision,
                cells);
    }

    private static BlueprintCell cell(int x, int y, int z, ResourceId blockId,
            Map<String, String> properties, BlueprintPlacementRole role,
            BlueprintReplacePolicy replacePolicy,
            BlueprintMaterialClass materialClass) {
        return new BlueprintCell(new BlueprintOffset(x, y, z),
                new BlockStateFingerprint(blockId, properties), role,
                replacePolicy, materialClass);
    }

    private static void assertHashChanges(Blueprint reference,
            Blueprint candidate) {
        Assertions.assertNotEquals(reference.contentHash(),
                candidate.contentHash());
    }
}
