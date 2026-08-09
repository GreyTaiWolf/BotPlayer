package io.github.greytaiwolf.botplayer.skill.builtin.defense;

import java.util.Objects;
import java.util.UUID;

/**
 * 状态机给未来动作适配层的单次、可关联请求。
 *
 * <p>适配层必须只执行本对象描述的一次动作，并将同一对象连同结果回传给
 * {@link LimitedSelfDefenseSession#acknowledge(DefenseActionReceipt)}。
 */
public record DefenseActionRequest(
        UUID runId,
        UUID targetId,
        long sequence,
        DefenseActionKind kind) {
    public DefenseActionRequest {
        DefenseTarget.requireNonZeroUuid(runId, "runId");
        DefenseTarget.requireNonZeroUuid(targetId, "targetId");
        if (sequence <= 0L) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        kind = Objects.requireNonNull(kind, "kind");
    }
}
