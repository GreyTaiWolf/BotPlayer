package io.github.greytaiwolf.botplayer.lifecycle.death;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** 用于验证死亡事务在每个耐久边界失败关闭的确定性测试替身。 */
final class FaultInjectingDurableFileOps implements DurableFileOps {
    enum Point {
        EXISTS,
        CREATE_DIRECTORIES,
        WRITE_AND_FORCE,
        ATOMIC_MOVE,
        DELETE,
        DELETE_IF_EXISTS,
        FORCE_FILE,
        FORCE_DIRECTORY,
        READ_BOUNDED
    }

    private final DurableFileOps delegate;
    private final Point point;
    private final int failureOccurrence;
    private int occurrences;

    FaultInjectingDurableFileOps(Point point) {
        this(point, 1);
    }

    FaultInjectingDurableFileOps(
            Point point, int failureOccurrence) {
        this(DurableFileOps.system(), point, failureOccurrence);
    }

    FaultInjectingDurableFileOps(
            DurableFileOps delegate,
            Point point,
            int failureOccurrence) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.point = Objects.requireNonNull(point, "point");
        if (failureOccurrence < 1) {
            throw new IllegalArgumentException(
                    "failureOccurrence must be positive");
        }
        this.failureOccurrence = failureOccurrence;
    }

    @Override
    public boolean existsNoFollow(Path path) throws IOException {
        failIfConfigured(Point.EXISTS);
        return delegate.existsNoFollow(path);
    }

    @Override
    public boolean isRegularFileNoFollow(Path path) {
        return delegate.isRegularFileNoFollow(path);
    }

    @Override
    public boolean isDirectoryNoFollow(Path path) {
        return delegate.isDirectoryNoFollow(path);
    }

    @Override
    public void createDirectories(Path directory) throws IOException {
        failIfConfigured(Point.CREATE_DIRECTORIES);
        delegate.createDirectories(directory);
    }

    @Override
    public void writeFullyAndForce(Path file, byte[] bytes)
            throws IOException {
        failIfConfigured(Point.WRITE_AND_FORCE);
        delegate.writeFullyAndForce(file, bytes);
    }

    @Override
    public void moveAtomicallyReplace(Path source, Path target)
            throws IOException {
        failIfConfigured(Point.ATOMIC_MOVE);
        delegate.moveAtomicallyReplace(source, target);
    }

    @Override
    public void delete(Path path) throws IOException {
        failIfConfigured(Point.DELETE);
        delegate.delete(path);
    }

    @Override
    public void deleteIfExists(Path path) throws IOException {
        failIfConfigured(Point.DELETE_IF_EXISTS);
        delegate.deleteIfExists(path);
    }

    @Override
    public void forceFile(Path file) throws IOException {
        failIfConfigured(Point.FORCE_FILE);
        delegate.forceFile(file);
    }

    @Override
    public void forceDirectory(Path directory) throws IOException {
        failIfConfigured(Point.FORCE_DIRECTORY);
        delegate.forceDirectory(directory);
    }

    @Override
    public byte[] readBoundedNoFollow(Path file, int maximumBytes)
            throws IOException {
        failIfConfigured(Point.READ_BOUNDED);
        return delegate.readBoundedNoFollow(file, maximumBytes);
    }

    private void failIfConfigured(Point candidate) throws IOException {
        if (point == candidate
                && ++occurrences == failureOccurrence) {
            throw new IOException(
                    "injected durable-file failure at " + candidate);
        }
    }
}
