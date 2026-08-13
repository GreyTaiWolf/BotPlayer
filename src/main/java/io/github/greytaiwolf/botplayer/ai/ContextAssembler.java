package io.github.greytaiwolf.botplayer.ai;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 将固定规则、短期对话和可选记忆装入一个严格有界的 Provider 上下文。
 *
 * <p>固定 SYSTEM 规则和当前 USER 请求是必需内容，装不下时直接失败。短期对话按最近优先保留
 * 完整后缀；记忆按置信度和稳定 sourceId 排序后再尝试装入。检索器故障或违反返回合同只会得到
 * 不含记忆的结果，绝不会把未验证部分塞入请求。
 */
public final class ContextAssembler {
    public static final int MAX_RETRIEVED_MEMORIES = MemoryQuery.MAX_ENTRIES;

    private static final Comparator<AiMemory> MEMORY_PRIORITY =
            Comparator.comparingDouble(AiMemory::confidence)
                    .reversed()
                    .thenComparing(AiMemory::sourceId)
                    .thenComparing(AiMemory::content);

    private final ContextBudget budget;
    private final MemoryRetriever memoryRetriever;

    public ContextAssembler(ContextBudget budget) {
        this(budget, MemoryRetriever.empty());
    }

    public ContextAssembler(
            ContextBudget budget, MemoryRetriever memoryRetriever) {
        this.budget = Objects.requireNonNull(budget, "budget");
        this.memoryRetriever = Objects.requireNonNull(
                memoryRetriever, "memoryRetriever");
    }

    public ContextAssembly assemble(ContextAssemblyInput input) {
        ContextAssemblyInput checkedInput = Objects.requireNonNull(input, "input");
        Usage usage = new Usage(budget);
        for (AiMessage systemMessage : checkedInput.policy().systemMessages()) {
            usage.addRequired(systemMessage);
        }
        usage.addRequired(checkedInput.currentUserMessage());

        List<AiMessage> retainedDialogue = retainRecentDialogue(
                checkedInput.conversation().messages(), usage);
        int omittedDialogue = checkedInput.conversation().messages().size()
                - retainedDialogue.size();

        /*
         * 先固定最近完整对话，避免在最终会被逐出的旧历史仍占用检索预算或触发外部工作。
         */
        MemoryResult memoryResult = retrieveMemories(checkedInput, usage);

        List<AiMessage> retainedMemories = new ArrayList<>();
        int omittedMemories = 0;
        for (AiMessage memory : memoryResult.messages()) {
            if (usage.canAdd(memory)) {
                usage.add(memory);
                retainedMemories.add(memory);
            } else {
                omittedMemories++;
            }
        }

        List<AiMessage> assembled = new ArrayList<>(
                checkedInput.policy().systemMessages().size()
                        + retainedMemories.size()
                        + retainedDialogue.size()
                        + 1);
        assembled.addAll(checkedInput.policy().systemMessages());
        assembled.addAll(retainedMemories);
        assembled.addAll(retainedDialogue);
        assembled.add(checkedInput.currentUserMessage());
        return new ContextAssembly(
                assembled,
                usage.estimatedTokens(),
                usage.characters(),
                omittedDialogue,
                omittedMemories,
                memoryResult.status());
    }

    private List<AiMessage> retainRecentDialogue(
            List<AiMessage> dialogue, Usage usage) {
        List<AiMessage> retainedReverse = new ArrayList<>();
        for (int index = dialogue.size() - 1; index >= 0; index--) {
            AiMessage message = dialogue.get(index);
            if (!usage.canAdd(message)) {
                break;
            }
            usage.add(message);
            retainedReverse.add(message);
        }
        List<AiMessage> retained = new ArrayList<>(retainedReverse.size());
        for (int index = retainedReverse.size() - 1; index >= 0; index--) {
            retained.add(retainedReverse.get(index));
        }
        return retained;
    }

