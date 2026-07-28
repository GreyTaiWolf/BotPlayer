package io.github.greytaiwolf.botplayer.inventory;

import java.util.Objects;
import java.util.Optional;

public final class BotInventorySession {
   private final InventorySessionToken token;
   private final BotInventoryLock lock;
   private InventorySessionState state = InventorySessionState.OPENING;
   private InventoryCloseReason closeReason;

   BotInventorySession(InventorySessionToken var1, BotInventoryLock var2) {
      this.token = Objects.requireNonNull(var1, "token");
      this.lock = Objects.requireNonNull(var2, "lock");
      if (!var2.heldBy(var1)) {
         throw new IllegalArgumentException("lock must be held by the session token");
      }
   }

   public InventorySessionToken token() {
      return this.token;
   }

   public InventorySessionState state() {
      return this.state;
   }

   public Optional<InventoryCloseReason> closeReason() {
      return Optional.ofNullable(this.closeReason);
   }

   public boolean writeLockHeld() {
      return this.lock.heldBy(this.token);
   }

   boolean markOpen() {
      if (this.state == InventorySessionState.OPEN) {
         return false;
      } else {
         this.state.requireTransitionTo(InventorySessionState.OPEN);
         this.state = InventorySessionState.OPEN;
         return true;
      }
   }

   boolean beginClosing(InventoryCloseReason var1) {
      Objects.requireNonNull(var1, "reason");
      if (this.state != InventorySessionState.CLOSING && this.state != InventorySessionState.CLOSED) {
         this.state.requireTransitionTo(InventorySessionState.CLOSING);
         this.closeReason = var1;
         this.state = InventorySessionState.CLOSING;
         return true;
      } else {
         return false;
      }
   }

   boolean markClosed() {
      if (this.state == InventorySessionState.CLOSED) {
         return false;
      } else {
         this.state.requireTransitionTo(InventorySessionState.CLOSED);
         this.state = InventorySessionState.CLOSED;
         return true;
      }
   }

   boolean releaseLock() {
      return this.lock.release(this.token);
   }
}
