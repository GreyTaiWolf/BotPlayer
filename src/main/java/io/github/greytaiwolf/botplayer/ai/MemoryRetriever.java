package io.github.greytaiwolf.botplayer.ai;

import java.util.List;

/**
 * P6 上下文的可选记忆检索边界。
 *
 * <p>首次接入使用 {@link #empty()}，不读取磁盘、SQLite 或 Minecraft 活动对象。未来 P7
 * 检索实现仍必须遵守 {@link MemoryQuery} 中的条数、字符和保守 token 预算。
 */
@FunctionalInterface
public interface MemoryRetriever {
    MemoryRetriever EMPTY = query -> List.of();

    List<AiMemory> retrieve(MemoryQuery query);

    static MemoryRetriever empty() {
        return EMPTY;
    }
}
