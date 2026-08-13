package io.github.greytaiwolf.botplayer.client.ai;

/** HTTP body 不是可严格解码的 UTF-8，因此不能作为 Provider DTO 继续处理。 */
final class DeepSeekMalformedResponseException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    DeepSeekMalformedResponseException() {
        super("DeepSeek response is not valid UTF-8");
    }
}
