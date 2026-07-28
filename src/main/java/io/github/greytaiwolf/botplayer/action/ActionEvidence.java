package io.github.greytaiwolf.botplayer.action;

import java.util.Objects;
import java.util.regex.Pattern;

public record ActionEvidence(String key, String value) {
   public static final int MAX_KEY_LENGTH = 48;
   public static final int MAX_VALUE_LENGTH = 160;
   private static final Pattern KEY_PATTERN = Pattern.compile("[a-z][a-z0-9_.-]{0,47}");

   public ActionEvidence(String key, String value) {
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(value, "value");
      if (!KEY_PATTERN.matcher(key).matches()) {
         throw new IllegalArgumentException("evidence key must be 1-48 lowercase safe ASCII characters");
      } else if (value.length() > 160) {
         throw new IllegalArgumentException("evidence value exceeds 160 characters");
      } else if (!value.equals(value.strip())) {
         throw new IllegalArgumentException("evidence value must not have surrounding whitespace");
      } else if (value.codePoints().anyMatch(Character::isISOControl)) {
         throw new IllegalArgumentException("evidence value must not contain control characters");
      } else {
         this.key = key;
         this.value = value;
      }
   }
}
