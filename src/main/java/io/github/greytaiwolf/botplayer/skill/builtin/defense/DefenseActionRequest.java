package io.github.greytaiwolf.botplayer.skill.builtin.defense;

import io.github.greytaiwolf.botplayer.safety.SafetyRetreat;
import java.util.Objects;
import java.util.Optional;
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
        DefenseActionKind kind,
        Optional<SafetyRetreat> safeRetreat) {
    public DefenseActionRequest {
        DefenseTarget.requireNonZeroUuid(runId, "runId");
        DefenseTarget.requireNonZeroUuid(targetId, "targetId");
        if (sequence <= 0L) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        kind = Objects.requireNonNull(kind, "kind");
        safeRetreat = Objects.requireNonNull(safeRetreat, "safeRetreat");
        if (kind == DefenseActionKind.MELEE_ATTACK
                && safeRetreat.isPresent()) {
            throw new IllegalArgumentException(
                    "melee action must not carry a retreat path");
        }
        if (kind == DefenseActionKind.RETREAT
                && safeRetreat.isEmpty()) {
            throw new IllegalArgumentException(
                    "retreat action requires a verified safety path");
        }
    }

    /** 旧的近战请求没有撤退输入，保持纯近战调用兼容。 */
    public DefenseActionRequest(
            UUID runId,
            UUID targetId,
            long sequence,
            DefenseActionKind kind) {
        this(runId, targetId, sequence, kind, Optional.empty());
    }
}
