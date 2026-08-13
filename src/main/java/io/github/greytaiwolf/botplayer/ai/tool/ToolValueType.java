package io.github.greytaiwolf.botplayer.ai.tool;

/**
 * 工具参数允许携带的受限 JSON 值类型。
 *
 * <p>浮点、二进制、任意 Java 对象和 Minecraft 对象都不属于这个边界。数量类参数使用
 * {@link #INTEGER}，由工具规则进一步规定上下限。
 */
public enum ToolValueType {
    NULL,
    BOOLEAN,
    INTEGER,
    STRING,
    ARRAY,
    OBJECT
}
