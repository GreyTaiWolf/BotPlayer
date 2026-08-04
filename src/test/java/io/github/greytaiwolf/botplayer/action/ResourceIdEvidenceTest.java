package io.github.greytaiwolf.botplayer.action;

import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ResourceIdEvidenceTest {
    @Test
    void preservesAResourceIdAtTheEvidenceLimit() {
        ResourceId resourceId = resourceIdWithLength(160);

        String encoded = ResourceIdEvidence.encode(resourceId);

        Assertions.assertEquals(resourceId.value(), encoded);
        Assertions.assertDoesNotThrow(() -> {
            new ActionEvidence("item.before_id", encoded);
        });
    }

    @Test
    void hashesAResourceIdOneCharacterAboveTheEvidenceLimit() {
        ResourceId resourceId = resourceIdWithLength(161);

        String encoded = ResourceIdEvidence.encode(resourceId);

        Assertions.assertEquals(
                "sha256#cfb8f0ad64c943a212101372d902c14ec9e51cc67f480cc82ffc355ed6bf80f9",
                encoded);
        Assertions.assertEquals(
                ResourceIdEvidence.SHA_256_PREFIX.length()
                        + 64,
                encoded.length());
        Assertions.assertEquals(
                encoded, ResourceIdEvidence.encode(resourceId));
        Assertions.assertDoesNotThrow(() -> {
            new ActionEvidence("item.before_id", encoded);
        });
    }

    @Test
    void hashesTheMaximumLengthResourceIdDeterministically() {
        ResourceId resourceId = resourceIdWithLength(256);

        String encoded = ResourceIdEvidence.encode(resourceId);

        Assertions.assertEquals(
                "sha256#f46ba739616ea66d037237484372f17f60a7363055e6f3077d1e287b37c6b604",
                encoded);
        Assertions.assertEquals(
                ResourceIdEvidence.SHA_256_PREFIX.length()
                        + 64,
                encoded.length());
        Assertions.assertEquals(
                encoded, ResourceIdEvidence.encode(resourceId));
        Assertions.assertDoesNotThrow(() -> {
            new ActionEvidence("item.before_id", encoded);
        });
    }

    private static ResourceId resourceIdWithLength(int length) {
        int namespaceLength =
                Math.max(1, length - 1 - 191);
        int pathLength =
                length - namespaceLength - 1;
        return new ResourceId(
                "n".repeat(namespaceLength)
                        + ":"
                        + "p".repeat(pathLength));
    }
}
