package io.github.greytaiwolf.botplayer.skill.reservation;

import java.util.Objects;

/**
 * 资源预留的纯数据键；不得保存方块、实体、容器等 Minecraft 活对象。
 */
public record ReservationKey(Kind kind, String scope, String subject) {
    public static final int MAX_SCOPE_LENGTH = 128;
    public static final int MAX_SUBJECT_LENGTH = 256;

    public ReservationKey {
        Objects.requireNonNull(kind, "kind");
        scope = requireText(scope, "scope", MAX_SCOPE_LENGTH);
        subject = requireText(
                subject, "subject", MAX_SUBJECT_LENGTH);
    }

    private static String requireText(
            String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(
                    name + " must contain 1-" + maximumLength
                            + " characters");
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                throw new IllegalArgumentException(
                        name + " must not contain control characters");
            }
        }
        return value;
    }

    public enum Kind {
        BLOCK,
        ENTITY,
        CONTAINER,
        ITEM_STACK,
        WORK_AREA
    }
}
