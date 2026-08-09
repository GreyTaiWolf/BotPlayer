package io.github.greytaiwolf.botplayer.lifecycle.death;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Set;

/** {@link DurableFileOps} 的标准 NIO 实现。 */
final class NioDurableFileOps implements DurableFileOps {
    static final NioDurableFileOps INSTANCE =
            new NioDurableFileOps();

    private NioDurableFileOps() {}

    @Override
    public boolean existsNoFollow(Path path) throws IOException {
        try {
            Files.readAttributes(
                    path,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            return true;
        } catch (NoSuchFileException exception) {
            return false;
        }
    }

    @Override
    public boolean isRegularFileNoFollow(Path path) {
        return Files.isRegularFile(
                path, LinkOption.NOFOLLOW_LINKS);
    }

    @Override
    public boolean isDirectoryNoFollow(Path path) {
        return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
    }

    @Override
    public void createDirectories(Path directory) throws IOException {
        Files.createDirectories(directory);
    }

    @Override
    public void writeFullyAndForce(Path file, byte[] bytes)
            throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        try (FileChannel channel = FileChannel.open(
                file,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    @Override
    public void moveAtomicallyReplace(Path source, Path target)
            throws IOException {
        Files.move(
                source,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public void delete(Path path) throws IOException {
        Files.delete(path);
    }

    @Override
    public void deleteIfExists(Path path) throws IOException {
        Files.deleteIfExists(path);
    }

    @Override
    public void forceFile(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(
                file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    @Override
    public void forceDirectory(Path directory) throws IOException {
        if (!isDirectoryNoFollow(directory)) {
            throw new IOException(
                    "durability directory is missing or unsafe");
        }
        try (FileChannel channel = FileChannel.open(
                directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    @Override
    public byte[] readBoundedNoFollow(Path file, int maximumBytes)
            throws IOException {
        if (maximumBytes < 1) {
            throw new IllegalArgumentException(
                    "maximumBytes must be positive");
        }
        ByteBuffer buffer = ByteBuffer.allocate(maximumBytes + 1);
        try (FileChannel channel = FileChannel.open(
                file,
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
        if (buffer.position() > maximumBytes) {
            throw new IOException("durability file is oversized");
        }
        return Arrays.copyOf(buffer.array(), buffer.position());
    }
}
