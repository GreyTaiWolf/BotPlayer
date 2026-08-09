package io.github.greytaiwolf.botplayer.skill.runtime;

import io.github.greytaiwolf.botplayer.action.interaction.BlockCoordinates;
import io.github.greytaiwolf.botplayer.skill.builtin.production.AcquisitionMethod;
import io.github.greytaiwolf.botplayer.skill.builtin.production.ProductionLedger;
import io.github.greytaiwolf.botplayer.skill.builtin.production.ProductionMaterials;
import io.github.greytaiwolf.botplayer.skill.builtin.production.ResourceAcquisition;
import io.github.greytaiwolf.botplayer.skill.core.SkillParameters;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorAvailability;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorBudget;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorEvidence;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorQuery;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorQueryType;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorResourceFilter;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorRunIdentity;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorScope;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class MinecraftProductionSkillPortsTest {
    private static final UUID BOT = new UUID(0L, 1L);
    private static final UUID RUN = new UUID(0L, 2L);
    private static final String DIGEST = "a".repeat(64);

    @Test
    void inventoryLedgerCountsOnlyTheClosedP5AMaterialWhitelist() {
        List<TaskSensorEvidence> evidence = emptyInventoryEvidence();
        evidence.set(3, item(3, "minecraft:oak_log", 4));
        evidence.set(4, item(4, "minecraft:cobblestone", 11));
        evidence.set(5, item(5, "minecraft:dirt", 64));

        ProductionLedger ledger = MinecraftProductionSkillPorts.inventoryLedger(
                new TaskSensorSnapshot(inventoryQuery(), 10L,
                        TaskSensorAvailability.AVAILABLE, false, evidence))
                .orElseThrow();

        Assertions.assertAll(
                () -> Assertions.assertEquals(4, ledger.quantityOf(
                        ProductionMaterials.OAK_LOG)),
                () -> Assertions.assertEquals(11, ledger.quantityOf(
                        ProductionMaterials.COBBLESTONE)),
                () -> Assertions.assertEquals(0, ledger.quantityOf(
                        ProductionMaterials.COAL)));
    }

    @Test
    void inventoryLedgerRejectsAPartialOrMalformedTaskSensorSnapshot() {
        List<TaskSensorEvidence> evidence = emptyInventoryEvidence();
        Assertions.assertTrue(MinecraftProductionSkillPorts.inventoryLedger(
                new TaskSensorSnapshot(inventoryQuery(), 10L,
                        TaskSensorAvailability.AVAILABLE, true, evidence))
                .isEmpty());

        evidence.set(0, new TaskSensorEvidence("inventory.slot",
                new SkillParameters(Map.of(
                        "slot", 0,
                        "empty", true,
                        "count", 1))));
        Assertions.assertTrue(MinecraftProductionSkillPorts.inventoryLedger(
                new TaskSensorSnapshot(inventoryQuery(), 10L,
                        TaskSensorAvailability.AVAILABLE, false, evidence))
                .isEmpty());
    }

    @Test
    void resourceCandidateAcceptsOnlyTheExactTaskSensorSchema() {
        TaskSensorEvidence valid = new TaskSensorEvidence(
                "resource.candidate", new SkillParameters(Map.of(
                        "x", 12,
                        "y", 64,
                        "z", -3,
                        "block", "minecraft:iron_ore")));
        MinecraftProductionSkillPorts.CandidateEvidence candidate =
                MinecraftProductionSkillPorts.resourceCandidate(valid)
                        .orElseThrow();

        Assertions.assertAll(
                () -> Assertions.assertEquals(12, candidate.position().x()),
                () -> Assertions.assertEquals("minecraft:iron_ore",
                        candidate.blockId()),
                () -> Assertions.assertTrue(MinecraftProductionSkillPorts
                        .resourceCandidate(new TaskSensorEvidence(
                                "resource.candidate",
                                new SkillParameters(Map.of(
                                        "x", 12,
                                        "y", 64,
                                        "z", -3,
                                        "block", "minecraft:iron_ore",
                                        "extra", true))))
                        .isEmpty()));
    }

    @Test
    void acquisitionMethodsHaveNoTagOrFallbackBlockMapping() {
        Assertions.assertAll(
                () -> Assertions.assertEquals("minecraft:oak_log",
                        MinecraftProductionSkillPorts.expectedResourceBlock(
                                acquisition(AcquisitionMethod.HARVEST_LOG,
                                        ProductionMaterials.OAK_LOG))),
                () -> Assertions.assertEquals("minecraft:cobblestone",
                        MinecraftProductionSkillPorts.expectedResourceBlock(
                                acquisition(AcquisitionMethod.MINE_COBBLESTONE,
                                        ProductionMaterials.COBBLESTONE))),
                () -> Assertions.assertEquals("minecraft:iron_ore",
                        MinecraftProductionSkillPorts.expectedResourceBlock(
                                acquisition(AcquisitionMethod.MINE_RAW_IRON,
                                        ProductionMaterials.RAW_IRON))),
                () -> Assertions.assertEquals("minecraft:coal_ore",
                        MinecraftProductionSkillPorts.expectedResourceBlock(
                                acquisition(AcquisitionMethod.MINE_COAL,
                                        ProductionMaterials.COAL))));
    }

    @Test
    void productionCandidatesUseExactSensorFiltersBeforeTheEvidenceCap() {
        Assertions.assertAll(
                () -> Assertions.assertEquals(
                        TaskSensorResourceFilter.OAK_LOG,
                        MinecraftProductionSkillPorts
                                .resourceFilterForExpectedBlock(
                                        "minecraft:oak_log")),
                () -> Assertions.assertEquals(
                        TaskSensorResourceFilter.COBBLESTONE,
                        MinecraftProductionSkillPorts
                                .resourceFilterForExpectedBlock(
                                        "minecraft:cobblestone")),
                () -> Assertions.assertEquals(
                        TaskSensorResourceFilter.IRON_ORE,
                        MinecraftProductionSkillPorts
                                .resourceFilterForExpectedBlock(
                                        "minecraft:iron_ore")),
                () -> Assertions.assertEquals(
                        TaskSensorResourceFilter.COAL_ORE,
                        MinecraftProductionSkillPorts
                                .resourceFilterForExpectedBlock(
                                        "minecraft:coal_ore")),
                () -> Assertions.assertEquals(
                        TaskSensorResourceFilter.CRAFTING_TABLE,
                        MinecraftProductionSkillPorts
                                .resourceFilterForExpectedBlock(
                                        "minecraft:crafting_table")),
                () -> Assertions.assertEquals(
                        TaskSensorResourceFilter.FURNACE,
                        MinecraftProductionSkillPorts
                                .resourceFilterForExpectedBlock(
                                        "minecraft:furnace")),
                () -> Assertions.assertThrows(
                        IllegalArgumentException.class,
                        () -> MinecraftProductionSkillPorts
                                .resourceFilterForExpectedBlock(
                                        "minecraft:stone")));
    }

    @Test
    void reachableResourceCandidatesPreferTheBlockUnderTheCurrentBody() {
        Vec3 standingOnTarget = new Vec3(3.5D, 2.0D, 4.5D);
        BlockCoordinates underneath = new BlockCoordinates(3, 1, 4);
        BlockCoordinates neighboring = new BlockCoordinates(4, 1, 3);

        Assertions.assertAll(
                () -> Assertions.assertTrue(MinecraftProductionSkillPorts
                        .compareReachableResourceCandidates(
                                standingOnTarget, underneath, neighboring)
                        < 0),
                () -> Assertions.assertTrue(MinecraftProductionSkillPorts
                        .compareReachableResourceCandidates(
                                standingOnTarget, neighboring, underneath)
                        > 0));
    }

    @Test
    void resourceAcquisitionRequiresGroundedReachableResourceApproach() {
        BlockCoordinates resource = new BlockCoordinates(3, 1, 4);

        Assertions.assertTrue(MinecraftProductionSkillPorts
                .groundedAtResourceApproach(new BlockPos(4, 1, 4), true,
                        resource));
        Assertions.assertTrue(MinecraftProductionSkillPorts
                .groundedAtResourceApproach(new BlockPos(3, 2, 4), true,
                        resource));
        Assertions.assertFalse(MinecraftProductionSkillPorts
                        .groundedAtResourceApproach(new BlockPos(4, 1, 4), false,
                                resource),
                "an airborne body must not break an apparent nearby resource");
        Assertions.assertFalse(MinecraftProductionSkillPorts
                        .groundedAtResourceApproach(new BlockPos(4, 1, 5), true,
                                resource),
                "a diagonal cell must not satisfy the radius-one approach");
    }

    private static ResourceAcquisition acquisition(
            AcquisitionMethod method,
            io.github.greytaiwolf.botplayer.skill.builtin.production
                    .ProductionMaterial material) {
        return new ResourceAcquisition(method, ProductionLedger.of(
                material, 1));
    }

    private static List<TaskSensorEvidence> emptyInventoryEvidence() {
        List<TaskSensorEvidence> evidence = new ArrayList<>();
        for (int slot = 0; slot < MinecraftProductionSkillPorts
                .INVENTORY_SLOTS; slot++) {
            evidence.add(new TaskSensorEvidence("inventory.slot",
                    new SkillParameters(Map.of(
                            "slot", slot,
                            "empty", true,
                            "count", 0))));
        }
        evidence.add(new TaskSensorEvidence("inventory.selected",
                new SkillParameters(Map.of("slot", 0))));
        return evidence;
    }

    private static TaskSensorEvidence item(
            int slot, String item, int count) {
        return new TaskSensorEvidence("inventory.slot",
                new SkillParameters(Map.of(
                        "slot", slot,
                        "empty", false,
                        "count", count,
                        "item", item,
                        "damage", 0,
                        "digest", DIGEST)));
    }

    private static TaskSensorQuery inventoryQuery() {
        return new TaskSensorQuery(
                new TaskSensorRunIdentity(BOT, 1L, RUN, 1L),
                TaskSensorQueryType.SELF_INVENTORY,
                new TaskSensorScope("minecraft:overworld", 0, 64, 0,
                        8, 1L),
                new TaskSensorBudget(0,
                        MinecraftProductionSkillPorts.INVENTORY_SLOTS,
                        0,
                        0,
                        MinecraftProductionSkillPorts.INVENTORY_EVIDENCE,
                        0L));
    }
}
