package io.github.greytaiwolf.botplayer.ai.tool;

/**
 * 解析不可信工具 JSON 时的失败。
 *
 * <p>异常消息只含稳定失败码，故意不回显模型参数、潜在 secret 或原始 JSON。
 */
public final class ToolCallCodecException extends Exception {
    private static final long serialVersionUID = 1L;

    private final ToolCallCodecFailure failure;

    ToolCallCodecException(ToolCallCodecFailure failure) {
        super("Tool call codec rejected input: " + failure);
        this.failure = failure;
    }

    public ToolCallCodecFailure failure() {
        return failure;
    }
}
