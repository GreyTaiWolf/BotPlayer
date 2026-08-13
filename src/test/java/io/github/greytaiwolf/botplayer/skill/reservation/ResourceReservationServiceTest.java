package io.github.greytaiwolf.botplayer.skill.reservation;

import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ResourceReservationServiceTest {
    private static final UUID FIRST_BOT = new UUID(0L, 501L);
    private static final UUID SECOND_BOT = new UUID(0L, 502L);
    private static final UUID FIRST_RUN = new UUID(0L, 601L);
    private static final UUID SECOND_RUN = new UUID(0L, 602L);
    private static final ReservationKey CHEST = new ReservationKey(
            ReservationKey.Kind.CONTAINER,
            "minecraft:overworld",
            "12,64,8");

    @Test
    void exclusiveLeaseConflictsAndExpires() {
        ResourceReservationService service =
                new ResourceReservationService(4, 100);

        ResourceReservationService.AcquireResult first =
                service.acquire(
                        FIRST_BOT,
                        1L,
                        FIRST_RUN,
                        CHEST,
                        ReservationMode.EXCLUSIVE,
                        10L,
                        5);
        ResourceReservationService.AcquireResult conflict =
                service.acquire(
                        SECOND_BOT,
                        1L,
                        SECOND_RUN,
                        CHEST,
                        ReservationMode.EXCLUSIVE,
                        10L,
                        5);

        Assertions.assertAll(
                () -> Assertions.assertEquals(
                        ResourceReservationService.AcquireStatus
                                .ACQUIRED,
                        first.status()),
                () -> Assertions.assertEquals(
                        ResourceReservationService.AcquireStatus
                                .CONFLICT,
                        conflict.status()),
                () -> Assertions.assertEquals(
                        0, service.activeLeaseCount(15L)));
    }

    @Test
    void sharedReadersCanCoexistButBlockAWriter() {
        ResourceReservationService service =
                new ResourceReservationService(4, 100);
        service.acquire(
                FIRST_BOT,
                1L,
                FIRST_RUN,
                CHEST,
                ReservationMode.SHARED_READ,
                1L,
                20);

        ResourceReservationService.AcquireResult reader =
                service.acquire(
                        SECOND_BOT,
                        1L,
                        SECOND_RUN,
                        CHEST,
                        ReservationMode.SHARED_READ,
                        1L,
                        20);
        ResourceReservationService.AcquireResult writer =
                service.acquire(
                        SECOND_BOT,
                        1L,
                        new UUID(0L, 603L),
                        CHEST,
                        ReservationMode.EXCLUSIVE,
                        1L,
                        20);

        Assertions.assertAll(
                () -> Assertions.assertEquals(
                        ResourceReservationService.AcquireStatus
                                .ACQUIRED,
                        reader.status()),
                () -> Assertions.assertEquals(
                        ResourceReservationService.AcquireStatus
                                .CONFLICT,
                        writer.status()),
                () -> Assertions.assertEquals(
                        2,
                        service.inspect(CHEST, 1L).size()));
    }

    @Test
    void renewalInvalidatesTheOldToken() {
        ResourceReservationService service =
                new ResourceReservationService(2, 100);
        ReservationToken original = service.acquire(
                        FIRST_BOT,
                        2L,
                        FIRST_RUN,
                        CHEST,
                        ReservationMode.EXCLUSIVE,
                        5L,
                        10)
                .token()
                .orElseThrow();
        ReservationToken renewed = service.renew(
                        original, 8L, 20)
                .token()
                .orElseThrow();

        Assertions.assertAll(
                () -> Assertions.assertEquals(
                        28L, renewed.expiresTick()),
                () -> Assertions.assertEquals(
                        ResourceReservationService.ReleaseStatus
                                .STALE_TOKEN,
                        service.release(original)),
                () -> Assertions.assertEquals(
                        ResourceReservationService.ReleaseStatus
                                .RELEASED,
                        service.release(renewed)));
    }

    @Test
    void generationAndRunCleanupAreExact() {
        ResourceReservationService service =
                new ResourceReservationService(4, 100);
        ReservationKey block = new ReservationKey(
                ReservationKey.Kind.BLOCK,
                "minecraft:overworld",
                "1,2,3");
        service.acquire(
                FIRST_BOT,
                3L,
                FIRST_RUN,
                CHEST,
                ReservationMode.EXCLUSIVE,
                1L,
                20);
        service.acquire(
                FIRST_BOT,
                3L,
                SECOND_RUN,
                block,
                ReservationMode.EXCLUSIVE,
                1L,
                20);

        Assertions.assertAll(
                () -> Assertions.assertEquals(
                        1,
                        service.releaseRun(
                                FIRST_BOT,
                                3L,
                                FIRST_RUN)),
                () -> Assertions.assertEquals(
                        1,
                        service.closeGeneration(
                                FIRST_BOT, 3L)),
                () -> Assertions.assertEquals(
                        0, service.activeLeaseCount(1L)));
    }

    @Test
    void capacityIdsTimeAndThreadAreFailClosed()
            throws InterruptedException {
        UUID reused = new UUID(0L, 701L);
        ArrayDeque<UUID> ids = new ArrayDeque<>(
                List.of(reused, reused, reused, reused,
                        reused, reused, reused, reused, reused));
        ResourceReservationService service =
                new ResourceReservationService(
                        1, 100, ids::removeFirst);
        service.acquire(
                FIRST_BOT,
                1L,
                FIRST_RUN,
                CHEST,
                ReservationMode.EXCLUSIVE,
                2L,
                10);
        ResourceReservationService.AcquireResult full =
                service.acquire(
                        SECOND_BOT,
                        1L,
                        SECOND_RUN,
                        new ReservationKey(
                                ReservationKey.Kind.BLOCK,
                                "minecraft:overworld",
                                "9,9,9"),
                        ReservationMode.EXCLUSIVE,
                        2L,
                        10);
        AtomicReference<Throwable> failure =
                new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                service.activeLeaseCount(2L);
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        thread.start();
        thread.join();

        Assertions.assertAll(
                () -> Assertions.assertEquals(
                        ResourceReservationService.AcquireStatus
                                .CAPACITY_EXHAUSTED,
                        full.status()),
                () -> Assertions.assertThrows(
                        IllegalArgumentException.class,
                        () -> service.activeLeaseCount(1L)),
                () -> Assertions.assertInstanceOf(
                        IllegalStateException.class,
                        failure.get()));
    }

    @Test
    void multiResourceAcquireUsesCanonicalOrderAndReportsEachToken() {
        ResourceReservationService service =
                new ResourceReservationService(6, 100);
        ReservationKey block = new ReservationKey(
                ReservationKey.Kind.BLOCK,
                "minecraft:overworld",
                "3,64,3");
        ReservationKey workArea = new ReservationKey(
                ReservationKey.Kind.WORK_AREA,
                "minecraft:overworld",
                "3,64,3:mine");

        ResourceReservationService.AcquireAllResult result =
                service.acquireAll(
                        FIRST_BOT,
                        1L,
                        FIRST_RUN,
                        List.of(
                                new ReservationRequest(
                                        workArea,
                                        ReservationMode.EXCLUSIVE),
                                new ReservationRequest(
                                        CHEST,
                                        ReservationMode.EXCLUSIVE),
                                new ReservationRequest(
                                        block,
                                        ReservationMode.EXCLUSIVE)),
                        10L,
                        20);

        Assertions.assertAll(
                () -> Assertions.assertEquals(
                        ResourceReservationService.AcquireAllStatus
                                .ACQUIRED,
                        result.status()),
                () -> Assertions.assertEquals(
                        List.of(block, CHEST, workArea),
                        result.entries().stream()
                                .map(ResourceReservationService
                                        .AcquireAllEntry::key)
                                .toList()),
                () -> Assertions.assertTrue(result.acquired()),
                () -> Assertions.assertEquals(
                        3, service.activeLeaseCount(10L)),
                () -> Assertions.assertThrows(
                        UnsupportedOperationException.class,
                        () -> result.entries().add(null)));
    }

    @Test
    void duplicateKeysFailClosedWithoutCreatingAnyLease() {
        ResourceReservationService service =
                new ResourceReservationService(4, 100);
        ReservationKey block = new ReservationKey(
                ReservationKey.Kind.BLOCK,
                "minecraft:overworld",
                "8,64,8");
        service.acquire(
                FIRST_BOT,
                1L,
                FIRST_RUN,
                CHEST,
                ReservationMode.SHARED_READ,
                10L,
                20);

        ResourceReservationService.AcquireAllResult result =
                service.acquireAll(
                        SECOND_BOT,
                        1L,
                        SECOND_RUN,
                        List.of(
                                new ReservationRequest(
                                        block,
                                        ReservationMode.EXCLUSIVE),
                                new ReservationRequest(
                                        block,
                                        ReservationMode.EXCLUSIVE)),
                        10L,
                        20);

        Assertions.assertAll(
                () -> Assertions.assertEquals(
                        ResourceReservationService.AcquireAllStatus
                                .DUPLICATE_KEY,
                        result.status()),
                () -> Assertions.assertTrue(result.entries().isEmpty()),
                () -> Assertions.assertEquals(
                        1, service.activeLeaseCount(10L)),
                () -> Assertions.assertTrue(
                        service.inspect(block, 10L).isEmpty()));
    }

    @Test
    void atomicCapacityAndIdFailuresLeaveNoPartialLease() {
        ReservationKey block = new ReservationKey(
                ReservationKey.Kind.BLOCK,
                "minecraft:overworld",
                "6,64,6");
        ReservationKey workArea = new ReservationKey(
                ReservationKey.Kind.WORK_AREA,
                "minecraft:overworld",
                "6,64,6:mine");
        ResourceReservationService limited =
                new ResourceReservationService(2, 100);
        limited.acquire(
                FIRST_BOT,
                1L,
                FIRST_RUN,
                CHEST,
                ReservationMode.EXCLUSIVE,
                5L,
                20);

        ResourceReservationService.AcquireAllResult capacity =
                limited.acquireAll(
                        SECOND_BOT,
                        1L,
                        SECOND_RUN,
                        List.of(
                                new ReservationRequest(
                                        block,
                                        ReservationMode.EXCLUSIVE),
                                new ReservationRequest(
                                        workArea,
                                        ReservationMode.EXCLUSIVE)),
                        5L,
                        20);

        UUID repeated = new UUID(0L, 710L);
        ArrayDeque<UUID> ids = new ArrayDeque<>();
        ids.add(repeated);
        for (int index = 0; index < 8; index++) {
            ids.add(repeated);
        }
        ResourceReservationService idsUnavailable =
                new ResourceReservationService(
                        4, 100, ids::removeFirst);
        ResourceReservationService.AcquireAllResult idFailure =
                idsUnavailable.acquireAll(
                        FIRST_BOT,
                        1L,
                        FIRST_RUN,
                        List.of(
                                new ReservationRequest(
                                        block,
                                        ReservationMode.EXCLUSIVE),
                                new ReservationRequest(
                                        workArea,
                                        ReservationMode.EXCLUSIVE)),
                        5L,
                        20);

        Assertions.assertAll(
                () -> Assertions.assertEquals(
                        ResourceReservationService.AcquireAllStatus
                                .CAPACITY_EXHAUSTED,
                        capacity.status()),
                () -> Assertions.assertEquals(
                        1, limited.activeLeaseCount(5L)),
                () -> Assertions.assertTrue(
                        limited.inspect(block, 5L).isEmpty()),
                () -> Assertions.assertEquals(
                        ResourceReservationService.AcquireAllStatus
                                .ID_UNAVAILABLE,
                        idFailure.status()),
                () -> Assertions.assertEquals(
                        0, idsUnavailable.activeLeaseCount(5L)),
                () -> Assertions.assertTrue(
                        idsUnavailable.inspect(block, 5L).isEmpty()));
    }

    @Test
    void existingCompatibleLeaseIsReturnedWithoutBreakingAtomicity() {
        ResourceReservationService service =
                new ResourceReservationService(4, 100);
        ReservationKey block = new ReservationKey(
                ReservationKey.Kind.BLOCK,
                "minecraft:overworld",
                "13,64,8");
        ReservationToken held = service.acquire(
                        FIRST_BOT,
                        2L,
                        FIRST_RUN,
                        CHEST,
                        ReservationMode.SHARED_READ,
                        10L,
                        20)
                .token()
                .orElseThrow();

        ResourceReservationService.AcquireAllResult result =
                service.acquireAll(
                        FIRST_BOT,
                        2L,
                        FIRST_RUN,
                        List.of(
                                new ReservationRequest(
                                        block,
                                        ReservationMode.EXCLUSIVE),
                                new ReservationRequest(
                                        CHEST,
                                        ReservationMode.SHARED_READ)),
                        10L,
                        20);

        Assertions.assertAll(
                () -> Assertions.assertEquals(
                        ResourceReservationService.AcquireAllStatus
                                .ACQUIRED,
                        result.status()),
                () -> Assertions.assertEquals(
                        List.of(
                                ResourceReservationService.AcquireStatus
                                        .ACQUIRED,
                                ResourceReservationService.AcquireStatus
                                        .ALREADY_HELD),
                        result.entries().stream()
                                .map(ResourceReservationService
                                        .AcquireAllEntry::status)
                                .toList()),
                () -> Assertions.assertEquals(
                        held,
                        result.entries().get(1).token()),
                () -> Assertions.assertEquals(
                        2, service.activeLeaseCount(10L)));
    }

    @Test
    void modeMismatchOnAnExistingOwnerLeaseRejectsTheEntireBatch() {
        ResourceReservationService service =
                new ResourceReservationService(4, 100);
        ReservationKey block = new ReservationKey(
                ReservationKey.Kind.BLOCK,
                "minecraft:overworld",
                "14,64,8");
        service.acquire(
                FIRST_BOT,
                2L,
                FIRST_RUN,
                CHEST,
                ReservationMode.SHARED_READ,
                10L,
                20);

        ResourceReservationService.AcquireAllResult result =
                service.acquireAll(
                        FIRST_BOT,
                        2L,
                        FIRST_RUN,
                        List.of(
                                new ReservationRequest(
                                        block,
                                        ReservationMode.EXCLUSIVE),
                                new ReservationRequest(
                                        CHEST,
                                        ReservationMode.EXCLUSIVE)),
                        10L,
                        20);

        Assertions.assertAll(
                () -> Assertions.assertEquals(
                        ResourceReservationService.AcquireAllStatus
                                .CONFLICT,
                        result.status()),
                () -> Assertions.assertTrue(result.entries().isEmpty()),
                () -> Assertions.assertEquals(
                        1, service.activeLeaseCount(10L)),
                () -> Assertions.assertTrue(
                        service.inspect(block, 10L).isEmpty()));
    }
}
