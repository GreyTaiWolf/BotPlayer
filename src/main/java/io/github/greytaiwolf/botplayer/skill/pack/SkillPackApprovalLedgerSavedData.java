package io.github.greytaiwolf.botplayer.skill.pack;

import io.github.greytaiwolf.botplayer.skill.core.SkillVersion;
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
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

/**
 * P5A 外部包的耐久人工审批账本。它故意只保存精确 revision 和审核者，绝不持久化
 * JSON、计划正文、节点或任何可执行对象；重启后仍必须重新从受限目录读取并静态校验正文。
 */
public final class SkillPackApprovalLedgerSavedData extends SavedData {
    public static final int SCHEMA_VERSION = 1;
    public static final String DATA_NAME = "botplayer_skill_pack_approvals";
    public static final int MAX_APPROVALS = SkillPackLimits.MAX_TRACKED_PACKS;

    private static final String SCHEMA_VERSION_TAG = "SchemaVersion";
    private static final String SERVER_INSTANCE_ID_TAG = "ServerInstanceId";
    private static final String APPROVALS_TAG = "Approvals";
    private static final String ID_TAG = "Id";
    private static final String MAJOR_TAG = "Major";
    private static final String MINOR_TAG = "Minor";
    private static final String PATCH_TAG = "Patch";
    private static final String HASH_TAG = "Hash";
    private static final String REVIEWER_TAG = "Reviewer";
    private static final UUID ZERO_UUID = new UUID(0L, 0L);

    @Nullable
    private UUID serverInstanceId;
    private final Map<SkillPackRevision, UUID> reviewersByRevision;

    private SkillPackApprovalLedgerSavedData() {
        this.serverInstanceId = null;
        this.reviewersByRevision = new LinkedHashMap<>();
    }

    private SkillPackApprovalLedgerSavedData(
            UUID serverInstanceId,
            Map<SkillPackRevision, UUID> reviewersByRevision) {
        requireNonZeroUuid(serverInstanceId, "serverInstanceId");
        if (reviewersByRevision.size() > MAX_APPROVALS) {
            throw new IllegalArgumentException(
                    "approval count exceeds maximum " + MAX_APPROVALS);
        }
        this.serverInstanceId = serverInstanceId;
        this.reviewersByRevision = new LinkedHashMap<>();
        reviewersByRevision.forEach((revision, reviewerId) -> {
            requireRevision(revision);
            requireNonZeroUuid(reviewerId, "reviewerId");
            if (this.reviewersByRevision.putIfAbsent(
                    revision, reviewerId) != null) {
                throw new IllegalArgumentException(
                        "approval ledger contains a duplicate revision");
            }
        });
    }

    /** 为不依赖 Minecraft 数据存储的严格编解码测试创建已绑定账本。 */
    public static SkillPackApprovalLedgerSavedData create(
            UUID serverInstanceId) {
        SkillPackApprovalLedgerSavedData data =
                new SkillPackApprovalLedgerSavedData(
                        serverInstanceId, Map.of());
        data.setDirty();
        return data;
    }

    public static SavedData.Factory<SkillPackApprovalLedgerSavedData>
            factory() {
        return new SavedData.Factory<>(
                SkillPackApprovalLedgerSavedData::new,
                SkillPackApprovalLedgerSavedData::load);
    }

    /** 账本与 roster 的 server instance 绑定，避免把别的世界的审批静默复用。 */
    public static SkillPackApprovalLedgerSavedData get(
            MinecraftServer server, UUID serverInstanceId) {
        Objects.requireNonNull(server, "server");
        requireNonZeroUuid(serverInstanceId, "serverInstanceId");
        SkillPackApprovalLedgerSavedData data = server.overworld()
                .getDataStorage()
                .computeIfAbsent(factory(), DATA_NAME);
        data.bindOrVerifyServerInstance(serverInstanceId);
        return data;
    }

    public UUID serverInstanceId() {
        return requireServerInstanceId();
    }

    public Optional<UUID> reviewer(SkillPackRevision revision) {
        return Optional.ofNullable(reviewersByRevision.get(
                requireRevision(revision)));
    }

    /** 稳定排序的只读诊断快照；entry 中不包含任何 Skill Pack 正文。 */
    public List<Approval> approvals() {
        List<Approval> result = new ArrayList<>(
                reviewersByRevision.size());
        reviewersByRevision.forEach((revision, reviewerId) -> result.add(
                new Approval(revision, reviewerId)));
        result.sort(Comparator.comparing(Approval::revision));
        return List.copyOf(result);
    }

