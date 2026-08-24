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
import io.github.greytaiwolf.botplayer.building.construction.ConstructionWorkPlan;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ConstructionWorkPackageMaterialAvailabilityProjectionTest {
    private static final UUID BOT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000a91");
    private static final UUID BLUEPRINT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000a92");
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
    void projectsOnePackageByItemTotalWithoutAllocatingAcrossMaterialClasses() {
        Blueprint blueprint = smallMixedBlueprint(1L);
        BlueprintPlaceableItemEvidence evidence = evidence(blueprint);
        ConstructionWorkPackageMaterialDemand demand = demand(blueprint, evidence, 0);
        ConstructionMaterialAvailability availability = usableAvailability(evidence, Map.of(
                STONE, 2,
                OAK_STAIRS, 2));

        ConstructionWorkPackageMaterialAvailabilityProjection projection =
                ConstructionWorkPackageMaterialAvailabilityProjection.project(demand, availability);

        assertEquals(4, demand.declaredRequirements().entries().size());
        assertEquals(ConstructionWorkPackageMaterialAvailabilityProjection.Status
                        .OBSERVED_ITEM_TOTALS_SUFFICIENT,
                projection.status());
        assertTrue(projection.observedItemTotalsSufficient());
        assertEquals(List.of(
                        new ConstructionWorkPackageMaterialAvailabilityProjection.Finding(
                                OAK_STAIRS, 2, 2, 0),
                        new ConstructionWorkPackageMaterialAvailabilityProjection.Finding(
                                STONE, 2, 2, 0)),
                projection.findings());
        assertThrows(UnsupportedOperationException.class, projection.findings()::clear);
    }

    @Test
    void fullBlueprintShortageCanStillObserveOneActualPackageAsSufficientInIsolation() {
        Blueprint blueprint = patternedBlueprint(65, 2L);
        BlueprintPlaceableItemEvidence evidence = evidence(blueprint);
        ConstructionWorkPackageMaterialDemand demand = demand(blueprint, evidence, 0);
        ConstructionMaterialAvailability availability = usableAvailability(evidence,
                packageItemTotals(demand));

        ConstructionWorkPackageMaterialAvailabilityProjection projection =
                ConstructionWorkPackageMaterialAvailabilityProjection.project(demand, availability);

        assertEquals(ConstructionMaterialAvailability.Status.SHORTAGE, availability.status());
        assertEquals(ConstructionWorkPackageMaterialAvailabilityProjection.Status
                        .OBSERVED_ITEM_TOTALS_SUFFICIENT,
                projection.status());
        assertTrue(projection.findings().stream().noneMatch(
                ConstructionWorkPackageMaterialAvailabilityProjection.Finding::hasPackageShortage));
    }

    @Test
    void reportsTheExactPackageItemShortageWithoutClaimingAReservation() {
        Blueprint blueprint = patternedBlueprint(65, 3L);
        BlueprintPlaceableItemEvidence evidence = evidence(blueprint);
        ConstructionWorkPackageMaterialDemand demand = demand(blueprint, evidence, 1);
        Map<ResourceId, Integer> observedCounts = new LinkedHashMap<>(packageItemTotals(demand));
        ResourceId shortItem = observedCounts.keySet().iterator().next();
        observedCounts.put(shortItem, observedCounts.get(shortItem) - 1);
        ConstructionMaterialAvailability availability = usableAvailability(evidence, observedCounts);

        ConstructionWorkPackageMaterialAvailabilityProjection projection =
                ConstructionWorkPackageMaterialAvailabilityProjection.project(demand, availability);

        assertEquals(ConstructionWorkPackageMaterialAvailabilityProjection.Status
                        .OBSERVED_ITEM_TOTALS_SHORTAGE,
                projection.status());
        assertFalse(projection.observedItemTotalsSufficient());
        assertTrue(projection.findings().contains(
                new ConstructionWorkPackageMaterialAvailabilityProjection.Finding(shortItem,
                        packageItemTotals(demand).get(shortItem), observedCounts.get(shortItem), 1)));
    }

    @Test
    void preservesUnavailableSourceReasonsWithoutInventingZeroStockFindings() {
        Blueprint blueprint = smallMixedBlueprint(4L);
        BlueprintPlaceableItemEvidence evidence = evidence(blueprint);
        ConstructionWorkPackageMaterialDemand demand = demand(blueprint, evidence, 0);

        ConstructionWorkPackageMaterialAvailabilityProjection unavailableMenu =
                ConstructionWorkPackageMaterialAvailabilityProjection.project(demand,
                        ConstructionMaterialAvailability.unavailableMenu(BOT_ID, 5L, evidence, 42L));
        ConstructionWorkPackageMaterialAvailabilityProjection unavailableRegistry =
                ConstructionWorkPackageMaterialAvailabilityProjection.project(demand,
                        ConstructionMaterialAvailability.unavailableRegistry(BOT_ID, 5L, evidence, 42L));

        assertEquals(ConstructionWorkPackageMaterialAvailabilityProjection.Status.UNAVAILABLE_MENU,
                unavailableMenu.status());
        assertEquals(ConstructionWorkPackageMaterialAvailabilityProjection.Status
                        .UNAVAILABLE_REGISTRY,
                unavailableRegistry.status());
        assertTrue(unavailableMenu.findings().isEmpty());
        assertTrue(unavailableRegistry.findings().isEmpty());
    }

    @Test
    void rejectsAvailabilityEvidenceDriftInsteadOfMatchingOnlyItemIds() {
        Blueprint blueprint = smallMixedBlueprint(5L);
        BlueprintPlaceableItemEvidence evidence = evidence(blueprint);
        ConstructionWorkPackageMaterialDemand demand = demand(blueprint, evidence, 0);
        Blueprint revisionDrift = new Blueprint(BLUEPRINT_ID, Blueprint.CURRENT_SCHEMA_VERSION, 6L,
                blueprint.cells());
        BlueprintPlaceableItemEvidence driftedEvidence = evidence(revisionDrift);

        assertThrows(IllegalArgumentException.class,
                () -> ConstructionWorkPackageMaterialAvailabilityProjection.project(demand,
                        usableAvailability(driftedEvidence, Map.of(STONE, 2, OAK_STAIRS, 2))));
    }

    @Test
    void rejectsDifferentExplicitItemMappingsForTheSameBlueprint() {
        Blueprint blueprint = smallMixedBlueprint(6L);
        BlueprintPlaceableItemEvidence evidence = evidence(blueprint);
        ConstructionWorkPackageMaterialDemand demand = demand(blueprint, evidence, 0);
        BlueprintPlaceableItemEvidence remappedEvidence = new BlueprintPlaceableItemEvidence(
                blueprint, List.of(
                        new PlaceableItemEvidence(STONE_STATE, OAK_STAIRS),
                        new PlaceableItemEvidence(OAK_STAIRS_NORTH, OAK_STAIRS)));

        assertThrows(IllegalArgumentException.class,
                () -> ConstructionWorkPackageMaterialAvailabilityProjection.project(demand,
                        usableAvailability(remappedEvidence, Map.of(OAK_STAIRS, 4))));
    }

    @Test
    void projectsEveryActualPackageFor65And256CellPlansWithoutCreatingPackageAllocation() {
        for (int cellCount : List.of(65, 256)) {
            Blueprint blueprint = patternedBlueprint(cellCount, cellCount);
            BlueprintPlaceableItemEvidence evidence = evidence(blueprint);
            ConstructionWorkPlan plan = ConstructionWorkPlan.partition(blueprint);
            ConstructionMaterialAvailability availability = usableAvailability(evidence,
                    fullItemTotals(evidence));
            List<ConstructionWorkPackageMaterialAvailabilityProjection> projections = plan
                    .workPackages()
                    .stream()
                    .map(workPackage -> ConstructionWorkPackageMaterialAvailabilityProjection.project(
                            ConstructionWorkPackageMaterialDemand.derive(plan, workPackage.key(),
                                    evidence),
                            availability))
                    .toList();

            assertTrue(projections.stream().allMatch(
                    ConstructionWorkPackageMaterialAvailabilityProjection
                            ::observedItemTotalsSufficient));
            assertEquals(cellCount, projections.stream()
                    .flatMap(projection -> projection.findings().stream())
                    .mapToInt(ConstructionWorkPackageMaterialAvailabilityProjection.Finding
                            ::packageRequiredCount)
                    .sum());
        }
    }

    @Test
    void projectionStaysPureAndHasNoPublicForgingConstructor() {
        assertTrue(Modifier.isFinal(
                ConstructionWorkPackageMaterialAvailabilityProjection.class.getModifiers()));
        assertEquals(0, ConstructionWorkPackageMaterialAvailabilityProjection.class
                .getConstructors().length);
        assertPureDtoType(ConstructionWorkPackageMaterialAvailabilityProjection.class);
        assertPureDtoType(ConstructionWorkPackageMaterialAvailabilityProjection.Finding.class);
        assertPureDtoType(ConstructionWorkPackageMaterialAvailabilityProjection.Status.class);
    }

    private static void assertPureDtoType(Class<?> type) {
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                assertPureType(type.getName() + "." + component.getName(),
                        component.getGenericType().getTypeName());
            }
        }
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            for (Class<?> parameterType : constructor.getParameterTypes()) {
                assertPureType(type.getName() + ".<init>", parameterType.getTypeName());
            }
        }
        for (Method method : type.getDeclaredMethods()) {
            assertPureType(type.getName() + "." + method.getName(),
                    method.getGenericReturnType().getTypeName());
            for (Class<?> parameterType : method.getParameterTypes()) {
                assertPureType(type.getName() + "." + method.getName(),
                        parameterType.getTypeName());
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

    private static ConstructionWorkPackageMaterialDemand demand(
            Blueprint blueprint,
            BlueprintPlaceableItemEvidence evidence,
            int packageOrdinal) {
        ConstructionWorkPlan plan = ConstructionWorkPlan.partition(blueprint);
        return ConstructionWorkPackageMaterialDemand.derive(plan,
                plan.workPackages().get(packageOrdinal).key(), evidence);
    }

    private static ConstructionMaterialAvailability usableAvailability(
            BlueprintPlaceableItemEvidence evidence,
            Map<ResourceId, Integer> observedCounts) {
        Map<ResourceId, Integer> requiredCounts = fullItemTotals(evidence);
        List<ConstructionMaterialAvailability.Finding> findings = new ArrayList<>(
                requiredCounts.size());
        boolean hasShortage = false;
        for (Map.Entry<ResourceId, Integer> entry : requiredCounts.entrySet()) {
            int observedCount = observedCounts.getOrDefault(entry.getKey(), 0);
            int shortageCount = Math.max(0, entry.getValue() - observedCount);
            findings.add(new ConstructionMaterialAvailability.Finding(entry.getKey(),
                    entry.getValue(), observedCount, shortageCount));
            hasShortage |= shortageCount > 0;
        }
        return new ConstructionMaterialAvailability(BOT_ID, 5L, evidence, 42L,
                hasShortage ? ConstructionMaterialAvailability.Status.SHORTAGE
                        : ConstructionMaterialAvailability.Status.AVAILABLE,
                Optional.of(new ConstructionMaterialAvailability.InventoryMenuFence(0, 7, 2)),
                findings);
    }

    private static Map<ResourceId, Integer> fullItemTotals(
            BlueprintPlaceableItemEvidence evidence) {
        Map<ResourceId, Integer> totals = new LinkedHashMap<>();
        for (BlueprintPlaceableItemRequirement requirement : evidence.declaredItemRequirements()
                .entries()) {
            totals.merge(requirement.placeableItemId(), requirement.count(), Math::addExact);
        }
        return Map.copyOf(totals);
    }

    private static Map<ResourceId, Integer> packageItemTotals(
            ConstructionWorkPackageMaterialDemand demand) {
        Map<ResourceId, Integer> totals = new LinkedHashMap<>();
        for (BlueprintPlaceableItemRequirement requirement : demand.declaredRequirements().entries()) {
            totals.merge(requirement.placeableItemId(), requirement.count(), Math::addExact);
        }
        return Map.copyOf(totals);
    }

    private static Blueprint smallMixedBlueprint(long revision) {
        return new Blueprint(BLUEPRINT_ID, Blueprint.CURRENT_SCHEMA_VERSION, revision, List.of(
                cell(new BlueprintOffset(0, 0, 0), STONE_STATE,
                        BlueprintMaterialClass.PERMANENT),
                cell(new BlueprintOffset(1, 0, 0), STONE_STATE,
                        BlueprintMaterialClass.TEMPORARY),
                cell(new BlueprintOffset(2, 0, 0), OAK_STAIRS_NORTH,
                        BlueprintMaterialClass.PERMANENT),
                cell(new BlueprintOffset(3, 0, 0), OAK_STAIRS_NORTH,
                        BlueprintMaterialClass.TEMPORARY)));
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
