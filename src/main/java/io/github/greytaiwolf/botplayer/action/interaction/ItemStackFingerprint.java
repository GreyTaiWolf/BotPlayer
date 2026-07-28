package io.github.greytaiwolf.botplayer.action.interaction;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

public record ItemStackFingerprint(Optional<ResourceId> itemId, int count, int damage, Optional<String> componentsDigest) {
   public static final int SHA_256_HEX_LENGTH = 64;
   private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
   private static final ItemStackFingerprint EMPTY = new ItemStackFingerprint(Optional.empty(), 0, 0, Optional.empty());

   public ItemStackFingerprint(Optional<ResourceId> itemId, int count, int damage, Optional<String> componentsDigest) {
      Objects.requireNonNull(itemId, "itemId");
      Objects.requireNonNull(componentsDigest, "componentsDigest");
      if (itemId.isEmpty()) {
         if (count != 0 || damage != 0 || componentsDigest.isPresent()) {
            throw new IllegalArgumentException("empty item fingerprint must have zero count/damage and no digest");
         }
      } else {
         if (count < 1) {
            throw new IllegalArgumentException("non-empty item fingerprint must have positive count");
         }

         if (damage < 0) {
            throw new IllegalArgumentException("item damage must not be negative");
         }

         String var5 = componentsDigest.orElseThrow(
            () -> new IllegalArgumentException(
               "non-empty item fingerprint requires a components digest"));
         if (!SHA_256.matcher(var5).matches()) {
            throw new IllegalArgumentException("components digest must be 64 lower-case hexadecimal characters");
         }
      }

      this.itemId = itemId;
      this.count = count;
      this.damage = damage;
      this.componentsDigest = componentsDigest;
   }

   public static ItemStackFingerprint empty() {
      return EMPTY;
   }

   public static ItemStackFingerprint of(ResourceId var0, int var1, int var2, String var3) {
      return new ItemStackFingerprint(
         Optional.of(Objects.requireNonNull(var0, "itemId")), var1, var2, Optional.of(Objects.requireNonNull(var3, "componentsDigest"))
      );
   }

   public boolean isEmpty() {
      return this.itemId.isEmpty();
   }

   public boolean sameItemAndComponents(ItemStackFingerprint other) {
      Objects.requireNonNull(other, "other");
      return this.itemId.equals(other.itemId)
         && this.damage == other.damage
         && this.componentsDigest.equals(other.componentsDigest);
   }
}
