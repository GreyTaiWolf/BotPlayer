package io.github.greytaiwolf.botplayer.skill.runtime;

import io.github.greytaiwolf.botplayer.action.interaction.InventoryContentsSnapshot;
import io.github.greytaiwolf.botplayer.action.interaction.InventoryLayoutCleanupLease;
import io.github.greytaiwolf.botplayer.action.interaction.InventoryLayoutCleanupRequest;
import io.github.greytaiwolf.botplayer.action.interaction.InventoryLayoutCleanupResult;
import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import io.github.greytaiwolf.botplayer.skill.core.SkillRegistry;
import io.github.greytaiwolf.botplayer.skill.core.SkillRunState;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SurvivalSkillServiceVanillaDeathTest {
    private static final UUID BOT = new UUID(7L, 1L);
    private static final String COMPONENTS =
            "0000000000000000000000000000000000000000000000000000000000000000";
    private static final ItemStackFingerprint FOOD =
            ItemStackFingerprint.of(
                    new ResourceId("minecraft:bread"),
                    1,
                    0,
                    COMPONENTS);

    @Test
    void retriesTheExactLeaseAfterTheFirstConsumeThrows()
            throws ReflectiveOperationException {
        FakeLayoutCompensator compensator =
                new FakeLayoutCompensator();
        compensator.consumeFailuresRemaining = 1;
        SurvivalSkillService service = service(compensator);
        InventoryLayoutCleanupLease lease = lease();
        addRun(service, BOT, 11L, lease);

        Assertions.assertFalse(
                service.closeVanillaDeathConsumedGeneration(
                        BOT, 11L, 5L, true));
        Assertions.assertEquals(
                SkillRunState.CREATED,
                service.inspect(BOT).orElseThrow().state());

        Assertions.assertTrue(
                service.closeVanillaDeathConsumedGeneration(
                        BOT, 11L, 6L, true));
        Assertions.assertEquals(2, compensator.consumeCalls.size());
        for (ConsumeCall call : compensator.consumeCalls) {
            Assertions.assertEquals(BOT, call.botId());
            Assertions.assertEquals(11L, call.generation());
            Assertions.assertSame(lease, call.lease());
        }
        Assertions.assertEquals(
                List.of(new CloseCall(BOT, 11L)),
                compensator.closeCalls);
        Assertions.assertEquals(0, compensator.releaseCalls);
        Assertions.assertEquals(
                SkillRunState.CANCELLED,
                service.inspect(BOT).orElseThrow().state());
    }

    @Test
    void retryAfterCloseGenerationThrowsDoesNotConsumeTwice()
            throws ReflectiveOperationException {
        FakeLayoutCompensator compensator =
                new FakeLayoutCompensator();
        compensator.closeFailuresRemaining = 1;
        SurvivalSkillService service = service(compensator);
        InventoryLayoutCleanupLease lease = lease();
        addRun(service, BOT, 12L, lease);

        Assertions.assertThrows(
                ExpectedCloseFailure.class,
                () -> service.closeVanillaDeathConsumedGeneration(
                        BOT, 12L, 5L, true));
        Assertions.assertEquals(1, compensator.consumeCalls.size());
        Assertions.assertSame(
                lease, compensator.consumeCalls.get(0).lease());
        Assertions.assertEquals(
                SkillRunState.CANCELLED,
                service.inspect(BOT).orElseThrow().state());

        Assertions.assertTrue(
                service.closeVanillaDeathConsumedGeneration(
                        BOT, 12L, 6L, true));
        Assertions.assertEquals(1, compensator.consumeCalls.size());
        Assertions.assertEquals(
                List.of(
                        new CloseCall(BOT, 12L),
                        new CloseCall(BOT, 12L)),
                compensator.closeCalls);
        Assertions.assertEquals(0, compensator.releaseCalls);
    }

    @Test
    void absentRunIsAlreadySafe()
            throws ReflectiveOperationException {
        FakeLayoutCompensator compensator =
                new FakeLayoutCompensator();
        SurvivalSkillService service = service(compensator);

        Assertions.assertTrue(
                service.closeVanillaDeathConsumedGeneration(
                        BOT, 13L, 5L, true));
        Assertions.assertTrue(service.inspect(BOT).isEmpty());
        Assertions.assertTrue(compensator.consumeCalls.isEmpty());
        Assertions.assertEquals(
                List.of(new CloseCall(BOT, 13L)),
                compensator.closeCalls);
    }

    @Test
    void closingAnOldAbsentGenerationDoesNotTouchANewerRun()
            throws ReflectiveOperationException {
        FakeLayoutCompensator compensator =
                new FakeLayoutCompensator();
        SurvivalSkillService service = service(compensator);
        Object newer = addRun(service, BOT, 15L, null);

        Assertions.assertTrue(
                service.closeVanillaDeathConsumedGeneration(
                        BOT, 14L, 5L, true));

        SurvivalSkillRunView view =
                service.inspect(BOT).orElseThrow();
        Assertions.assertEquals(15L, view.botGeneration());
        Assertions.assertEquals(SkillRunState.CREATED, view.state());
        Assertions.assertSame(newer, activeRuns(service).get(BOT));
        Assertions.assertTrue(compensator.consumeCalls.isEmpty());
        Assertions.assertEquals(
                List.of(new CloseCall(BOT, 14L)),
                compensator.closeCalls);
    }

    private static SurvivalSkillService service(
            FakeLayoutCompensator compensator) {
        return new SurvivalSkillService(
                new SkillRegistry(),
                (botId, generation) -> Optional.empty(),
                (envelope, priority) -> {
                    throw new AssertionError(
                            "death closure must not submit an action");
                },
                (botId, actionId) -> {
                    throw new AssertionError(
                            "death closure must not cancel an action");
                },
                compensator,
                (botId, generation, currentTick) -> true);
    }

    private static Object addRun(
            SurvivalSkillService service,
            UUID botId,
            long generation,
            InventoryLayoutCleanupLease lease)
            throws ReflectiveOperationException {
        Class<?> runType = Class.forName(
                SurvivalSkillService.class.getName()
                        + "$ActiveRun");
        Constructor<?> constructor = runType.getDeclaredConstructor(
                UUID.class,
                UUID.class,
                UUID.class,
                long.class,
                SurvivalSkillKind.class,
                long.class,
                long.class,
                long.class);
        constructor.setAccessible(true);
        Object run = constructor.newInstance(
                lease == null ? UUID.randomUUID() : lease.runId(),
                UUID.randomUUID(),
                botId,
                generation,
                SurvivalSkillKind.EAT_FOOD,
                1L,
                10L,
                20L);
        if (lease != null) {
            setField(runType, run, "layoutLease", lease);
            setField(runType, run, "layoutLeaseOpen", true);
        }
        activeRuns(service).put(botId, run);
        return run;
    }

    private static void setField(
            Class<?> owner,
            Object target,
            String name,
            Object value)
            throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, Object> activeRuns(
            SurvivalSkillService service)
            throws ReflectiveOperationException {
        Field field = SurvivalSkillService.class
                .getDeclaredField("activeRuns");
        field.setAccessible(true);
        return (Map<UUID, Object>) field.get(service);
    }

    private static InventoryLayoutCleanupLease lease() {
        return new InventoryLayoutCleanupLease(
                UUID.randomUUID(),
                9,
                1,
                FOOD,
                new InventoryContentsSnapshot(List.of(FOOD)),
                0,
                true,
                true);
    }

    private record ConsumeCall(
            UUID botId,
            long generation,
            InventoryLayoutCleanupLease lease) {}

    private record CloseCall(UUID botId, long generation) {}

    private static final class FakeLayoutCompensator
            implements SurvivalSkillService
                    .GenerationLayoutCompensator {
        private final List<ConsumeCall> consumeCalls =
                new ArrayList<>();
        private final List<CloseCall> closeCalls =
                new ArrayList<>();
        private int consumeFailuresRemaining;
        private int closeFailuresRemaining;
        private int releaseCalls;

        @Override
        public boolean open(
                UUID botId,
                long generation,
                InventoryLayoutCleanupLease layoutLease) {
            throw new AssertionError(
                    "test run is injected after its lease is armed");
        }

        @Override
        public InventoryLayoutCleanupResult cleanup(
                UUID botId,
                long generation,
                InventoryLayoutCleanupRequest request) {
            throw new AssertionError(
                    "vanilla death must not use ordinary cleanup");
        }

        @Override
        public InventoryLayoutCleanupResult consumeVanillaDeath(
                UUID botId,
                long generation,
                InventoryLayoutCleanupLease layoutLease) {
            consumeCalls.add(new ConsumeCall(
                    botId, generation, layoutLease));
            if (consumeFailuresRemaining > 0) {
                consumeFailuresRemaining--;
                throw new ExpectedConsumeFailure();
            }
            return InventoryLayoutCleanupResult
                    .VANILLA_DEATH_CONSUMED;
        }

        @Override
        public void release(
                UUID botId,
                long generation,
                UUID runId) {
            releaseCalls++;
        }

        @Override
        public void closeGeneration(
                UUID botId,
                long generation) {
            closeCalls.add(new CloseCall(botId, generation));
            if (closeFailuresRemaining > 0) {
                closeFailuresRemaining--;
                throw new ExpectedCloseFailure();
            }
        }

        @Override
        public void closeAll() {
            throw new AssertionError(
                    "generation closure must not close the service");
        }
    }

    private static final class ExpectedConsumeFailure
            extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static final class ExpectedCloseFailure
            extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
