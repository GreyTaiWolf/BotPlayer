package io.github.greytaiwolf.botplayer.skill.checkpoint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

/** 主世界 SavedData 中按 Bot 身份索引的 P5A 检查点仓库。 */
public final class SkillCheckpointSavedData extends SavedData {
    public static final int SCHEMA_VERSION = 1;
    public static final String DATA_NAME = "botplayer_skill_checkpoints";
    public static final int MAX_CHECKPOINTS = 256;

    private static final String SCHEMA_VERSION_TAG = "SchemaVersion";
    private static final String SERVER_INSTANCE_ID_TAG = "ServerInstanceId";
    private static final String CHECKPOINTS_TAG = "Checkpoints";
    private static final String INTEGRITY_TAG = "Integrity";
    private static final String DAMAGED_DIAGNOSTIC =
            "checkpoint data failed strict validation";
    private static final String UNSUPPORTED_SCHEMA_DIAGNOSTIC =
            "checkpoint schema version is unsupported";

    @Nullable
    private UUID serverInstanceId;
    private final Map<UUID, SkillCheckpoint> checkpointsByBot;
    private final SkillCheckpointLoadStatus loadStatus;
    @Nullable
    private final String loadDiagnostic;

    private SkillCheckpointSavedData() {
        this.serverInstanceId = null;
        this.checkpointsByBot = new LinkedHashMap<>();
        this.loadStatus = SkillCheckpointLoadStatus.VALID;
        this.loadDiagnostic = null;
    }

    private SkillCheckpointSavedData(
            UUID serverInstanceId, Map<UUID, SkillCheckpoint> checkpoints) {
        CheckpointNbt.requireNonZeroUuid(serverInstanceId, "serverInstanceId");
        this.serverInstanceId = serverInstanceId;
        this.checkpointsByBot = new LinkedHashMap<>(checkpoints);
        this.loadStatus = SkillCheckpointLoadStatus.VALID;
        this.loadDiagnostic = null;
    }

    /**
     * 受损仓库刻意不保留任何来自 NBT 的正文、身份或摘要。它只能向恢复判定器报告
     * 一个固定状态和诊断，绝不能因后续保存而覆盖原始证据。
     */
    private SkillCheckpointSavedData(
            SkillCheckpointLoadStatus loadStatus, String loadDiagnostic) {
        if (loadStatus != SkillCheckpointLoadStatus.DAMAGED
                && loadStatus != SkillCheckpointLoadStatus.UNSUPPORTED_SCHEMA) {
            throw new IllegalArgumentException(
                    "damaged repository requires a damaged load status");
        }
        this.serverInstanceId = null;
        this.checkpointsByBot = new LinkedHashMap<>();
        this.loadStatus = loadStatus;
        this.loadDiagnostic = Objects.requireNonNull(
                loadDiagnostic, "loadDiagnostic");
    }

    /** 为不依赖世界加载器的测试或显式导入创建已绑定仓库。 */
    public static SkillCheckpointSavedData create(UUID serverInstanceId) {
        SkillCheckpointSavedData data = new SkillCheckpointSavedData(
                serverInstanceId, Map.of());
        data.setDirty();
        return data;
    }

    public static SavedData.Factory<SkillCheckpointSavedData> factory() {
        return new SavedData.Factory<>(
                SkillCheckpointSavedData::new,
                SkillCheckpointSavedData::load);
    }

    /**
     * 从唯一 Overworld 数据存储取得仓库，并用 roster 的 server instance 绑定世界身份。
     */
    public static SkillCheckpointSavedData get(
            MinecraftServer server, UUID serverInstanceId) {
        Objects.requireNonNull(server, "server");
        CheckpointNbt.requireNonZeroUuid(serverInstanceId, "serverInstanceId");
        SkillCheckpointSavedData data = server.overworld()
                .getDataStorage()
                .computeIfAbsent(factory(), DATA_NAME);
        data.bindOrVerifyServerInstance(serverInstanceId);
        return data;
    }

    public UUID serverInstanceId() {
        return requireServerInstanceId();
    }

    /** 当前磁盘读取的完整性状态；空仓库仍是 {@link SkillCheckpointLoadStatus#VALID}。 */
    public SkillCheckpointLoadStatus loadStatus() {
        return loadStatus;
    }

    /**
     * 固定且不含原始 NBT 内容的加载诊断。有效仓库不暴露诊断，避免调用方把“空”误判为损坏。
     */
    public Optional<String> loadDiagnostic() {
        return Optional.ofNullable(loadDiagnostic);
    }

    public Optional<SkillCheckpoint> load(UUID botId) {
        CheckpointNbt.requireNonZeroUuid(botId, "botId");
        if (!isWritable()) {
            return Optional.empty();
        }
        return Optional.ofNullable(checkpointsByBot.get(botId));
    }

