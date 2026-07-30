package io.github.greytaiwolf.botplayer.action;

import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Encodes a resource id into a deterministic, bounded action-evidence value.
 */
public final class ResourceIdEvidence {
    public static final String SHA_256_PREFIX = "sha256#";

    private ResourceIdEvidence() {}

    public static String encode(ResourceId resourceId) {
        String value =
                Objects.requireNonNull(resourceId, "resourceId").value();
        if (value.length() <= ActionEvidence.MAX_VALUE_LENGTH) {
            return value;
        }
        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");
            return SHA_256_PREFIX
                    + HexFormat.of().formatHex(digest.digest(
                            value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable", exception);
        }
    }
}
