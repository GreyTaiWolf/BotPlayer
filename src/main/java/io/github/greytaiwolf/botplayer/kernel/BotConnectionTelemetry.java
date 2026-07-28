package io.github.greytaiwolf.botplayer.kernel;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

final class BotConnectionTelemetry {
   private final AtomicBoolean open = new AtomicBoolean(true);
   private final AtomicBoolean readOnly = new AtomicBoolean();
   private final AtomicLong discardedPackets = new AtomicLong();
   private final AtomicLong rejectedPackets = new AtomicLong();
   private final AtomicLong successfulCallbacks = new AtomicLong();
   private final AtomicLong failedCallbacks = new AtomicLong();
   private final AtomicLong keepAliveAcknowledgements = new AtomicLong();
   private final AtomicLong teleportAcknowledgements = new AtomicLong();
   private final AtomicReference<String> lastPacketType = new AtomicReference<>("");
   private final AtomicReference<BotConnectionCloseReason> closeReason = new AtomicReference<>(BotConnectionCloseReason.NONE);

   boolean markClosed(BotConnectionCloseReason var1) {
      Objects.requireNonNull(var1, "reason");
      if (var1 == BotConnectionCloseReason.NONE) {
         throw new IllegalArgumentException("close reason must not be NONE");
      } else if (!this.open.compareAndSet(true, false)) {
         return false;
      } else {
         this.closeReason.set(var1);
         return true;
      }
   }

   void markReadOnly() {
      this.readOnly.set(true);
   }

   void recordDiscarded(String var1) {
      this.lastPacketType.set(safePacketType(var1));
      incrementSaturated(this.discardedPackets);
   }

   void recordRejected(String var1) {
      this.lastPacketType.set(safePacketType(var1));
      incrementSaturated(this.rejectedPackets);
   }

   void recordSuccessfulCallback() {
      incrementSaturated(this.successfulCallbacks);
   }

   void recordFailedCallback() {
      incrementSaturated(this.failedCallbacks);
   }

   void recordKeepAliveAcknowledgement() {
      incrementSaturated(this.keepAliveAcknowledgements);
   }

   void recordTeleportAcknowledgement() {
      incrementSaturated(this.teleportAcknowledgements);
   }

   BotConnectionSnapshot snapshot() {
      boolean var1 = this.open.get();
      BotConnectionCloseReason var2 = this.closeReason.get();
      if (var1) {
         var2 = BotConnectionCloseReason.NONE;
      } else if (var2 == BotConnectionCloseReason.NONE) {
         var2 = BotConnectionCloseReason.EXPLICIT_CLOSE;
      }

      return new BotConnectionSnapshot(
         var1,
         this.readOnly.get(),
         this.discardedPackets.get(),
         this.rejectedPackets.get(),
         this.successfulCallbacks.get(),
         this.failedCallbacks.get(),
         this.keepAliveAcknowledgements.get(),
         this.teleportAcknowledgements.get(),
         this.lastPacketType.get(),
         var2
      );
   }

   private static String safePacketType(String var0) {
      Objects.requireNonNull(var0, "packetType");
      String var1 = var0.strip();
      StringBuilder var2 = new StringBuilder(Math.min(var1.length(), 96));
      int var3 = 0;

      while (var3 < var1.length() && var2.length() < 96) {
         int var4 = var1.codePointAt(var3);
         var3 += Character.charCount(var4);
         if (!Character.isISOControl(var4) && var2.length() + Character.charCount(var4) <= 96) {
            var2.appendCodePoint(var4);
         }
      }

      return var2.toString();
   }

   private static void incrementSaturated(AtomicLong var0) {
      long var1;
      do {
         var1 = var0.get();
         if (var1 == Long.MAX_VALUE) {
            return;
         }
      } while (!var0.compareAndSet(var1, var1 + 1L));
   }
}
