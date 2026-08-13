package io.github.greytaiwolf.botplayer.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 由 {@link AiConversationWindow} 产生的、只能包含 USER 与已验证 ASSISTANT 消息的不可变快照。
 *
 * <p>构造入口刻意不公开，避免外部调用方把任意字符串伪装成 assistant 或 TOOL 协议历史。
 */
public final class AiConversationHistory {
    private static final AiConversationHistory EMPTY = new AiConversationHistory(List.of());

    private final List<AiMessage> messages;

    private AiConversationHistory(List<AiMessage> messages) {
        this.messages = messages;
    }

    /** 返回不含任何历史消息的安全快照。 */
    public static AiConversationHistory empty() {
        return EMPTY;
    }

    static AiConversationHistory fromTrustedWindow(List<AiMessage> source) {
        Objects.requireNonNull(source, "source");
        if (source.isEmpty()) {
            return EMPTY;
        }
        if (source.size() > AiRequest.MAX_MESSAGES) {
            throw new IllegalArgumentException(
                    "conversation exceeds maximum size " + AiRequest.MAX_MESSAGES);
        }
        List<AiMessage> copied = new ArrayList<>(source.size());
        for (AiMessage message : source) {
            AiMessage checked = Objects.requireNonNull(message, "message");
            if (checked.role() != AiMessageRole.USER
                    && checked.role() != AiMessageRole.ASSISTANT) {
                throw new IllegalArgumentException(
                        "conversation history must contain only USER or ASSISTANT messages");
            }
            copied.add(checked);
        }
        return new AiConversationHistory(List.copyOf(copied));
    }

    /** 返回按时间从旧到新排列的不可变消息。 */
    public List<AiMessage> messages() {
        return messages;
    }

    /** 对话正文不得出现在日志或异常诊断里。 */
    @Override
    public String toString() {
        int characters = messages.stream()
                .mapToInt(message -> message.content().length())
                .sum();
        return "AiConversationHistory[messageCount=" + messages.size()
                + ", characters=" + characters + "]";
    }
}
