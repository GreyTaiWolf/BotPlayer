package io.github.greytaiwolf.botplayer.client.ai;

import io.github.greytaiwolf.botplayer.ai.tool.ToolDefinition;
import io.github.greytaiwolf.botplayer.ai.tool.ToolRiskLevel;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 发给 DeepSeek 的可信静态函数描述。
 *
 * <p>模型只能看到预先注册的 schema；该描述不是执行授权。返回调用仍必须经过服务端
 * ToolCallCodec、ToolFirewall、owner/ACL、revision 与世界状态复核。</p>
 */
public record DeepSeekToolDefinition(ToolDefinition definition, String description) {
    public static final int MAX_FUNCTION_NAME_CHARACTERS = 64;
    private static final Pattern PROVIDER_FUNCTION_NAME =
            Pattern.compile("[A-Za-z0-9_-]{1," + MAX_FUNCTION_NAME_CHARACTERS + "}");
    public static final int MAX_DESCRIPTION_CHARACTERS = 1_024;

    public DeepSeekToolDefinition {
        definition = Objects.requireNonNull(definition, "definition");
        if (!PROVIDER_FUNCTION_NAME.matcher(definition.toolName()).matches()) {
            throw new IllegalArgumentException(
                    "DeepSeek function names must contain only letters, digits, underscores or dashes");
        }
        if (definition.riskLevel() == ToolRiskLevel.FORBIDDEN) {
            throw new IllegalArgumentException(
                    "forbidden tools must not be exposed to DeepSeek");
        }
        description = Objects.requireNonNull(description, "description");
        if (description.isBlank()
                || description.length() > MAX_DESCRIPTION_CHARACTERS
                || description.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    "DeepSeek tool description is invalid");
        }
    }

    /** 静态函数说明也属于发往模型的 prompt，不默认回显。 */
    @Override
    public String toString() {
        return "DeepSeekToolDefinition[toolName=" + definition.toolName()
                + ", descriptionLength=" + description.length() + "]";
    }
}
