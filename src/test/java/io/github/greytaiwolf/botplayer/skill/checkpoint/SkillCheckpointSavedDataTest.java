package io.github.greytaiwolf.botplayer.skill.checkpoint;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SkillCheckpointSavedDataTest {
    private static final UUID SERVER_ID = new UUID(1L, 1L);
    private static final UUID BOT_ID = new UUID(1L, 2L);

    @Test
    void upsertRejectsStaleAndConflictingWritesThenClosesExactGeneration() {
        SkillCheckpointSavedData data = SkillCheckpointSavedData.create(SERVER_ID);
        SkillCheckpoint first = SkillCheckpointCodecTest.checkpoint(4L, 9L);
        data.upsert(first);

        Assertions.assertEquals(Optional.of(first), data.load(BOT_ID));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> data.upsert(SkillCheckpointCodecTest.checkpoint(3L, 10L)));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> data.upsert(SkillCheckpointCodecTest.checkpoint(4L, 8L)));

        SkillCheckpoint conflicting = new SkillCheckpoint(
                first.checkpointId(),
                first.serverInstanceId(),
                first.botId(),
                first.playerId(),
                first.generation(),
                first.runId(),
                first.plan(),
                first.phase(),
                first.continuationState(),
                first.stateRevision(),
                first.attemptCount(),
                first.recoveryCount(),
                first.checkpointTick() + 1L,
                first.scope(),
                first.nodes(),
                first.evidence(),
                first.failureCode());
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> data.upsert(conflicting));

        SkillCheckpoint nextGeneration = SkillCheckpointCodecTest.checkpoint(5L, 0L);
        data.upsert(nextGeneration);
        Assertions.assertFalse(data.closeGeneration(BOT_ID, 4L));
        Assertions.assertTrue(data.closeGeneration(BOT_ID, 5L));
        Assertions.assertTrue(data.load(BOT_ID).isEmpty());
    }

    @Test
    void savesLoadsAndRemovesWithoutCrossServerAcceptance() {
        SkillCheckpointSavedData data = SkillCheckpointSavedData.create(SERVER_ID);
        SkillCheckpoint checkpoint = SkillCheckpointCodecTest.checkpoint(4L, 9L);
        data.upsert(checkpoint);

        CompoundTag encoded = data.save(new CompoundTag(), null);
        SkillCheckpointSavedData restored = SkillCheckpointSavedData.load(encoded, null);
        Assertions.assertEquals(SERVER_ID, restored.serverInstanceId());
        Assertions.assertEquals(Optional.of(checkpoint), restored.load(BOT_ID));
        Assertions.assertEquals(
                SkillCheckpointLoadStatus.VALID,
                restored.recoverySource(BOT_ID).loadStatus());
        Assertions.assertEquals(
                SkillCheckpointLoadStatus.MISSING,
                restored.recoverySource(new UUID(1L, 3L)).loadStatus());

        SkillCheckpoint otherServer = new SkillCheckpoint(
                checkpoint.checkpointId(),
                new UUID(9L, 9L),
                checkpoint.botId(),
                checkpoint.playerId(),
                checkpoint.generation(),
                checkpoint.runId(),
                checkpoint.plan(),
                checkpoint.phase(),
                checkpoint.continuationState(),
                checkpoint.stateRevision() + 1L,
                checkpoint.attemptCount(),
                checkpoint.recoveryCount(),
                checkpoint.checkpointTick(),
                checkpoint.scope(),
                checkpoint.nodes(),
                checkpoint.evidence(),
                checkpoint.failureCode());
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> restored.upsert(otherServer));
        Assertions.assertEquals(Optional.of(checkpoint), restored.remove(BOT_ID));
        Assertions.assertTrue(restored.remove(BOT_ID).isEmpty());
    }

    @Test
    void terminalFenceBlocksTheOldRunButAllowsANewRunToReachASafePoint() {
        SkillCheckpointSavedData data = SkillCheckpointSavedData.create(SERVER_ID);
        SkillCheckpoint prior = SkillCheckpointCodecTest.checkpoint(4L, 9L);
        data.upsert(prior);
        SkillCheckpoint fenced = SkillCheckpointBridge.dispatchFence(
                prior, 100L);
        data.upsert(fenced);

        SkillCheckpoint staleSameRun = new SkillCheckpoint(
                prior.checkpointId(),
                prior.serverInstanceId(),
                prior.botId(),
                prior.playerId(),
                prior.generation(),
                prior.runId(),
                prior.plan(),
                prior.phase(),
                SkillCheckpointContinuationState.RESTARTABLE,
                prior.stateRevision(),
                prior.attemptCount(),
                prior.recoveryCount(),
                prior.checkpointTick(),
                prior.scope(),
                prior.nodes(),
                prior.evidence(),
                prior.failureCode());
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> data.upsert(staleSameRun));

        SkillCheckpoint nextRun = new SkillCheckpoint(
                new UUID(2L, 1L),
                prior.serverInstanceId(),
                prior.botId(),
                prior.playerId(),
                prior.generation(),
                new UUID(2L, 2L),
                prior.plan(),
                "preparing",
                SkillCheckpointContinuationState.RESTARTABLE,
                1L,
                prior.attemptCount(),
                prior.recoveryCount(),
                101L,
                prior.scope(),
                prior.nodes(),
                prior.evidence(),
                Optional.empty());
        data.upsert(nextRun);
        Assertions.assertEquals(Optional.of(nextRun), data.load(BOT_ID));
    }

    @Test
    void malformedSavedDataBecomesReadOnlyDamagedRecoverySource() {
        SkillCheckpointSavedData data = SkillCheckpointSavedData.create(SERVER_ID);
        SkillCheckpoint checkpoint = SkillCheckpointCodecTest.checkpoint(4L, 9L);
        data.upsert(checkpoint);
        CompoundTag encoded = data.save(new CompoundTag(), null);
        encoded.putString("Integrity", "0".repeat(64));

        SkillCheckpointSavedData damaged = SkillCheckpointSavedData.load(encoded, null);

        Assertions.assertEquals(SkillCheckpointLoadStatus.DAMAGED,
                damaged.loadStatus());
        Assertions.assertEquals(
                Optional.of("checkpoint data failed strict validation"),
                damaged.loadDiagnostic());
        Assertions.assertEquals(
                SkillCheckpointLoadStatus.DAMAGED,
                damaged.recoverySource(BOT_ID).loadStatus());
        Assertions.assertTrue(damaged.recoverySource(BOT_ID).checkpoint().isEmpty());
        Assertions.assertTrue(damaged.load(BOT_ID).isEmpty());
        Assertions.assertTrue(damaged.checkpoints().isEmpty());

        Assertions.assertThrows(IllegalStateException.class,
                () -> damaged.upsert(checkpoint));
        Assertions.assertThrows(IllegalStateException.class,
                () -> damaged.remove(BOT_ID));
        Assertions.assertThrows(IllegalStateException.class,
                () -> damaged.closeGeneration(BOT_ID, checkpoint.generation()));
        Assertions.assertThrows(IllegalStateException.class,
                () -> damaged.save(new CompoundTag(), null));

        // 模拟 get() 的 bind 分支：损坏仓库必须保持未绑定，不能被当成新空数据覆写。
        damaged.bindOrVerifyServerInstance(SERVER_ID);
        damaged.bindOrVerifyServerInstance(new UUID(7L, 7L));
        Assertions.assertEquals(SkillCheckpointLoadStatus.DAMAGED,
                damaged.recoverySource(BOT_ID).loadStatus());
        Assertions.assertThrows(IllegalStateException.class,
                damaged::serverInstanceId);
    }

    @Test
    void unsupportedSchemaIsDistinctFromMalformedDataAndAlsoCannotBeRebound() {
        SkillCheckpointSavedData data = SkillCheckpointSavedData.create(SERVER_ID);
        CompoundTag encoded = data.save(new CompoundTag(), null);
        encoded.putInt("SchemaVersion", SkillCheckpointSavedData.SCHEMA_VERSION + 1);

        SkillCheckpointSavedData unsupported = SkillCheckpointSavedData.load(encoded, null);

        Assertions.assertEquals(SkillCheckpointLoadStatus.UNSUPPORTED_SCHEMA,
                unsupported.loadStatus());
        Assertions.assertEquals(
                Optional.of("checkpoint schema version is unsupported"),
                unsupported.loadDiagnostic());
        Assertions.assertEquals(SkillCheckpointLoadStatus.UNSUPPORTED_SCHEMA,
                unsupported.recoverySource(BOT_ID).loadStatus());
        unsupported.bindOrVerifyServerInstance(SERVER_ID);
        Assertions.assertThrows(IllegalStateException.class,
                () -> unsupported.remove(BOT_ID));

        CompoundTag wrongSchemaType = data.save(new CompoundTag(), null);
        wrongSchemaType.putString("SchemaVersion", "future");
        SkillCheckpointSavedData malformed = SkillCheckpointSavedData.load(
                wrongSchemaType, null);
        Assertions.assertEquals(SkillCheckpointLoadStatus.DAMAGED,
                malformed.loadStatus());
        Assertions.assertEquals(SkillCheckpointLoadStatus.DAMAGED,
                malformed.recoverySource(BOT_ID).loadStatus());
    }

    @Test
    void nullInputIsContainedAsDamagedRepository() {
        SkillCheckpointSavedData damaged = SkillCheckpointSavedData.load(null, null);

        Assertions.assertEquals(SkillCheckpointLoadStatus.DAMAGED,
                damaged.loadStatus());
        Assertions.assertTrue(damaged.loadDiagnostic().isPresent());
    }
}
