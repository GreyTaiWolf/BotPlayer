package io.github.greytaiwolf.botplayer.worldmodel;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 运行期内有界的分层 revision 追踪器。
 *
 * <p>全局值只用于权威侧审计与关联 scope 的同一变化纪元；它不会进入 bot 快照。
 * 事实失效使用维度与精确 scope，避免任意远处变化让所有事实抖动。
 */
public final class WorldRevisionTracker {
    public static final int MAX_SCOPE_CAPACITY = 65_536;

    private final int scopeCapacity;
    private final Map<String, Long> dimensionRevisions = new LinkedHashMap<>();
    private final LinkedHashMap<RevisionScope, Long> scopeRevisions =
            new LinkedHashMap<>(16, 0.75F, true);
    private long globalRevision;

    public WorldRevisionTracker(int scopeCapacity) {
        if (scopeCapacity < 1 || scopeCapacity > MAX_SCOPE_CAPACITY) {
            throw new IllegalArgumentException(
                    "scopeCapacity must be between 1 and "
                            + MAX_SCOPE_CAPACITY);
        }
        this.scopeCapacity = scopeCapacity;
    }

    public RevisionStamp advance(RevisionScope scope) {
        Objects.requireNonNull(scope, "scope");
        globalRevision = Math.incrementExact(globalRevision);
        long dimensionRevision = dimensionRevisions.merge(
                scope.dimension(), 1L, Math::addExact);
        /*
         * LRU 淘汰后不能从 1 重新开始，否则旧事实可能拥有更大的 target revision，
         * 后续真实变化将无法使其失效。首次出现以全局单调值作为新纪元基线。
         */
        Long retained = scopeRevisions.get(scope);
        long targetRevision = retained == null
                ? globalRevision
                : Math.incrementExact(retained);
        scopeRevisions.put(scope, targetRevision);
        trimScopes();
        return new RevisionStamp(
                globalRevision, dimensionRevision, targetRevision);
    }

    /**
     * 在同一次权威世界变化中推进关联 scope，不重复推进 global/dimension epoch。
     */
    public RevisionStamp advanceRelated(
            RevisionScope scope, RevisionStamp epoch) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(epoch, "epoch");
        long currentDimension = dimensionRevisions.getOrDefault(
                scope.dimension(), 0L);
        if (epoch.global() != globalRevision
                || epoch.dimension() != currentDimension) {
            throw new IllegalArgumentException(
                    "related scope must use the current world epoch");
        }
        Long retained = scopeRevisions.get(scope);
        long targetRevision = retained == null
                ? globalRevision
                : Math.incrementExact(retained);
        scopeRevisions.put(scope, targetRevision);
        trimScopes();
        return new RevisionStamp(
                globalRevision,
                currentDimension,
                targetRevision);
    }

    public RevisionStamp current(RevisionScope scope) {
        Objects.requireNonNull(scope, "scope");
        return new RevisionStamp(
                globalRevision,
                dimensionRevisions.getOrDefault(scope.dimension(), 0L),
                scopeRevisions.getOrDefault(scope, globalRevision));
    }

    public long globalRevision() {
        return globalRevision;
    }

    private void trimScopes() {
        while (scopeRevisions.size() > scopeCapacity) {
            RevisionScope eldest = scopeRevisions.keySet().iterator().next();
            scopeRevisions.remove(eldest);
        }
    }
}
