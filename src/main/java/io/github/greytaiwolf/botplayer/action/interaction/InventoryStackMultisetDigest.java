package io.github.greytaiwolf.botplayer.action.interaction;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * 为有界玩家背包生成与槽位顺序无关的完整堆叠摘要。
 *
 * <p>该纯 Java 边界不引用 Minecraft 运行时，使守恒算法可以在普通 JUnit
 * classpath 中直接验收。
 */
public final class InventoryStackMultisetDigest {
    private InventoryStackMultisetDigest() {
    }

    public static String sha256(
            List<ItemStackFingerprint> fingerprints) {
        Objects.requireNonNull(fingerprints, "fingerprints");
        List<ItemStackFingerprint> canonical =
                new ArrayList<>(fingerprints.size());
        for (ItemStackFingerprint fingerprint : fingerprints) {
            canonical.add(Objects.requireNonNull(
                    fingerprint, "inventory fingerprint"));
        }
        canonical.sort(Comparator
                .comparing((ItemStackFingerprint fingerprint) ->
                        fingerprint.itemId()
                                .map(ResourceId::value)
                                .orElse(""))
                .thenComparingInt(ItemStackFingerprint::count)
                .thenComparingInt(ItemStackFingerprint::damage)
                .thenComparing(fingerprint ->
                        fingerprint.componentsDigest().orElse("")));

        MessageDigest digest = newDigest();
        updateInt(digest, canonical.size());
        for (ItemStackFingerprint fingerprint : canonical) {
            updateString(
                    digest,
                    fingerprint.itemId()
                            .map(ResourceId::value)
                            .orElse(""));
            updateInt(digest, fingerprint.count());
            updateInt(digest, fingerprint.damage());
            updateString(
                    digest,
                    fingerprint.componentsDigest().orElse(""));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void updateString(
            MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        updateInt(digest, bytes.length);
        digest.update(bytes);
    }

    private static void updateInt(
            MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable", exception);
        }
    }
}
