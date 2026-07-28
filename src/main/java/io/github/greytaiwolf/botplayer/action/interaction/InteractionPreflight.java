package io.github.greytaiwolf.botplayer.action.interaction;

import io.github.greytaiwolf.botplayer.action.ActionFailureCode;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

public final class InteractionPreflight {
   private InteractionPreflight() {
   }

   public static InteractionPreflight.Result evaluate(InteractionPreflight.Requirements var0, InteractionPreflight.Observation var1) {
      Objects.requireNonNull(var0, "requirements");
      Objects.requireNonNull(var1, "observation");
      if (!var1.targetPresent()) {
         return InteractionPreflight.Result.denied(InteractionPreflight.Failure.TARGET_UNAVAILABLE);
      } else {
         ResourceId var2 = var1.targetDimension().orElseThrow();
         if (!var0.expectedDimension().equals(var1.actorDimension()) || !var0.expectedDimension().equals(var2)) {
            return InteractionPreflight.Result.denied(InteractionPreflight.Failure.DIMENSION_MISMATCH);
         } else if (!var1.targetChunkLoaded()) {
            return InteractionPreflight.Result.denied(InteractionPreflight.Failure.CHUNK_UNLOADED);
         } else if (!var1.targetFingerprintMatches()) {
            return InteractionPreflight.Result.denied(InteractionPreflight.Failure.TARGET_CHANGED);
         } else if (var1.distanceSquared().orElseThrow() > var1.allowedDistanceSquared()) {
            return InteractionPreflight.Result.denied(InteractionPreflight.Failure.OUT_OF_REACH);
         } else if (var0.lineOfSightRequired() && !var1.hasLineOfSight()) {
            return InteractionPreflight.Result.denied(InteractionPreflight.Failure.LINE_OF_SIGHT_BLOCKED);
         } else if (!var1.gameModeAllowed()) {
            return InteractionPreflight.Result.denied(InteractionPreflight.Failure.GAME_MODE_DENIED);
         } else if (var0.cooldownRequired() && !var1.cooldownReady()) {
            return InteractionPreflight.Result.denied(InteractionPreflight.Failure.COOLDOWN_ACTIVE);
         } else if (var0.expectedHeldItem().isPresent() && !var0.expectedHeldItem().orElseThrow().equals(var1.actualHeldItem())) {
            return InteractionPreflight.Result.denied(InteractionPreflight.Failure.HELD_ITEM_CHANGED);
         } else {
            return !var1.worldPermissionAllowed()
               ? InteractionPreflight.Result.denied(InteractionPreflight.Failure.WORLD_PERMISSION_DENIED)
               : InteractionPreflight.Result.allowedResult();
         }
      }
   }

   public static enum Failure {
      NONE(ActionFailureCode.NONE, ""),
      TARGET_UNAVAILABLE(ActionFailureCode.TARGET_UNAVAILABLE, "Interaction target is unavailable"),
      DIMENSION_MISMATCH(ActionFailureCode.PRECONDITION_FAILED, "Interaction target dimension changed"),
      CHUNK_UNLOADED(ActionFailureCode.TARGET_UNAVAILABLE, "Interaction target chunk is not loaded"),
      TARGET_CHANGED(ActionFailureCode.PRECONDITION_FAILED, "Interaction target fingerprint changed"),
      OUT_OF_REACH(ActionFailureCode.PRECONDITION_FAILED, "Interaction target is out of reach"),
      LINE_OF_SIGHT_BLOCKED(ActionFailureCode.PRECONDITION_FAILED, "Interaction target line of sight is blocked"),
      GAME_MODE_DENIED(ActionFailureCode.PRECONDITION_FAILED, "Current game mode does not allow this interaction"),
      COOLDOWN_ACTIVE(ActionFailureCode.PRECONDITION_FAILED, "Interaction cooldown is still active"),
      HELD_ITEM_CHANGED(ActionFailureCode.PRECONDITION_FAILED, "Held item fingerprint changed"),
      WORLD_PERMISSION_DENIED(ActionFailureCode.PERMISSION_DENIED, "World protection denied the interaction");

      private final ActionFailureCode actionFailureCode;
      private final String safeSummary;

      private Failure(ActionFailureCode nullxx, String nullxxx) {
         this.actionFailureCode = nullxx;
         this.safeSummary = nullxxx;
      }

