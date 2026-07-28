package io.github.greytaiwolf.botplayer.perception;

import io.github.greytaiwolf.botplayer.perception.event.SpatialPoint;
import java.util.Map;
import java.util.Objects;

public record SelfObservation(
        SpatialPoint position,
        SpatialPoint velocity,
        float yaw,
        float pitch,
        float health,
        float maximumHealth,
        int food,
        float saturation,
        int air,
        int maximumAir,
        boolean onFire,
        boolean underWater,
        boolean onGround,
        boolean sprinting,
        boolean crouching,
        String pose,
        Map<String, String> effects) {
    public SelfObservation {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(velocity, "velocity");
        if (!Float.isFinite(yaw)
                || !Float.isFinite(pitch)
                || !Float.isFinite(health)
                || !Float.isFinite(maximumHealth)
                || !Float.isFinite(saturation)) {
            throw new IllegalArgumentException("self numeric values must be finite");
        }
        if (maximumHealth <= 0.0F || health < 0.0F || health > maximumHealth) {
            throw new IllegalArgumentException("health range is invalid");
        }
        if (food < 0 || food > 20 || saturation < 0.0F) {
            throw new IllegalArgumentException("food state is invalid");
        }
        if (maximumAir < 0 || air < -maximumAir || air > maximumAir) {
            throw new IllegalArgumentException("air range is invalid");
        }
        Objects.requireNonNull(pose, "pose");
        if (pose.isBlank() || pose.length() > 64) {
            throw new IllegalArgumentException("pose must contain 1-64 characters");
        }
        effects = Map.copyOf(Objects.requireNonNull(effects, "effects"));
        if (effects.size() > 64) {
            throw new IllegalArgumentException("effects exceeds maximum size 64");
        }
    }
}
