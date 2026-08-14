package io.github.greytaiwolf.botplayer.ai;

import java.util.Objects;
import java.util.Optional;

/**
 * {@link ContextAssembler} 的纯 DTO 输入。
 *
 * <p>固定规则与动态对话有不同的信任边界：SYSTEM 只能来自 {@link ContextPolicy}，当前
 * 请求必须是 USER。调用方应把已裁剪的 {@link AiConversationWindow#snapshot()} 传为
 * {@code conversation}，而不是传入 Minecraft 活动对象或整个聊天/世界历史。
 */
public record ContextAssemblyInput(
        ContextPolicy policy,
        AiConversationHistory conversation,
        AiMessage currentUserMessage,
        Optional<String> memoryQuery) {
    public static final int MAX_SYSTEM_MESSAGES = 32;

    public ContextAssemblyInput {
        policy = Objects.requireNonNull(policy, "policy");
        conversation = Objects.requireNonNull(conversation, "conversation");
        currentUserMessage = Objects.requireNonNull(
                currentUserMessage, "currentUserMessage");
        ContextBudget.estimateTextTokens(currentUserMessage.content());
        if (currentUserMessage.role() != AiMessageRole.USER) {
            throw new IllegalArgumentException(
                    "currentUserMessage must have USER role");
        }
        memoryQuery = AiChecks.optionalText(
                memoryQuery,
                "memoryQuery",
                MemoryQuery.MAX_QUERY_LENGTH);
        memoryQuery.ifPresent(ContextBudget::estimateTextTokens);
        requireWholeInputWithinHardBounds(
                policy, conversation, currentUserMessage, memoryQuery);
    }

    private static void requireWholeInputWithinHardBounds(
            ContextPolicy policy,
            AiConversationHistory conversation,
            AiMessage currentUserMessage,
            Optional<String> memoryQuery) {
        long messageCount = policy.systemMessages().size()
                + (long) conversation.messages().size() + 1L;
        if (messageCount > AiRequest.MAX_MESSAGES) {
            throw new IllegalArgumentException(
                    "policy, dialogue and current user exceed maximum message count "
                            + AiRequest.MAX_MESSAGES);
        }
        long characters = currentUserMessage.content().length();
        for (AiMessage systemMessage : policy.systemMessages()) {
            characters += systemMessage.content().length();
        }
        for (AiMessage dialogueMessage : conversation.messages()) {
            characters += dialogueMessage.content().length();
        }
        characters += memoryQuery.map(String::length).orElse(0);
        if (characters > AiRequest.MAX_TOTAL_MESSAGE_CHARACTERS) {
            throw new IllegalArgumentException(
                    "context input exceeds hard character bound "
                            + AiRequest.MAX_TOTAL_MESSAGE_CHARACTERS);
        }
    }

    /** 输入只描述分层数量；默认 record 输出会泄漏完整 prompt、对话和检索词。 */
    @Override
    public String toString() {
        return "ContextAssemblyInput[policy=" + policy
                + ", dialogueMessageCount=" + conversation.messages().size()
                + ", currentUserLength=" + currentUserMessage.content().length()
                + ", memoryQueryPresent=" + memoryQuery.isPresent()
                + ", memoryQueryLength="
                + memoryQuery.map(String::length).orElse(0) + "]";
    }
}
