package io.github.greytaiwolf.botplayer.skill.checkpoint;

import io.github.greytaiwolf.botplayer.skill.core.SkillId;
import io.github.greytaiwolf.botplayer.skill.core.SkillParameters;
import io.github.greytaiwolf.botplayer.skill.core.SkillVersion;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlan;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanEdge;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanNode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SkillCheckpointRestartPlanBuilderTest {
    private static final UUID BOT_ID = new UUID(42L, 1L);
    private static final UUID PLAYER_ID = new UUID(42L, 2L);
    private static final UUID FULL_PLAN_ID = new UUID(42L, 3L);
    private static final UUID FIRST = new UUID(42L, 4L);
    private static final UUID SECOND = new UUID(42L, 5L);
    private static final UUID THIRD = new UUID(42L, 6L);
    private static final UUID OLD_RUN_ID = new UUID(42L, 7L);
    private static final UUID OLD_CHECKPOINT_ID = new UUID(42L, 8L);
    private static final SkillId SKILL = new SkillId(
            "botplayer", "restart_suffix_test");
    private static final SkillVersion VERSION = new SkillVersion(1, 0, 0);

    @Test
    void buildsDeterministicSuffixAfterACompletedTopologicalPrefix() {
        SkillPlan fullPlan = fullPlan();
        SkillCheckpointRestartContext context = context(
                fullPlan,
                SkillCheckpointNodeState.SUCCEEDED,
                SkillCheckpointNodeState.READY,
                SkillCheckpointNodeState.PENDING);

        SkillCheckpointRestartPlanBuild first =
                SkillCheckpointRestartPlanBuilder.build(fullPlan, context);
        SkillCheckpointRestartPlanBuild second =
                SkillCheckpointRestartPlanBuilder.build(fullPlan, context);

        SkillCheckpointRestartPlan restart = first.restartPlan().orElseThrow();
        Assertions.assertAll(
                () -> Assertions.assertEquals(
                        SkillCheckpointRestartPlanBuild.Status.BUILT,
                        first.status()),
                () -> Assertions.assertEquals(
                        List.of(FIRST), restart.completedPrefix()),
                () -> Assertions.assertEquals(
                        List.of(SECOND, THIRD),
                        restart.suffixPlan().nodes().stream()
                                .map(SkillPlanNode::nodeId).toList()),
                () -> Assertions.assertEquals(
                        List.of(new SkillPlanEdge(SECOND, THIRD)),
                        restart.suffixPlan().edges()),
                () -> Assertions.assertEquals(
                        restart.suffixPlan().planId(),
                        second.restartPlan().orElseThrow()
                                .suffixPlan().planId()),
                () -> Assertions.assertNotEquals(
                        FULL_PLAN_ID, restart.suffixPlan().planId()),
                () -> Assertions.assertNotEquals(
                        OLD_RUN_ID, restart.suffixPlan().planId()),
                () -> Assertions.assertNotEquals(
                        OLD_CHECKPOINT_ID, restart.suffixPlan().planId()));
    }

    @Test
    void rejectsCompletedNodesThatAreNotOnePrefix() {
        SkillPlan fullPlan = fullPlan();
        SkillCheckpointRestartPlanBuild build =
                SkillCheckpointRestartPlanBuilder.build(
                        fullPlan,
                        context(
                                fullPlan,
                                SkillCheckpointNodeState.READY,
                                SkillCheckpointNodeState.SUCCEEDED,
                                SkillCheckpointNodeState.PENDING));

        Assertions.assertAll(
                () -> Assertions.assertEquals(
                        SkillCheckpointRestartPlanBuild.Status
                                .COMPLETED_NOT_PREFIX,
                        build.status()),
                () -> Assertions.assertTrue(build.restartPlan().isEmpty()));
    }

    @Test
    void rejectsFailedOrIncompleteCheckpointNodeSets() {
        SkillPlan fullPlan = fullPlan();
        SkillCheckpointRestartPlanBuild failed =
                SkillCheckpointRestartPlanBuilder.build(
                        fullPlan,
                        context(
                                fullPlan,
                                SkillCheckpointNodeState.SUCCEEDED,
                                SkillCheckpointNodeState.FAILED,
                                SkillCheckpointNodeState.PENDING));
        SkillCheckpointRestartContext missing = new SkillCheckpointRestartContext(
                BOT_ID,
                PLAYER_ID,
                9L,
                reference(fullPlan),
                "revalidate",
                4L,
                List.of(node(FIRST, SkillCheckpointNodeState.SUCCEEDED)),
                Optional.empty(),
                List.of(evidence("saved", 100L)),
                List.of(evidence("reobserved", 200L)));
        SkillCheckpointRestartPlanBuild missingNodes =
                SkillCheckpointRestartPlanBuilder.build(fullPlan, missing);

        Assertions.assertAll(
                () -> Assertions.assertEquals(
                        SkillCheckpointRestartPlanBuild.Status
                                .FAILED_NODE_PRESENT,
                        failed.status()),
                () -> Assertions.assertEquals(
                        SkillCheckpointRestartPlanBuild.Status
                                .CHECKPOINT_NODE_SET_MISMATCH,
                        missingNodes.status()));
    }

    @Test
    void restartContextRejectsRepeatedCompletedNodeIdsBeforeBuilding() {
        SkillPlan fullPlan = fullPlan();
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new SkillCheckpointRestartContext(
                        BOT_ID,
                        PLAYER_ID,
                        9L,
                        reference(fullPlan),
                        "revalidate",
                        4L,
                        List.of(
                                node(FIRST, SkillCheckpointNodeState.SUCCEEDED),
                                node(FIRST, SkillCheckpointNodeState.SUCCEEDED)),
                        Optional.empty(),
                        List.of(evidence("saved", 100L)),
                        List.of(evidence("reobserved", 200L))));
    }

    static SkillPlan fullPlan() {
        return new SkillPlan(
                FULL_PLAN_ID,
                BOT_ID,
                3L,
                List.of(nodePlan(FIRST), nodePlan(SECOND), nodePlan(THIRD)),
                List.of(
                        new SkillPlanEdge(FIRST, SECOND),
                        new SkillPlanEdge(SECOND, THIRD)));
    }

    static SkillCheckpointRestartContext context(
            SkillPlan plan,
            SkillCheckpointNodeState first,
            SkillCheckpointNodeState second,
            SkillCheckpointNodeState third) {
        return new SkillCheckpointRestartContext(
                BOT_ID,
                PLAYER_ID,
                9L,
                reference(plan),
                "revalidate",
                4L,
                List.of(node(FIRST, first), node(SECOND, second), node(THIRD, third)),
                Optional.empty(),
                List.of(evidence("saved", 100L)),
                List.of(evidence("reobserved", 200L)));
    }

    static SkillCheckpointPlan reference(SkillPlan plan) {
        return new SkillCheckpointPlan(
                plan.planId(),
                plan.revision(),
                SkillCheckpointBridge.planDigest(plan));
    }

    static SkillCheckpointNode node(
            UUID nodeId, SkillCheckpointNodeState state) {
        return new SkillCheckpointNode(
                nodeId,
                state,
                0,
                state == SkillCheckpointNodeState.FAILED
                        ? Optional.of("test.failed")
                        : Optional.empty(),
                List.of());
    }

    static CheckpointEvidence evidence(String code, long tick) {
        return new CheckpointEvidence(code, "已重新验证", tick);
    }

    private static SkillPlanNode nodePlan(UUID nodeId) {
        return new SkillPlanNode(
                nodeId, SKILL, VERSION, SkillParameters.empty());
    }
}
