package io.github.greytaiwolf.botplayer.ai;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * 进程内、有界且线程安全的短期对话窗口。
 *
 * <p>它不保存 SYSTEM 或 TOOL 消息：固定规则必须由 {@link ContextPolicy} 单独传入，不能随着
 * 用户对话被逐出或伪造；当前 DTO 也没有可信的 tool_call_id/结果配对语义。每次写入都会从最旧的
 * 完整消息开始淘汰，绝不截断一条消息。
 */
public final class AiConversationWindow {
    private final ContextBudget budget;
    private final Object lock = new Object();
    private final Deque<WindowMessage> messages = new ArrayDeque<>();
    private long estimatedTokens;
    private long characters;

    public AiConversationWindow(ContextBudget budget) {
        this.budget = Objects.requireNonNull(budget, "budget");
    }

    /** 追加经过输入边界验证的 USER 消息。 */
    public void appendUser(String content) {
        appendUser(new AiMessage(AiMessageRole.USER, content));
    }

    /** 追加经过输入边界验证的 USER 消息。 */
    public void appendUser(AiMessage message) {
        AiMessage checkedMessage = Objects.requireNonNull(message, "message");
        if (checkedMessage.role() != AiMessageRole.USER) {
            throw new IllegalArgumentException(
                    "appendUser requires a USER message");
        }
        appendChecked(checkedMessage);
    }

    /**
     * 只接受已由受信 Provider 返回并完成验证的 assistant 输出；工具调用不会被降级成普通历史。
     */
    void appendVerifiedAssistant(AiResponse response) {
        AiResponse checkedResponse = Objects.requireNonNull(response, "response");
        if (checkedResponse.finishReason() == AiFinishReason.TOOL_CALLS) {
            throw new IllegalArgumentException(
                    "tool call responses must not enter conversation history");
        }
        appendChecked(new AiMessage(AiMessageRole.ASSISTANT,
                checkedResponse.outputText()));
    }

    /**
     * 兼容旧的 USER 入口；不能借此伪造 assistant/tool 历史。
     *
     * @deprecated 改用 {@link #appendUser(AiMessage)} 或
     *     由同包会话层调用的已验证 assistant 入口。
     */
    @Deprecated(forRemoval = false)
    public void append(AiMessage message) {
        appendUser(message);
    }

    private void appendChecked(AiMessage checkedMessage) {
        int tokenEstimate = budget.estimateTokens(checkedMessage);
        int characterCount = checkedMessage.content().length();
        if (tokenEstimate > budget.maximumInputTokens()
                || characterCount > budget.maximumCharacters()) {
            throw new IllegalArgumentException(
                    "message exceeds conversation window budget");
        }

        synchronized (lock) {
            messages.addLast(new WindowMessage(
                    checkedMessage, tokenEstimate, characterCount));
            estimatedTokens += tokenEstimate;
            characters += characterCount;
            while (exceedsBudget()) {
                WindowMessage evicted = messages.removeFirst();
                estimatedTokens -= evicted.estimatedTokens();
                characters -= evicted.characters();
            }
        }
    }

    /** 返回按时间从旧到新排列、且不可由外部伪造角色的不可变快照。 */
    public AiConversationHistory snapshot() {
        synchronized (lock) {
            List<AiMessage> copied = new ArrayList<>(messages.size());
            for (WindowMessage message : messages) {
                copied.add(message.message());
            }
            return AiConversationHistory.fromTrustedWindow(copied);
        }
    }

    public int size() {
        synchronized (lock) {
            return messages.size();
        }
    }

    public int estimatedTokenCount() {
        synchronized (lock) {
            return Math.toIntExact(estimatedTokens);
        }
    }

    public int characterCount() {
        synchronized (lock) {
            return Math.toIntExact(characters);
        }
    }

    public void clear() {
        synchronized (lock) {
            messages.clear();
            estimatedTokens = 0L;
            characters = 0L;
        }
    }

    private boolean exceedsBudget() {
        return messages.size() > budget.maximumMessages()
                || estimatedTokens > budget.maximumInputTokens()
                || characters > budget.maximumCharacters();
    }

    private record WindowMessage(
            AiMessage message, int estimatedTokens, int characters) {
    }
}
