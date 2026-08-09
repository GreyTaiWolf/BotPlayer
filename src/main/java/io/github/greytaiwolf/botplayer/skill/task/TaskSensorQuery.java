package io.github.greytaiwolf.botplayer.skill.task;

import java.util.Objects;

/**
 * 不含 Minecraft 活对象的任务私有查询请求，可作为缓存键安全比较。
 */
public record TaskSensorQuery(
        TaskSensorRunIdentity identity,
        TaskSensorQueryType type,
        TaskSensorScope scope,
        TaskSensorBudget budget) {
    public TaskSensorQuery {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(budget, "budget");
        if (!budget.supports(type)) {
            throw new IllegalArgumentException(
                    "budget requests work not allowed for query type");
        }
    }
}
