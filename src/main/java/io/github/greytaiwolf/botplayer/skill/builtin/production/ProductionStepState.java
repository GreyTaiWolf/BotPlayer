package io.github.greytaiwolf.botplayer.skill.builtin.production;

/**
 * 单一步骤的纯状态机；执行器须按此边界保存和恢复，而不能从中间点击状态猜测成功。
 */
public enum ProductionStepState {
    PLANNED,
    PREFLIGHT_READY,
    DISPATCHED,
    VERIFYING,
    COMPLETED,
    REJECTED;

    public boolean canTransitionTo(ProductionStepState next) {
        return switch (this) {
            case PLANNED -> next == PREFLIGHT_READY || next == REJECTED;
            case PREFLIGHT_READY -> next == DISPATCHED || next == REJECTED;
            case DISPATCHED -> next == VERIFYING || next == REJECTED;
            case VERIFYING -> next == COMPLETED || next == REJECTED;
            case COMPLETED, REJECTED -> false;
        };
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == REJECTED;
    }
}
