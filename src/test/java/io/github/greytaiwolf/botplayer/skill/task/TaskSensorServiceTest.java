package io.github.greytaiwolf.botplayer.skill.task;

import io.github.greytaiwolf.botplayer.skill.core.SkillParameters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TaskSensorServiceTest {
    private static final UUID BOT_ID = new UUID(1L, 1L);
    private static final UUID RUN_ID = new UUID(1L, 2L);
    private static final TaskSensorRunIdentity IDENTITY =
            new TaskSensorRunIdentity(BOT_ID, 1L, RUN_ID, 1L);
    private static final TaskSensorScope SCOPE = new TaskSensorScope(
            "minecraft:overworld", 10, 64, 10, 8, 1L);
    private static final TaskSensorBudget BUDGET = new TaskSensorBudget(
            0, 4, 0, 0, 2, 2L);

    @Test
    void cachesOnlyCurrentIdentityAndExpiresAtBoundedTtl() {
        AtomicInteger sampled = new AtomicInteger();
        TaskSensorService service = service(
                IDENTITY, TaskSensorLimits.defaults());
        TaskSensorQuery query = query(SCOPE, BUDGET);
        TaskSensorSampler sampler = (requested, tick) -> {
            sampled.incrementAndGet();
            return available(requested, tick);
        };

        service.beginTick(100L);
        Assertions.assertEquals(
                TaskSensorResponse.Status.SAMPLED,
                service.query(query, 100L, sampler).status());
        Assertions.assertEquals(
                TaskSensorResponse.Status.CACHE_HIT,
                service.query(query, 100L, sampler).status());
        Assertions.assertEquals(1, sampled.get());

        service.beginTick(102L);
        Assertions.assertEquals(
                TaskSensorResponse.Status.CACHE_HIT,
                service.query(query, 102L, sampler).status());
        service.beginTick(103L);
        Assertions.assertEquals(
                TaskSensorResponse.Status.SAMPLED,
                service.query(query, 103L, sampler).status());
        Assertions.assertEquals(2, sampled.get());
    }

    @Test
    void rejectsOldGenerationOrRevisionBeforeSamplerRuns() {
        AtomicInteger sampled = new AtomicInteger();
        TaskSensorRunIdentity actual = new TaskSensorRunIdentity(
                BOT_ID, 2L, RUN_ID, 2L);
        TaskSensorService service = service(
                actual, TaskSensorLimits.defaults());
        service.beginTick(5L);

        TaskSensorResponse response = service.query(
                query(SCOPE, BUDGET),
                5L,
                (requested, tick) -> {
                    sampled.incrementAndGet();
                    return available(requested, tick);
                });

        Assertions.assertEquals(
                TaskSensorResponse.Status.STALE_IDENTITY, response.status());
        Assertions.assertTrue(response.snapshot().isEmpty());
        Assertions.assertEquals(0, sampled.get());
    }

    @Test
    void enforcesTickRunAndCacheCapacityWithoutResetByDuplicateBegin() {
        TaskSensorLimits limits = new TaskSensorLimits(
                1, 1, 64, 64, 1, 10L);
        TaskSensorService service = service(IDENTITY, limits);
        TaskSensorQuery first = query(SCOPE, BUDGET);
        TaskSensorQuery second = query(new TaskSensorScope(
                "minecraft:overworld", 11, 64, 10, 8, 2L), BUDGET);
        service.beginTick(20L);
        Assertions.assertEquals(
                TaskSensorResponse.Status.SAMPLED,
                service.query(first, 20L, TaskSensorServiceTest::available)
                        .status());
        service.beginTick(20L);
        Assertions.assertEquals(
                TaskSensorResponse.Status.TICK_BUDGET_EXHAUSTED,
                service.query(second, 20L, TaskSensorServiceTest::available)
                        .status());

        TaskSensorLimits runLimits = new TaskSensorLimits(
                4, 1, 64, 64, 4, 10L);
        TaskSensorService runService = service(IDENTITY, runLimits);
        runService.beginTick(21L);
        Assertions.assertEquals(
                TaskSensorResponse.Status.SAMPLED,
                runService.query(first, 21L, TaskSensorServiceTest::available)
                        .status());
        Assertions.assertEquals(
                TaskSensorResponse.Status.RUN_BUDGET_EXHAUSTED,
                runService.query(second, 21L, TaskSensorServiceTest::available)
                        .status());

        TaskSensorLimits cacheLimits = new TaskSensorLimits(
                4, 4, 64, 64, 1, 10L);
        TaskSensorService cacheService = service(IDENTITY, cacheLimits);
        cacheService.beginTick(22L);
        Assertions.assertEquals(
                TaskSensorResponse.Status.SAMPLED,
                cacheService.query(first, 22L, TaskSensorServiceTest::available)
                        .status());
        Assertions.assertEquals(
                TaskSensorResponse.Status.CACHE_CAPACITY_EXCEEDED,
                cacheService.query(second, 22L, TaskSensorServiceTest::available)
                        .status());
    }

    @Test
    void failsClosedForWrongTickMalformedSnapshotAndClosedTick() {
        TaskSensorService service = service(
                IDENTITY, TaskSensorLimits.defaults());
        TaskSensorQuery query = query(SCOPE, BUDGET);
        Assertions.assertEquals(
                TaskSensorResponse.Status.TICK_NOT_OPEN,
                service.query(query, 1L, TaskSensorServiceTest::available)
                        .status());
        service.beginTick(2L);
        Assertions.assertEquals(
                TaskSensorResponse.Status.INVALID_SNAPSHOT,
                service.query(query, 2L, (requested, tick) ->
                        available(requested, tick - 1L)).status());
        Assertions.assertEquals(
                TaskSensorResponse.Status.SAMPLER_FAILURE,
                service.query(new TaskSensorQuery(
                        IDENTITY,
                        TaskSensorQueryType.OPEN_MENU,
                        SCOPE,
                        BUDGET), 2L, (requested, tick) -> {
                            throw new IllegalStateException("test");
                        }).status());
    }

    @Test
    void snapshotAndEvidenceRemainImmutableAndUnavailableCarriesNoFacts() {
        List<TaskSensorEvidence> evidence = new ArrayList<>();
        evidence.add(new TaskSensorEvidence(
                "inventory.slot",
                new SkillParameters(Map.of("slot", 1))));
        TaskSensorSnapshot snapshot = new TaskSensorSnapshot(
                query(SCOPE, BUDGET),
                1L,
                TaskSensorAvailability.AVAILABLE,
                false,
                evidence);
        evidence.clear();
        Assertions.assertEquals(1, snapshot.evidence().size());
        Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.evidence().clear());
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new TaskSensorSnapshot(
                        query(SCOPE, BUDGET),
                        1L,
                        TaskSensorAvailability.UNAVAILABLE,
                        false,
                        List.of(new TaskSensorEvidence(
                                "inventory.slot",
                                SkillParameters.empty()))));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new TaskSensorQuery(
                        IDENTITY,
                        TaskSensorQueryType.SELF_INVENTORY,
                        SCOPE,
                        new TaskSensorBudget(1, 0, 0, 0, 1, 1L)));
    }

    private static TaskSensorService service(
            TaskSensorRunIdentity current, TaskSensorLimits limits) {
        return new TaskSensorService(
                (botId, skillRunId) -> botId.equals(current.botId())
                        && skillRunId.equals(current.skillRunId())
                                ? Optional.of(current)
                                : Optional.empty(),
                limits);
    }

    private static TaskSensorQuery query(
            TaskSensorScope scope, TaskSensorBudget budget) {
        return new TaskSensorQuery(
                IDENTITY,
                TaskSensorQueryType.SELF_INVENTORY,
                scope,
                budget);
    }

    private static TaskSensorSnapshot available(
            TaskSensorQuery query, long tick) {
        return new TaskSensorSnapshot(
                query,
                tick,
                TaskSensorAvailability.AVAILABLE,
                false,
                List.of(new TaskSensorEvidence(
                        "inventory.slot",
                        new SkillParameters(Map.of("slot", 1)))));
    }
}
