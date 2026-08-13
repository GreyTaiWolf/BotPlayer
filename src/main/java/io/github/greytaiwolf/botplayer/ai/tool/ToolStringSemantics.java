package io.github.greytaiwolf.botplayer.ai.tool;

/**
 * 静态工具 schema 中字符串的业务语义。
 *
 * <p>字符串不能一律视为任意文本：资源标识符、枚举和给玩家看的文字有不同的安全边界。
 * 此枚举只描述静态形状，不授予文件、HTTP、命令或世界操作权限。</p>
 */
public enum ToolStringSemantics {
    /** 非 String 规则必须使用此值。 */
    NONE,
    /** 受长度和 Firewall 危险文本检查约束的普通展示/澄清文本。 */
    FREE_TEXT,
    /** 仅允许 {@code namespace:path} 形式的 Minecraft 风格资源标识符。 */
    RESOURCE_IDENTIFIER
}
