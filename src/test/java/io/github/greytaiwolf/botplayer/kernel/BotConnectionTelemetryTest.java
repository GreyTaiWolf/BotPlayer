package io.github.greytaiwolf.botplayer.kernel;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class BotConnectionTelemetryTest {
   @Test
   void keepsOnlyConstantSpaceSafeDiagnostics() {
      BotConnectionTelemetry var1 = new BotConnectionTelemetry();

      for (int var2 = 0; var2 < 10000; var2++) {
         var1.recordDiscarded("ClientboundPacket" + var2);
      }

      var1.recordRejected("FallbackPacket");
      var1.recordSuccessfulCallback();
      var1.recordFailedCallback();
      var1.recordKeepAliveAcknowledgement();
      var1.recordTeleportAcknowledgement();
      var1.markReadOnly();
      BotConnectionSnapshot var3 = var1.snapshot();
      Assertions.assertTrue(var3.open());
      Assertions.assertTrue(var3.readOnly());
      Assertions.assertEquals(10000L, var3.discardedPacketCount());
      Assertions.assertEquals(1L, var3.rejectedPacketCount());
      Assertions.assertEquals(1L, var3.successfulCallbackCount());
      Assertions.assertEquals(1L, var3.failedCallbackCount());
      Assertions.assertEquals(1L, var3.keepAliveAcknowledgementCount());
      Assertions.assertEquals(1L, var3.teleportAcknowledgementCount());
      Assertions.assertEquals("FallbackPacket", var3.lastPacketType());
      Assertions.assertEquals(BotConnectionCloseReason.NONE, var3.closeReason());
   }

   @Test
   void firstCloseReasonWinsAndCloseIsIdempotent() {
      BotConnectionTelemetry var1 = new BotConnectionTelemetry();
      Assertions.assertTrue(var1.markClosed(BotConnectionCloseReason.LISTENER_DISCONNECT));
      Assertions.assertFalse(var1.markClosed(BotConnectionCloseReason.EXPLICIT_CLOSE));
      BotConnectionSnapshot var2 = var1.snapshot();
      Assertions.assertFalse(var2.open());
      Assertions.assertEquals(BotConnectionCloseReason.LISTENER_DISCONNECT, var2.closeReason());
   }

   @Test
   void snapshotRejectsSensitiveOrIncoherentShapes() {
      Assertions.assertThrows(
         IllegalArgumentException.class, () -> new BotConnectionSnapshot(true, false, 0L, 0L, 0L, 0L, 0L, 0L, "", BotConnectionCloseReason.EXPLICIT_CLOSE)
      );
      Assertions.assertThrows(
         IllegalArgumentException.class, () -> new BotConnectionSnapshot(false, false, 0L, 0L, 0L, 0L, 0L, 0L, "", BotConnectionCloseReason.NONE)
      );
      Assertions.assertThrows(
         IllegalArgumentException.class, () -> new BotConnectionSnapshot(true, false, -1L, 0L, 0L, 0L, 0L, 0L, "", BotConnectionCloseReason.NONE)
      );
      Assertions.assertThrows(
         IllegalArgumentException.class, () -> new BotConnectionSnapshot(true, false, 0L, 0L, 0L, 0L, 0L, 0L, "Packet\npayload", BotConnectionCloseReason.NONE)
      );
   }
}
