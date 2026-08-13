package io.github.greytaiwolf.botplayer.ai;

import java.util.Objects;

/**
 * 不含凭据、附件或 Minecraft 活动对象的单条有界消息。
 */
public record AiMessage(AiMessageRole role, String content) {
    public static final int MAX_CONTENT_LENGTH = 65_536;

    public AiMessage {
        Objects.requireNonNull(role, "role");
        content = AiChecks.boundedText(
                content,
                "content",
                MAX_CONTENT_LENGTH,
                false);
    }

    /** 消息正文属于模型上下文，异常和日志只能看到角色及长度。 */
    @Override
    public String toString() {
        return "AiMessage[role=" + role + ", contentLength=" + content.length() + "]";
    }
}
