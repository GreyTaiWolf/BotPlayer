package io.github.greytaiwolf.botplayer.skill.runtime.core;

import io.github.greytaiwolf.botplayer.skill.core.SkillCategory;
import io.github.greytaiwolf.botplayer.skill.core.SkillDescriptor;
import io.github.greytaiwolf.botplayer.skill.core.SkillFailureCode;
import io.github.greytaiwolf.botplayer.skill.core.SkillId;
import io.github.greytaiwolf.botplayer.skill.core.SkillParameterSchema;
import io.github.greytaiwolf.botplayer.skill.core.SkillParameters;
import io.github.greytaiwolf.botplayer.skill.core.SkillRegistry;
import io.github.greytaiwolf.botplayer.skill.core.SkillRiskLevel;
import io.github.greytaiwolf.botplayer.skill.core.SkillRunState;
import io.github.greytaiwolf.botplayer.skill.core.SkillSignal;
import io.github.greytaiwolf.botplayer.skill.core.SkillSignalStatus;
import io.github.greytaiwolf.botplayer.skill.core.SkillSignalType;
import io.github.greytaiwolf.botplayer.skill.core.SkillVersion;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlan;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanEdge;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanLimits;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanNode;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanValidator;
import io.github.greytaiwolf.botplayer.skill.reservation.ReservationKey;
import io.github.greytaiwolf.botplayer.skill.reservation.ReservationMode;
import io.github.greytaiwolf.botplayer.skill.reservation.ReservationRequest;
import io.github.greytaiwolf.botplayer.skill.reservation.ResourceReservationService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SkillRuntimeTest {
    private static final UUID BOT = new UUID(0L, 10L);
    private static final UUID PLAN = new UUID(0L, 20L);
    private static final UUID NODE_ONE = new UUID(0L, 30L);
    private static final UUID NODE_TWO = new UUID(0L, 31L);
    private static final SkillId SKILL = new SkillId("botplayer", "test");
    private static final SkillVersion VERSION = new SkillVersion(1, 0, 0);

    @Test
    void runsAValidatedDagAndRejectsStaleSignals() {
        TestHandler handler = new TestHandler();
        SkillRuntime runtime = runtime(handler, 40);
        SkillRunSubmission submission = runtime.submit(request(twoNodePlan(), 0L));
        UUID runId = submission.runId().orElseThrow();

        runtime.tick(0L);
        SkillRunView waiting = runtime.inspectRun(runId).orElseThrow();
        Assertions.assertAll(
                () -> Assertions.assertEquals(
                        SkillRunState.WAITING_ACTION, waiting.state()),
                () -> Assertions.assertEquals(2L, waiting.stateRevision()),
                () -> Assertions.assertEquals(NODE_ONE,
                        waiting.activeNodeId().orElseThrow()));

        Assertions.assertEquals(
                io.github.greytaiwolf.botplayer.skill.core.SkillSignalInbox
                        .OfferStatus.ENQUEUED,
                runtime.offerSignal(signal(runId, 1L, 1L)));
        runtime.tick(1L);
        Assertions.assertEquals(SkillRunState.WAITING_ACTION,
                runtime.inspectRun(runId).orElseThrow().state());

        Assertions.assertEquals(
                io.github.greytaiwolf.botplayer.skill.core.SkillSignalInbox
                        .OfferStatus.ENQUEUED,
                runtime.offerSignal(signal(runId, waiting.stateRevision(), 2L)));
        runtime.tick(2L);
        Assertions.assertEquals(SkillRunState.VERIFYING,
                runtime.inspectRun(runId).orElseThrow().state());
        runtime.tick(3L);
        SkillRunView second = runtime.inspectRun(runId).orElseThrow();
        Assertions.assertAll(
                () -> Assertions.assertEquals(SkillRunState.PREPARING,
                        second.state()),
                () -> Assertions.assertEquals(NODE_TWO,
                        second.activeNodeId().orElseThrow()));

        runtime.tick(4L);
        long secondRevision = runtime.inspectRun(runId)
                .orElseThrow().stateRevision();
        runtime.offerSignal(signal(runId, secondRevision, 4L));
        runtime.tick(5L);
        runtime.tick(6L);
        Assertions.assertAll(
                () -> Assertions.assertEquals(0, runtime.activeRunCount()),
                () -> Assertions.assertEquals(SkillRunState.SUCCEEDED,
                        runtime.inspectRun(runId).orElseThrow().state()),
                () -> Assertions.assertEquals(List.of(NODE_ONE, NODE_TWO),
                        handler.startedNodes));
    }

    @Test
    void cancellationReleasesReservationsAndLateSignalsAreRejected() {
        TestHandler handler = new TestHandler();
        handler.reserve = true;
        SkillRuntime runtime = runtime(handler, 40);
        UUID runId = runtime.submit(request(oneNodePlan(), 0L))
                .runId().orElseThrow();
        runtime.tick(0L);
        Assertions.assertEquals(1, handler.reservations.activeLeaseCount(0L));

        Assertions.assertEquals(SkillRuntime.CancelStatus.CANCELLED,
                runtime.cancel(runId, 1L, "操作员取消了技能计划"));
        Assertions.assertAll(
                () -> Assertions.assertEquals(0,
                        handler.reservations.activeLeaseCount(1L)),
                () -> Assertions.assertEquals(SkillRunState.CANCELLED,
                        runtime.inspectRun(runId).orElseThrow().state()),
                () -> Assertions.assertEquals(
                        io.github.greytaiwolf.botplayer.skill.core
                                .SkillSignalInbox.OfferStatus.UNKNOWN_RUN,
                        runtime.offerSignal(signal(runId, 2L, 1L))));
    }

    @Test
    void pauseResumeAndTimeoutStayWithinTheCentralStateMachine() {
        TestHandler handler = new TestHandler();
        handler.pauseFirstBegin = true;
        handler.reserve = true;
        SkillRuntime runtime = runtime(handler, 2);
        UUID runId = runtime.submit(request(oneNodePlan(), 0L))
                .runId().orElseThrow();

        runtime.tick(0L);
        Assertions.assertEquals(SkillRunState.PAUSED,
                runtime.inspectRun(runId).orElseThrow().state());
        Assertions.assertEquals(0,
                handler.reservations.activeLeaseCount(0L),
                "paused run must not retain a world reservation");
        Assertions.assertEquals(SkillRuntime.ResumeStatus.RESUMING,
                runtime.resume(runId, 1L));
        runtime.tick(1L);
        Assertions.assertEquals(SkillRunState.WAITING_ACTION,
                runtime.inspectRun(runId).orElseThrow().state());
        Assertions.assertEquals(1,
                handler.reservations.activeLeaseCount(1L),
                "resumed node must re-acquire its reservation before action wait");
        runtime.tick(2L);
        SkillRunView timeout = runtime.inspectRun(runId).orElseThrow();
        Assertions.assertAll(
                () -> Assertions.assertEquals(SkillRunState.FAILED,
                        timeout.state()),
                () -> Assertions.assertEquals(
                        SkillFailureCode.TIMEOUT,
                        timeout.failureCode().orElseThrow()));
    }

    @Test
    void rejectsInvalidPlansAndUnavailableHandlersWithoutBindingRuns() {
        SkillRuntime noHandler = runtime(null, 40);
        SkillRunSubmission unavailable = noHandler.submit(
                request(oneNodePlan(), 0L));
        SkillPlan invalid = new SkillPlan(
                PLAN,
                BOT,
                1L,
                List.of(),
                List.of());
        SkillRunSubmission invalidSubmission = noHandler.submit(
                request(invalid, 1L));

        Assertions.assertAll(
                () -> Assertions.assertEquals(
                        SkillRunSubmission.Status.HANDLER_UNAVAILABLE,
                        unavailable.status()),
                () -> Assertions.assertEquals(
                        SkillRunSubmission.Status.INVALID_PLAN,
                        invalidSubmission.status()),
                () -> Assertions.assertEquals(0, noHandler.activeRunCount()));
    }

    @Test
    void dispatchFenceRejectsBeforeTheHandlerCanSubmitWorldWork() {
        TestHandler handler = new TestHandler();
        handler.reserve = true;
        SkillRegistry registry = new SkillRegistry();
        Assertions.assertEquals(SkillRegistry.RegisterStatus.REGISTERED,
                registry.register(new SkillDescriptor(
                        SKILL,
                        VERSION,
                        SkillCategory.SURVIVAL,
                        SkillParameterSchema.empty(),
                        SkillRiskLevel.LOW,
                        java.util.Set.of(),
                        40,
                        1,
                        true)));
        ResourceReservationService reservations =
                new ResourceReservationService(16, 100);
        SkillRuntime runtime = new SkillRuntime(
                registry,
                new SkillPlanValidator(registry, SkillPlanLimits.defaults()),
                reservations,
                new SkillRuntimeBudget(4, 8, 16, 40),
                (checkpoint, tick) -> SkillRuntimeDispatchFence.Result.reject(
                        "测试耐久派发围栏拒绝动作"));
        handler.reservations = reservations;
        Assertions.assertEquals(
                SkillRuntime.HandlerRegistrationStatus.REGISTERED,
                runtime.registerHandler(SKILL, VERSION, handler));
        UUID runId = runtime.submit(request(oneNodePlan(), 0L))
                .runId().orElseThrow();

        runtime.tick(0L);

        SkillRunView failed = runtime.inspectRun(runId).orElseThrow();
        Assertions.assertAll(
                () -> Assertions.assertEquals(SkillRunState.FAILED,
                        failed.state()),
                () -> Assertions.assertEquals(
                        SkillFailureCode.INVALID_CHECKPOINT,
                        failed.failureCode().orElseThrow()),
                () -> Assertions.assertTrue(handler.startedNodes.isEmpty(),
                        "handler begin must not run before durable dispatch permission"),
                () -> Assertions.assertEquals(0,
                        reservations.activeLeaseCount(0L),
                        "rejected fence must release reservations before any action"));
    }

    private static SkillRuntime runtime(
            TestHandler handler, int maximumRunTicks) {
        SkillRegistry registry = new SkillRegistry();
        Assertions.assertEquals(SkillRegistry.RegisterStatus.REGISTERED,
                registry.register(new SkillDescriptor(
                        SKILL,
                        VERSION,
                        SkillCategory.SURVIVAL,
                        SkillParameterSchema.empty(),
                        SkillRiskLevel.LOW,
                        java.util.Set.of(),
                        maximumRunTicks,
                        1,
                        true)));
        ResourceReservationService reservations =
                new ResourceReservationService(16, 100);
        SkillRuntime runtime = new SkillRuntime(
                registry,
                new SkillPlanValidator(
                        registry, SkillPlanLimits.defaults()),
                reservations,
                new SkillRuntimeBudget(4, 8, 16, maximumRunTicks));
        if (handler != null) {
            handler.reservations = reservations;
            Assertions.assertEquals(
                    SkillRuntime.HandlerRegistrationStatus.REGISTERED,
                    runtime.registerHandler(SKILL, VERSION, handler));
        }
        return runtime;
    }

    private static SkillRunRequest request(
            SkillPlan plan, long tick) {
        return new SkillRunRequest(BOT, 1L, plan, tick);
    }

    private static SkillPlan oneNodePlan() {
        return new SkillPlan(
                PLAN,
                BOT,
                1L,
                List.of(node(NODE_ONE)),
                List.of());
    }

    private static SkillPlan twoNodePlan() {
        return new SkillPlan(
                PLAN,
                BOT,
                1L,
                List.of(node(NODE_ONE), node(NODE_TWO)),
                List.of(new SkillPlanEdge(NODE_ONE, NODE_TWO)));
    }

    private static SkillPlanNode node(UUID nodeId) {
        return new SkillPlanNode(
                nodeId, SKILL, VERSION, SkillParameters.empty());
    }

    private static SkillSignal signal(
            UUID runId, long revision, long tick) {
        return new SkillSignal(
                new UUID(0L, 100L + tick),
                runId,
                BOT,
                1L,
                revision,
                new UUID(0L, 200L + tick),
                SkillSignalType.ACTION,
                SkillSignalStatus.SUCCEEDED,
                SkillFailureCode.NONE,
                List.of(),
                "测试动作已完成",
                tick);
    }

    private static final class TestHandler implements SkillNodeHandler {
        private final List<UUID> startedNodes = new ArrayList<>();
        private ResourceReservationService reservations;
        private boolean reserve;
        private boolean pauseFirstBegin;
        private boolean paused;

        @Override
        public List<ReservationRequest> requiredReservations(
                SkillNodeContext context) {
            if (!reserve) {
                return List.of();
            }
            return List.of(new ReservationRequest(
                    new ReservationKey(
                            ReservationKey.Kind.WORK_AREA,
                            "minecraft:overworld",
                            "test"),
                    ReservationMode.EXCLUSIVE));
        }

        @Override
        public SkillNodeDirective begin(SkillNodeContext context) {
            startedNodes.add(context.node().nodeId());
            if (pauseFirstBegin && !paused) {
                paused = true;
                return SkillNodeDirective.pause("测试请求暂停");
            }
            return SkillNodeDirective.waitFor(
                    SkillNodeDirective.Kind.WAIT_ACTION,
                    "等待测试动作回执");
        }

        @Override
        public SkillNodeDirective signal(
                SkillNodeContext context, SkillSignal signal) {
            return SkillNodeDirective.verify("测试动作已经进入验证");
        }

        @Override
        public SkillNodeDirective tick(SkillNodeContext context) {
            return context.nodeIndex() == 0
                    ? SkillNodeDirective.complete("测试节点已验证")
                    : SkillNodeDirective.complete("第二个测试节点已验证");
        }
    }
}
