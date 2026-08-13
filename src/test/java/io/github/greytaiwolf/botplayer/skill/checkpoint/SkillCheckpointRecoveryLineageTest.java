package io.github.greytaiwolf.botplayer.skill.checkpoint;

import io.github.greytaiwolf.botplayer.skill.core.SkillRunState;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlan;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillRunView;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillRuntimeCheckpoint;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SkillCheckpointRecoveryLineageTest {
    private static final UUID SERVER_ID = new UUID(51L, 1L);
    private static final UUID BOT_ID = new UUID(42L, 1L);
    private static final UUID PLAYER_ID = new UUID(42L, 2L);
    private static final UUID OLD_CHECKPOINT_ID = new UUID(51L, 2L);
    private static final UUID OLD_RUN_ID = new UUID(51L, 3L);
    private static final UUID NEW_RUN_ID = new UUID(51L, 4L);

    @Test
    void mergesSuffixRuntimeStateBackIntoTheApprovedFullPlanLineage() {
        SkillPlan full = SkillCheckpointRestartPlanBuilderTest.fullPlan();
        SkillCheckpointRestartPlan restart =
                SkillCheckpointRestartPlanBuilder.build(
                        full,
                        SkillCheckpointRestartPlanBuilderTest.context(
                                full,
                                SkillCheckpointNodeState.SUCCEEDED,
                                SkillCheckpointNodeState.READY,
                                SkillCheckpointNodeState.PENDING))
                        .restartPlan()
                        .orElseThrow();
        SkillCheckpoint prior = prior(full);
        SkillRuntimeCheckpoint runtime = runtime(restart.suffixPlan());
        SkillCheckpointScope firstScope = scope(1);
        SkillCheckpointScope refreshedScope = scope(2);

        SkillCheckpoint merged = SkillCheckpointBridge.recoveredCheckpoint(
                SERVER_ID, PLAYER_ID, runtime, prior, restart, firstScope);
        SkillCheckpoint sameGeneration =
                SkillCheckpointBridge.recoveredCheckpoint(
                        SERVER_ID,
                        PLAYER_ID,
                        runtime,
                        merged,
                        restart,
                        refreshedScope);

        Assertions.assertAll(
                () -> Assertions.assertEquals(prior.plan(), merged.plan()),
                () -> Assertions.assertNotEquals(
                        restart.suffixPlan().planId(), merged.plan().planId()),
                () -> Assertions.assertEquals(NEW_RUN_ID, merged.runId()),
                () -> Assertions.assertEquals(NEW_RUN_ID, merged.checkpointId()),
                () -> Assertions.assertNotEquals(OLD_RUN_ID, merged.runId()),
                () -> Assertions.assertNotEquals(
                        OLD_CHECKPOINT_ID, merged.checkpointId()),
                () -> Assertions.assertEquals(9L, merged.generation()),
                () -> Assertions.assertEquals(3, merged.attemptCount()),
                () -> Assertions.assertEquals(2, merged.recoveryCount()),
                () -> Assertions.assertEquals(
                        Optional.of(firstScope), merged.scope()),
                () -> Assertions.assertEquals(
                        List.of(
                                SkillCheckpointNodeState.SUCCEEDED,
                                SkillCheckpointNodeState.IN_PROGRESS,
                                SkillCheckpointNodeState.PENDING),
                        merged.nodes().stream()
                                .map(SkillCheckpointNode::state).toList()),
                () -> Assertions.assertEquals(
                        merged.attemptCount(), sameGeneration.attemptCount()),
                () -> Assertions.assertEquals(
                        merged.recoveryCount(), sameGeneration.recoveryCount()),
                () -> Assertions.assertEquals(
                        Optional.of(refreshedScope), sameGeneration.scope()));
    }

    @Test
    void initialBridgePersistsTheFreshlyCapturedScope() {
        SkillPlan full = SkillCheckpointRestartPlanBuilderTest.fullPlan();
        SkillCheckpointScope scope = scope(3);

        SkillCheckpoint checkpoint = SkillCheckpointBridge.checkpoint(
                SERVER_ID, PLAYER_ID, runtime(full), scope);

        Assertions.assertEquals(Optional.of(scope), checkpoint.scope());
    }

    @Test
    void durableHandoffConsumesExactlyOneRecoveryBudgetBeforeSubmission() {
        SkillPlan full = SkillCheckpointRestartPlanBuilderTest.fullPlan();
        SkillCheckpoint prior = prior(full);

        SkillCheckpoint handoff = SkillCheckpointBridge.recoveryHandoff(
                prior, 150L);

        Assertions.assertAll(
                () -> Assertions.assertEquals(
                        prior.generation(), handoff.generation()),
                () -> Assertions.assertEquals(
                        prior.stateRevision() + 1L,
                        handoff.stateRevision()),
                () -> Assertions.assertEquals(
                        prior.attemptCount() + 1,
                        handoff.attemptCount()),
                () -> Assertions.assertEquals(
                        prior.recoveryCount() + 1,
                        handoff.recoveryCount()),
                () -> Assertions.assertNotEquals(
                        prior.checkpointId(), handoff.checkpointId()),
                () -> Assertions.assertNotEquals(
                        prior.runId(), handoff.runId()),
                () -> Assertions.assertEquals(
                        SkillCheckpointContinuationState.RESTARTABLE,
                        handoff.continuationState()),
                () -> Assertions.assertEquals("recovery.handoff",
                        handoff.evidence().get(0).code()));
    }

    @Test
    void durableHandoffRefusesExhaustedBudgetBeforeAnyRunCanBeSubmitted() {
        SkillPlan full = SkillCheckpointRestartPlanBuilderTest.fullPlan();
        SkillCheckpoint exhausted = new SkillCheckpoint(
                OLD_CHECKPOINT_ID,
                SERVER_ID,
                BOT_ID,
                PLAYER_ID,
                8L,
                OLD_RUN_ID,
                SkillCheckpointRestartPlanBuilderTest.reference(full),
                "revalidate",
                SkillCheckpointContinuationState.RESTARTABLE,
                4L,
                SkillCheckpoint.MAX_ATTEMPTS,
                1,
                100L,
                Optional.empty(),
                prior(full).nodes(),
                List.of(SkillCheckpointRestartPlanBuilderTest.evidence(
                        "saved", 100L)),
                Optional.empty());

        Assertions.assertThrows(IllegalStateException.class,
                () -> SkillCheckpointBridge.recoveryHandoff(
                        exhausted, 150L));
    }

    @Test
    void dispatchFenceTurnsAnOldRestartPointIntoANonRecoverableRecord() {
        SkillPlan full = SkillCheckpointRestartPlanBuilderTest.fullPlan();
        SkillCheckpoint prior = prior(full);

        SkillCheckpoint fenced = SkillCheckpointBridge.dispatchFence(
                prior, 150L);
        SkillCheckpoint terminal = SkillCheckpointBridge.terminalTombstone(
                prior, 151L);

        Assertions.assertAll(
                () -> Assertions.assertEquals(
                        SkillCheckpointContinuationState.CANCELLED,
                        fenced.continuationState()),
                () -> Assertions.assertEquals(
                        prior.stateRevision() + 1L,
                        fenced.stateRevision()),
                () -> Assertions.assertEquals(
                        "dispatch_fenced", fenced.phase()),
                () -> Assertions.assertEquals(
                        "checkpoint.dispatch_fenced",
                        fenced.evidence().get(0).code()),
                () -> Assertions.assertEquals(
                        SkillCheckpointContinuationState.CANCELLED,
                        terminal.continuationState()),
                () -> Assertions.assertEquals("revoked", terminal.phase()));
    }

    private static SkillCheckpoint prior(SkillPlan full) {
        return new SkillCheckpoint(
                OLD_CHECKPOINT_ID,
                SERVER_ID,
                BOT_ID,
                PLAYER_ID,
                8L,
                OLD_RUN_ID,
                SkillCheckpointRestartPlanBuilderTest.reference(full),
                "revalidate",
                SkillCheckpointContinuationState.RESTARTABLE,
                4L,
                2,
                1,
                100L,
                Optional.empty(),
                List.of(
                        SkillCheckpointRestartPlanBuilderTest.node(
                                new UUID(42L, 4L),
                                SkillCheckpointNodeState.SUCCEEDED),
                        SkillCheckpointRestartPlanBuilderTest.node(
                                new UUID(42L, 5L),
                                SkillCheckpointNodeState.READY),
                        SkillCheckpointRestartPlanBuilderTest.node(
                                new UUID(42L, 6L),
                                SkillCheckpointNodeState.PENDING)),
                List.of(SkillCheckpointRestartPlanBuilderTest.evidence(
                        "saved", 100L)),
                Optional.empty());
    }

    private static SkillRuntimeCheckpoint runtime(SkillPlan suffix) {
        SkillRunView view = new SkillRunView(
                NEW_RUN_ID,
                BOT_ID,
                9L,
                suffix.planId(),
                suffix.revision(),
                SkillRunState.PREPARING,
                7L,
                Optional.of(suffix.nodes().get(0).nodeId()),
                Optional.of(suffix.nodes().get(0).skillId()),
                0,
                suffix.nodes().size(),
                200L,
                210L,
                500L,
                Optional.empty(),
                "恢复后缀已重新观察");
        return new SkillRuntimeCheckpoint(
                view,
                suffix,
                suffix.nodes().stream()
                        .map(node -> new SkillRuntimeCheckpoint.Node(
                                node.nodeId(),
                                node.nodeId().equals(
                                        suffix.nodes().get(0).nodeId())
                                                ? SkillRuntimeCheckpoint.State
                                                        .IN_PROGRESS
                                                : SkillRuntimeCheckpoint.State
                                                        .PENDING))
                        .toList());
    }

    private static SkillCheckpointScope scope(int marker) {
        return new SkillCheckpointScope(
                "minecraft:overworld",
                0,
                64,
                0,
                Optional.empty(),
                "0".repeat(63) + marker,
                "p5a-scope-v1");
    }
}
