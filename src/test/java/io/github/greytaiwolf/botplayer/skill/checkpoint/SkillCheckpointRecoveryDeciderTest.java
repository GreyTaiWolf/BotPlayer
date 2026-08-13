package io.github.greytaiwolf.botplayer.skill.checkpoint;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SkillCheckpointRecoveryDeciderTest {
    @Test
    void restartsOnlyIntoNewGenerationWithoutOldRunToken() {
        SkillCheckpoint checkpoint = SkillCheckpointCodecTest.checkpoint(4L, 9L);

        SkillCheckpointRecoveryOutcome outcome =
                SkillCheckpointRecoveryDecider.evaluate(request(checkpoint));

        Assertions.assertEquals(
                SkillCheckpointRecoveryDisposition.RESTART_FROM_SAFE_PHASE,
                outcome.disposition());
        Assertions.assertTrue(outcome.rejection().isEmpty());
        SkillCheckpointContinuation continuation = outcome.continuation()
                .orElseThrow();
        Assertions.assertEquals(checkpoint.checkpointId(), continuation.checkpointId());
        Assertions.assertEquals(checkpoint.generation() + 1L, continuation.generation());
        Assertions.assertEquals(checkpoint.phase(), continuation.safePhase());
        Assertions.assertEquals(checkpoint.nodes(), continuation.nodes());
    }

    @Test
    void rejectsWrongIdentityOldGenerationAndTerminalCheckpoint() {
        SkillCheckpoint checkpoint = SkillCheckpointCodecTest.checkpoint(4L, 9L);
        SkillCheckpointRecoveryRequest wrongServer = request(
                checkpoint,
                new UUID(9L, 1L),
                checkpoint.botId(),
                checkpoint.playerId(),
                checkpoint.generation() + 1L,
                allSafe(),
                reobservation(checkpoint));
        assertRejected(
                wrongServer,
                SkillCheckpointRecoveryRejection.SERVER_INSTANCE_MISMATCH);

        SkillCheckpointRecoveryRequest wrongBot = request(
                checkpoint,
                checkpoint.serverInstanceId(),
                new UUID(9L, 2L),
                checkpoint.playerId(),
                checkpoint.generation() + 1L,
                allSafe(),
                reobservation(checkpoint));
        assertRejected(wrongBot, SkillCheckpointRecoveryRejection.BOT_ID_MISMATCH);

        SkillCheckpointRecoveryRequest wrongPlayer = request(
                checkpoint,
                checkpoint.serverInstanceId(),
                checkpoint.botId(),
                new UUID(9L, 3L),
                checkpoint.generation() + 1L,
                allSafe(),
                reobservation(checkpoint));
        assertRejected(
                wrongPlayer, SkillCheckpointRecoveryRejection.PLAYER_ID_MISMATCH);

        SkillCheckpointRecoveryRequest oldGeneration = request(
                checkpoint,
                checkpoint.serverInstanceId(),
                checkpoint.botId(),
                checkpoint.playerId(),
                checkpoint.generation(),
                allSafe(),
                reobservation(checkpoint));
        assertRejected(
                oldGeneration,
                SkillCheckpointRecoveryRejection.GENERATION_NOT_ADVANCED);

        SkillCheckpoint terminal = withState(
                checkpoint, SkillCheckpointContinuationState.SUCCEEDED);
        assertRejected(
                request(terminal),
                SkillCheckpointRecoveryRejection.CHECKPOINT_TERMINAL);
    }

    @Test
    void rejectsDamagedSchemaAndUnavailablePlanOrDescriptors() {
        SkillCheckpoint checkpoint = SkillCheckpointCodecTest.checkpoint(4L, 9L);
        SkillCheckpointRecoveryRequest damaged = new SkillCheckpointRecoveryRequest(
                SkillCheckpointRecoverySource.unavailable(
                        SkillCheckpointLoadStatus.DAMAGED),
                checkpoint.serverInstanceId(),
                checkpoint.botId(),
                checkpoint.playerId(),
                checkpoint.generation() + 1L,
                availability(checkpoint),
                allSafe(),
                reobservation(checkpoint));
        assertRejected(
                damaged, SkillCheckpointRecoveryRejection.CHECKPOINT_DAMAGED);

        SkillCheckpointRecoveryRequest schema = new SkillCheckpointRecoveryRequest(
                SkillCheckpointRecoverySource.unavailable(
                        SkillCheckpointLoadStatus.UNSUPPORTED_SCHEMA),
                checkpoint.serverInstanceId(),
                checkpoint.botId(),
                checkpoint.playerId(),
                checkpoint.generation() + 1L,
                availability(checkpoint),
                allSafe(),
                reobservation(checkpoint));
        assertRejected(
                schema,
                SkillCheckpointRecoveryRejection.CHECKPOINT_SCHEMA_UNSUPPORTED);

        SkillCheckpointRecoveryRequest noPlan = request(
                checkpoint,
                SkillCheckpointRecoveryAvailability.unavailable(),
                allSafe(),
                reobservation(checkpoint));
        assertRejected(
                noPlan, SkillCheckpointRecoveryRejection.PLAN_UNAVAILABLE);

        SkillCheckpointRecoveryRequest wrongPlanVersion = request(
                checkpoint,
                new SkillCheckpointRecoveryAvailability(
                        Optional.of(new SkillCheckpointPlan(
                                checkpoint.plan().planId(),
                                checkpoint.plan().revision() + 1L,
                                "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210")),
                        true),
                allSafe(),
                reobservation(checkpoint));
        assertRejected(
                wrongPlanVersion,
                SkillCheckpointRecoveryRejection.PLAN_VERSION_MISMATCH);

        SkillCheckpointRecoveryRequest noDescriptors = request(
                checkpoint,
                new SkillCheckpointRecoveryAvailability(
                        Optional.of(checkpoint.plan()), false),
                allSafe(),
                reobservation(checkpoint));
        assertRejected(
                noDescriptors,
                SkillCheckpointRecoveryRejection.DESCRIPTOR_UNAVAILABLE);
    }

    @Test
    void rejectsOpenMenuPendingActionCarriedStateAndOldTokens() {
        SkillCheckpoint checkpoint = SkillCheckpointCodecTest.checkpoint(4L, 9L);
        assertRejected(
                request(checkpoint,
                        new SkillCheckpointRecoverySafety(
                                false, true, true, true)),
                SkillCheckpointRecoveryRejection.MENU_STILL_OPEN);
        assertRejected(
                request(checkpoint,
                        new SkillCheckpointRecoverySafety(
                                true, false, true, true)),
                SkillCheckpointRecoveryRejection.ACTION_NOT_QUIESCENT);
        assertRejected(
                request(checkpoint,
                        new SkillCheckpointRecoverySafety(
                                true, true, false, true)),
                SkillCheckpointRecoveryRejection.CARRIED_STATE_NOT_EMPTY);
        assertRejected(
                request(checkpoint,
                        new SkillCheckpointRecoverySafety(
                                true, true, true, false)),
                SkillCheckpointRecoveryRejection.OLD_GENERATION_TOKEN_PRESENT);
    }

    @Test
    void requiresCompleteFreshMatchingReobservation() {
        SkillCheckpoint checkpoint = SkillCheckpointCodecTest.checkpoint(4L, 9L);
        SkillCheckpointRecoveryRequest incomplete = request(
                checkpoint,
                availability(checkpoint),
                allSafe(),
                new SkillCheckpointReobservation(
                        false, Optional.empty(), List.of()));
        assertRejected(
                incomplete,
                SkillCheckpointRecoveryRejection.REOBSERVATION_INCOMPLETE);

        SkillCheckpointRecoveryRequest wrongFingerprint = request(
                checkpoint,
                availability(checkpoint),
                allSafe(),
                new SkillCheckpointReobservation(
                        true,
                        Optional.of(
                                "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"),
                        List.of(new CheckpointEvidence(
                                "scope_reobserved", "当前范围已重新读取", 101L))));
        assertRejected(
                wrongFingerprint,
                SkillCheckpointRecoveryRejection.SCOPE_FINGERPRINT_MISMATCH);
    }

    @Test
    void rejectsCheckpointWithoutAReobservableScope() {
        SkillCheckpoint checkpoint = withoutScope(
                SkillCheckpointCodecTest.checkpoint(4L, 9L));

        assertRejected(
                request(checkpoint),
                SkillCheckpointRecoveryRejection.CHECKPOINT_SCOPE_MISSING);
    }

    private static SkillCheckpointRecoveryRequest request(
            SkillCheckpoint checkpoint) {
        return request(
                checkpoint,
                checkpoint.serverInstanceId(),
                checkpoint.botId(),
                checkpoint.playerId(),
                checkpoint.generation() + 1L,
                allSafe(),
                reobservation(checkpoint));
    }

    private static SkillCheckpointRecoveryRequest request(
            SkillCheckpoint checkpoint,
            SkillCheckpointRecoverySafety safety) {
        return request(
                checkpoint,
                checkpoint.serverInstanceId(),
                checkpoint.botId(),
                checkpoint.playerId(),
                checkpoint.generation() + 1L,
                safety,
                reobservation(checkpoint));
    }

    private static SkillCheckpointRecoveryRequest request(
            SkillCheckpoint checkpoint,
            SkillCheckpointRecoveryAvailability availability,
            SkillCheckpointRecoverySafety safety,
            SkillCheckpointReobservation reobservation) {
        return request(
                checkpoint,
                checkpoint.serverInstanceId(),
                checkpoint.botId(),
                checkpoint.playerId(),
                checkpoint.generation() + 1L,
                availability,
                safety,
                reobservation);
    }

    private static SkillCheckpointRecoveryRequest request(
            SkillCheckpoint checkpoint,
            UUID serverInstanceId,
            UUID botId,
            UUID playerId,
            long newGeneration,
            SkillCheckpointRecoverySafety safety,
            SkillCheckpointReobservation reobservation) {
        return request(
                checkpoint,
                serverInstanceId,
                botId,
                playerId,
                newGeneration,
                availability(checkpoint),
                safety,
                reobservation);
    }

    private static SkillCheckpointRecoveryRequest request(
            SkillCheckpoint checkpoint,
            UUID serverInstanceId,
            UUID botId,
            UUID playerId,
            long newGeneration,
            SkillCheckpointRecoveryAvailability availability,
            SkillCheckpointRecoverySafety safety,
            SkillCheckpointReobservation reobservation) {
        return new SkillCheckpointRecoveryRequest(
                SkillCheckpointRecoverySource.valid(checkpoint),
                serverInstanceId,
                botId,
                playerId,
                newGeneration,
                availability,
                safety,
                reobservation);
    }

    private static SkillCheckpointRecoveryAvailability availability(
            SkillCheckpoint checkpoint) {
        return new SkillCheckpointRecoveryAvailability(
                Optional.of(checkpoint.plan()), true);
    }

    private static SkillCheckpointRecoverySafety allSafe() {
        return new SkillCheckpointRecoverySafety(true, true, true, true);
    }

    private static SkillCheckpointReobservation reobservation(
            SkillCheckpoint checkpoint) {
        return new SkillCheckpointReobservation(
                true,
                checkpoint.scope().map(SkillCheckpointScope::expectedFingerprint),
                List.of(new CheckpointEvidence(
                        "scope_reobserved", "当前范围已重新读取", 101L)));
    }

    private static SkillCheckpoint withState(
            SkillCheckpoint source,
            SkillCheckpointContinuationState continuationState) {
        return new SkillCheckpoint(
                source.checkpointId(),
                source.serverInstanceId(),
                source.botId(),
                source.playerId(),
                source.generation(),
                source.runId(),
                source.plan(),
                source.phase(),
                continuationState,
                source.stateRevision(),
                source.attemptCount(),
                source.recoveryCount(),
                source.checkpointTick(),
                source.scope(),
                source.nodes(),
                source.evidence(),
                Optional.empty());
    }

    private static SkillCheckpoint withoutScope(SkillCheckpoint source) {
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
                Optional.empty(),
                source.nodes(),
                source.evidence(),
                source.failureCode());
    }

    private static void assertRejected(
            SkillCheckpointRecoveryRequest request,
            SkillCheckpointRecoveryRejection expected) {
        SkillCheckpointRecoveryOutcome outcome =
                SkillCheckpointRecoveryDecider.evaluate(request);
        Assertions.assertEquals(
                SkillCheckpointRecoveryDisposition.REJECT,
                outcome.disposition());
        Assertions.assertEquals(Optional.of(expected), outcome.rejection());
        Assertions.assertTrue(outcome.continuation().isEmpty());
    }
}
