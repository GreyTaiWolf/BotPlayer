package io.github.greytaiwolf.botplayer.technique.combat;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Narrow, redaction-safe result of the administrator-only shield-hold ingress. */
public record ShieldHoldSubmission(
        Status status,
        Optional<UUID> techniqueRunId,
        String safeSummary) {
    public ShieldHoldSubmission {
        status = Objects.requireNonNull(status, "status");
        techniqueRunId = Optional.ofNullable(Objects.requireNonNull(
                techniqueRunId, "techniqueRunId").orElse(null));
        safeSummary = requireSummary(safeSummary);
        if (status == Status.ACCEPTED && techniqueRunId.isEmpty()) {
            throw new IllegalArgumentException(
                    "accepted shield hold submission requires a technique run id");
        }
        if (status != Status.ACCEPTED && techniqueRunId.isPresent()) {
            throw new IllegalArgumentException(
                    "rejected shield hold submission cannot contain a technique run id");
        }
    }

    public static ShieldHoldSubmission accepted(UUID techniqueRunId,
            String safeSummary) {
        return new ShieldHoldSubmission(Status.ACCEPTED,
                Optional.of(Objects.requireNonNull(techniqueRunId,
                        "techniqueRunId")), safeSummary);
    }

    public static ShieldHoldSubmission rejected(Status status,
            String safeSummary) {
        if (Objects.requireNonNull(status, "status") == Status.ACCEPTED) {
            throw new IllegalArgumentException(
                    "use accepted() for an accepted shield hold submission");
        }
        return new ShieldHoldSubmission(status, Optional.empty(), safeSummary);
    }

    public boolean accepted() {
        return status == Status.ACCEPTED;
    }

    public enum Status {
        ACCEPTED,
        BOT_NOT_ACTIVE,
        BUSY,
        INVENTORY_UNSAFE,
        USING_ITEM,
        NO_OFFHAND_SHIELD,
        TECHNIQUE_REJECTED,
        INTERNAL_ERROR
    }

    private static String requireSummary(String value) {
        String required = Objects.requireNonNull(value, "safeSummary");
        if (required.isEmpty() || required.length() > 256
                || !required.equals(required.strip())
                || required.codePoints().anyMatch(Character::isISOControl)
                || required.codePoints().anyMatch(codePoint -> codePoint >= 0xD800
                        && codePoint <= 0xDFFF)) {
            throw new IllegalArgumentException(
                    "shield hold safe summary must be bounded plain text");
        }
        return required;
    }
}
