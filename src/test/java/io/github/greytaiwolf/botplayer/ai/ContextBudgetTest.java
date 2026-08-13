package io.github.greytaiwolf.botplayer.ai;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ContextBudgetTest {
    @Test
    void estimatesUtf8BytesConservativelyAndRejectsMalformedUnicode() {
        Assertions.assertEquals(1,
                ContextBudget.estimateTextTokens("a"));
        Assertions.assertEquals(3,
                ContextBudget.estimateTextTokens("橡"));
        Assertions.assertEquals(4,
                ContextBudget.estimateTextTokens("😀"));
        Assertions.assertEquals(8,
                ContextBudget.estimateTextTokens("a橡😀"));

        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> ContextBudget.estimateTextTokens("\ud800"));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> ContextBudget.estimateTextTokens("\udc00"));
    }

    @Test
    void acceptsOnlyValuesThatCanStillProduceAnAiRequest() {
        ContextBudget defaults = ContextBudget.defaults();
        Assertions.assertTrue(defaults.maximumInputTokens() > 0);
        Assertions.assertTrue(defaults.maximumMessages() <= AiRequest.MAX_MESSAGES);
        Assertions.assertTrue(defaults.maximumCharacters()
                <= AiRequest.MAX_TOTAL_MESSAGE_CHARACTERS);

        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new ContextBudget(0, 1, 1));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new ContextBudget(1, AiRequest.MAX_MESSAGES + 1, 1));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new ContextBudget(
                        1,
                        1,
                        AiRequest.MAX_TOTAL_MESSAGE_CHARACTERS + 1));
    }
}
