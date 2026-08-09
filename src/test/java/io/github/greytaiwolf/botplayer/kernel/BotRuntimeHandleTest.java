package io.github.greytaiwolf.botplayer.kernel;

import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class BotRuntimeHandleTest {
    @Test
    void durableGenerationFloorIsMonotonicBeforeFirstAttach() {
        BotRuntimeHandle handle = new BotRuntimeHandle(
                new UUID(0L, 1L), "P5Floor", null);

        handle.rebaseGenerationFloorBeforeAttach(41L);
        handle.rebaseGenerationFloorBeforeAttach(7L);

        Assertions.assertEquals(41L, handle.generation());
    }

    @Test
    void rejectsFloorsThatCannotProduceASuccessor() {
        BotRuntimeHandle handle = new BotRuntimeHandle(
                new UUID(0L, 2L), "P5FloorMax", null);

        Assertions.assertAll(
                () -> Assertions.assertThrows(
                        IllegalArgumentException.class,
                        () -> handle.rebaseGenerationFloorBeforeAttach(-1L)),
                () -> Assertions.assertThrows(
                        IllegalArgumentException.class,
                        () -> handle.rebaseGenerationFloorBeforeAttach(
                                Long.MAX_VALUE - 1L)),
                () -> Assertions.assertThrows(
                        IllegalArgumentException.class,
                        () -> handle.rebaseGenerationFloorBeforeAttach(
                                Long.MAX_VALUE)));
    }
}
