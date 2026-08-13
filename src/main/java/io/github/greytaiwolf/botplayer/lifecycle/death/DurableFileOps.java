package io.github.greytaiwolf.botplayer.lifecycle.death;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 死亡事务使用的窄文件耐久边界。
 *
 * <p>这里故意只暴露 tombstone 与 playerdata 提交所需的操作。生产环境使用 {@link
 * NioDurableFileOps}；测试可以注入实现，在发布、刷盘或回读的任一点确定性失败。调用方必须把
 * {@link IOException} 视为“耐久性尚未证明”，不得继续清除 tombstone。
 */
public interface DurableFileOps {
    /** 返回默认的 NIO 实现。 */
    static DurableFileOps system() {
        return NioDurableFileOps.INSTANCE;
    }

    /**
     * 判断路径是否存在，不能把权限或属性读取失败伪装成“不存在”。
     */
    boolean existsNoFollow(Path path) throws IOException;

    boolean isRegularFileNoFollow(Path path);

    boolean isDirectoryNoFollow(Path path);

    void createDirectories(Path directory) throws IOException;

    /** 将完整字节写入目标，并在关闭前对文件执行 {@code force(true)}。 */
    void writeFullyAndForce(Path file, byte[] bytes) throws IOException;

    /** 原子替换发布；不接受非原子降级。 */
    void moveAtomicallyReplace(Path source, Path target)
            throws IOException;

    void delete(Path path) throws IOException;

    void deleteIfExists(Path path) throws IOException;

    /** 刷新一个已存在的普通文件。 */
    void forceFile(Path file) throws IOException;

    /** 刷新一个已存在的目录项。 */
    void forceDirectory(Path directory) throws IOException;

    /**
     * 以不跟随符号链接的方式读取整个文件。
     *
     * <p>超出 {@code maximumBytes} 的文件必须失败，而不能截断后继续解析。
     */
    byte[] readBoundedNoFollow(Path file, int maximumBytes)
            throws IOException;
}
