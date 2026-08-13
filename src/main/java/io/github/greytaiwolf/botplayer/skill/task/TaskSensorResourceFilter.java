package io.github.greytaiwolf.botplayer.skill.task;

import java.util.Objects;

/**
 * {@link TaskSensorQueryType#RESOURCE_CANDIDATES} 的服务端受限精确方块过滤器。
 *
 * <p>这不是客户端或技能包可传入的 tag、正则或资源位置字符串。新增可观察方块必须在这里
 * 经代码审查加入一个精确常量；默认 {@link #UNFILTERED} 保留既有全体非空气方块候选语义。
 */
public enum TaskSensorResourceFilter {
    UNFILTERED(null),
    OAK_LOG("minecraft:oak_log"),
    COBBLESTONE("minecraft:cobblestone"),
    IRON_ORE("minecraft:iron_ore"),
    COAL_ORE("minecraft:coal_ore"),
    CRAFTING_TABLE("minecraft:crafting_table"),
    FURNACE("minecraft:furnace"),
    BLAST_FURNACE("minecraft:blast_furnace"),
    SMOKER("minecraft:smoker");

    private final String exactBlockId;

    TaskSensorResourceFilter(String exactBlockId) {
        this.exactBlockId = exactBlockId;
    }

    /**
     * 返回观察到的方块是否属于此受限过滤器。
     */
    boolean accepts(String observedBlockId) {
        Objects.requireNonNull(observedBlockId, "observedBlockId");
        return exactBlockId == null || exactBlockId.equals(observedBlockId);
    }

    boolean isUnfiltered() {
        return exactBlockId == null;
    }
}
