package io.github.greytaiwolf.botplayer.technique.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ShieldHoldSubmissionTest {
    @Test
    void onlyAcceptedIngressCarriesTheTechniqueRunIdentity() {
        UUID runId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        ShieldHoldSubmission accepted = ShieldHoldSubmission.accepted(runId,
                "Shield hold accepted");
        ShieldHoldSubmission rejected = ShieldHoldSubmission.rejected(
                ShieldHoldSubmission.Status.NO_OFFHAND_SHIELD,
                "Off-hand does not contain a shield");

        assertTrue(accepted.accepted());
        assertEquals(Optional.of(runId), accepted.techniqueRunId());
        assertFalse(rejected.accepted());
        assertTrue(rejected.techniqueRunId().isEmpty());
    }

    @Test
    void rejectsIdentityStatusMismatchAndUnsafeSummary() {
        assertThrows(IllegalArgumentException.class,
                () -> new ShieldHoldSubmission(ShieldHoldSubmission.Status.ACCEPTED,
                        Optional.empty(), "missing run"));
        assertThrows(IllegalArgumentException.class,
                () -> ShieldHoldSubmission.rejected(
                        ShieldHoldSubmission.Status.ACCEPTED,
                        "wrong factory"));
        assertThrows(IllegalArgumentException.class,
                () -> ShieldHoldSubmission.rejected(
                        ShieldHoldSubmission.Status.BUSY,
                        " surrounding whitespace "));
        assertThrows(IllegalArgumentException.class,
                () -> ShieldHoldSubmission.rejected(
                        ShieldHoldSubmission.Status.BUSY,
                        "bad\u0000summary"));
        assertThrows(IllegalArgumentException.class,
                () -> ShieldHoldSubmission.rejected(
                        ShieldHoldSubmission.Status.BUSY,
                        "bad\uD800summary"));
    }
}
