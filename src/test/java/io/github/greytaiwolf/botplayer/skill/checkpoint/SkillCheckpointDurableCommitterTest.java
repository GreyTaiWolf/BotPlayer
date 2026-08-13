package io.github.greytaiwolf.botplayer.skill.checkpoint;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SkillCheckpointDurableCommitterTest {
    private static final UUID SERVER_ID = new UUID(1L, 1L);
    private static final UUID BOT_ID = new UUID(1L, 2L);

    @Test
    void upsertRequiresInjectedSynchronousCommit() {
        AtomicInteger commits = new AtomicInteger();
        SkillCheckpointSavedData data = SkillCheckpointSavedData.create(
                SERVER_ID);
        SkillCheckpointDurableCommitter committer = new SkillCheckpointDurableCommitter(
                data, commits::incrementAndGet);

        SkillCheckpoint checkpoint = SkillCheckpointCodecTest.checkpoint(4L, 9L);
        committer.upsert(checkpoint);

        Assertions.assertEquals(1, commits.get());
        Assertions.assertTrue(committer.isReady());
        Assertions.assertEquals(checkpoint,
                data.load(BOT_ID).orElseThrow());
    }

    @Test
    void failedCommitLatchesAndRejectsLaterMutationsBeforeMemoryChanges() {
        AtomicInteger commits = new AtomicInteger();
        SkillCheckpointSavedData data = SkillCheckpointSavedData.create(
                SERVER_ID);
        SkillCheckpointDurableCommitter committer = new SkillCheckpointDurableCommitter(
                data, () -> {
                    commits.incrementAndGet();
                    throw new IllegalStateException("injected flush failure");
                });
        SkillCheckpoint first = SkillCheckpointCodecTest.checkpoint(4L, 9L);

        Assertions.assertThrows(SkillCheckpointDurableCommitException.class,
                () -> committer.upsert(first));
        Assertions.assertEquals(SkillCheckpointCommitStatus.FAILED, committer.status());
        // 第一次变更已进入内存但未获耐久证据；后续操作必须在触碰内存前被拒绝。
        SkillCheckpoint later = SkillCheckpointCodecTest.checkpoint(5L, 0L);
        Assertions.assertThrows(SkillCheckpointDurableCommitException.class,
                () -> committer.upsert(later));
        Assertions.assertEquals(1, commits.get());
        Assertions.assertEquals(first,
                data.load(BOT_ID).orElseThrow());
    }

    @Test
    void exactGenerationCloseCommitsOnlyWhenItActuallyDeletes() {
        AtomicInteger commits = new AtomicInteger();
        SkillCheckpointSavedData data = SkillCheckpointSavedData.create(
                SERVER_ID);
        SkillCheckpointDurableCommitter committer = new SkillCheckpointDurableCommitter(
                data, commits::incrementAndGet);
        SkillCheckpoint checkpoint = SkillCheckpointCodecTest.checkpoint(4L, 9L);
        committer.upsert(checkpoint);

        Assertions.assertFalse(committer.closeGeneration(
                BOT_ID, 3L));
        Assertions.assertEquals(1, commits.get());
        Assertions.assertTrue(committer.closeGeneration(
                BOT_ID, 4L));
        Assertions.assertEquals(2, commits.get());
        Assertions.assertTrue(data.load(BOT_ID).isEmpty());
    }

    @Test
    void removeCommitsOnlyWhenItActuallyDeletes() {
        AtomicInteger commits = new AtomicInteger();
        SkillCheckpointSavedData data = SkillCheckpointSavedData.create(
                SERVER_ID);
        SkillCheckpointDurableCommitter committer = new SkillCheckpointDurableCommitter(
                data, commits::incrementAndGet);
        SkillCheckpoint checkpoint = SkillCheckpointCodecTest.checkpoint(4L, 9L);
        committer.upsert(checkpoint);

        Assertions.assertTrue(committer.remove(new UUID(9L, 9L)).isEmpty());
        Assertions.assertEquals(1, commits.get());
        Assertions.assertEquals(checkpoint, committer.remove(BOT_ID).orElseThrow());
        Assertions.assertEquals(2, commits.get());
        Assertions.assertTrue(data.load(BOT_ID).isEmpty());
    }

    @Test
    void unavailablePortNeverTreatsDirtySavedDataAsDurable() {
        SkillCheckpointSavedData data = SkillCheckpointSavedData.create(
                SERVER_ID);
        SkillCheckpointDurableCommitter committer = new SkillCheckpointDurableCommitter(
                data, SkillCheckpointDurability.unavailable());

        Assertions.assertThrows(SkillCheckpointDurableCommitException.class,
                () -> committer.upsert(SkillCheckpointCodecTest.checkpoint(4L, 9L)));
        Assertions.assertFalse(committer.isReady());
    }

    @Test
    void minecraftAdapterInvokesTheOnePointTwentyOneOneSynchronousSaveBoundary()
            throws Exception {
        FakeDataStorage storage = new FakeDataStorage();
        MinecraftSavedDataCheckpointDurability durability =
                new MinecraftSavedDataCheckpointDurability(storage);

        durability.commit();

        Assertions.assertEquals(1, storage.saveCalls.get());
    }

    @Test
    void minecraftAdapterRejectsStorageWithoutTheOnePointTwentyOneOneSaveApi() {
        Assertions.assertThrows(IllegalStateException.class,
                () -> new MinecraftSavedDataCheckpointDurability(
                        new MissingSaveDataStorage()));
    }

    /** 只模拟映射适配器所需的公开同步 API，不伪造 Minecraft 持久化实现。 */
    public static final class FakeDataStorage {
        private final AtomicInteger saveCalls = new AtomicInteger();

        public void save() {
            saveCalls.incrementAndGet();
        }
    }

    /** 没有明确同步边界的存储绝不能被适配器悄悄接受。 */
    public static final class MissingSaveDataStorage {}
}
