package io.github.greytaiwolf.botplayer.skill.pack;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 文件发现、严格 JSON 解析和审核状态机的窄编排层。发现/重载只会 STAGE，永远不会
 * 自动批准或执行外部 pack。
 */
public final class SkillPackManager {
    private final SkillPackFileLoader loader;
    private final SkillPackJsonParser parser;
    private final SkillPackApprovalService approvals;
    private final Path worldRoot;
    private Set<SkillPackRevision> latestReloadedRevisions = Set.of();

    public SkillPackManager(
            SkillPackFileLoader loader,
            SkillPackJsonParser parser,
            SkillPackApprovalService approvals,
            Path worldRoot) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.approvals = Objects.requireNonNull(approvals, "approvals");
        this.worldRoot = Objects.requireNonNull(worldRoot, "worldRoot")
                .toAbsolutePath().normalize();
    }

    public ReloadResult reload(long currentTick) {
        List<SkillPackTransition> transitions = new ArrayList<>();
        List<String> rejectedPaths = new ArrayList<>();
        Set<SkillPackRevision> reloadedRevisions = new LinkedHashSet<>();
        List<SkillPackSource> sources = loader.load(worldRoot);
        Set<SkillPackId> observedIds = new LinkedHashSet<>();
        for (SkillPackSource source : sources) {
            try {
                observedIds.add(sourceId(source));
            } catch (IllegalStateException exception) {
                rejectedPaths.add(source.relativePath());
            }
        }
        // 先撤销已删除或已改坏路径的旧正文，避免失败 reload 继续运行历史审批。
        approvals.retainOnly(observedIds);
        for (SkillPackSource source : sources) {
            SkillPackId sourceId;
            try {
                sourceId = sourceId(source);
            } catch (IllegalStateException exception) {
                continue;
            }
            try {
                SkillPackCandidate candidate = parser.parse(source);
                if (!sourceId.equals(candidate.definition().id())) {
                    approvals.discard(sourceId);
                    rejectedPaths.add(source.relativePath());
                    continue;
                }
                observedIds.add(sourceId);
                SkillPackTransition transition = approvals.stage(
                        candidate, currentTick);
                transitions.add(transition);
                if (transition.status()
                        != SkillPackTransition.Status
                                .REVISION_CONTENT_CONFLICT) {
                    transition.record().ifPresent(record ->
                            reloadedRevisions.add(record.revision()));
                } else {
                    approvals.discard(sourceId);
                }
            } catch (IllegalArgumentException exception) {
                approvals.discard(sourceId);
                rejectedPaths.add(source.relativePath());
            }
        }
        // JSON 声明的 ID 也保留作有限诊断，但仍不可能绕过源路径匹配校验而获批。
        approvals.retainOnly(observedIds);
        latestReloadedRevisions = Set.copyOf(reloadedRevisions);
        return new ReloadResult(transitions, rejectedPaths);
    }

    public SkillPackTransition approve(
            SkillPackRevision revision,
            UUID administratorId,
            long currentTick) {
        return approvals.approve(revision, administratorId, currentTick);
    }

    public SkillPackTransition reject(
            SkillPackRevision revision,
            UUID administratorId,
            long currentTick) {
        return approvals.reject(revision, administratorId, currentTick);
    }

    /**
     * 只允许已在当前重载中严格解析、验证并暂存的完全相同 revision 恢复审批记录。
     */
    public SkillPackTransition restoreApproved(
            SkillPackRevision revision,
            UUID administratorId,
            long currentTick) {
        Objects.requireNonNull(revision, "revision");
        if (!latestReloadedRevisions.contains(revision)) {
            return new SkillPackTransition(
                    SkillPackTransition.Status.NOT_STAGED,
                    approvals.find(revision.id()));
        }
        return approvals.restoreApproved(
                revision, administratorId, currentTick);
    }

    public Optional<SkillPackRecord> approved(SkillPackRevision revision) {
        return approvals.approved(revision);
    }

    public Optional<SkillPackRecord> find(SkillPackId id) {
        return approvals.find(id);
    }

    /** 只读审核记录快照，按 pack revision 稳定排序。 */
    public List<SkillPackRecord> records() {
        return approvals.records();
    }

    private static SkillPackId sourceId(SkillPackSource source) {
        String relativePath = Objects.requireNonNull(source, "source")
                .relativePath();
        String[] parts = relativePath.split("/", -1);
        if (parts.length != 5
                || !"data".equals(parts[0])
                || !"botplayer".equals(parts[2])
                || !"skills".equals(parts[3])
                || !parts[4].endsWith(".json")) {
            throw new IllegalStateException(
                    "skill pack loader produced an unsafe relative path");
        }
        String path = parts[4].substring(
                0, parts[4].length() - ".json".length());
        return new SkillPackId(parts[1], path);
    }

    public record ReloadResult(
            List<SkillPackTransition> transitions,
            List<String> rejectedPaths) {
        public ReloadResult {
            transitions = List.copyOf(Objects.requireNonNull(
                    transitions, "transitions"));
            rejectedPaths = List.copyOf(Objects.requireNonNull(
                    rejectedPaths, "rejectedPaths"));
        }

        public int stagedOrKnownCount() {
            return transitions.size();
        }

        public int rejectedCount() {
            return rejectedPaths.size();
        }
    }
}
