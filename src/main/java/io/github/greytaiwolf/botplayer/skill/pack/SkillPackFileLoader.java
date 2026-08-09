package io.github.greytaiwolf.botplayer.skill.pack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 从世界根目录受限读取声明式包。只扫描精确
 * data/&lt;namespace&gt;/botplayer/skills/&lt;name&gt;.json 形状，
 * 不跟随链接，也不会接受离开 world root 的路径。
 */
public final class SkillPackFileLoader {
    public static final int MAX_DISCOVERED_PACKS = 512;

    public List<SkillPackSource> load(Path worldRoot) {
        Objects.requireNonNull(worldRoot, "worldRoot");
        Path root = worldRoot.toAbsolutePath().normalize();
        Path data = root.resolve("data").normalize();
        if (!data.startsWith(root) || !Files.isDirectory(
                data, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(data, 5)) {
            List<Path> candidates = new ArrayList<>(paths
                    .filter(path -> Files.isRegularFile(
                            path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .map(path -> path.toAbsolutePath().normalize())
                    .filter(path -> path.startsWith(root))
                    .filter(path -> matchesSafeRelative(root.relativize(path)))
                    .limit(MAX_DISCOVERED_PACKS + 1)
                    .toList());
            if (candidates.size() > MAX_DISCOVERED_PACKS) {
                throw new IllegalStateException(
                        "declarative skill pack count exceeds maximum "
                                + MAX_DISCOVERED_PACKS);
            }
            candidates.sort(Comparator.comparing(Path::toString));
            List<SkillPackSource> result = new ArrayList<>(candidates.size());
            for (Path path : candidates) {
                Path relative = root.relativize(path);
                String safeRelative = relative.toString().replace(
                        path.getFileSystem().getSeparator(), "/");
                byte[] bytes = readBounded(path);
                result.add(new SkillPackSource(safeRelative, bytes));
            }
            return List.copyOf(result);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not load declarative skill packs", exception);
        }
    }

    private static boolean matchesSafeRelative(Path relative) {
        String value = relative.toString().replace(
                relative.getFileSystem().getSeparator(), "/");
        try {
            return new SkillPackSource(value, new byte[0]) != null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static byte[] readBounded(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(
                path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(
                    SkillPackSource.ABSOLUTE_MAX_BYTES + 1);
            if (bytes.length > SkillPackSource.ABSOLUTE_MAX_BYTES) {
                throw new IllegalStateException(
                        "declarative skill pack exceeds byte limit");
            }
            return bytes;
        }
    }
}