    private MemoryResult retrieveMemories(
            ContextAssemblyInput input, Usage usage) {
        if (input.memoryQuery().isEmpty()) {
            return new MemoryResult(
                    MemoryRetrievalStatus.NOT_REQUESTED, List.of());
        }
        int remainingTokens = usage.remainingTokens();
        int remainingCharacters = usage.remainingCharacters();
        int remainingMessages = usage.remainingMessages();
        if (remainingTokens < 1
                || remainingCharacters < 1
                || remainingMessages < 1) {
            return new MemoryResult(
                    MemoryRetrievalStatus.SKIPPED_BY_BUDGET, List.of());
        }

        MemoryQuery query = new MemoryQuery(
                input.memoryQuery().orElseThrow(),
                Math.min(MAX_RETRIEVED_MEMORIES, remainingMessages),
                remainingTokens,
                remainingCharacters);
        List<AiMemory> retrieved;
        try {
            retrieved = memoryRetriever.retrieve(query);
        } catch (RuntimeException exception) {
            return new MemoryResult(
                    MemoryRetrievalStatus.UNAVAILABLE, List.of());
        }
        if (retrieved == null) {
            return new MemoryResult(
                    MemoryRetrievalStatus.REJECTED, List.of());
        }

        List<AiMemory> copied;
        long totalTokens = 0L;
        long totalCharacters = 0L;
        try {
            int reportedSize = retrieved.size();
            if (reportedSize > query.maximumEntries()) {
                return new MemoryResult(
                        MemoryRetrievalStatus.REJECTED, List.of());
            }
            copied = new ArrayList<>(reportedSize);
            for (AiMemory memory : retrieved) {
                if (copied.size() >= query.maximumEntries()) {
                    return new MemoryResult(
                            MemoryRetrievalStatus.REJECTED, List.of());
                }
                AiMemory checked = Objects.requireNonNull(memory, "memory");
                AiMessage contextMessage = checked.asContextMessage();
                totalTokens += budget.estimateTokens(contextMessage);
                totalCharacters += contextMessage.content().length();
                if (totalTokens > query.maximumEstimatedTokens()
                        || totalCharacters > query.maximumCharacters()) {
                    return new MemoryResult(
                            MemoryRetrievalStatus.REJECTED, List.of());
                }
                copied.add(checked);
            }
        } catch (RuntimeException exception) {
            return new MemoryResult(
                    MemoryRetrievalStatus.REJECTED, List.of());
        }
        if (copied.isEmpty()) {
            return new MemoryResult(MemoryRetrievalStatus.EMPTY, List.of());
        }
        copied.sort(MEMORY_PRIORITY);
        List<AiMessage> messages = new ArrayList<>(copied.size());
        for (AiMemory memory : copied) {
            messages.add(memory.asContextMessage());
        }
        return new MemoryResult(MemoryRetrievalStatus.RETRIEVED, messages);
    }

    private record MemoryResult(
            MemoryRetrievalStatus status, List<AiMessage> messages) {
    }

    private static final class Usage {
        private final ContextBudget budget;
        private long estimatedTokens;
        private long characters;
        private int messages;

        private Usage(ContextBudget budget) {
            this.budget = budget;
        }

        private void addRequired(AiMessage message) {
            if (!canAdd(message)) {
                throw new ContextBudgetExceededException(
                        "required context cannot fit configured budget");
            }
            add(message);
        }

        private boolean canAdd(AiMessage message) {
            int tokenEstimate = budget.estimateTokens(message);
            long nextTokens = estimatedTokens + tokenEstimate;
            long nextCharacters = characters + message.content().length();
            return messages < budget.maximumMessages()
                    && nextTokens <= budget.maximumInputTokens()
                    && nextCharacters <= budget.maximumCharacters();
        }

        private void add(AiMessage message) {
            estimatedTokens += budget.estimateTokens(message);
            characters += message.content().length();
            messages++;
        }

        private int estimatedTokens() {
            return Math.toIntExact(estimatedTokens);
        }

        private int characters() {
            return Math.toIntExact(characters);
        }

        private int remainingTokens() {
            return budget.maximumInputTokens() - estimatedTokens();
        }

        private int remainingCharacters() {
            return budget.maximumCharacters() - characters();
        }

        private int remainingMessages() {
            return budget.maximumMessages() - messages;
        }
    }
}
