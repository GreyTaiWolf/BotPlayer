package io.github.greytaiwolf.botplayer.ai.tool;

/** 静态工具描述使用的风险等级；真实 owner/ACL 复核仍属于后续会话层。 */
public enum ToolRiskLevel {
    SAFE,
    LOW,
    MEDIUM,
    HIGH,
    FORBIDDEN;

    public boolean isAtMost(ToolRiskLevel maximum) {
        return this != FORBIDDEN && ordinal() <= maximum.ordinal();
    }
}
