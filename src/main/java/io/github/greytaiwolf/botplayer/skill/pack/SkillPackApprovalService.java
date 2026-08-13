package io.github.greytaiwolf.botplayer.skill.pack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 纯内存的审核状态机。任何内容摘要或版本变化都会替换旧记录并撤销原批准。
 */
public final class SkillPackApprovalService {
    private final SkillPackValidator validator;
    private final int maximumTrackedPacks;
    private final Map<SkillPackId, SkillPackRecord> records =
            new LinkedHashMap<>();

    public SkillPackApprovalService(
            SkillPackValidator validator, int maximumTrackedPacks) {
        this.validator = Objects.requireNonNull(validator, "validator");
        if (maximumTrackedPacks < 1
                || maximumTrackedPacks
                        > SkillPackLimits.MAX_TRACKED_PACKS) {
            throw new IllegalArgumentException(
                    "maximumTrackedPacks must be between 1 and "
                            + SkillPackLimits.MAX_TRACKED_PACKS);
        }
        this.maximumTrackedPacks = maximumTrackedPacks;
    }

    public synchronized SkillPackTransition stage(
            SkillPackCandidate candidate, long currentTick) {
        Objects.requireNonNull(candidate, "candidate");
        requireTick(currentTick);
        SkillPackRecord existing = records.get(candidate.definition().id());
        if (existing != null && existing.revision().equals(
                candidate.revision())) {
            if (!sameParsedContent(existing.candidate(), candidate)) {
                return new SkillPackTransition(
                        SkillPackTransition.Status.REVISION_CONTENT_CONFLICT,
                        Optional.of(existing));
            }
            return new SkillPackTransition(
                    switch (existing.state()) {
                        case STAGED -> SkillPackTransition.Status.ALREADY_STAGED;
                        case APPROVED -> SkillPackTransition.Status.ALREADY_APPROVED;
                        case REJECTED -> SkillPackTransition.Status.ALREADY_REJECTED;
                    },
                    Optional.of(existing));
        }
        if (existing == null && records.size() >= maximumTrackedPacks) {
            return new SkillPackTransition(
                    SkillPackTransition.Status.CAPACITY_EXCEEDED,
                    Optional.empty());
        }
        SkillPackValidation validation = validator.validate(candidate);
        SkillPackRecord record = new SkillPackRecord(
                candidate,
                validation,
                validation.valid()
                        ? SkillPackState.STAGED
                        : SkillPackState.REJECTED,
                currentTick,
                Optional.empty());
        records.put(candidate.definition().id(), record);
        return new SkillPackTransition(
                validation.valid()
                        ? SkillPackTransition.Status.STAGED
                        : SkillPackTransition.Status.REJECTED_BY_VALIDATION,
                Optional.of(record));
    }

    public synchronized SkillPackTransition approve(
            SkillPackRevision revision,
            UUID administratorId,
            long currentTick) {
        Objects.requireNonNull(revision, "revision");
        requireAdministrator(administratorId);
        requireTick(currentTick);
        SkillPackRecord current = records.get(revision.id());
        if (current == null) {
            return new SkillPackTransition(
                    SkillPackTransition.Status.NOT_FOUND, Optional.empty());
        }
        if (!current.revision().equals(revision)) {
            return new SkillPackTransition(
                    SkillPackTransition.Status.REVISION_MISMATCH,
                    Optional.of(current));
        }
        if (current.state() == SkillPackState.APPROVED) {
            return new SkillPackTransition(
                    SkillPackTransition.Status.ALREADY_APPROVED,
                    Optional.of(current));
        }
        if (current.state() != SkillPackState.STAGED) {
            return new SkillPackTransition(
                    SkillPackTransition.Status.NOT_STAGED,
                    Optional.of(current));
        }
        SkillPackRecord approved = new SkillPackRecord(
                current.candidate(),
                current.validation(),
                SkillPackState.APPROVED,
                currentTick,
                Optional.of(administratorId));
        records.put(revision.id(), approved);
        return new SkillPackTransition(
                SkillPackTransition.Status.APPROVED,
                Optional.of(approved));
    }

    public synchronized SkillPackTransition reject(
            SkillPackRevision revision,
            UUID administratorId,
            long currentTick) {
        Objects.requireNonNull(revision, "revision");
        requireAdministrator(administratorId);
        requireTick(currentTick);
        SkillPackRecord current = records.get(revision.id());
        if (current == null) {
            return new SkillPackTransition(
                    SkillPackTransition.Status.NOT_FOUND, Optional.empty());
        }
        if (!current.revision().equals(revision)) {
            return new SkillPackTransition(
                    SkillPackTransition.Status.REVISION_MISMATCH,
                    Optional.of(current));
        }
        SkillPackRecord rejected = new SkillPackRecord(
                current.candidate(),
                current.validation(),
                SkillPackState.REJECTED,
                currentTick,
                Optional.of(administratorId));
        records.put(revision.id(), rejected);
        return new SkillPackTransition(
                SkillPackTransition.Status.REJECTED,
                Optional.of(rejected));
    }

