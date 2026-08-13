package io.github.greytaiwolf.botplayer.skill.checkpoint;

import java.util.Objects;
import java.util.Optional;

/** 当前服务器是否仍能精确找到已审核计划及其全部 descriptor。 */
public record SkillCheckpointRecoveryAvailability(
        Optional<SkillCheckpointPlan> registeredPlan,
        boolean allDescriptorsAvailable) {
    public SkillCheckpointRecoveryAvailability {
        registeredPlan = Objects.requireNonNull(
                registeredPlan, "registeredPlan");
    }

    public static SkillCheckpointRecoveryAvailability unavailable() {
        return new SkillCheckpointRecoveryAvailability(Optional.empty(), false);
    }

    public boolean matches(SkillCheckpointPlan checkpointPlan) {
        return registeredPlan.isPresent()
                && registeredPlan.orElseThrow().equals(checkpointPlan);
    }
}
