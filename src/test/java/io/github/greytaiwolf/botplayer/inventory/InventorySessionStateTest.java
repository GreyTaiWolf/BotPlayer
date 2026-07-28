package io.github.greytaiwolf.botplayer.inventory;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class InventorySessionStateTest {
   private static final Map<InventorySessionState, Set<InventorySessionState>> LEGAL_TRANSITIONS = Map.of(
      InventorySessionState.OPENING,
      EnumSet.of(InventorySessionState.OPEN, InventorySessionState.CLOSING),
      InventorySessionState.OPEN,
      EnumSet.of(InventorySessionState.CLOSING),
      InventorySessionState.CLOSING,
      EnumSet.of(InventorySessionState.CLOSED)
   );

   @Test
   void centralTableAcceptsOnlyDocumentedTransitions() {
      for (InventorySessionState var4 : InventorySessionState.values()) {
         Set<InventorySessionState> var5 =
               LEGAL_TRANSITIONS.getOrDefault(var4, Set.of());

         for (InventorySessionState var9 : InventorySessionState.values()) {
            Assertions.assertTrue(var4.canTransitionTo(var9) == var5.contains(var9), () -> "Unexpected transition decision for " + var4 + " -> " + var9);
         }
      }
   }

   @Test
   void rejectsSkippedStagesAndTerminalRevival() {
      Assertions.assertDoesNotThrow(() -> InventorySessionState.OPENING.requireTransitionTo(InventorySessionState.OPEN));
      Assertions.assertThrows(IllegalStateException.class, () -> InventorySessionState.OPENING.requireTransitionTo(InventorySessionState.CLOSED));
      Assertions.assertTrue(InventorySessionState.CLOSED.isTerminal());
      Assertions.assertFalse(InventorySessionState.CLOSED.canTransitionTo(InventorySessionState.OPENING));
   }

   @Test
   void nullTransitionIsRejected() {
      Assertions.assertThrows(NullPointerException.class, () -> InventorySessionState.OPEN.canTransitionTo(null));
   }
}
