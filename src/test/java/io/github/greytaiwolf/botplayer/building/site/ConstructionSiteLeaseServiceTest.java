package io.github.greytaiwolf.botplayer.building.site;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.greytaiwolf.botplayer.action.interaction.BlockCoordinates;
import io.github.greytaiwolf.botplayer.action.interaction.BlockStateFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import io.github.greytaiwolf.botplayer.building.blueprint.Blueprint;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintCell;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintMaterialClass;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintOffset;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintPlacementRole;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintReplacePolicy;
import io.github.greytaiwolf.botplayer.building.construction.ConstructionWorkPlan;
import io.github.greytaiwolf.botplayer.skill.reservation.ReservationKey;
import io.github.greytaiwolf.botplayer.skill.reservation.ReservationMode;
import io.github.greytaiwolf.botplayer.skill.reservation.ReservationRequest;
import io.github.greytaiwolf.botplayer.skill.reservation.ReservationToken;
import io.github.greytaiwolf.botplayer.skill.reservation.ResourceReservationService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ConstructionSiteLeaseServiceTest {
    private static final UUID FIRST_BOT = new UUID(0L, 11L);
    private static final UUID SECOND_BOT = new UUID(0L, 12L);
    private static final UUID FIRST_RUN = new UUID(0L, 21L);
    private static final UUID SECOND_RUN = new UUID(0L, 22L);
    private static final UUID FIRST_SITE = new UUID(0L, 31L);
    private static final UUID SECOND_SITE = new UUID(0L, 32L);
    private static final ResourceId OVERWORLD = new ResourceId("minecraft:overworld");
    private static final ResourceId NETHER = new ResourceId("minecraft:the_nether");
    private static final BlockStateFingerprint STONE = new BlockStateFingerprint(
            new ResourceId("minecraft:stone"), Map.of());

    @Test
    void overlappingBindingsAreExclusivelyReserved() {
        ResourceReservationService reservations = new ResourceReservationService(16, 100);
        ConstructionSiteLeaseService service = new ConstructionSiteLeaseService(reservations);
        ConstructionSiteBinding first = binding(FIRST_SITE, OVERWORLD, 0, 64, 0,
                new BlueprintOffset(0, 0, 0));
        ConstructionSiteBinding second = binding(SECOND_SITE, OVERWORLD, 0, 64, 0,
                new BlueprintOffset(0, 0, 0));

        ConstructionSiteLease lease = requireAcquired(service, FIRST_BOT, 1L, FIRST_RUN,
                first, 10L, 20);
        ConstructionSiteLeaseService.AcquireResult conflict = service.acquire(
                SECOND_BOT, 1L, SECOND_RUN, second, 10L, 20);

        assertEquals(ConstructionSiteLeaseService.AcquireStatus.CONFLICT, conflict.status());
        assertTrue(conflict.lease().isEmpty());
        assertTrue(service.isCurrent(lease, first, 10L));
        assertEquals(1, reservations.activeLeaseCount(10L));
    }

    @Test
    void fixedTilesUseFloorDivisionAtNegativeBoundariesAndStayBounded() {
        ConstructionSiteBinding negativeBoundary = binding(FIRST_SITE, OVERWORLD, -1, 64, -1,
                new BlueprintOffset(0, 0, 0), new BlueprintOffset(1, 0, 1));
        List<ReservationKey> negativeKeys = ConstructionSiteLeaseService.tileKeysFor(
                negativeBoundary);

        assertEquals(List.of(
                        "construction/tile/-1/2/-1",
                        "construction/tile/-1/2/0",
                        "construction/tile/0/2/-1",
                        "construction/tile/0/2/0"),
                negativeKeys.stream().map(ReservationKey::subject).toList());
        assertTrue(negativeKeys.stream().allMatch(key -> key.scope().equals(OVERWORLD.value())));

        ConstructionSiteBinding maximumCrossing = binding(SECOND_SITE, OVERWORLD, 31, 31, 31,
                new BlueprintOffset(0, 0, 0), new BlueprintOffset(31, 31, 31));
        assertEquals(ConstructionSiteLeaseService.MAX_TILES_PER_BINDING,
                ConstructionSiteLeaseService.tileKeysFor(maximumCrossing).size());
    }

    @Test
    void differentDimensionsDoNotConflict() {
        ResourceReservationService reservations = new ResourceReservationService(16, 100);
        ConstructionSiteLeaseService service = new ConstructionSiteLeaseService(reservations);
        ConstructionSiteBinding overworld = binding(FIRST_SITE, OVERWORLD, 4, 64, 4,
                new BlueprintOffset(0, 0, 0));
        ConstructionSiteBinding nether = binding(SECOND_SITE, NETHER, 4, 64, 4,
                new BlueprintOffset(0, 0, 0));

        ConstructionSiteLease first = requireAcquired(service, FIRST_BOT, 1L, FIRST_RUN,
                overworld, 10L, 20);
        ConstructionSiteLease second = requireAcquired(service, SECOND_BOT, 1L, SECOND_RUN,
                nether, 10L, 20);

        assertTrue(service.isCurrent(first, overworld, 10L));
        assertTrue(service.isCurrent(second, nether, 10L));
        assertEquals(2, reservations.activeLeaseCount(10L));
    }

    @Test
    void atomicConflictDoesNotLeaveAnUnrelatedTileLease() {
        ResourceReservationService reservations = new ResourceReservationService(16, 100);
        ConstructionSiteLeaseService service = new ConstructionSiteLeaseService(reservations);
        ConstructionSiteBinding blocker = binding(SECOND_SITE, OVERWORLD, 32, 64, 0,
                new BlueprintOffset(0, 0, 0));
        ConstructionSiteBinding crossing = binding(FIRST_SITE, OVERWORLD, 31, 64, 0,
                new BlueprintOffset(0, 0, 0), new BlueprintOffset(31, 0, 0));
        requireAcquired(service, SECOND_BOT, 1L, SECOND_RUN, blocker, 10L, 20);

        ConstructionSiteLeaseService.AcquireResult result = service.acquire(
                FIRST_BOT, 1L, FIRST_RUN, crossing, 10L, 20);
        ReservationKey unblockedTile = ConstructionSiteLeaseService.tileKeysFor(crossing)
                .stream()
                .filter(key -> key.subject().equals("construction/tile/0/2/0"))
                .findFirst()
                .orElseThrow();

        assertEquals(ConstructionSiteLeaseService.AcquireStatus.CONFLICT, result.status());
        assertTrue(result.lease().isEmpty());
        assertTrue(reservations.inspect(unblockedTile, 10L).isEmpty());
        assertEquals(1, reservations.activeLeaseCount(10L));
    }

    @Test
    void exactBindingIsIdempotentButIdentityDriftIsRejected() {
        ResourceReservationService reservations = new ResourceReservationService(16, 100);
        ConstructionSiteLeaseService service = new ConstructionSiteLeaseService(reservations);
        ConstructionSiteBinding exact = binding(FIRST_SITE, OVERWORLD, 0, 64, 0,
                new BlueprintOffset(0, 0, 0));
        ConstructionSiteBinding drifted = binding(SECOND_SITE, OVERWORLD, 0, 64, 0,
                new BlueprintOffset(0, 0, 0));
        ConstructionSiteLease first = requireAcquired(service, FIRST_BOT, 1L, FIRST_RUN,
                exact, 10L, 20);

        ConstructionSiteLeaseService.AcquireResult repeated = service.acquire(
                FIRST_BOT, 1L, FIRST_RUN, exact, 10L, 20);
        ConstructionSiteLeaseService.AcquireResult drift = service.acquire(
                FIRST_BOT, 1L, FIRST_RUN, drifted, 10L, 20);

        assertEquals(ConstructionSiteLeaseService.AcquireStatus.ALREADY_HELD, repeated.status());
        assertSame(first, repeated.lease().orElseThrow());
        assertEquals(ConstructionSiteLeaseService.AcquireStatus.BINDING_MISMATCH, drift.status());
        assertTrue(drift.lease().isEmpty());
        assertFalse(service.isCurrent(first, drifted, 10L));
        assertEquals(1, reservations.activeLeaseCount(10L));
    }

    @Test
    void renewalPublishesReplacementLeaseAndImmediatelyFencesThePriorLease() {
        ResourceReservationService reservations = new ResourceReservationService(16, 100);
        ConstructionSiteLeaseService service = new ConstructionSiteLeaseService(reservations);
        ConstructionSiteBinding binding = binding(FIRST_SITE, OVERWORLD, 0, 64, 0,
                new BlueprintOffset(0, 0, 0));
        ConstructionSiteLease original = requireAcquired(service, FIRST_BOT, 1L, FIRST_RUN,
                binding, 10L, 20);

        ConstructionSiteLeaseService.RenewResult result = service.renew(
                original, binding, 12L, 30);
        ConstructionSiteLease replacement = result.lease().orElseThrow();
        ReservationToken replacementToken = replacement.tokens().get(0);

        assertEquals(ConstructionSiteLeaseService.RenewStatus.RENEWED, result.status());
        assertFalse(original == replacement);
        assertEquals(original.binding(), replacement.binding());
        assertEquals(original.acquiredTick(), replacement.acquiredTick());
        assertEquals(42L, replacement.expiresTick());
        assertFalse(service.isCurrent(original, binding, 12L));
        assertTrue(service.isCurrent(replacement, binding, 12L));
        assertEquals(ConstructionSiteLeaseService.RenewStatus.FOREIGN_OR_STALE,
                service.renew(original, binding, 12L, 40).status());
        assertEquals(List.of(replacementToken), reservations.inspect(replacementToken.key(), 12L));
    }

    @Test
    void renewalRejectsForeignAndBindingDriftBeforeRawRenewal() {
        ResourceReservationService reservations = new ResourceReservationService(16, 100);
        ConstructionSiteLeaseService service = new ConstructionSiteLeaseService(reservations);
        ConstructionSiteLeaseService foreignService = new ConstructionSiteLeaseService(reservations);
        ConstructionSiteBinding binding = binding(FIRST_SITE, OVERWORLD, 0, 64, 0,
                new BlueprintOffset(0, 0, 0));
        ConstructionSiteBinding drifted = binding(SECOND_SITE, OVERWORLD, 0, 64, 0,
                new BlueprintOffset(0, 0, 0));
        ConstructionSiteLease lease = requireAcquired(service, FIRST_BOT, 1L, FIRST_RUN,
                binding, 10L, 20);
        ReservationToken originalToken = lease.tokens().get(0);

        assertEquals(ConstructionSiteLeaseService.RenewStatus.FOREIGN_OR_STALE,
                foreignService.renew(lease, binding, 11L, 40).status());
        assertEquals(List.of(originalToken), reservations.inspect(originalToken.key(), 11L));
        assertEquals(ConstructionSiteLeaseService.RenewStatus.BINDING_MISMATCH,
                service.renew(lease, drifted, 11L, 40).status());
        assertEquals(List.of(originalToken), reservations.inspect(originalToken.key(), 11L));
        assertTrue(service.isCurrent(lease, binding, 11L));
    }

    @Test
    void outOfBandRawRenewalInvalidatesTheCacheAndCannotBeAdopted() {
        ResourceReservationService reservations = new ResourceReservationService(16, 100);
        ConstructionSiteLeaseService service = new ConstructionSiteLeaseService(reservations);
        ConstructionSiteBinding binding = binding(FIRST_SITE, OVERWORLD, 0, 64, 0,
                new BlueprintOffset(0, 0, 0));
        ConstructionSiteLease lease = requireAcquired(service, FIRST_BOT, 1L, FIRST_RUN,
                binding, 10L, 20);
        ReservationToken originalToken = lease.tokens().get(0);
        ReservationToken rawReplacement = reservations.renew(originalToken, 12L, 20)
                .token()
                .orElseThrow();

        ConstructionSiteLeaseService.RenewResult result = service.renew(
                lease, binding, 12L, 60);

        assertEquals(ConstructionSiteLeaseService.RenewStatus.FOREIGN_OR_STALE, result.status());
        assertTrue(result.lease().isEmpty());
        assertFalse(service.isCurrent(lease, binding, 12L));
        assertEquals(32L, rawReplacement.expiresTick());
        assertEquals(List.of(rawReplacement), reservations.inspect(rawReplacement.key(), 12L));
        assertEquals(ConstructionSiteLeaseService.AcquireStatus.RESERVATION_STATE_UNTRACKED,
                service.acquire(FIRST_BOT, 1L, FIRST_RUN, binding, 12L, 20).status());
    }

    @Test
    void overlongDimensionFailsClosedWithoutTruncationOrAReservation() {
        ResourceReservationService reservations = new ResourceReservationService(16, 100);
        ConstructionSiteLeaseService service = new ConstructionSiteLeaseService(reservations);
        ResourceId tooLongForScope = new ResourceId("minecraft:" + "a".repeat(119));
        ConstructionSiteBinding binding = binding(FIRST_SITE, tooLongForScope, 0, 64, 0,
                new BlueprintOffset(0, 0, 0));

        ConstructionSiteLeaseService.AcquireResult result = service.acquire(
                FIRST_BOT, 1L, FIRST_RUN, binding, 10L, 20);

        assertEquals(ConstructionSiteLeaseService.AcquireStatus.DIMENSION_KEY_TOO_LONG,
                result.status());
        assertTrue(result.lease().isEmpty());
        assertEquals(0, reservations.activeLeaseCount(10L));
    }

    @Test
    void expirationRunCleanupAndGenerationCleanupLazilyInvalidateCachedLeases() {
        ConstructionSiteBinding binding = binding(FIRST_SITE, OVERWORLD, 0, 64, 0,
                new BlueprintOffset(0, 0, 0));

        ResourceReservationService expiredReservations = new ResourceReservationService(16, 100);
        ConstructionSiteLeaseService expiredService = new ConstructionSiteLeaseService(
                expiredReservations);
        ConstructionSiteLease expired = requireAcquired(expiredService, FIRST_BOT, 1L, FIRST_RUN,
                binding, 10L, 5);
        assertFalse(expiredService.isCurrent(expired, binding, 15L));
        assertEquals(ConstructionSiteLeaseService.AcquireStatus.ACQUIRED,
                expiredService.acquire(SECOND_BOT, 1L, SECOND_RUN, binding, 15L, 20).status());

        ResourceReservationService runReservations = new ResourceReservationService(16, 100);
        ConstructionSiteLeaseService runService = new ConstructionSiteLeaseService(runReservations);
        ConstructionSiteLease releasedByRun = requireAcquired(runService, FIRST_BOT, 1L, FIRST_RUN,
                binding, 10L, 20);
        assertEquals(1, runReservations.releaseRun(FIRST_BOT, 1L, FIRST_RUN));
        assertFalse(runService.isCurrent(releasedByRun, binding, 10L));
        assertEquals(ConstructionSiteLeaseService.AcquireStatus.ACQUIRED,
                runService.acquire(SECOND_BOT, 1L, SECOND_RUN, binding, 10L, 20).status());

        ResourceReservationService generationReservations = new ResourceReservationService(16, 100);
        ConstructionSiteLeaseService generationService = new ConstructionSiteLeaseService(
                generationReservations);
        ConstructionSiteLease releasedByGeneration = requireAcquired(generationService,
                FIRST_BOT, 1L, FIRST_RUN, binding, 10L, 20);
        assertEquals(1, generationReservations.closeGeneration(FIRST_BOT, 1L));
        assertFalse(generationService.isCurrent(releasedByGeneration, binding, 10L));
        assertEquals(ConstructionSiteLeaseService.AcquireStatus.ACQUIRED,
                generationService.acquire(SECOND_BOT, 1L, SECOND_RUN, binding, 10L, 20).status());
    }

    @Test
    void foreignStaleAndExternallyUntrackedLeasesCannotGainAuthority() {
        ResourceReservationService reservations = new ResourceReservationService(16, 100);
        ConstructionSiteLeaseService service = new ConstructionSiteLeaseService(reservations);
        ConstructionSiteLeaseService foreignService = new ConstructionSiteLeaseService(reservations);
        ConstructionSiteBinding binding = binding(FIRST_SITE, OVERWORLD, 0, 64, 0,
                new BlueprintOffset(0, 0, 0));
        ConstructionSiteLease lease = requireAcquired(service, FIRST_BOT, 1L, FIRST_RUN,
                binding, 10L, 20);

        assertEquals(ConstructionSiteLeaseService.ReleaseStatus.FOREIGN_OR_STALE,
                foreignService.release(lease, 10L));
        assertTrue(service.isCurrent(lease, binding, 10L));
        assertEquals(ConstructionSiteLeaseService.ReleaseStatus.RELEASED,
                service.release(lease, 10L));
        assertFalse(service.isCurrent(lease, binding, 10L));
        assertEquals(ConstructionSiteLeaseService.ReleaseStatus.FOREIGN_OR_STALE,
                service.release(lease, 10L));

        ResourceReservationService rawReservations = new ResourceReservationService(16, 100);
        ConstructionSiteLeaseService untrackedService = new ConstructionSiteLeaseService(rawReservations);
        List<ReservationKey> keys = ConstructionSiteLeaseService.tileKeysFor(binding);
        rawReservations.acquireAll(FIRST_BOT, 1L, FIRST_RUN,
                keys.stream().map(key -> new ReservationRequest(key, ReservationMode.EXCLUSIVE))
                        .toList(),
                10L, 20);
        ConstructionSiteLeaseService.AcquireResult untracked = untrackedService.acquire(
                FIRST_BOT, 1L, FIRST_RUN, binding, 10L, 20);
        assertEquals(ConstructionSiteLeaseService.AcquireStatus.RESERVATION_STATE_UNTRACKED,
                untracked.status());
        assertTrue(untracked.lease().isEmpty());
    }

    @Test
    void rejectsNonOwnerThreadBeforeTouchingReservationState() throws InterruptedException {
        ResourceReservationService reservations = new ResourceReservationService(16, 100);
        ConstructionSiteLeaseService service = new ConstructionSiteLeaseService(reservations);
        ConstructionSiteBinding binding = binding(FIRST_SITE, OVERWORLD, 0, 64, 0,
                new BlueprintOffset(0, 0, 0));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                service.acquire(FIRST_BOT, 1L, FIRST_RUN, binding, 10L, 20);
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });

        thread.start();
        thread.join();

        assertInstanceOf(IllegalStateException.class, failure.get());
        ConstructionSiteLease lease = requireAcquired(service, FIRST_BOT, 1L, FIRST_RUN,
                binding, 9L, 20);
        AtomicReference<Throwable> renewalFailure = new AtomicReference<>();
        Thread renewalThread = new Thread(() -> {
            try {
                service.renew(lease, binding, 10L, 20);
            } catch (Throwable throwable) {
                renewalFailure.set(throwable);
            }
        });

        renewalThread.start();
        renewalThread.join();

        assertInstanceOf(IllegalStateException.class, renewalFailure.get());
        assertThrows(IllegalArgumentException.class,
                () -> service.renew(lease, binding, 8L, 20));
        assertTrue(service.isCurrent(lease, binding, 9L));
        assertEquals(1, reservations.activeLeaseCount(9L));
    }

    private static ConstructionSiteLease requireAcquired(ConstructionSiteLeaseService service,
            UUID botId, long generation, UUID skillRunId, ConstructionSiteBinding binding,
            long currentTick, int leaseTicks) {
        ConstructionSiteLeaseService.AcquireResult result = service.acquire(botId, generation,
                skillRunId, binding, currentTick, leaseTicks);
        assertEquals(ConstructionSiteLeaseService.AcquireStatus.ACQUIRED, result.status());
        return result.lease().orElseThrow();
    }

    private static ConstructionSiteBinding binding(UUID siteId, ResourceId dimension,
            int originX, int originY, int originZ, BlueprintOffset... offsets) {
        List<BlueprintCell> cells = List.of(offsets).stream()
                .map(ConstructionSiteLeaseServiceTest::cell)
                .toList();
        Blueprint blueprint = new Blueprint(new UUID(0L, 41L), Blueprint.CURRENT_SCHEMA_VERSION,
                1L, cells);
        return ConstructionSiteBinding.bind(siteId, ConstructionWorkPlan.partition(blueprint),
                new ConstructionSiteAnchor(dimension,
                        new BlockCoordinates(originX, originY, originZ)));
    }

    private static BlueprintCell cell(BlueprintOffset offset) {
        return new BlueprintCell(offset, STONE, BlueprintPlacementRole.FOUNDATION,
                BlueprintReplacePolicy.PRESERVE_EXISTING, BlueprintMaterialClass.PERMANENT);
    }
}
