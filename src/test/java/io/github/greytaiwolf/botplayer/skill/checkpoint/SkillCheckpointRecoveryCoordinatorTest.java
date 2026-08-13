package io.github.greytaiwolf.botplayer.skill.checkpoint;

import io.github.greytaiwolf.botplayer.skill.core.SkillId;
import io.github.greytaiwolf.botplayer.skill.core.SkillParameters;
import io.github.greytaiwolf.botplayer.skill.core.SkillVersion;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlan;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanNode;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillRunRequest;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SkillCheckpointRecoveryCoordinatorTest {
    private static final UUID SERVER_ID = new UUID(31L, 1L);
    private static final UUID BOT_ID = new UUID(31L, 2L);
    private static final UUID PLAYER_ID = new UUID(31L, 3L);
    private static final UUID CHECKPOINT_ID = new UUID(31L, 4L);
    private static final UUID OLD_RUN_ID = new UUID(31L, 5L);
    private static final UUID PLAN_ID = new UUID(31L, 6L);
    private static final UUID NODE_ID = new UUID(31L, 7L);
    private static final SkillId SKILL_ID = new SkillId(
            "botplayer", "restart_test");
    private static final SkillVersion SKILL_VERSION = new SkillVersion(1, 0, 0);

    @Test
    void turnsOnlyAnExactApprovedPlanIntoANewGenerationRequest() {
        SkillPlan approved = plan(PLAN_ID, "observe");
        SkillCheckpoint checkpoint = checkpoint(approved, 7L);

        SkillCheckpointRecoveryCoordination coordination =
                SkillCheckpointRecoveryCoordinator.coordinate(request(
                        checkpoint,
                        Optional.of(approved),
                        true));

        SkillRunRequest fresh = coordination.request().orElseThrow();
        Assertions.assertAll(
                () -> Assertions.assertEquals(
                        SkillCheckpointRecoveryCoordinationStatus.READY,
                        coordination.status()),
                () -> Assertions.assertEquals(
                        SkillCheckpointRecoveryDisposition
                                .RESTART_FROM_SAFE_PHASE,
                        coordination.disposition()),
                () -> Assertions.assertEquals(BOT_ID, fresh.botId()),
                () -> Assertions.assertEquals(8L, fresh.botGeneration()),
                () -> Assertions.assertEquals(approved.nodes(), fresh.plan().nodes()),
                () -> Assertions.assertEquals(approved.edges(), fresh.plan().edges()),
                () -> Assertions.assertEquals(400L, fresh.submittedTick()),
                () -> Assertions.assertFalse(Arrays.stream(
                                SkillCheckpointRestartContext.class
                                        .getRecordComponents())
                        .map(RecordComponent::getName)
                        .anyMatch("runId"::equals)),
                () -> Assertions.assertFalse(Arrays.stream(
                                SkillCheckpointRestartContext.class
                                        .getRecordComponents())
                        .map(RecordComponent::getName)
                        .anyMatch("checkpointId"::equals)),
                () -> Assertions.assertFalse(Arrays.stream(
                                SkillRunRequest.class.getRecordComponents())
                        .map(RecordComponent::getName)
                        .anyMatch("runId"::equals)),
                () -> Assertions.assertNotEquals(
                        OLD_RUN_ID, fresh.plan().planId()),
                () -> Assertions.assertNotEquals(
                        approved.planId(), fresh.plan().planId()));
    }

    @Test
    void rejectsChangedApprovedPlanBeforeItBuildsASuffix() {
        SkillPlan checkpointPlan = plan(PLAN_ID, "observe");
        SkillPlan changedCurrentPlan = plan(PLAN_ID, "changed");
        SkillCheckpoint checkpoint = checkpoint(checkpointPlan, 7L);

        SkillCheckpointRecoveryCoordination coordination =
                SkillCheckpointRecoveryCoordinator.coordinate(request(
                        checkpoint,
                        Optional.of(changedCurrentPlan),
                        true));

        Assertions.assertAll(
                () -> Assertions.assertEquals(
                        SkillCheckpointRecoveryCoordinationStatus
                                .DECISION_REJECTED,
                        coordination.status()),
                () -> Assertions.assertEquals(
                        SkillCheckpointRecoveryRejection.PLAN_VERSION_MISMATCH,
                        coordination.rejection().orElseThrow()),
                () -> Assertions.assertTrue(coordination.request().isEmpty()));
    }

    @Test
    void rejectsCheckpointWhoseCompletedPrefixWouldLeaveNoSuffix() {
        SkillPlan approved = plan(PLAN_ID, "observe");
        SkillCheckpoint checkpoint = withNodeState(
                checkpoint(approved, 7L), SkillCheckpointNodeState.SUCCEEDED);

        SkillCheckpointRecoveryCoordination coordination =
                SkillCheckpointRecoveryCoordinator.coordinate(request(
                        checkpoint,
                        Optional.of(approved),
                        true));

        Assertions.assertAll(
                () -> Assertions.assertEquals(
                        SkillCheckpointRecoveryCoordinationStatus
                                .RESTART_PLAN_REJECTED,
                        coordination.status()),
                () -> Assertions.assertEquals(
                        SkillCheckpointRecoveryDisposition
                                .RESTART_FROM_SAFE_PHASE,
                        coordination.disposition()),
                () -> Assertions.assertTrue(coordination.request().isEmpty()));
    }

    @Test
    void failsClosedWhenDescriptorsDisappear() {
        SkillPlan approved = plan(PLAN_ID, "observe");
        SkillCheckpoint checkpoint = checkpoint(approved, 7L);
        SkillCheckpointRecoveryCoordination descriptorsMissing =
                SkillCheckpointRecoveryCoordinator.coordinate(request(
                        checkpoint,
                        Optional.of(approved),
                        false));

        Assertions.assertAll(
                () -> Assertions.assertEquals(
                        SkillCheckpointRecoveryCoordinationStatus
                                .DECISION_REJECTED,
                        descriptorsMissing.status()),
                () -> Assertions.assertEquals(
                        SkillCheckpointRecoveryRejection.DESCRIPTOR_UNAVAILABLE,
                        descriptorsMissing.rejection().orElseThrow()));
    }

    private static SkillCheckpointRecoveryCoordinationRequest request(
            SkillCheckpoint checkpoint,
            Optional<SkillPlan> approvedPlan,
            boolean descriptorsAvailable) {
        return new SkillCheckpointRecoveryCoordinationRequest(
                SkillCheckpointRecoverySource.valid(checkpoint),
                SERVER_ID,
                BOT_ID,
                PLAYER_ID,
                checkpoint.generation() + 1L,
                approvedPlan,
                descriptorsAvailable,
                new SkillCheckpointRecoverySafety(true, true, true, true),
                new SkillCheckpointReobservation(
                        true,
                        checkpoint.scope().map(
                                SkillCheckpointScope::expectedFingerprint),
                        List.of(new CheckpointEvidence(
                                "restart_reobserved",
                                "当前库存与目标已重新观察",
                                400L))),
                400L);
    }

    private static SkillPlan plan(UUID planId, String marker) {
        return new SkillPlan(
                planId,
                BOT_ID,
                1L,
                List.of(new SkillPlanNode(
                        NODE_ID,
                        SKILL_ID,
                        SKILL_VERSION,
                        new SkillParameters(java.util.Map.of(
                                "marker", marker)))),
                List.of());
    }

    private static SkillCheckpoint checkpoint(
            SkillPlan plan, long generation) {
        return new SkillCheckpoint(
                CHECKPOINT_ID,
                SERVER_ID,
                BOT_ID,
                PLAYER_ID,
                generation,
                OLD_RUN_ID,
                new SkillCheckpointPlan(
                        plan.planId(),
                        plan.revision(),
                        SkillCheckpointBridge.planDigest(plan)),
                "revalidate",
                SkillCheckpointContinuationState.RESTARTABLE,
                11L,
                0,
                0,
                100L,
                Optional.of(new SkillCheckpointScope(
                        "minecraft:overworld",
                        12,
                        64,
                        -4,
                        Optional.empty(),
                        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                        "库存、位置和工作站已重新观察")),
                List.of(new SkillCheckpointNode(
                        NODE_ID,
                        SkillCheckpointNodeState.READY,
                        0,
                        Optional.empty(),
                        List.of())),
                List.of(new CheckpointEvidence(
                        "saved",
                        "已在中央安全点保存",
                        100L)),
                Optional.empty());
    }

    private static SkillCheckpoint withNodeState(
            SkillCheckpoint source, SkillCheckpointNodeState state) {
        SkillCheckpointNode node = source.nodes().get(0);
        return new SkillCheckpoint(
                source.checkpointId(),
                source.serverInstanceId(),
                source.botId(),
                source.playerId(),
                source.generation(),
                source.runId(),
                source.plan(),
                source.phase(),
                source.continuationState(),
                source.stateRevision(),
                source.attemptCount(),
                source.recoveryCount(),
                source.checkpointTick(),
                source.scope(),
                List.of(new SkillCheckpointNode(
                        node.nodeId(), state, 0, Optional.empty(), List.of())),
                source.evidence(),
                source.failureCode());
    }
}
