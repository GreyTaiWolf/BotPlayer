package io.github.greytaiwolf.botplayer.action;

import java.util.Objects;
import java.util.UUID;

public record ControllerOrigin(
        ControllerKind kind, UUID controllerRunId) {
    private static final UUID ZERO_UUID = new UUID(0L, 0L);

    public ControllerOrigin {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(controllerRunId, "controllerRunId");
        if (ZERO_UUID.equals(controllerRunId)) {
            throw new IllegalArgumentException(
                    "controllerRunId must not be the zero UUID");
        }
    }
}
