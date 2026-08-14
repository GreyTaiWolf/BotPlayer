package io.github.greytaiwolf.botplayer.client.ai;

/** HTTP body 超过客户端硬上限时使用的内部脱敏失败信号。 */
final class DeepSeekResponseLimitException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    DeepSeekResponseLimitException() {
        super("DeepSeek response body exceeded the configured client limit");
    }
}
