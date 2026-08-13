package io.github.greytaiwolf.botplayer.skill.checkpoint;

import java.util.Objects;
import java.util.Optional;

/** 以当前身份、瞬态清理和重新观察为前提的纯恢复判定器。 */
public final class SkillCheckpointRecoveryDecider {
    private SkillCheckpointRecoveryDecider() {}

    public static SkillCheckpointRecoveryOutcome evaluate(
            SkillCheckpointRecoveryRequest request) {
        Objects.requireNonNull(request, "request");
        Optional<SkillCheckpointRecoveryRejection> sourceRejection =
                sourceRejection(request.source());
        if (sourceRejection.isPresent()) {
            return SkillCheckpointRecoveryOutcome.reject(
                    sourceRejection.orElseThrow());
        }
        SkillCheckpoint checkpoint = request.source()
                .checkpoint()
                .orElseThrow();
        if (!checkpoint.serverInstanceId().equals(request.serverInstanceId())) {
            return SkillCheckpointRecoveryOutcome.reject(
                    SkillCheckpointRecoveryRejection.SERVER_INSTANCE_MISMATCH);
        }
        if (!checkpoint.botId().equals(request.botId())) {
            return SkillCheckpointRecoveryOutcome.reject(
                    SkillCheckpointRecoveryRejection.BOT_ID_MISMATCH);
        }
        if (!checkpoint.playerId().equals(request.playerId())) {
            return SkillCheckpointRecoveryOutcome.reject(
                    SkillCheckpointRecoveryRejection.PLAYER_ID_MISMATCH);
        }
        if (request.newGeneration() <= checkpoint.generation()) {
            return SkillCheckpointRecoveryOutcome.reject(
                    SkillCheckpointRecoveryRejection.GENERATION_NOT_ADVANCED);
        }
        if (checkpoint.continuationState().isTerminal()) {
            return SkillCheckpointRecoveryOutcome.reject(
                    SkillCheckpointRecoveryRejection.CHECKPOINT_TERMINAL);
        }
        /*
         * 旧版本可能写出没有 scope 的 checkpoint。它没有保存足以证明库存、位置
         * 和工作站仍一致的边界，绝不能把“新 body 的菜单已关闭”当作可恢复证据。
         */
        if (checkpoint.scope().isEmpty()) {
            return SkillCheckpointRecoveryOutcome.reject(
                    SkillCheckpointRecoveryRejection.CHECKPOINT_SCOPE_MISSING);
        }
        SkillCheckpointRecoveryAvailability availability = request.availability();
        if (availability.registeredPlan().isEmpty()) {
            return SkillCheckpointRecoveryOutcome.reject(
                    SkillCheckpointRecoveryRejection.PLAN_UNAVAILABLE);
        }
        if (!availability.matches(checkpoint.plan())) {
            return SkillCheckpointRecoveryOutcome.reject(
                    SkillCheckpointRecoveryRejection.PLAN_VERSION_MISMATCH);
        }
        if (!availability.allDescriptorsAvailable()) {
            return SkillCheckpointRecoveryOutcome.reject(
                    SkillCheckpointRecoveryRejection.DESCRIPTOR_UNAVAILABLE);
        }
        SkillCheckpointRecoverySafety safety = request.safety();
        if (!safety.menuClosed()) {
            return SkillCheckpointRecoveryOutcome.reject(
                    SkillCheckpointRecoveryRejection.MENU_STILL_OPEN);
        }
        if (!safety.actionQuiescent()) {
            return SkillCheckpointRecoveryOutcome.reject(
                    SkillCheckpointRecoveryRejection.ACTION_NOT_QUIESCENT);
        }
        if (!safety.carriedStateEmpty()) {
            return SkillCheckpointRecoveryOutcome.reject(
                    SkillCheckpointRecoveryRejection.CARRIED_STATE_NOT_EMPTY);
        }
        if (!safety.oldGenerationTokensCleared()) {
            return SkillCheckpointRecoveryOutcome.reject(
                    SkillCheckpointRecoveryRejection.OLD_GENERATION_TOKEN_PRESENT);
        }
        SkillCheckpointReobservation reobservation = request.reobservation();
        if (!reobservation.complete()) {
            return SkillCheckpointRecoveryOutcome.reject(
                    SkillCheckpointRecoveryRejection.REOBSERVATION_INCOMPLETE);
        }
        if (checkpoint.scope().isPresent()
                && (!reobservation.observedScopeFingerprint().equals(
                        Optional.of(checkpoint.scope().orElseThrow()
                                .expectedFingerprint())))) {
            return SkillCheckpointRecoveryOutcome.reject(
                    SkillCheckpointRecoveryRejection.SCOPE_FINGERPRINT_MISMATCH);
        }
        return SkillCheckpointRecoveryOutcome.restart(
                new SkillCheckpointContinuation(
                        checkpoint.checkpointId(),
                        checkpoint.botId(),
                        checkpoint.playerId(),
                        request.newGeneration(),
                        checkpoint.plan(),
                        checkpoint.phase(),
                        checkpoint.stateRevision(),
                        checkpoint.nodes(),
                        checkpoint.scope(),
                        checkpoint.evidence(),
                        reobservation.evidence()));
    }

    private static Optional<SkillCheckpointRecoveryRejection> sourceRejection(
            SkillCheckpointRecoverySource source) {
        return switch (source.loadStatus()) {
            case VALID -> Optional.empty();
            case MISSING -> Optional.of(
                    SkillCheckpointRecoveryRejection.CHECKPOINT_MISSING);
            case DAMAGED -> Optional.of(
                    SkillCheckpointRecoveryRejection.CHECKPOINT_DAMAGED);
            case UNSUPPORTED_SCHEMA -> Optional.of(
                    SkillCheckpointRecoveryRejection
                            .CHECKPOINT_SCHEMA_UNSUPPORTED);
        };
    }
}
