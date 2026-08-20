package io.github.greytaiwolf.botplayer.technique.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.greytaiwolf.botplayer.action.ActionChannel;
import io.github.greytaiwolf.botplayer.technique.core.TechniqueDescriptor;
import io.github.greytaiwolf.botplayer.technique.core.TechniqueFailureCode;
import io.github.greytaiwolf.botplayer.technique.core.TechniqueId;
import io.github.greytaiwolf.botplayer.technique.core.TechniqueParameters;
import io.github.greytaiwolf.botplayer.technique.core.TechniqueRiskLevel;
import io.github.greytaiwolf.botplayer.technique.core.TechniqueVersion;
import io.github.greytaiwolf.botplayer.technique.runtime.PlayerTechnique;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueCancelReason;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueChildDispatcher;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueChildRequest;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueChildTicket;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueContext;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueDirective;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueRunView;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueStartRequest;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueSubmission;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TechniqueLifecycleCoordinatorTest {
    private static final UUID BOT = UUID.fromString(
            "11111111-1111-1111-1111-111111111111");

    @Test
    void singleRuntimeKeepsOneForegroundRunAndRoutesReentrantCancellationExactly() {
        TechniqueLifecycleCoordinator coordinator =
                new TechniqueLifecycleCoordinator();
        RecordingRoute first = new RecordingRoute(coordinator,
                technique("contract/first"), true);
        RecordingRoute second = new RecordingRoute(coordinator,
                technique("contract/second"), false);
        coordinator.register(first);
        coordinator.register(second);

        TechniqueSubmission firstSubmission = coordinator.start(first,
                request(first.technique(),
                        "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", 0L));

        assertEquals(TechniqueSubmission.Status.ACCEPTED,
                firstSubmission.status());
        assertTrue(first.nestedStartRejected,
                "route dispatch must not re-enter a coordinator start");
        assertEquals(first.submittedTicket, first.cancelledTicket,
                "the coordinator must route a synchronous close to the exact ticket");
        assertEquals(TechniqueCancelReason.GENERATION_CHANGED,
                first.cancelReason);
        assertEquals(1, coordinator.activeRunCount());

        TechniqueSubmission secondSubmission = coordinator.start(second,
                request(second.technique(),
                        "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", 0L));

        assertEquals(TechniqueSubmission.Status.BOT_BUSY,
                secondSubmission.status());
    }

    @Test
    void synchronouslyRejectedChildrenReapTheirRouteBeforeTheNextLifecyclePass() {
        TechniqueLifecycleCoordinator coordinator =
                new TechniqueLifecycleCoordinator();
        RecordingRoute rejecting = new RecordingRoute(coordinator,
                technique("contract/rejecting"), false, true);
        coordinator.register(rejecting);

        TechniqueSubmission first = coordinator.start(rejecting,
                request(rejecting.technique(),
                        "dddddddd-dddd-dddd-dddd-dddddddddddd", 0L));
        TechniqueSubmission second = coordinator.start(rejecting,
                request(rejecting.technique(),
                        "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee", 0L));

        assertEquals(TechniqueSubmission.Status.ACCEPTED, first.status());
        assertEquals(TechniqueSubmission.Status.ACCEPTED, second.status());
        assertEquals(2, rejecting.reapedRunCount,
                "each synchronous rejection must release its route immediately");
        assertEquals(0, coordinator.activeRunCount());
    }

    private static TechniqueStartRequest request(PlayerTechnique technique,
            String skillRunId, long tick) {
        TechniqueDescriptor descriptor = technique.descriptor();
        return new TechniqueStartRequest(UUID.fromString(skillRunId), BOT, 1L,
                descriptor.id(), descriptor.version(), TechniqueParameters.empty(),
                tick);
    }

    private static PlayerTechnique technique(String path) {
        TechniqueDescriptor descriptor = new TechniqueDescriptor(
                new TechniqueId("botplayer", path),
                new TechniqueVersion(1, 0, 0), TechniqueRiskLevel.LOW,
                20, 2, 1, 1, 0);
        return new PlayerTechnique() {
            @Override
            public TechniqueDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public TechniqueDirective start(TechniqueContext context,
                    TechniqueParameters parameters) {
                return TechniqueDirective.awaitChildren("contract-child", List.of(
                        new TechniqueChildRequest("contract-child",
                                Set.of(ActionChannel.MAIN_HAND))));
            }

            @Override
            public TechniqueDirective tick(TechniqueContext context,
                    TechniqueRunView run) {
                return TechniqueDirective.complete("contract complete");
            }

            @Override
            public TechniqueDirective verify(TechniqueContext context,
                    TechniqueRunView run) {
                return TechniqueDirective.fail(TechniqueFailureCode.INTERNAL_ERROR,
                        "contract route does not verify");
            }
        };
    }

    private static final class RecordingRoute extends TechniqueRoute {
        private final TechniqueLifecycleCoordinator coordinator;
        private final PlayerTechnique technique;
        private final boolean closeDuringFirstSubmit;
        private final boolean rejectChild;
        private TechniqueChildTicket submittedTicket;
        private TechniqueChildTicket cancelledTicket;
        private TechniqueCancelReason cancelReason;
        private boolean nestedStartRejected;
        private int reapedRunCount;

        private RecordingRoute(TechniqueLifecycleCoordinator coordinator,
                PlayerTechnique technique, boolean closeDuringFirstSubmit) {
            this(coordinator, technique, closeDuringFirstSubmit, false);
        }

        private RecordingRoute(TechniqueLifecycleCoordinator coordinator,
                PlayerTechnique technique, boolean closeDuringFirstSubmit,
                boolean rejectChild) {
            this.coordinator = coordinator;
            this.technique = technique;
            this.closeDuringFirstSubmit = closeDuringFirstSubmit;
            this.rejectChild = rejectChild;
        }

        @Override
        PlayerTechnique technique() {
            return technique;
        }

        @Override
        void observeTick(long currentTick) {
            // The coordinator owns monotonic tick validation in this contract test.
        }

        @Override
        TechniqueChildDispatcher.Submission submitChild(TechniqueChildTicket ticket,
                TechniqueRunView run) {
            submittedTicket = ticket;
            try {
                coordinator.start(this, request(technique,
                        "cccccccc-cccc-cccc-cccc-cccccccccccc",
                        ticket.submittedTick()));
            } catch (IllegalStateException expected) {
                nestedStartRejected = true;
            }
            if (closeDuringFirstSubmit) {
                coordinator.closeGeneration(ticket.botId(), ticket.botGeneration(),
                        ticket.submittedTick());
            }
            if (rejectChild) {
                return TechniqueChildDispatcher.Submission.rejected(
                        TechniqueChildDispatcher.Status.REJECTED,
                        "contract child rejected");
            }
            return TechniqueChildDispatcher.Submission.accepted(
                    "contract child accepted");
        }

        @Override
        void cancelChild(TechniqueChildTicket ticket,
                TechniqueCancelReason reason) {
            cancelledTicket = ticket;
            cancelReason = reason;
        }

        @Override
        void drainCompletedChildren(long currentTick,
                TechniqueLifecycleCoordinator.SignalSink signals) {
            // This contract route owns no external child terminal source.
        }

        @Override
        void onGenerationClosing(UUID botId, long botGeneration,
                long currentTick) {
            // Exact child cancellation above is the assertion target.
        }

        @Override
        void closeIngress() {
            // No independent ingress exists in this test route.
        }

        @Override
        boolean isRouteGenerationSafe(UUID botId, long botGeneration) {
            return true;
        }

        @Override
        void verifyRun(TechniqueRunView run) {
            assertEquals(technique.descriptor().id(), run.techniqueId());
            assertEquals(technique.descriptor().version(), run.techniqueVersion());
            assertEquals(Optional.of(submittedTicket),
                    run.childTickets().stream().findFirst());
        }

        @Override
        void reapTerminalRun(UUID techniqueRunId) {
            reapedRunCount++;
        }
    }
}
