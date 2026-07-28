package io.github.greytaiwolf.botplayer.kernel;

import java.util.Objects;

public record BotConnectionSnapshot(
   boolean open,
   boolean readOnly,
   long discardedPacketCount,
   long rejectedPacketCount,
   long successfulCallbackCount,
   long failedCallbackCount,
   long keepAliveAcknowledgementCount,
   long teleportAcknowledgementCount,
   String lastPacketType,
   BotConnectionCloseReason closeReason
) {
   public static final int MAX_PACKET_TYPE_LENGTH = 96;

   public BotConnectionSnapshot(
      boolean open,
      boolean readOnly,
      long discardedPacketCount,
      long rejectedPacketCount,
      long successfulCallbackCount,
      long failedCallbackCount,
      long keepAliveAcknowledgementCount,
      long teleportAcknowledgementCount,
      String lastPacketType,
      BotConnectionCloseReason closeReason
   ) {
      requireNonNegative(discardedPacketCount, "discardedPacketCount");
      requireNonNegative(rejectedPacketCount, "rejectedPacketCount");
      requireNonNegative(successfulCallbackCount, "successfulCallbackCount");
      requireNonNegative(failedCallbackCount, "failedCallbackCount");
      requireNonNegative(keepAliveAcknowledgementCount, "keepAliveAcknowledgementCount");
      requireNonNegative(teleportAcknowledgementCount, "teleportAcknowledgementCount");
      lastPacketType = validatePacketType(lastPacketType);
      Objects.requireNonNull(closeReason, "closeReason");
      if (open && closeReason != BotConnectionCloseReason.NONE) {
         throw new IllegalArgumentException("an open connection cannot have a close reason");
      } else if (!open && closeReason == BotConnectionCloseReason.NONE) {
         throw new IllegalArgumentException("a closed connection must have a close reason");
      } else {
         this.open = open;
         this.readOnly = readOnly;
         this.discardedPacketCount = discardedPacketCount;
         this.rejectedPacketCount = rejectedPacketCount;
         this.successfulCallbackCount = successfulCallbackCount;
         this.failedCallbackCount = failedCallbackCount;
         this.keepAliveAcknowledgementCount = keepAliveAcknowledgementCount;
         this.teleportAcknowledgementCount = teleportAcknowledgementCount;
         this.lastPacketType = lastPacketType;
         this.closeReason = closeReason;
      }
   }

   static String validatePacketType(String var0) {
      Objects.requireNonNull(var0, "lastPacketType");
      if (var0.length() > 96) {
         throw new IllegalArgumentException("lastPacketType exceeds 96 characters");
      } else if (!var0.equals(var0.strip())) {
         throw new IllegalArgumentException("lastPacketType must not have surrounding whitespace");
      } else if (var0.codePoints().anyMatch(Character::isISOControl)) {
         throw new IllegalArgumentException("lastPacketType must not contain control characters");
      } else {
         return var0;
      }
   }

   private static void requireNonNegative(long var0, String var2) {
      if (var0 < 0L) {
         throw new IllegalArgumentException(var2 + " must not be negative");
      }
   }
}
