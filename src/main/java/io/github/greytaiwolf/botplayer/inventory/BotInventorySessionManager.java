package io.github.greytaiwolf.botplayer.inventory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public final class BotInventorySessionManager {
   public static final int DEFAULT_CLOSED_TOKEN_CAPACITY = 256;
   public static final int MAX_CLOSED_TOKEN_CAPACITY = 4096;
   private static final int MAX_NONCE_ATTEMPTS = 8;
   private final Thread ownerThread;
   private final InventoryPermissionValidator permissionValidator;
   private final InventoryDistanceValidator distanceValidator;
   private final InventoryLifecycleValidator lifecycleValidator;
   private final Supplier<UUID> nonceSupplier;
   private final int closedTokenCapacity;
   private final Map<UUID, BotInventorySession> sessionsByBot = new LinkedHashMap<>();
   private final Map<UUID, BotInventorySession> sessionsByViewer = new LinkedHashMap<>();
   private final Map<InventorySessionToken, InventoryCloseReason> closedTokens = new LinkedHashMap<>();
   private final InventoryMutationGate mutationGate = this::checkMutation;

   public BotInventorySessionManager(InventoryPermissionValidator var1, InventoryDistanceValidator var2, InventoryLifecycleValidator var3) {
      this(var1, var2, var3, UUID::randomUUID, 256);
   }

   BotInventorySessionManager(
      InventoryPermissionValidator var1, InventoryDistanceValidator var2, InventoryLifecycleValidator var3, Supplier<UUID> var4, int var5
   ) {
      this.ownerThread = Thread.currentThread();
      this.permissionValidator = Objects.requireNonNull(var1, "permissionValidator");
      this.distanceValidator = Objects.requireNonNull(var2, "distanceValidator");
      this.lifecycleValidator = Objects.requireNonNull(var3, "lifecycleValidator");
      this.nonceSupplier = Objects.requireNonNull(var4, "nonceSupplier");
      if (var5 > 0 && var5 <= 4096) {
         this.closedTokenCapacity = var5;
      } else {
         throw new IllegalArgumentException("closedTokenCapacity must be between 1 and 4096");
      }
   }

   public BotInventorySessionManager.OpenResult open(UUID var1, long var2, UUID var4) {
      this.requireOwnerThread();
      BotInventorySessionManager.OpenStatus status = this.probeOpen(var1, var2, var4);
      if (status != BotInventorySessionManager.OpenStatus.OPENING) {
         Optional<BotInventorySession> existing = switch (status) {
            case EXISTING_SESSION, SESSION_CLOSING -> Optional.ofNullable(this.sessionsByBot.get(var1));
            case VIEWER_BUSY -> Optional.ofNullable(this.sessionsByViewer.get(var4));
            default -> Optional.empty();
         };
         return new BotInventorySessionManager.OpenResult(status, existing);
      }

      UUID nonce = this.nextNonce();
      if (nonce == null) {
         return BotInventorySessionManager.OpenResult.rejected(BotInventorySessionManager.OpenStatus.NONCE_UNAVAILABLE);
      }
      InventorySessionToken token = new InventorySessionToken(var1, var2, var4, nonce);
      BotInventoryLock lock = new BotInventoryLock(token);
      BotInventorySession session = new BotInventorySession(token, lock);
      this.sessionsByBot.put(var1, session);
      this.sessionsByViewer.put(var4, session);
      return new BotInventorySessionManager.OpenResult(BotInventorySessionManager.OpenStatus.OPENING, Optional.of(session));
   }

   /**
    * 只读检查一次开包请求；它不会分配 nonce、会话或写锁。
    *
    * <p>生命周期层用它先排除无权限、超距和现有查看者，再让动作运行时收口菜单事务。
    */
   public BotInventorySessionManager.OpenStatus probeOpen(UUID botId, long botGeneration, UUID viewerId) {
      this.requireOwnerThread();
      validateIdentity(botId, botGeneration, viewerId);
      if (!this.permissionValidator.canWriteInventory(botId, viewerId)) {
         return BotInventorySessionManager.OpenStatus.PERMISSION_DENIED;
      }
      InventoryLifecycleValidator.LifecycleStatus lifecycle = this.validateLifecycle(botId, botGeneration);
      if (!lifecycle.active()) {
         return mapOpenStatus(lifecycle);
      }
      InventoryDistanceValidator.SpatialStatus spatial = this.validateDistance(botId, viewerId);
      if (!spatial.valid()) {
         return mapOpenStatus(spatial);
      }

      BotInventorySession botSession = this.sessionsByBot.get(botId);
      if (botSession != null) {
         InventorySessionToken token = botSession.token();
         if (token.viewerId().equals(viewerId) && token.botGeneration() == botGeneration) {
            return botSession.state() == InventorySessionState.CLOSING
               ? BotInventorySessionManager.OpenStatus.SESSION_CLOSING
               : BotInventorySessionManager.OpenStatus.EXISTING_SESSION;
         }
         return BotInventorySessionManager.OpenStatus.BOT_LOCKED;
      }
      return this.sessionsByViewer.containsKey(viewerId)
         ? BotInventorySessionManager.OpenStatus.VIEWER_BUSY
         : BotInventorySessionManager.OpenStatus.OPENING;
   }

   public BotInventorySessionManager.OpenConfirmationStatus markOpened(InventorySessionToken var1) {
      this.requireOwnerThread();
      Objects.requireNonNull(var1, "token");
      BotInventorySession var2 = this.currentExact(var1);
      if (var2 == null) {
         return this.closedTokens.containsKey(var1)
            ? BotInventorySessionManager.OpenConfirmationStatus.ALREADY_CLOSED
            : BotInventorySessionManager.OpenConfirmationStatus.NOT_FOUND;
      } else {
         return switch (var2.state()) {
            case OPENING -> {
               var2.markOpen();
               yield BotInventorySessionManager.OpenConfirmationStatus.OPENED;
            }
            case OPEN -> BotInventorySessionManager.OpenConfirmationStatus.ALREADY_OPEN;
            case CLOSING -> BotInventorySessionManager.OpenConfirmationStatus.CLOSING;
            case CLOSED -> BotInventorySessionManager.OpenConfirmationStatus.ALREADY_CLOSED;
         };
      }
   }

   public BotInventorySessionManager.ValidationStatus revalidate(InventorySessionToken var1) {
      this.requireOwnerThread();
      Objects.requireNonNull(var1, "token");
      BotInventorySession var2 = this.currentExact(var1);
      if (var2 == null) {
         return this.closedTokens.containsKey(var1)
            ? BotInventorySessionManager.ValidationStatus.CLOSED
            : BotInventorySessionManager.ValidationStatus.NOT_FOUND;
      } else if (var2.state() == InventorySessionState.CLOSING) {
         return BotInventorySessionManager.ValidationStatus.CLOSING;
      } else if (!this.permissionValidator.canWriteInventory(var1.botId(), var1.viewerId())) {
         this.forceClose(var1, InventoryCloseReason.PERMISSION_REVOKED);
         return BotInventorySessionManager.ValidationStatus.PERMISSION_DENIED;
      } else {
         InventoryLifecycleValidator.LifecycleStatus var3 = this.validateLifecycle(var1.botId(), var1.botGeneration());
         if (!var3.active()) {
            this.forceClose(var1, closeReason(var3));
            return mapValidationStatus(var3);
         } else {
            InventoryDistanceValidator.SpatialStatus var4 = this.validateDistance(var1.botId(), var1.viewerId());
            if (!var4.valid()) {
               this.forceClose(var1, closeReason(var4));
               return mapValidationStatus(var4);
            } else {
               return BotInventorySessionManager.ValidationStatus.VALID;
            }
         }
      }
   }

   public BotInventorySessionManager.ForceCloseStatus forceClose(InventorySessionToken var1, InventoryCloseReason var2) {
      this.requireOwnerThread();
      Objects.requireNonNull(var1, "token");
      Objects.requireNonNull(var2, "reason");
      BotInventorySession var3 = this.currentExact(var1);
      if (var3 == null) {
         return this.closedTokens.containsKey(var1)
            ? BotInventorySessionManager.ForceCloseStatus.ALREADY_CLOSED
            : BotInventorySessionManager.ForceCloseStatus.NOT_FOUND;
      } else if (var3.beginClosing(var2)) {
         return BotInventorySessionManager.ForceCloseStatus.CLOSE_REQUESTED;
      } else {
         return var3.state() == InventorySessionState.CLOSED
            ? BotInventorySessionManager.ForceCloseStatus.ALREADY_CLOSED
            : BotInventorySessionManager.ForceCloseStatus.ALREADY_CLOSING;
      }
   }

   public BotInventorySessionManager.ForceCloseStatus forceCloseBot(UUID var1, long var2, InventoryCloseReason var4) {
      this.requireOwnerThread();
      Objects.requireNonNull(var1, "botId");
      Objects.requireNonNull(var4, "reason");
      if (var2 <= 0L) {
         throw new IllegalArgumentException("expectedGeneration must be positive");
      } else {
         BotInventorySession var5 = this.sessionsByBot.get(var1);
         if (var5 == null) {
            return BotInventorySessionManager.ForceCloseStatus.NOT_FOUND;
         } else {
            return var5.token().botGeneration() != var2 ? BotInventorySessionManager.ForceCloseStatus.GENERATION_MISMATCH : this.forceClose(var5.token(), var4);
         }
      }
   }

   public BotInventorySessionManager.ForceCloseStatus forceCloseViewer(UUID var1, InventoryCloseReason var2) {
      this.requireOwnerThread();
      Objects.requireNonNull(var1, "viewerId");
      Objects.requireNonNull(var2, "reason");
      BotInventorySession var3 = this.sessionsByViewer.get(var1);
      return var3 == null ? BotInventorySessionManager.ForceCloseStatus.NOT_FOUND : this.forceClose(var3.token(), var2);
   }

   public List<InventorySessionToken> forceCloseAll(InventoryCloseReason var1) {
      this.requireOwnerThread();
      Objects.requireNonNull(var1, "reason");
      ArrayList<InventorySessionToken> var2 = new ArrayList<>();

      for (BotInventorySession var4 : List.copyOf(this.sessionsByBot.values())) {
         if (this.forceClose(var4.token(), var1) == BotInventorySessionManager.ForceCloseStatus.CLOSE_REQUESTED) {
            var2.add(var4.token());
         }
      }

      return List.copyOf(var2);
   }

   public BotInventorySessionManager.CloseConfirmationStatus confirmClosed(InventorySessionToken var1) {
      this.requireOwnerThread();
      Objects.requireNonNull(var1, "token");
      BotInventorySession var2 = this.currentExact(var1);
      if (var2 == null) {
         return this.closedTokens.containsKey(var1)
            ? BotInventorySessionManager.CloseConfirmationStatus.ALREADY_CLOSED
            : BotInventorySessionManager.CloseConfirmationStatus.NOT_FOUND;
      } else if (var2.state() != InventorySessionState.CLOSING) {
         return BotInventorySessionManager.CloseConfirmationStatus.NOT_CLOSING;
      } else {
         var2.markClosed();
         if (!var2.releaseLock()) {
            throw new IllegalStateException("inventory session lock was not held at close");
         } else {
            this.sessionsByBot.remove(var1.botId(), var2);
            this.sessionsByViewer.remove(var1.viewerId(), var2);
            this.rememberClosed(var1, var2.closeReason().orElseThrow());
            return BotInventorySessionManager.CloseConfirmationStatus.CLOSED;
         }
      }
   }

   public BotInventorySessionManager.CloseConfirmationStatus failOpen(InventorySessionToken var1) {
      this.requireOwnerThread();
      BotInventorySessionManager.ForceCloseStatus var2 = this.forceClose(var1, InventoryCloseReason.OPEN_FAILED);
      if (var2 == BotInventorySessionManager.ForceCloseStatus.NOT_FOUND) {
         return BotInventorySessionManager.CloseConfirmationStatus.NOT_FOUND;
      } else {
         return var2 == BotInventorySessionManager.ForceCloseStatus.ALREADY_CLOSED
            ? BotInventorySessionManager.CloseConfirmationStatus.ALREADY_CLOSED
            : this.confirmClosed(var1);
      }
   }

   public InventoryMutationGate mutationGate() {
      return this.mutationGate;
   }

   public Optional<BotInventorySession> sessionForBot(UUID var1) {
      this.requireOwnerThread();
      Objects.requireNonNull(var1, "botId");
      return Optional.ofNullable(this.sessionsByBot.get(var1));
   }

   public Optional<BotInventorySession> sessionForViewer(UUID var1) {
      this.requireOwnerThread();
      Objects.requireNonNull(var1, "viewerId");
      return Optional.ofNullable(this.sessionsByViewer.get(var1));
   }

   public int activeSessionCount() {
      this.requireOwnerThread();
      return this.sessionsByBot.size();
   }

   public int closedTokenCount() {
      this.requireOwnerThread();
      return this.closedTokens.size();
   }

   private InventoryMutationGate.MutationStatus checkMutation(UUID var1, long var2) {
      this.requireOwnerThread();
      Objects.requireNonNull(var1, "botId");
      if (var2 <= 0L) {
         throw new IllegalArgumentException("expectedGeneration must be positive");
      } else {
         InventoryLifecycleValidator.LifecycleStatus var4 = this.validateLifecycle(var1, var2);
         if (!var4.active()) {
            return mapMutationStatus(var4);
         } else {
            BotInventorySession var5 = this.sessionsByBot.get(var1);
            if (var5 == null) {
               return InventoryMutationGate.MutationStatus.ALLOWED;
            } else {
               return var5.token().botGeneration() == var2
                  ? InventoryMutationGate.MutationStatus.VIEWER_WRITE_LOCKED
                  : InventoryMutationGate.MutationStatus.STALE_VIEWER_LOCK;
            }
         }
      }
   }

   private BotInventorySession currentExact(InventorySessionToken var1) {
      BotInventorySession var2 = this.sessionsByBot.get(var1.botId());
      return var2 != null && var2.token().equals(var1) ? var2 : null;
   }

   private UUID nextNonce() {
      for (int var1 = 0; var1 < 8; var1++) {
         UUID var2 = this.nonceSupplier.get();
         if (var2 != null && !var2.equals(new UUID(0L, 0L)) && !this.nonceInUse(var2)) {
            return var2;
         }
      }

      return null;
   }

   private boolean nonceInUse(UUID var1) {
      return this.sessionsByBot.values().stream().anyMatch(var1x -> var1x.token().nonce().equals(var1))
         || this.closedTokens.keySet().stream().anyMatch(var1x -> var1x.nonce().equals(var1));
   }

   private void rememberClosed(InventorySessionToken var1, InventoryCloseReason var2) {
      this.closedTokens.put(var1, var2);

      while (this.closedTokens.size() > this.closedTokenCapacity) {
         InventorySessionToken var3 = this.closedTokens.keySet().iterator().next();
         this.closedTokens.remove(var3);
      }
   }

   private InventoryLifecycleValidator.LifecycleStatus validateLifecycle(UUID var1, long var2) {
      return InventoryLifecycleValidator.requireResult(this.lifecycleValidator.validate(var1, var2));
   }

   private InventoryDistanceValidator.SpatialStatus validateDistance(UUID var1, UUID var2) {
      return InventoryDistanceValidator.requireResult(this.distanceValidator.validate(var1, var2));
   }

   private void requireOwnerThread() {
      if (Thread.currentThread() != this.ownerThread) {
         throw new IllegalStateException("Bot inventory session access must run on the owner server thread");
      }
   }

   private static void validateIdentity(UUID var0, long var1, UUID var3) {
      Objects.requireNonNull(var0, "botId");
      Objects.requireNonNull(var3, "viewerId");
      if (var0.equals(new UUID(0L, 0L))) {
         throw new IllegalArgumentException("botId must not be zero");
      } else if (var3.equals(new UUID(0L, 0L))) {
         throw new IllegalArgumentException("viewerId must not be zero");
      } else if (var1 <= 0L) {
         throw new IllegalArgumentException("botGeneration must be positive");
      }
   }

   private static BotInventorySessionManager.OpenStatus mapOpenStatus(InventoryLifecycleValidator.LifecycleStatus var0) {
      return switch (var0) {
         case ACTIVE -> throw new IllegalArgumentException("ACTIVE is not a rejection");
         case UNKNOWN_BOT -> BotInventorySessionManager.OpenStatus.UNKNOWN_BOT;
         case NOT_ACTIVE -> BotInventorySessionManager.OpenStatus.BOT_NOT_ACTIVE;
         case STALE_GENERATION -> BotInventorySessionManager.OpenStatus.STALE_GENERATION;
         case INVALID_INSTANCE -> BotInventorySessionManager.OpenStatus.INVALID_INSTANCE;
         case SERVER_STOPPING -> BotInventorySessionManager.OpenStatus.SERVER_STOPPING;
      };
   }

   private static BotInventorySessionManager.OpenStatus mapOpenStatus(InventoryDistanceValidator.SpatialStatus var0) {
      return switch (var0) {
         case IN_RANGE -> throw new IllegalArgumentException("IN_RANGE is not a rejection");
         case OUT_OF_RANGE -> BotInventorySessionManager.OpenStatus.OUT_OF_RANGE;
         case DIFFERENT_DIMENSION -> BotInventorySessionManager.OpenStatus.DIFFERENT_DIMENSION;
         case VIEWER_NOT_ALIVE -> BotInventorySessionManager.OpenStatus.VIEWER_NOT_ALIVE;
         case BOT_NOT_ALIVE -> BotInventorySessionManager.OpenStatus.BOT_NOT_ALIVE;
      };
   }

   private static BotInventorySessionManager.ValidationStatus mapValidationStatus(InventoryLifecycleValidator.LifecycleStatus var0) {
      return switch (var0) {
         case ACTIVE -> BotInventorySessionManager.ValidationStatus.VALID;
         case UNKNOWN_BOT -> BotInventorySessionManager.ValidationStatus.UNKNOWN_BOT;
         case NOT_ACTIVE -> BotInventorySessionManager.ValidationStatus.BOT_NOT_ACTIVE;
         case STALE_GENERATION -> BotInventorySessionManager.ValidationStatus.STALE_GENERATION;
         case INVALID_INSTANCE -> BotInventorySessionManager.ValidationStatus.INVALID_INSTANCE;
         case SERVER_STOPPING -> BotInventorySessionManager.ValidationStatus.SERVER_STOPPING;
      };
   }

   private static BotInventorySessionManager.ValidationStatus mapValidationStatus(InventoryDistanceValidator.SpatialStatus var0) {
      return switch (var0) {
         case IN_RANGE -> BotInventorySessionManager.ValidationStatus.VALID;
         case OUT_OF_RANGE -> BotInventorySessionManager.ValidationStatus.OUT_OF_RANGE;
         case DIFFERENT_DIMENSION -> BotInventorySessionManager.ValidationStatus.DIFFERENT_DIMENSION;
         case VIEWER_NOT_ALIVE -> BotInventorySessionManager.ValidationStatus.VIEWER_NOT_ALIVE;
         case BOT_NOT_ALIVE -> BotInventorySessionManager.ValidationStatus.BOT_NOT_ALIVE;
      };
   }

   private static InventoryMutationGate.MutationStatus mapMutationStatus(InventoryLifecycleValidator.LifecycleStatus var0) {
      return switch (var0) {
         case ACTIVE -> InventoryMutationGate.MutationStatus.ALLOWED;
         case UNKNOWN_BOT -> InventoryMutationGate.MutationStatus.UNKNOWN_BOT;
         case NOT_ACTIVE -> InventoryMutationGate.MutationStatus.BOT_NOT_ACTIVE;
         case STALE_GENERATION -> InventoryMutationGate.MutationStatus.STALE_GENERATION;
         case INVALID_INSTANCE -> InventoryMutationGate.MutationStatus.INVALID_INSTANCE;
         case SERVER_STOPPING -> InventoryMutationGate.MutationStatus.SERVER_STOPPING;
      };
   }

   private static InventoryCloseReason closeReason(InventoryLifecycleValidator.LifecycleStatus var0) {
      return switch (var0) {
         case ACTIVE -> throw new IllegalArgumentException("ACTIVE has no close reason");
         case UNKNOWN_BOT, NOT_ACTIVE -> InventoryCloseReason.BOT_NOT_ACTIVE;
         case STALE_GENERATION -> InventoryCloseReason.STALE_GENERATION;
         case INVALID_INSTANCE -> InventoryCloseReason.INVALID_BOT_INSTANCE;
         case SERVER_STOPPING -> InventoryCloseReason.SERVER_STOPPING;
      };
   }

   private static InventoryCloseReason closeReason(InventoryDistanceValidator.SpatialStatus var0) {
      return switch (var0) {
         case IN_RANGE -> throw new IllegalArgumentException("IN_RANGE has no close reason");
         case OUT_OF_RANGE -> InventoryCloseReason.OUT_OF_RANGE;
         case DIFFERENT_DIMENSION -> InventoryCloseReason.DIMENSION_CHANGED;
         case VIEWER_NOT_ALIVE -> InventoryCloseReason.VIEWER_INVALID;
         case BOT_NOT_ALIVE -> InventoryCloseReason.BOT_NOT_ACTIVE;
      };
   }

   public static enum CloseConfirmationStatus {
      CLOSED,
      ALREADY_CLOSED,
      NOT_CLOSING,
      NOT_FOUND;
   }

   public static enum ForceCloseStatus {
      CLOSE_REQUESTED,
      ALREADY_CLOSING,
      ALREADY_CLOSED,
      GENERATION_MISMATCH,
      NOT_FOUND;
   }

   public static enum OpenConfirmationStatus {
      OPENED,
      ALREADY_OPEN,
      CLOSING,
      ALREADY_CLOSED,
      NOT_FOUND;
   }

   public static record OpenResult(BotInventorySessionManager.OpenStatus status, Optional<BotInventorySession> session) {
      public OpenResult(BotInventorySessionManager.OpenStatus status, Optional<BotInventorySession> session) {
         Objects.requireNonNull(status, "status");
         session = Objects.requireNonNull(session, "session");
         if ((
               status.accepted()
                  || status == BotInventorySessionManager.OpenStatus.SESSION_CLOSING
                  || status == BotInventorySessionManager.OpenStatus.VIEWER_BUSY
            )
            != session.isPresent()) {
            throw new IllegalArgumentException("session presence does not match open status " + status);
         } else {
            this.status = status;
            this.session = session;
         }
      }

      private static BotInventorySessionManager.OpenResult rejected(BotInventorySessionManager.OpenStatus var0) {
         return new BotInventorySessionManager.OpenResult(var0, Optional.empty());
      }
   }

   public static enum OpenStatus {
      OPENING,
      EXISTING_SESSION,
      SESSION_CLOSING,
      PERMISSION_DENIED,
      UNKNOWN_BOT,
      BOT_NOT_ACTIVE,
      STALE_GENERATION,
      INVALID_INSTANCE,
      SERVER_STOPPING,
      OUT_OF_RANGE,
      DIFFERENT_DIMENSION,
      VIEWER_NOT_ALIVE,
      BOT_NOT_ALIVE,
      BOT_LOCKED,
      VIEWER_BUSY,
      NONCE_UNAVAILABLE;

      public boolean accepted() {
         return this == OPENING || this == EXISTING_SESSION;
      }
   }

   public static enum ValidationStatus {
      VALID,
      CLOSING,
      CLOSED,
      NOT_FOUND,
      PERMISSION_DENIED,
      UNKNOWN_BOT,
      BOT_NOT_ACTIVE,
      STALE_GENERATION,
      INVALID_INSTANCE,
      SERVER_STOPPING,
      OUT_OF_RANGE,
      DIFFERENT_DIMENSION,
      VIEWER_NOT_ALIVE,
      BOT_NOT_ALIVE;

      public boolean valid() {
         return this == VALID;
      }
   }
}
