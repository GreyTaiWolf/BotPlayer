package io.github.greytaiwolf.botplayer.skill.task;

import java.util.Objects;

/**
 * 不含 Minecraft 活对象的任务私有查询请求，可作为缓存键安全比较。
 */
public record TaskSensorQuery(
        TaskSensorRunIdentity identity,
        TaskSensorQueryType type,
        TaskSensorScope scope,
        TaskSensorBudget budget,
        TaskSensorResourceFilter resourceFilter) {
    /**
     * 保留既有调用方的无过滤资源候选语义。
     */
    public TaskSensorQuery(
            TaskSensorRunIdentity identity,
            TaskSensorQueryType type,
            TaskSensorScope scope,
            TaskSensorBudget budget) {
        this(identity, type, scope, budget,
                TaskSensorResourceFilter.UNFILTERED);
    }

    public TaskSensorQuery {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(resourceFilter, "resourceFilter");
        if (!budget.supports(type)) {
            throw new IllegalArgumentException(
                    "budget requests work not allowed for query type");
        }
        if (type != TaskSensorQueryType.RESOURCE_CANDIDATES
                && !resourceFilter.isUnfiltered()) {
            throw new IllegalArgumentException(
                    "resource filter is only allowed for resource candidates");
        }
    }
}
