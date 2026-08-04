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
}
