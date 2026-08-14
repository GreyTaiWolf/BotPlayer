package io.github.greytaiwolf.botplayer.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 上下文组装的不可变结果及其不含原文的预算诊断。
 */
public record ContextAssembly(
        List<AiMessage> messages,
        int estimatedInputTokens,
        int totalCharacters,
        int omittedDialogueMessages,
        int omittedMemories,
        MemoryRetrievalStatus memoryRetrievalStatus) {
    public ContextAssembly {
        Objects.requireNonNull(messages, "messages");
        if (messages.isEmpty() || messages.size() > AiRequest.MAX_MESSAGES) {
            throw new IllegalArgumentException(
                    "messages must contain between 1 and "
                            + AiRequest.MAX_MESSAGES + " entries");
        }
        List<AiMessage> copied = new ArrayList<>(messages.size());
        long calculatedTokens = 0L;
        long calculatedCharacters = 0L;
        for (AiMessage message : messages) {
            AiMessage checked = Objects.requireNonNull(message, "message");
            copied.add(checked);
            calculatedTokens += ContextBudget.estimateTextTokens(
                    checked.content());
            calculatedCharacters += checked.content().length();
        }
        if (calculatedTokens > ContextBudget.MAX_INPUT_TOKENS
                || calculatedCharacters > AiRequest.MAX_TOTAL_MESSAGE_CHARACTERS) {
            throw new IllegalArgumentException(
                    "messages exceed hard context limits");
        }
        if (estimatedInputTokens != calculatedTokens) {
            throw new IllegalArgumentException(
                    "estimatedInputTokens must match message content");
        }
        if (totalCharacters != calculatedCharacters) {
            throw new IllegalArgumentException(
                    "totalCharacters must match message content");
        }
        if (omittedDialogueMessages < 0 || omittedMemories < 0) {
            throw new IllegalArgumentException(
                    "omitted message counters must not be negative");
        }
        Objects.requireNonNull(memoryRetrievalStatus, "memoryRetrievalStatus");
        messages = List.copyOf(copied);
    }

    /** 汇总上下文只能报告数量和预算，不能递归渲染消息内容。 */
    @Override
    public String toString() {
        return "ContextAssembly[messageCount=" + messages.size()
                + ", estimatedInputTokens=" + estimatedInputTokens
                + ", totalCharacters=" + totalCharacters
                + ", omittedDialogueMessages=" + omittedDialogueMessages
                + ", omittedMemories=" + omittedMemories
                + ", memoryRetrievalStatus=" + memoryRetrievalStatus + "]";
    }
}
