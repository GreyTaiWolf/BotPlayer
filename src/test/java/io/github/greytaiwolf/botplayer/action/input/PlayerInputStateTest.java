package io.github.greytaiwolf.botplayer.action.input;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class PlayerInputStateTest {
   @Test
   void idleIsCanonicalAndMovementIsNotIdle() {
      Assertions.assertTrue(PlayerInputState.IDLE.isIdle());
      Assertions.assertFalse(new PlayerInputState(1.0F, 0.0F, false, false, false, false).isIdle());
   }

   @Test
   void axesAndMovementModesAreBounded() {
      Assertions.assertThrows(IllegalArgumentException.class, () -> new PlayerInputState(1.01F, 0.0F, false, false, false, false));
      Assertions.assertThrows(IllegalArgumentException.class, () -> new PlayerInputState(0.0F, Float.POSITIVE_INFINITY, false, false, false, false));
      Assertions.assertThrows(IllegalArgumentException.class, () -> new PlayerInputState(1.0F, 0.0F, false, true, true, false));
      Assertions.assertThrows(IllegalArgumentException.class, () -> new PlayerInputState(0.0F, 0.0F, false, true, false, false));
      Assertions.assertThrows(IllegalArgumentException.class, () -> new PlayerInputState(0.0F, 0.0F, false, false, false, true));
   }
}
