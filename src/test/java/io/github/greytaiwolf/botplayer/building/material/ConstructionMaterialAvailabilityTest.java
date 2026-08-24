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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConstructionMaterialAvailabilityTest {
    private static final UUID BOT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000a71");
    private static final UUID BLUEPRINT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000a72");
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
    void canonicalizesDefensivelyCopiesAndAggregatesRequirementsAcrossMaterialClasses() {
        BlueprintPlaceableItemEvidence declared = declared();
        List<ConstructionMaterialAvailability.Finding> supplied = new ArrayList<>(List.of(
                new ConstructionMaterialAvailability.Finding(STONE, 2, 1, 1),
                new ConstructionMaterialAvailability.Finding(OAK_STAIRS, 1, 1, 0)));

        ConstructionMaterialAvailability availability = new ConstructionMaterialAvailability(
                BOT_ID, 3L, declared, 42L,
                ConstructionMaterialAvailability.Status.SHORTAGE,
                Optional.of(new ConstructionMaterialAvailability.InventoryMenuFence(0, 7, 2)),
                supplied);
        supplied.clear();

        assertEquals(List.of(
                        new ConstructionMaterialAvailability.Finding(OAK_STAIRS, 1, 1, 0),
                        new ConstructionMaterialAvailability.Finding(STONE, 2, 1, 1)),
                availability.findings());
        assertEquals(ConstructionMaterialAvailability.Status.SHORTAGE, availability.status());
        assertFalse(availability.isAvailable());
        assertThrows(UnsupportedOperationException.class, availability.findings()::clear);
    }

    @Test
    void rejectsForgedAvailableAndUnavailableResultsInsteadOfTreatingMissingEvidenceAsZero() {
        BlueprintPlaceableItemEvidence declared = declared();
        ConstructionMaterialAvailability.InventoryMenuFence fence =
                new ConstructionMaterialAvailability.InventoryMenuFence(0, 7, 2);
        ConstructionMaterialAvailability.Finding stoneShortage =
                new ConstructionMaterialAvailability.Finding(STONE, 2, 1, 1);
        ConstructionMaterialAvailability.Finding stoneEnough =
                new ConstructionMaterialAvailability.Finding(STONE, 2, 2, 0);
        ConstructionMaterialAvailability.Finding stairsEnough =
                new ConstructionMaterialAvailability.Finding(OAK_STAIRS, 1, 1, 0);

        assertThrows(IllegalArgumentException.class,
                () -> new ConstructionMaterialAvailability(BOT_ID, 3L, declared, 42L,
                        ConstructionMaterialAvailability.Status.AVAILABLE, Optional.of(fence),
                        List.of(stoneShortage, stairsEnough)));
        assertThrows(IllegalArgumentException.class,
                () -> new ConstructionMaterialAvailability(BOT_ID, 3L, declared, 42L,
                        ConstructionMaterialAvailability.Status.SHORTAGE, Optional.of(fence),
                        List.of(stoneEnough, stairsEnough)));
        assertThrows(IllegalArgumentException.class,
                () -> new ConstructionMaterialAvailability(BOT_ID, 3L, declared, 42L,
                        ConstructionMaterialAvailability.Status.UNAVAILABLE_MENU, Optional.of(fence),
                        List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ConstructionMaterialAvailability(BOT_ID, 3L, declared, 42L,
                        ConstructionMaterialAvailability.Status.UNAVAILABLE_REGISTRY, Optional.empty(),
                        List.of(stoneShortage, stairsEnough)));
        assertThrows(IllegalArgumentException.class,
                () -> new ConstructionMaterialAvailability.Finding(STONE, 2, 1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ConstructionMaterialAvailability(BOT_ID, 3L, declared, 42L,
                        ConstructionMaterialAvailability.Status.AVAILABLE, Optional.of(fence),
                        List.of(new ConstructionMaterialAvailability.Finding(STONE, 1, 1, 0),
                                stairsEnough)));
    }

    @Test
    void publicAvailabilityDtosStayPureAndExposeNoRuntimeOrReservationAuthority() {
        List<Class<?>> dtoTypes = List.of(
                ConstructionMaterialAvailability.class,
                ConstructionMaterialAvailability.InventoryMenuFence.class,
                ConstructionMaterialAvailability.Finding.class);

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

    private static BlueprintPlaceableItemEvidence declared() {
        return new BlueprintPlaceableItemEvidence(new Blueprint(BLUEPRINT_ID,
                Blueprint.CURRENT_SCHEMA_VERSION, 1L, List.of(
                        cell(new BlueprintOffset(0, 0, 0), STONE_STATE,
                                BlueprintMaterialClass.PERMANENT),
                        cell(new BlueprintOffset(1, 0, 0), STONE_STATE,
                                BlueprintMaterialClass.TEMPORARY),
                        cell(new BlueprintOffset(2, 0, 0), OAK_STAIRS_NORTH,
                                BlueprintMaterialClass.PERMANENT))),
                List.of(
                        new PlaceableItemEvidence(STONE_STATE, STONE),
                        new PlaceableItemEvidence(OAK_STAIRS_NORTH, OAK_STAIRS)));
    }

    private static BlueprintCell cell(
            BlueprintOffset offset,
            BlockStateFingerprint state,
            BlueprintMaterialClass materialClass) {
        return new BlueprintCell(offset, state, BlueprintPlacementRole.FOUNDATION,
                BlueprintReplacePolicy.PRESERVE_EXISTING, materialClass);
    }
}
