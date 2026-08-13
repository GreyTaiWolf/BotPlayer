package io.github.greytaiwolf.botplayer.skill.builtin.defense;

import java.util.Objects;
import java.util.Optional;

/**
 * P5A 的单目标、有限近战自卫状态机。
 *
 * <p>本类不接触 Minecraft 实体、背包或动作队列。调用方在 {@link #next} 返回
 * 动作后才可通过受控动作层执行，并以同一请求回执。L0 安全层随时可调用
 * {@link #preemptBySafety()} 终止本会话；之后任何迟到回执均被忽略。
 */
public final class LimitedSelfDefenseSession {
    private final DefenseRequest request;
    private final DefensePolicy policy;
    private DefenseState state;
    private DefenseReason reason;
    private int attacksIssued;
    private int retreatsIssued;
    private long nextSequence = 1L;
    private DefenseActionRequest outstanding;

    public LimitedSelfDefenseSession(
            DefenseRequest request, DefensePolicy policy) {
        this.request = Objects.requireNonNull(request, "request");
        this.policy = Objects.requireNonNull(policy, "policy");
        if (request.target().targetClass().isExplicitHostile()) {
            state = DefenseState.READY;
            reason = DefenseReason.READY;
        } else {
            state = DefenseState.REJECTED;
            reason = rejectionReason(request.target().targetClass());
        }
    }

    /**
     * 从最新观测最多签发一个动作。
     *
     * <p>攻击、撤退的预算均在此处签发时消耗。动作仍在途时不会重新签发。
     */
    public DefenseDecision next(DefenseObservation observation) {
        Objects.requireNonNull(observation, "observation");
        if (state.terminal() || outstanding != null) {
            return decision();
        }
        if (!request.target().entityId().equals(observation.target().entityId())) {
            transitionTerminal(DefenseState.REJECTED,
                    DefenseReason.TARGET_CHANGED);
            return decision();
        }
        if (!observation.target().targetClass().isExplicitHostile()) {
            transitionTerminal(DefenseState.REJECTED,
                    rejectionReason(observation.target().targetClass()));
            return decision();
        }
        if (!observation.target().alive()) {
            transitionTerminal(DefenseState.COMPLETED,
                    DefenseReason.TARGET_ELIMINATED);
            return decision();
        }
        if (observation.healthFraction() <= policy.retreatHealthFraction()) {
            return issueRetreat(DefenseReason.RETREAT_LOW_HEALTH);
        }
        if (observation.target().distanceSquared()
                > policy.maximumMeleeDistanceSquared()) {
            return issueRetreat(DefenseReason.RETREAT_OUT_OF_MELEE_RANGE);
        }
        if (attacksIssued >= policy.maximumAttackAttempts()) {
            return issueRetreat(
                    DefenseReason.RETREAT_ATTACK_BUDGET_EXHAUSTED);
        }
        attacksIssued++;
        outstanding = newAction(DefenseActionKind.MELEE_ATTACK);
        state = DefenseState.ATTACK_IN_FLIGHT;
        reason = DefenseReason.ATTACK_ISSUED;
        return decision();
    }

    /**
     * 接收动作层结果。仅当前 outstanding 请求可改变状态，旧回执不能复活会话。
     */
    public DefenseReceiptStatus acknowledge(DefenseActionReceipt receipt) {
        return acknowledge(receipt, false);
    }

    /**
     * 接收动作层结果，并可携带由精确攻击合同证明的目标已移除事实。
     *
     * <p>这个额外事实只能用于一份成功的当前近战回执。调用方不得把普通“实体暂时不可
     * 观察”升级为消灭；那种情况仍应在下一次观察中保守失败。
     */
    public DefenseReceiptStatus acknowledge(
            DefenseActionReceipt receipt, boolean targetEliminated) {
        Objects.requireNonNull(receipt, "receipt");
        if (outstanding == null || !outstanding.equals(receipt.request())) {
            return DefenseReceiptStatus.STALE_IGNORED;
        }
        DefenseActionKind kind = outstanding.kind();
        if (targetEliminated
                && (kind != DefenseActionKind.MELEE_ATTACK
                        || receipt.outcome() != DefenseActionOutcome.SUCCEEDED)) {
            throw new IllegalArgumentException(
                    "only a successful melee receipt may confirm target elimination");
        }
        outstanding = null;
        if (kind == DefenseActionKind.MELEE_ATTACK) {
            if (targetEliminated) {
                transitionTerminal(DefenseState.COMPLETED,
                        DefenseReason.TARGET_ELIMINATED);
                return DefenseReceiptStatus.ACCEPTED;
            }
            state = DefenseState.READY;
            reason = receipt.outcome() == DefenseActionOutcome.SUCCEEDED
                    ? DefenseReason.ATTACK_COMPLETED
                    : DefenseReason.ATTACK_FAILED;
            return DefenseReceiptStatus.ACCEPTED;
        }
        if (receipt.outcome() == DefenseActionOutcome.SUCCEEDED) {
            transitionTerminal(DefenseState.COMPLETED,
                    DefenseReason.RETREAT_COMPLETED);
        } else {
            state = DefenseState.READY;
            reason = DefenseReason.RETREAT_FAILED;
        }
        return DefenseReceiptStatus.ACCEPTED;
    }

    /**
     * 允许 L0 安全层无条件抢占；该转移不可恢复。
     *
     * @return 本次是否真正完成了抢占
     */
    public boolean preemptBySafety() {
        if (state.terminal()) {
            return false;
        }
        outstanding = null;
        transitionTerminal(DefenseState.PREEMPTED,
                DefenseReason.L0_SAFETY_PREEMPTED);
        return true;
    }

    public DefenseDecision decision() {
        return new DefenseDecision(
                state,
                Optional.ofNullable(outstanding),
                reason,
                policy.maximumAttackAttempts() - attacksIssued,
                policy.maximumRetreatAttempts() - retreatsIssued);
    }

    private DefenseDecision issueRetreat(DefenseReason retreatReason) {
        if (retreatsIssued >= policy.maximumRetreatAttempts()) {
            transitionTerminal(DefenseState.EXHAUSTED,
                    DefenseReason.RETREAT_BUDGET_EXHAUSTED);
            return decision();
        }
        retreatsIssued++;
        outstanding = newAction(DefenseActionKind.RETREAT);
        state = DefenseState.RETREAT_IN_FLIGHT;
        reason = retreatReason;
        return decision();
    }

    private DefenseActionRequest newAction(DefenseActionKind kind) {
        long sequence = nextSequence;
        try {
            nextSequence = Math.incrementExact(nextSequence);
        } catch (ArithmeticException exception) {
            throw new IllegalStateException(
                    "limited self-defense action sequence overflow",
                    exception);
        }
        return new DefenseActionRequest(
                request.runId(), request.target().entityId(), sequence, kind);
    }

    private void transitionTerminal(
            DefenseState terminalState, DefenseReason terminalReason) {
        if (!terminalState.terminal()) {
            throw new IllegalArgumentException("terminalState must be terminal");
        }
        outstanding = null;
        state = terminalState;
        reason = terminalReason;
    }

    private static DefenseReason rejectionReason(
            DefenseTargetClass targetClass) {
        return switch (targetClass) {
            case PLAYER -> DefenseReason.PVP_TARGET_REJECTED;
            case FRIENDLY -> DefenseReason.FRIENDLY_TARGET_REJECTED;
            case EXPLICIT_HOSTILE, NEUTRAL, UNKNOWN ->
                    DefenseReason.TARGET_NOT_EXPLICIT_HOSTILE;
        };
    }
}
