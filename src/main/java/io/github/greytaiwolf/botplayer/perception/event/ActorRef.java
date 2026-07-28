package io.github.greytaiwolf.botplayer.perception.event;

import java.util.Objects;
import java.util.UUID;

/**
 * 事件中的不可变参与者引用。
 */
public record ActorRef(UUID actorId, String typeId, String displayName) {
    public static final int MAX_TYPE_LENGTH = 128;
    public static final int MAX_DISPLAY_NAME_LENGTH = 64;

    public ActorRef {
        Objects.requireNonNull(actorId, "actorId");
        typeId = requireText(typeId, "typeId", MAX_TYPE_LENGTH);
        displayName = requireText(
                displayName, "displayName", MAX_DISPLAY_NAME_LENGTH);
    }

    private static String requireText(String value, String field, int maximumLength) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(
                    field + " must contain 1-" + maximumLength + " characters");
        }
        return value;
    }
}
