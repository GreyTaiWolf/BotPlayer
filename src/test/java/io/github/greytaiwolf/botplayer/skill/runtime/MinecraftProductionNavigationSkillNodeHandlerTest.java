package io.github.greytaiwolf.botplayer.skill.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.greytaiwolf.botplayer.navigation.GridPoint;
import io.github.greytaiwolf.botplayer.navigation.NavigationGoal;
import io.github.greytaiwolf.botplayer.skill.builtin.P5ABuiltinSkillIds;
import io.github.greytaiwolf.botplayer.skill.core.SkillParameters;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanNode;
import io.github.greytaiwolf.botplayer.skill.reservation.ResourceReservationService;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillNodeContext;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorAvailability;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorBudget;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorEvidence;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorQuery;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorQueryType;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorResourceFilter;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorRunIdentity;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorScope;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorSnapshot;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MinecraftProductionNavigationSkillNodeHandlerTest {
    private static final UUID BOT = new UUID(20L, 1L);
    private static final UUID RUN = new UUID(20L, 2L);
    private static final UUID NODE = new UUID(20L, 3L);

    @Test
    void acceptsOnlyOneCompilerReviewedResourceBlockParameter() {
        assertEquals("minecraft:oak_log",
                MinecraftProductionNavigationSkillNodeHandler
                        .approvedResourceBlock(context(Map.of(
                                P5ABuiltinSkillIds
                                        .RESOURCE_NAVIGATION_BLOCK_ID_PARAMETER,
                                "minecraft:oak_log")))
                        .orElseThrow());
        assertTrue(MinecraftProductionNavigationSkillNodeHandler
                .approvedResourceBlock(context(Map.of(
                        P5ABuiltinSkillIds
                                .RESOURCE_NAVIGATION_BLOCK_ID_PARAMETER,
                        "minecraft:stone")))
                .isEmpty());
        assertTrue(MinecraftProductionNavigationSkillNodeHandler
                .approvedResourceBlock(context(Map.of(
                        P5ABuiltinSkillIds
                                .RESOURCE_NAVIGATION_BLOCK_ID_PARAMETER,
                        "minecraft:oak_log",
                        "target.x",
                        4)))
                .isEmpty());
    }

    @Test
    void selectsOnlyExactCurrentCandidateInsideTaskSensorScope() {
        TaskSensorQuery query = query();
        TaskSensorSnapshot valid = snapshot(query, List.of(candidate(
                3, 65, -2, "minecraft:oak_log")));
        TaskSensorSnapshot wrongBlock = snapshot(query, List.of(candidate(
                3, 65, -2, "minecraft:stone")));
        TaskSensorSnapshot outsideScope = snapshot(query, List.of(candidate(
                9, 64, 0, "minecraft:oak_log")));
        TaskSensorSnapshot malformed = snapshot(query, List.of(
                new TaskSensorEvidence("resource.candidate",
                        new SkillParameters(Map.of(
                                "x", 3,
                                "y", 65,
                                "z", -2,
                                "block", "minecraft:oak_log",
                                "extra", true)))));

        assertEquals(new GridPoint(3, 65, -2),
                MinecraftProductionNavigationSkillNodeHandler.selectGoal(
                        valid, query, "minecraft:oak_log").orElseThrow());
        assertTrue(MinecraftProductionNavigationSkillNodeHandler.selectGoal(
                wrongBlock, query, "minecraft:oak_log").isEmpty());
        assertTrue(MinecraftProductionNavigationSkillNodeHandler.selectGoal(
                outsideScope, query, "minecraft:oak_log").isEmpty());
        assertTrue(MinecraftProductionNavigationSkillNodeHandler.selectGoal(
                malformed, query, "minecraft:oak_log").isEmpty());
    }

    @Test
    void resourceNavigationGoalRequiresStandingAboveTheSensedBlock() {
        GridPoint resource = new GridPoint(3, 64, 3);
        GridPoint pickupStand = MinecraftProductionNavigationSkillNodeHandler
                .pickupStandGoal(resource);
        NavigationGoal.NearPosition goal = new NavigationGoal.NearPosition(
                "minecraft:overworld",
                pickupStand,
                MinecraftProductionNavigationSkillNodeHandler
                        .RESOURCE_GOAL_RADIUS);

        assertEquals(new GridPoint(3, 65, 3), pickupStand);
        assertTrue(goal.reached(pickupStand));
        assertFalse(goal.reached(resource),
                "the solid resource itself is not a safe pickup stand");
        assertFalse(goal.reached(new GridPoint(4, 65, 3)),
                "radius zero must not complete from an adjacent cell");
    }

    private static SkillNodeContext context(Map<String, Object> parameters) {
        return new SkillNodeContext(
                RUN,
                BOT,
                1L,
                1L,
                0L,
                1L,
                new SkillPlanNode(
                        NODE,
                        P5ABuiltinSkillIds.NAVIGATE_TO_RESOURCE,
                        P5ABuiltinSkillIds.VERSION,
                        new SkillParameters(parameters)),
                0,
                10L,
                100L,
                new ResourceReservationService(1, 1));
    }

    private static TaskSensorQuery query() {
        return new TaskSensorQuery(
                new TaskSensorRunIdentity(BOT, 1L, RUN, 1L),
                TaskSensorQueryType.RESOURCE_CANDIDATES,
                new TaskSensorScope(
                        "minecraft:overworld",
                        0,
                        64,
                        0,
                        MinecraftProductionNavigationSkillNodeHandler
                                .RESOURCE_RADIUS,
                        1L),
                new TaskSensorBudget(
                        MinecraftProductionNavigationSkillNodeHandler
                                .RESOURCE_MAX_CANDIDATES,
                        0,
                        MinecraftProductionNavigationSkillNodeHandler
                                .RESOURCE_MAX_BLOCKS,
                        0,
                        MinecraftProductionNavigationSkillNodeHandler
                                .RESOURCE_MAX_EVIDENCE,
                        0L),
                TaskSensorResourceFilter.OAK_LOG);
    }

    private static TaskSensorSnapshot snapshot(
            TaskSensorQuery query, List<TaskSensorEvidence> evidence) {
        return new TaskSensorSnapshot(
                query,
                10L,
                TaskSensorAvailability.AVAILABLE,
                false,
                evidence);
    }

    private static TaskSensorEvidence candidate(
            int x, int y, int z, String blockId) {
        return new TaskSensorEvidence("resource.candidate",
                new SkillParameters(Map.of(
                        "x", x,
                        "y", y,
                        "z", z,
                        "block", blockId)));
    }
}