    /**
     * 向恢复路径传递加载状态，防止损坏存档在普通 {@link #load(UUID)} 的空值路径上被误当成
     * “没有 checkpoint”。
     */
    public SkillCheckpointRecoverySource recoverySource(UUID botId) {
        CheckpointNbt.requireNonZeroUuid(botId, "botId");
        return switch (loadStatus) {
            case VALID -> load(botId)
                    .map(SkillCheckpointRecoverySource::valid)
                    .orElseGet(() -> SkillCheckpointRecoverySource.unavailable(
                            SkillCheckpointLoadStatus.MISSING));
            case DAMAGED, UNSUPPORTED_SCHEMA ->
                    SkillCheckpointRecoverySource.unavailable(loadStatus);
            case MISSING -> throw new IllegalStateException(
                    "repository cannot have a missing load status");
        };
    }

    public List<SkillCheckpoint> checkpoints() {
        List<SkillCheckpoint> result = new ArrayList<>(checkpointsByBot.values());
        result.sort(Comparator.comparing(SkillCheckpoint::botId));
        return List.copyOf(result);
    }

    /**
     * 以同一 Bot 的较新 generation/state revision 覆盖检查点；旧结果不能倒灌。
     */
    public void upsert(SkillCheckpoint checkpoint) {
        requireWritable();
        Objects.requireNonNull(checkpoint, "checkpoint");
        if (!checkpoint.serverInstanceId().equals(requireServerInstanceId())) {
            throw new IllegalArgumentException(
                    "checkpoint belongs to a different server instance");
        }
        SkillCheckpoint previous = checkpointsByBot.get(checkpoint.botId());
        if (previous != null) {
            if (checkpoint.generation() < previous.generation()) {
                throw new IllegalArgumentException(
                        "checkpoint generation would move backwards");
            }
            if (checkpoint.generation() == previous.generation()) {
                boolean replacementAfterTerminal = previous
                        .continuationState().isTerminal()
                        && !checkpoint.continuationState().isTerminal()
                        && !checkpoint.runId().equals(previous.runId());
                if (!replacementAfterTerminal) {
                    if (checkpoint.stateRevision()
                            < previous.stateRevision()) {
                        throw new IllegalArgumentException(
                                "checkpoint state revision would move backwards");
                    }
                    if (checkpoint.stateRevision()
                            == previous.stateRevision()
                            && !checkpoint.equals(previous)) {
                        throw new IllegalArgumentException(
                                "checkpoint state revision conflicts with existing data");
                    }
                }
            }
            if (checkpoint.equals(previous)) {
                return;
            }
        } else if (checkpointsByBot.size() >= MAX_CHECKPOINTS) {
            throw new IllegalStateException(
                    "checkpoint repository exceeds maximum " + MAX_CHECKPOINTS);
        }
        checkpointsByBot.put(checkpoint.botId(), checkpoint);
        setDirty();
    }

    public Optional<SkillCheckpoint> remove(UUID botId) {
        requireWritable();
        CheckpointNbt.requireNonZeroUuid(botId, "botId");
        SkillCheckpoint removed = checkpointsByBot.remove(botId);
        if (removed != null) {
            setDirty();
        }
        return Optional.ofNullable(removed);
    }

    /** 仅关闭精确 generation，避免旧 body 清除新身体的检查点。 */
    public boolean closeGeneration(UUID botId, long generation) {
        requireWritable();
        CheckpointNbt.requireNonZeroUuid(botId, "botId");
        if (generation <= 0L) {
            throw new IllegalArgumentException("generation must be positive");
        }
        SkillCheckpoint existing = checkpointsByBot.get(botId);
        if (existing == null || existing.generation() != generation) {
            return false;
        }
        checkpointsByBot.remove(botId);
        setDirty();
        return true;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        requireWritable();
        UUID boundServerInstanceId = requireServerInstanceId();
        tag.putInt(SCHEMA_VERSION_TAG, SCHEMA_VERSION);
        CheckpointNbt.putUuid(tag, SERVER_INSTANCE_ID_TAG, boundServerInstanceId);
        List<SkillCheckpoint> sorted = checkpoints();
        ListTag checkpointsTag = new ListTag();
        for (SkillCheckpoint checkpoint : sorted) {
            checkpointsTag.add(checkpoint.save());
        }
        tag.put(CHECKPOINTS_TAG, checkpointsTag);
        CheckpointNbt.writeIntegrity(tag, INTEGRITY_TAG);
        return tag;
    }

    public static SkillCheckpointSavedData load(
            CompoundTag tag, HolderLookup.Provider provider) {
        try {
            return loadValid(tag);
        } catch (RuntimeException exception) {
            return damagedRepository(tag);
        }
    }

