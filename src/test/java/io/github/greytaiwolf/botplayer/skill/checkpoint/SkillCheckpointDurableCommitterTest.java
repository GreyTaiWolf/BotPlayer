package io.github.greytaiwolf.botplayer.skill.checkpoint;

import java.util.concurrent.CompletableFuture;
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
    void minecraftAdapterInvokesOnlySynchronousSaveBoundary() throws Exception {
        FakeDataStorage storage = new FakeDataStorage();
        MinecraftSavedDataCheckpointDurability durability =
                new MinecraftSavedDataCheckpointDurability(storage);

        durability.commit();

        Assertions.assertEquals(1, storage.saveCalls.get());
    }

    @Test
    void minecraftAdapterAcceptsVoidSynchronousSaveBoundary() throws Exception {
        VoidFakeDataStorage storage = new VoidFakeDataStorage();
        MinecraftSavedDataCheckpointDurability durability =
                new MinecraftSavedDataCheckpointDurability(storage);

        durability.commit();

        Assertions.assertEquals(1, storage.saveCalls.get());
    }

    /** 只模拟映射适配器所需的公开同步 API，不伪造 Minecraft 持久化实现。 */
    public static final class FakeDataStorage {
        private final AtomicInteger saveCalls = new AtomicInteger();

        public CompletableFuture<Void> saveAndJoin() {
            saveCalls.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }
    }

    /** 某些映射版本将 saveAndJoin 暴露为 void；同步语义仍由该方法名承担。 */
    public static final class VoidFakeDataStorage {
        private final AtomicInteger saveCalls = new AtomicInteger();

        public void saveAndJoin() {
            saveCalls.incrementAndGet();
        }
    }
}