    /** 用于在变更运行时状态之前进行容量预检。 */
    public boolean canRecord(SkillPackRevision revision) {
        requireRevision(revision);
        return reviewersByRevision.containsKey(revision)
                || reviewersByRevision.size() < MAX_APPROVALS;
    }

    /** 记录精确 revision 的实际审核者；相同值是幂等的。 */
    public void approve(SkillPackRevision revision, UUID reviewerId) {
        requireRevision(revision);
        requireNonZeroUuid(reviewerId, "reviewerId");
        UUID previous = reviewersByRevision.get(revision);
        if (previous == null && reviewersByRevision.size() >= MAX_APPROVALS) {
            throw new IllegalStateException(
                    "approval ledger exceeds maximum " + MAX_APPROVALS);
        }
        if (reviewerId.equals(previous)) {
            return;
        }
        reviewersByRevision.put(revision, reviewerId);
        setDirty();
    }

    /** 只撤销精确 revision；不同 hash/version 的历史审批不会被误删。 */
    public boolean revoke(SkillPackRevision revision) {
        requireRevision(revision);
        if (reviewersByRevision.remove(revision) == null) {
            return false;
        }
        setDirty();
        return true;
    }

    /**
     * 仅保留本轮成功重新读取、重新静态校验的 revision。删除、改坏或换 hash 的文件不能
     * 永久占据有限审批账本，更不能在未来意外恢复历史审批。
     */
    public int retainOnly(Set<SkillPackRevision> liveRevisions) {
        Objects.requireNonNull(liveRevisions, "liveRevisions");
        Set<SkillPackRevision> exactLive = Set.copyOf(liveRevisions);
        int before = reviewersByRevision.size();
        reviewersByRevision.keySet().removeIf(revision ->
                !exactLive.contains(revision));
        int removed = before - reviewersByRevision.size();
        if (removed > 0) {
            setDirty();
        }
        return removed;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        tag.putInt(SCHEMA_VERSION_TAG, SCHEMA_VERSION);
        tag.put(SERVER_INSTANCE_ID_TAG, NbtUtils.createUUID(
                requireServerInstanceId()));
        ListTag encodedApprovals = new ListTag();
        for (Approval approval : approvals()) {
            encodedApprovals.add(saveApproval(approval));
        }
        tag.put(APPROVALS_TAG, encodedApprovals);
        return tag;
    }

    public static SkillPackApprovalLedgerSavedData load(
            CompoundTag tag, HolderLookup.Provider provider) {
        Objects.requireNonNull(tag, "tag");
        String subject = "skill pack approval ledger";
        requireExactKeys(
                tag,
                Set.of(
                        SCHEMA_VERSION_TAG,
                        SERVER_INSTANCE_ID_TAG,
                        APPROVALS_TAG),
                subject);
        requireType(tag, SCHEMA_VERSION_TAG, Tag.TAG_INT, subject);
        if (tag.getInt(SCHEMA_VERSION_TAG) != SCHEMA_VERSION) {
            throw invalid(subject, "schema version is unsupported", null);
        }
        UUID serverInstanceId = readUuid(
                tag, SERVER_INSTANCE_ID_TAG, subject);
        requireType(tag, APPROVALS_TAG, Tag.TAG_LIST, subject);
        Tag encodedTag = Objects.requireNonNull(
                tag.get(APPROVALS_TAG), APPROVALS_TAG);
        if (!(encodedTag instanceof ListTag encodedApprovals)) {
            throw invalid(subject, "approvals is not an NBT list", null);
        }
        if (encodedApprovals.size() > MAX_APPROVALS) {
            throw invalid(subject, "approval count exceeds its maximum", null);
        }
        Map<SkillPackRevision, UUID> reviewers = new LinkedHashMap<>();
        for (Tag encoded : encodedApprovals) {
            if (!(encoded instanceof CompoundTag approvalTag)) {
                throw invalid(subject,
                        "approvals contains a non-compound entry", null);
            }
            Approval approval = loadApproval(approvalTag, subject);
            if (reviewers.putIfAbsent(
                    approval.revision(), approval.reviewerId()) != null) {
                throw invalid(subject, "contains a duplicate revision", null);
            }
        }
        return new SkillPackApprovalLedgerSavedData(
                serverInstanceId, reviewers);
    }

