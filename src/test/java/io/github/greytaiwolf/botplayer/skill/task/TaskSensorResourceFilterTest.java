package io.github.greytaiwolf.botplayer.skill.task;

import java.util.UUID;
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
}
