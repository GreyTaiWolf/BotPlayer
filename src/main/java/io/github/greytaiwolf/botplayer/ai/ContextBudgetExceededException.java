package io.github.greytaiwolf.botplayer.ai;

/**
 * 必需上下文无法装入硬预算时的 fail-closed 信号。
 *
 * <p>异常消息不得拼接 prompt、记忆或用户文本，避免诊断路径反向泄露上下文。
 */
public final class ContextBudgetExceededException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public ContextBudgetExceededException(String message) {
        super(message);
    }
}