    private void bindOrVerifyServerInstance(UUID expectedServerInstanceId) {
        if (serverInstanceId == null) {
            serverInstanceId = expectedServerInstanceId;
            setDirty();
            return;
        }
        if (!serverInstanceId.equals(expectedServerInstanceId)) {
            throw new IllegalStateException(
                    "skill pack approval ledger belongs to another server instance");
        }
    }

    private UUID requireServerInstanceId() {
        if (serverInstanceId == null) {
            throw new IllegalStateException(
                    "skill pack approval ledger has not been bound to a server instance");
        }
        return serverInstanceId;
    }

    private static CompoundTag saveApproval(Approval approval) {
        CompoundTag tag = new CompoundTag();
        SkillPackRevision revision = approval.revision();
        tag.putString(ID_TAG, revision.id().toString());
        tag.putInt(MAJOR_TAG, revision.version().major());
        tag.putInt(MINOR_TAG, revision.version().minor());
        tag.putInt(PATCH_TAG, revision.version().patch());
        tag.putString(HASH_TAG, revision.contentHash().value());
        tag.put(REVIEWER_TAG, NbtUtils.createUUID(approval.reviewerId()));
        return tag;
    }

    private static Approval loadApproval(
            CompoundTag tag, String subject) {
        requireExactKeys(
                tag,
                Set.of(
                        ID_TAG,
                        MAJOR_TAG,
                        MINOR_TAG,
                        PATCH_TAG,
                        HASH_TAG,
                        REVIEWER_TAG),
                subject + " entry");
        requireType(tag, ID_TAG, Tag.TAG_STRING, subject);
        requireType(tag, MAJOR_TAG, Tag.TAG_INT, subject);
        requireType(tag, MINOR_TAG, Tag.TAG_INT, subject);
        requireType(tag, PATCH_TAG, Tag.TAG_INT, subject);
        requireType(tag, HASH_TAG, Tag.TAG_STRING, subject);
        UUID reviewerId = readUuid(tag, REVIEWER_TAG, subject);
        try {
            return new Approval(
                    new SkillPackRevision(
                            SkillPackId.parse(tag.getString(ID_TAG)),
                            new SkillVersion(
                                    tag.getInt(MAJOR_TAG),
                                    tag.getInt(MINOR_TAG),
                                    tag.getInt(PATCH_TAG)),
                            new SkillPackHash(tag.getString(HASH_TAG))),
                    reviewerId);
        } catch (IllegalArgumentException exception) {
            throw invalid(subject, "entry contains an invalid revision", exception);
        }
    }

    private static UUID readUuid(
            CompoundTag tag, String key, String subject) {
        requireType(tag, key, Tag.TAG_INT_ARRAY, subject);
        try {
            UUID value = NbtUtils.loadUUID(
                    Objects.requireNonNull(tag.get(key), key));
            requireNonZeroUuid(value, key);
            return value;
        } catch (IllegalArgumentException exception) {
            throw invalid(subject, key + " is not a valid UUID", exception);
        }
    }

    private static void requireExactKeys(
            CompoundTag tag, Set<String> expected, String subject) {
        if (!expected.equals(tag.getAllKeys())) {
            throw invalid(subject,
                    "contains missing or unexpected fields", null);
        }
    }

    private static void requireType(
            CompoundTag tag, String key, byte type, String subject) {
        if (!tag.contains(key, type)) {
            throw invalid(subject,
                    key + " has a missing or invalid type", null);
        }
    }

    private static SkillPackRevision requireRevision(
            SkillPackRevision revision) {
        return Objects.requireNonNull(revision, "revision");
    }

    private static void requireNonZeroUuid(UUID value, String name) {
        Objects.requireNonNull(value, name);
        if (ZERO_UUID.equals(value)) {
            throw new IllegalArgumentException(
                    name + " must not be the zero UUID");
        }
    }

    private static IllegalStateException invalid(
            String subject, String message, @Nullable Throwable cause) {
        String fullMessage = "Invalid " + subject + ": " + message;
        return cause == null
                ? new IllegalStateException(fullMessage)
                : new IllegalStateException(fullMessage, cause);
    }

    /** 只读审批快照；不包含来源路径、JSON 或运行时计划对象。 */
    public record Approval(SkillPackRevision revision, UUID reviewerId) {
        public Approval {
            requireRevision(revision);
            requireNonZeroUuid(reviewerId, "reviewerId");
        }
    }
}