    /**
     * 仅由持久审批账本在本轮重载已经重新解析、重新静态校验后的精确 revision 上调用。
     * 此入口不读取文件、不接受正文，也绝不能把不同 hash/version 的草案升为已批准。
     */
    public synchronized SkillPackTransition restoreApproved(
            SkillPackRevision revision,
            UUID administratorId,
            long currentTick) {
        Objects.requireNonNull(revision, "revision");
        requireAdministrator(administratorId);
        requireTick(currentTick);
        SkillPackRecord current = records.get(revision.id());
        if (current == null) {
            return new SkillPackTransition(
                    SkillPackTransition.Status.NOT_FOUND, Optional.empty());
        }
        if (!current.revision().equals(revision)) {
            return new SkillPackTransition(
                    SkillPackTransition.Status.REVISION_MISMATCH,
                    Optional.of(current));
        }
        if (current.state() == SkillPackState.APPROVED) {
            return new SkillPackTransition(
                    SkillPackTransition.Status.ALREADY_APPROVED,
                    Optional.of(current));
        }
        if (current.state() != SkillPackState.STAGED) {
            return new SkillPackTransition(
                    SkillPackTransition.Status.NOT_STAGED,
                    Optional.of(current));
        }
        SkillPackRecord restored = new SkillPackRecord(
                current.candidate(),
                current.validation(),
                SkillPackState.APPROVED,
                currentTick,
                Optional.of(administratorId));
        records.put(revision.id(), restored);
        return new SkillPackTransition(
                SkillPackTransition.Status.APPROVED,
                Optional.of(restored));
    }

    public synchronized Optional<SkillPackRecord> find(
            SkillPackId id) {
        return Optional.ofNullable(records.get(
                Objects.requireNonNull(id, "id")));
    }

    /**
     * 每次完整文件重载后丢弃不再被受限目录观察到的运行时正文与审批状态。持久账本会保留
     * 历史 exact revision，但没有重新发现、重新解析的正文就绝不能执行或恢复批准。
     */
    synchronized void retainOnly(Set<SkillPackId> observedIds) {
        Objects.requireNonNull(observedIds, "observedIds");
        Set<SkillPackId> exactIds = Set.copyOf(observedIds);
        records.keySet().removeIf(id -> !exactIds.contains(id));
    }

    /**
     * 发现到文件路径但本轮正文无法严格解析/校验时，必须丢弃这个 ID 的历史正文和审批。
     * 否则一次损坏 JSON 会让上一轮已批准内容在新进程中继续可执行。
     */
    synchronized boolean discard(SkillPackId id) {
        return records.remove(Objects.requireNonNull(id, "id")) != null;
    }

    /**
     * 返回稳定排序的只读审核快照；不会泄露内部可变 Map，也不会触发重新解析或状态迁移。
     */
    public synchronized List<SkillPackRecord> records() {
        List<SkillPackRecord> snapshot = new ArrayList<>(
                records.values());
        snapshot.sort(Comparator.comparing(SkillPackRecord::revision));
        return List.copyOf(snapshot);
    }

    public synchronized Optional<SkillPackRecord> approved(
            SkillPackRevision revision) {
        Objects.requireNonNull(revision, "revision");
        SkillPackRecord current = records.get(revision.id());
        return current != null
                && current.state() == SkillPackState.APPROVED
                && current.revision().equals(revision)
                        ? Optional.of(current)
                        : Optional.empty();
    }

    private static void requireTick(long currentTick) {
        if (currentTick < 0L) {
            throw new IllegalArgumentException(
                    "currentTick must be non-negative");
        }
    }

    private static boolean sameParsedContent(
            SkillPackCandidate first, SkillPackCandidate second) {
        return first.definition().equals(second.definition())
                && first.source().relativePath().equals(
                        second.source().relativePath());
    }

    private static void requireAdministrator(UUID administratorId) {
        Objects.requireNonNull(administratorId, "administratorId");
        if (administratorId.getMostSignificantBits() == 0L
                && administratorId.getLeastSignificantBits() == 0L) {
            throw new IllegalArgumentException(
                    "administratorId must not be zero UUID");
        }
    }
}
