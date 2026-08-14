package io.github.greytaiwolf.botplayer.ai.plan;

import java.util.UUID;

/**
 * Opaque plan capability issued by one {@link AiSkillPlanPort} after P5 validation.
 *
 * <p>The contract deliberately does not expose a {@code SkillPlan}: P6 can request validation and
 * submission but cannot inspect, mutate or bypass P5's canonical plan representation. Port
 * implementations must reject a token they did not issue and recheck live binding facts at submit time.
 */
public interface AiValidatedSkillPlan {
    UUID validationId();

    AiPlanBinding binding();

    int sourceToolCallCount();

    int nodeCount();

    int edgeCount();
}
