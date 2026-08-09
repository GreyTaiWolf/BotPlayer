package io.github.greytaiwolf.botplayer.skill.builtin.defense;

import java.util.Objects;
import java.util.UUID;

/**
 * 提交有限自卫状态机的不可变请求。
 *
 * <p>请求刻意只携带一个目标，接口层无法表达多目标攻击、仇恨列表或范围攻击。
 */
public record DefenseRequest(UUID runId, DefenseTarget target) {
    public DefenseRequest {
        DefenseTarget.requireNonZeroUuid(runId, "runId");
        target = Objects.requireNonNull(target, "target");
    }
}