      public ActionFailureCode actionFailureCode() {
         return this.actionFailureCode;
      }

      public String safeSummary() {
         return this.safeSummary;
      }
   }

   public static record Observation(
      ResourceId actorDimension,
      boolean targetPresent,
      Optional<ResourceId> targetDimension,
      boolean targetChunkLoaded,
      boolean targetFingerprintMatches,
      OptionalDouble distanceSquared,
      double allowedDistanceSquared,
      boolean hasLineOfSight,
      boolean gameModeAllowed,
      boolean cooldownReady,
      ItemStackFingerprint actualHeldItem,
      boolean worldPermissionAllowed
   ) {
      public Observation(
         ResourceId actorDimension,
         boolean targetPresent,
         Optional<ResourceId> targetDimension,
         boolean targetChunkLoaded,
         boolean targetFingerprintMatches,
         OptionalDouble distanceSquared,
         double allowedDistanceSquared,
         boolean hasLineOfSight,
         boolean gameModeAllowed,
         boolean cooldownReady,
         ItemStackFingerprint actualHeldItem,
         boolean worldPermissionAllowed
      ) {
         Objects.requireNonNull(actorDimension, "actorDimension");
         Objects.requireNonNull(targetDimension, "targetDimension");
         Objects.requireNonNull(distanceSquared, "distanceSquared");
         Objects.requireNonNull(actualHeldItem, "actualHeldItem");
         InteractionChecks.requireFiniteNonNegative(allowedDistanceSquared, "allowedDistanceSquared");
         if (distanceSquared.isPresent()) {
            InteractionChecks.requireFiniteNonNegative(distanceSquared.orElseThrow(), "distanceSquared");
         }

         if (!targetPresent || !targetDimension.isEmpty() && !distanceSquared.isEmpty()) {
            this.actorDimension = actorDimension;
            this.targetPresent = targetPresent;
            this.targetDimension = targetDimension;
            this.targetChunkLoaded = targetChunkLoaded;
            this.targetFingerprintMatches = targetFingerprintMatches;
            this.distanceSquared = distanceSquared;
            this.allowedDistanceSquared = allowedDistanceSquared;
            this.hasLineOfSight = hasLineOfSight;
            this.gameModeAllowed = gameModeAllowed;
            this.cooldownReady = cooldownReady;
            this.actualHeldItem = actualHeldItem;
            this.worldPermissionAllowed = worldPermissionAllowed;
         } else {
            throw new IllegalArgumentException("present target requires a dimension and distance");
         }
      }
   }

   public static record Requirements(
      ResourceId expectedDimension, boolean lineOfSightRequired, boolean cooldownRequired, Optional<ItemStackFingerprint> expectedHeldItem
   ) {
      public Requirements(ResourceId expectedDimension, boolean lineOfSightRequired, boolean cooldownRequired, Optional<ItemStackFingerprint> expectedHeldItem) {
         Objects.requireNonNull(expectedDimension, "expectedDimension");
         Objects.requireNonNull(expectedHeldItem, "expectedHeldItem");
         this.expectedDimension = expectedDimension;
         this.lineOfSightRequired = lineOfSightRequired;
         this.cooldownRequired = cooldownRequired;
         this.expectedHeldItem = expectedHeldItem;
      }
   }

   public static record Result(InteractionPreflight.Failure failure) {
      public Result(InteractionPreflight.Failure failure) {
         Objects.requireNonNull(failure, "failure");
         this.failure = failure;
      }

      public static InteractionPreflight.Result allowedResult() {
         return new InteractionPreflight.Result(InteractionPreflight.Failure.NONE);
      }

      public static InteractionPreflight.Result denied(InteractionPreflight.Failure var0) {
         Objects.requireNonNull(var0, "failure");
         if (var0 == InteractionPreflight.Failure.NONE) {
            throw new IllegalArgumentException("denied result requires a failure");
         } else {
            return new InteractionPreflight.Result(var0);
         }
      }

      public boolean allowed() {
         return this.failure == InteractionPreflight.Failure.NONE;
      }

      public ActionFailureCode actionFailureCode() {
         return this.failure.actionFailureCode();
      }

      public String safeSummary() {
         return this.failure.safeSummary();
      }
   }
}