    private static SkillCheckpointSavedData loadValid(
            CompoundTag tag) {
        Objects.requireNonNull(tag, "tag");
        String subject = "skill checkpoint saved data";
        CheckpointNbt.requireExactKeys(
                tag,
                Set.of(
                        SCHEMA_VERSION_TAG,
                        SERVER_INSTANCE_ID_TAG,
                        CHECKPOINTS_TAG,
                        INTEGRITY_TAG),
                Set.of(),
                subject);
        CheckpointNbt.requireType(tag, SCHEMA_VERSION_TAG, Tag.TAG_INT, subject);
        if (tag.getInt(SCHEMA_VERSION_TAG) != SCHEMA_VERSION) {
            throw CheckpointNbt.invalid(subject, "schema version is unsupported", null);
        }
        CheckpointNbt.requireType(tag, CHECKPOINTS_TAG, Tag.TAG_LIST, subject);
        UUID serverInstanceId = CheckpointNbt.readUuid(
                tag, SERVER_INSTANCE_ID_TAG, subject);
        ListTag encoded = (ListTag) Objects.requireNonNull(
                tag.get(CHECKPOINTS_TAG), CHECKPOINTS_TAG);
        if (encoded.size() > MAX_CHECKPOINTS) {
            throw CheckpointNbt.invalid(subject, "checkpoint count exceeds its maximum", null);
        }
        Map<UUID, SkillCheckpoint> checkpoints = new LinkedHashMap<>();
        for (Tag value : encoded) {
            if (!(value instanceof CompoundTag checkpointTag)) {
                throw CheckpointNbt.invalid(subject, "contains a non-compound checkpoint", null);
            }
            SkillCheckpoint checkpoint = SkillCheckpoint.load(checkpointTag);
            if (!checkpoint.serverInstanceId().equals(serverInstanceId)) {
                throw CheckpointNbt.invalid(
                        subject, "checkpoint has another server instance", null);
            }
            if (checkpoints.putIfAbsent(checkpoint.botId(), checkpoint) != null) {
                throw CheckpointNbt.invalid(subject, "contains a duplicate bot id", null);
            }
        }
        // 子记录已完成字段与容量校验，此时摘要遍历的结构已经有硬上限。
        CheckpointNbt.verifyIntegrity(tag, INTEGRITY_TAG, subject);
        return new SkillCheckpointSavedData(serverInstanceId, checkpoints);
    }

    /**
     * 只允许完整校验成功的数据绑定 roster 身份。受损对象在这里保持无身份、非 dirty，
     * 因而 {@link #get(MinecraftServer, UUID)} 不会把它洗成当前世界的新空仓库。
     */
    void bindOrVerifyServerInstance(UUID expectedServerInstanceId) {
        CheckpointNbt.requireNonZeroUuid(
                expectedServerInstanceId, "expectedServerInstanceId");
        if (!isWritable()) {
            return;
        }
        if (serverInstanceId == null) {
            serverInstanceId = expectedServerInstanceId;
            setDirty();
            return;
        }
        if (!serverInstanceId.equals(expectedServerInstanceId)) {
            throw new IllegalStateException(
                    "checkpoint data belongs to a different server instance");
        }
    }

    private UUID requireServerInstanceId() {
        if (!isWritable()) {
            throw new IllegalStateException(
                    "checkpoint data is unavailable because load status is "
                            + loadStatus);
        }
        if (serverInstanceId == null) {
            throw new IllegalStateException(
                    "checkpoint data has not been bound to a server instance");
        }
        return serverInstanceId;
    }

    private static SkillCheckpointSavedData damagedRepository(CompoundTag tag) {
        if (hasUnsupportedSchemaVersion(tag)) {
            return new SkillCheckpointSavedData(
                    SkillCheckpointLoadStatus.UNSUPPORTED_SCHEMA,
                    UNSUPPORTED_SCHEMA_DIAGNOSTIC);
        }
        return new SkillCheckpointSavedData(
                SkillCheckpointLoadStatus.DAMAGED,
                DAMAGED_DIAGNOSTIC);
    }

    /**
     * 只有 SchemaVersion 本身是格式正确的 int 且与本实现不一致时，才判为版本不支持；
     * 缺字段、错类型或当前版本的任何校验失败都属于损坏。
     */
    private static boolean hasUnsupportedSchemaVersion(@Nullable CompoundTag tag) {
        if (tag == null) {
            return false;
        }
        try {
            return tag.contains(SCHEMA_VERSION_TAG, Tag.TAG_INT)
                    && tag.getInt(SCHEMA_VERSION_TAG) != SCHEMA_VERSION;
        } catch (RuntimeException ignored) {
            // 分类本身绝不能让 SavedData.Factory 重新抛出加载失败。
            return false;
        }
    }

    private boolean isWritable() {
        return loadStatus == SkillCheckpointLoadStatus.VALID;
    }

    private void requireWritable() {
        if (!isWritable()) {
            throw new IllegalStateException(
                    "checkpoint repository is read-only because load status is "
                            + loadStatus);
        }
    }
}
