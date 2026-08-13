package io.github.greytaiwolf.botplayer.skill.builtin.production;

import java.util.Objects;

/**
 * 可持久化的不可变步骤状态；重试必须重新从 PLANNED 做观察，不能复用旧菜单证据。
 */
public record ProductionStepLifecycle(
        String nodeId, ProductionStepState state, int attempt) {
    public static final int MAX_ATTEMPTS = 4;

    public ProductionStepLifecycle {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(state, "state");
        if (attempt < 0 || attempt > MAX_ATTEMPTS) {
            throw new IllegalArgumentException(
                    "production step attempt is outside the bounded range");
        }
    }

    public static ProductionStepLifecycle planned(String nodeId) {
        return new ProductionStepLifecycle(nodeId, ProductionStepState.PLANNED, 0);
    }

    public ProductionStepLifecycle transition(ProductionStepState next) {
        Objects.requireNonNull(next, "next");
        if (!state.canTransitionTo(next)) {
            throw new IllegalStateException(
                    "invalid production step state transition");
        }
        return new ProductionStepLifecycle(nodeId, next, attempt);
    }

    public ProductionStepLifecycle retry() {
        if (state != ProductionStepState.REJECTED
                || attempt >= MAX_ATTEMPTS) {
            throw new IllegalStateException(
                    "production step cannot be retried");
        }
        return new ProductionStepLifecycle(nodeId, ProductionStepState.PLANNED,
                attempt + 1);
    }
}
