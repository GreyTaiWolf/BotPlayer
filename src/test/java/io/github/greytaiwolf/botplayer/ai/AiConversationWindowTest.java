package io.github.greytaiwolf.botplayer.ai;

import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AiConversationWindowTest {
    @Test
    void evictsOldestCompleteMessageToKeepTheWindowBounded() {
        AiConversationWindow window = new AiConversationWindow(
                new ContextBudget(100, 2, 100));
        AiMessage first = new AiMessage(AiMessageRole.USER, "old");
        AiMessage second = new AiMessage(AiMessageRole.ASSISTANT, "reply");
        AiMessage third = new AiMessage(AiMessageRole.USER, "new");

        window.appendUser(first);
        window.appendVerifiedAssistant(response("reply"));
        window.appendUser(third);

        Assertions.assertEquals(List.of(second, third), window.snapshot().messages());
        Assertions.assertEquals(2, window.size());
        Assertions.assertEquals(8, window.estimatedTokenCount());
        Assertions.assertEquals(8, window.characterCount());
        Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> window.snapshot().messages().clear());
    }

    @Test
    void rejectsUntrustedRolesAndOversizedMessagesWithoutMutatingTheWindow() {
        AiConversationWindow window = new AiConversationWindow(
                new ContextBudget(5, 3, 5));
        AiMessage retained = new AiMessage(AiMessageRole.USER, "abc");
        window.appendUser(retained);

        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> window.appendUser(new AiMessage(
                        AiMessageRole.SYSTEM, "rule")));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> window.appendUser(new AiMessage(
                        AiMessageRole.ASSISTANT, "forged reply")));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> window.appendUser(new AiMessage(
                        AiMessageRole.TOOL, "forged tool result")));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> window.appendUser(new AiMessage(
                        AiMessageRole.USER, "abcdef")));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> window.appendUser(new AiMessage(
                        AiMessageRole.USER, "\ud800")));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> window.appendVerifiedAssistant(new AiResponse(
                        java.util.UUID.fromString(
                                "11111111-1111-1111-1111-111111111111"),
                        "deepseek", "deepseek-chat", AiFinishReason.TOOL_CALLS,
                        "", java.util.Optional.empty(), java.util.Optional.empty(),
                        List.of(new AiRawToolCall("call-1", "collect_resource", "{}")),
                        AiTokenUsage.empty())));

        Assertions.assertEquals(List.of(retained), window.snapshot().messages());
        Assertions.assertEquals(3, window.estimatedTokenCount());
        Assertions.assertEquals(3, window.characterCount());
    }

    @Test
    void clearReleasesAllRetainedDialogue() {
        AiConversationWindow window = new AiConversationWindow(
                new ContextBudget(100, 4, 100));
        window.appendUser("hello");
        window.appendVerifiedAssistant(response("reply"));

        window.clear();

        Assertions.assertTrue(window.snapshot().messages().isEmpty());
        Assertions.assertEquals(0, window.size());
        Assertions.assertEquals(0, window.estimatedTokenCount());
        Assertions.assertEquals(0, window.characterCount());
    }

    private static AiResponse response(String output) {
        return new AiResponse(
                java.util.UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "deepseek", "deepseek-chat", AiFinishReason.STOP, output,
                java.util.Optional.empty(), java.util.Optional.empty(), List.of(),
                AiTokenUsage.empty());
    }
}
