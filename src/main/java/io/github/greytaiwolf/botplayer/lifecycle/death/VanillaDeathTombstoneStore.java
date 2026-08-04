package io.github.greytaiwolf.botplayer.lifecycle.death;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.zip.CRC32;

/**
 * 原版死亡背包消费前的耐久 tombstone。
 *
 * <p>这张票据防止崩溃后从旧 playerdata 复活已经掉落的物品。它不能保证 marker
 * 发布后、掉落实体保存前发生崩溃时物品不丢失；该窗口选择防复制而不是凭空恢复。
 */
public final class VanillaDeathTombstoneStore {
    private static final int MAGIC = 0x42504457;
    private static final int VERSION = 2;
    private static final int MAX_MARKER_BYTES = 512;
    private static final String SUFFIX = ".death-v2";
    private final Path directory;

    public VanillaDeathTombstoneStore(Path directory) {
        this.directory = Objects.requireNonNull(
                        directory, "directory")
                .toAbsolutePath()
                .normalize();
    }

    public Path markerPath(UUID botId) {
        Objects.requireNonNull(botId, "botId");
        return directory.resolve(botId + SUFFIX);
    }

    public void arm(VanillaDeathTicket ticket)
            throws IOException {
        Objects.requireNonNull(ticket, "ticket");
        Optional<VanillaDeathTicket> existing =
                read(ticket.botId());
        if (existing.isPresent()) {
            if (existing.orElseThrow().equals(ticket)) {
                return;
            }
            throw new IOException(
                    "another vanilla-death tombstone is already armed");
        }

        Path parent = directory.getParent();
        if (parent == null) {
            throw new IOException(
                    "vanilla-death tombstone directory has no parent");
        }
        boolean directoryExisted = Files.isDirectory(
                directory, LinkOption.NOFOLLOW_LINKS);
        Files.createDirectories(directory);
        if (!directoryExisted) {
            /* Persist the newly created directory entry before publishing a marker. */
            forceDirectory(parent);
        }
        forceDirectory(directory);
        Path marker = markerPath(ticket.botId());
        Path temporary = marker.resolveSibling(
                marker.getFileName() + ".tmp");
        byte[] encoded = encode(ticket);
        try {
            try (FileChannel channel = FileChannel.open(
                    temporary,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(encoded);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            Files.move(
                    temporary,
                    marker,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            forceFile(marker);
            forceDirectory(directory);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public Optional<VanillaDeathTicket> read(UUID botId)
            throws IOException {
        Path marker = markerPath(botId);
        if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(
                marker, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(
                    "vanilla-death tombstone is not a regular file");
        }
        byte[] encoded = readBounded(marker);
        if (encoded.length == 0) {
            throw new IOException(
                    "vanilla-death tombstone has an invalid size");
        }
        VanillaDeathTicket ticket = decode(encoded);
        if (!ticket.botId().equals(botId)) {
            throw new IOException(
                    "vanilla-death tombstone identity mismatch");
        }
        return Optional.of(ticket);
    }

    private static byte[] readBounded(Path marker)
            throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(
                MAX_MARKER_BYTES + 1);
        try (FileChannel channel = FileChannel.open(
                marker,
                Set.of(
                        StandardOpenOption.READ,
                        LinkOption.NOFOLLOW_LINKS))) {
            while (buffer.hasRemaining()) {
                int read = channel.read(buffer);
                if (read < 0) {
                    break;
                }
            }
        }
        if (buffer.position() > MAX_MARKER_BYTES) {
            throw new IOException(
                    "vanilla-death tombstone is oversized");
        }
        return Arrays.copyOf(
                buffer.array(), buffer.position());
    }

    public void clear(
            UUID botId, UUID transactionId)
            throws IOException {
        Objects.requireNonNull(transactionId, "transactionId");
        Optional<VanillaDeathTicket> current = read(botId);
        if (current.isEmpty()) {
            if (!Files.isDirectory(
                    directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException(
                        "vanilla-death tombstone directory is missing");
            }
            forceDirectory(directory);
            return;
        }
        if (!current.orElseThrow()
                .transactionId()
                .equals(transactionId)) {
            throw new IOException(
                    "refusing to clear a different vanilla-death tombstone");
        }
        Files.delete(markerPath(botId));
        forceDirectory(directory);
    }

    private static byte[] encode(
            VanillaDeathTicket ticket) throws IOException {
        ByteArrayOutputStream payloadBytes =
                new ByteArrayOutputStream();
        try (DataOutputStream output =
                new DataOutputStream(payloadBytes)) {
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            writeUuid(output, ticket.transactionId());
            writeUuid(output, ticket.botId());
            output.writeLong(ticket.generation());
            output.writeLong(ticket.createdTick());
            output.writeBoolean(ticket.preserveExperience());
            output.writeInt(ticket.baseExperienceReward());
            output.writeInt(ticket.respawnExperience().level());
            output.writeInt(ticket.respawnExperience().total());
            output.writeInt(Float.floatToRawIntBits(
                    ticket.respawnExperience().progress()));
        }
        byte[] payload = payloadBytes.toByteArray();
        CRC32 checksum = new CRC32();
        checksum.update(payload);
        ByteArrayOutputStream encoded =
                new ByteArrayOutputStream(payload.length + Long.BYTES);
        encoded.write(payload);
        try (DataOutputStream output =
                new DataOutputStream(encoded)) {
            output.writeLong(checksum.getValue());
        }
        return encoded.toByteArray();
    }

    private static VanillaDeathTicket decode(byte[] encoded)
            throws IOException {
        if (encoded.length <= Long.BYTES
                || encoded.length > MAX_MARKER_BYTES) {
            throw new IOException(
                    "vanilla-death tombstone is truncated or oversized");
        }
        int payloadLength = encoded.length - Long.BYTES;
        CRC32 checksum = new CRC32();
        checksum.update(encoded, 0, payloadLength);
        try (DataInputStream checksumInput =
                        new DataInputStream(
                                new ByteArrayInputStream(
                                        encoded,
                                        payloadLength,
                                        Long.BYTES));
                DataInputStream input =
                        new DataInputStream(
                                new ByteArrayInputStream(
                                        encoded,
                                        0,
                                        payloadLength))) {
            long expectedChecksum = checksumInput.readLong();
            if (expectedChecksum != checksum.getValue()) {
                throw new IOException(
                        "vanilla-death tombstone checksum mismatch");
            }
            if (input.readInt() != MAGIC) {
                throw new IOException(
                        "vanilla-death tombstone magic mismatch");
            }
            int version = input.readInt();
            if (version != VERSION) {
                throw new IOException(
                        "unsupported vanilla-death tombstone version");
            }
            UUID transactionId = readUuid(input);
            UUID botId = readUuid(input);
            long generation = input.readLong();
            long createdTick = input.readLong();
            boolean preserveExperience = input.readBoolean();
            int baseExperienceReward = input.readInt();
            DeathExperienceSnapshot experience =
                    new DeathExperienceSnapshot(
                            input.readInt(),
                            input.readInt(),
                            Float.intBitsToFloat(
                                    input.readInt()));
            if (input.available() != 0) {
                throw new IOException(
                        "vanilla-death tombstone has trailing data");
            }
            return new VanillaDeathTicket(
                    transactionId,
                    botId,
                    generation,
                    createdTick,
                    preserveExperience,
                    baseExperienceReward,
                    experience);
        } catch (EOFException | IllegalArgumentException exception) {
            throw new IOException(
                    "invalid vanilla-death tombstone", exception);
        }
    }

    private static void writeUuid(
            DataOutputStream output, UUID value)
            throws IOException {
        output.writeLong(value.getMostSignificantBits());
        output.writeLong(value.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream input)
            throws IOException {
        return new UUID(input.readLong(), input.readLong());
    }

    private static void forceFile(Path file)
            throws IOException {
        try (FileChannel channel = FileChannel.open(
                file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static void forceDirectory(Path targetDirectory)
            throws IOException {
        if (!Files.isDirectory(
                targetDirectory,
                LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(
                    "durability directory is missing or unsafe");
        }
        try (FileChannel channel = FileChannel.open(
                targetDirectory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }
}
