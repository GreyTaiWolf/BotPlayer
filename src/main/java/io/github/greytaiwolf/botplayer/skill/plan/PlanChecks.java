package io.github.greytaiwolf.botplayer.skill.plan;

import java.util.Objects;
import java.util.UUID;

final class PlanChecks {
    private static final UUID ZERO_UUID = new UUID(0L, 0L);

    private PlanChecks() {}

    static void requireNonZero(UUID value, String name) {
        Objects.requireNonNull(value, name);
        if (ZERO_UUID.equals(value)) {
            throw new IllegalArgumentException(
                    name + " must not be the zero UUID");
        }
    }
}
