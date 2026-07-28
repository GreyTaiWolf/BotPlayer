package io.github.greytaiwolf.botplayer.action.interaction;

import java.util.Objects;
import java.util.regex.Pattern;

public record ResourceId(String value) {
   public static final int MAX_LENGTH = 256;
   private static final Pattern PATTERN = Pattern.compile("[a-z0-9_.-]{1,64}:[a-z0-9/._-]{1,191}");

   public ResourceId(String value) {
      Objects.requireNonNull(value, "value");
      if (value.length() <= 256 && PATTERN.matcher(value).matches()) {
         this.value = value;
      } else {
         throw new IllegalArgumentException("resource id must be an explicit lowercase namespace:path identifier");
      }
   }

   @Override
   public String toString() {
      return this.value;
   }
}
