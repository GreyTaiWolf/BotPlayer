package io.github.greytaiwolf.botplayer.action;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable provenance for an Action issued by one exact Technique child.
 *
 * <p>This is deliberately an Action-layer scalar value rather than a reference
 * to a live Technique object.  It lets a lifecycle adapter prove that an
 * Action belongs to the server-issued child ticket which requested it, without
 * introducing an Action-to-Technique runtime dependency.
 */
public record TechniqueChildOrigin(
        UUID techniqueRunId,
        UUID techniqueChildTicketId,
        long techniqueChildRevision) {
    private static final UUID ZERO_UUID = new UUID(0L, 0L);

    public TechniqueChildOrigin {
        requireNonZero(techniqueRunId, "techniqueRunId");
        requireNonZero(techniqueChildTicketId,
                "techniqueChildTicketId");
        if (techniqueChildRevision < 1L) {
            throw new IllegalArgumentException(
                    "techniqueChildRevision must be positive");
        }
    }

    private static void requireNonZero(UUID value, String name) {
        if (ZERO_UUID.equals(Objects.requireNonNull(value, name))) {
            throw new IllegalArgumentException(name + " must not be the zero UUID");
        }
    }
}
