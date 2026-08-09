package io.github.greytaiwolf.botplayer.skill.builtin.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ProductionStepLifecycleTest {
    @Test
    void lifecycleRequiresFreshPreflightBeforeRetry() {
        ProductionStepLifecycle rejected = ProductionStepLifecycle.planned("smelt_iron")
                .transition(ProductionStepState.PREFLIGHT_READY)
                .transition(ProductionStepState.DISPATCHED)
                .transition(ProductionStepState.REJECTED);

        ProductionStepLifecycle retry = rejected.retry();

        assertEquals(ProductionStepState.PLANNED, retry.state());
        assertEquals(1, retry.attempt());
        assertThrows(IllegalStateException.class, () -> retry.transition(
                ProductionStepState.COMPLETED));
    }
}
