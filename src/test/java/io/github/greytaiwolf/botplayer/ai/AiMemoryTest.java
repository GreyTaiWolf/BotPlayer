package io.github.greytaiwolf.botplayer.ai;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AiMemoryTest {
    @Test
    void wrapsMemoryAsEscapedUntrustedUserData() {
        AiMemory memory = new AiMemory(
                "p7.session-1",
                0.75D,
                "line\nquote=\" slash=\\ control=" + (char) 1);

        AiMessage message = memory.asContextMessage();

        Assertions.assertEquals(AiMessageRole.USER, message.role());
        Assertions.assertEquals(
                "{\"kind\":\"untrusted_memory\",\"source\":\"p7.session-1\","
                        + "\"confidence\":0.75,\"content\":\"line\\nquote=\\\" "
                        + "slash=\\\\ control=\\u0001\"}",
                message.content());
    }

    @Test
    void rejectsUnboundedOrAmbiguousMemoryValues() {
        Assertions.assertEquals(0.0D,
                new AiMemory("valid", -0.0D, "content").confidence());
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new AiMemory("UPPER", 0.5D, "content"));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new AiMemory("valid", Double.NaN, "content"));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new AiMemory(
                        "valid",
                        0.5D,
                        "x".repeat(AiMemory.MAX_CONTENT_LENGTH + 1)));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new AiMemory("valid", 0.5D, "\ud800"));
    }
}
