package io.github.greytaiwolf.botplayer.skill.task;

import java.util.Optional;
import java.util.UUID;

/**
 * 由运行时提供当前权威 run 身份；空值和任何不相等值一律拒绝查询。
 */
@FunctionalInterface
public interface TaskSensorAuthority {
    Optional<TaskSensorRunIdentity> current(
            UUID botId, UUID skillRunId);
}
