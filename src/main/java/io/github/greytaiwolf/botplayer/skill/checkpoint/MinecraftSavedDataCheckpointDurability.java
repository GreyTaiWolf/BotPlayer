package io.github.greytaiwolf.botplayer.skill.checkpoint;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * 1.21.1 DimensionDataStorage 的同步保存适配器。
 *
 * <p>映射更新曾改变此类的静态签名，因此这里故意把存储对象视为 {@code Object}，在运行时只接
 * 受明确的 {@code saveAndJoin()} 同步边界。没有该方法、不可访问、返回异步阶段但未完成，都会
 * 抛出并由 {@link SkillCheckpointDurableCommitter} 锁死恢复；绝不退化成 {@code setDirty()}。
 * 生命周期应传入 {@code server.overworld().getDataStorage()}。</p>
 *
 * <p>此适配器证明 Minecraft 已完成其 SavedData 保存等待；底层文件系统/目录 fsync 的断电语义
 * 仍须由后续真实断电验证覆盖，不能从该方法的成功返回推断。</p>
 */
public final class MinecraftSavedDataCheckpointDurability
        implements SkillCheckpointDurability {
    private final Object dataStorage;
    private final Method saveAndJoin;

    public MinecraftSavedDataCheckpointDurability(Object dataStorage) {
        this.dataStorage = Objects.requireNonNull(dataStorage, "dataStorage");
        try {
            this.saveAndJoin = dataStorage.getClass().getMethod("saveAndJoin");
        } catch (NoSuchMethodException exception) {
            throw new IllegalStateException(
                    "DimensionDataStorage does not expose synchronous saveAndJoin", exception);
        }
    }

    @Override
    public void commit() throws Exception {
        try {
            Object result = saveAndJoin.invoke(dataStorage);
            if (result instanceof CompletionStage<?> completion) {
                completion.toCompletableFuture().get();
            }
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception checked) {
                throw checked;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("synchronous SavedData save failed", cause);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException(
                    "cannot invoke synchronous DimensionDataStorage save", exception);
        }
    }
}
