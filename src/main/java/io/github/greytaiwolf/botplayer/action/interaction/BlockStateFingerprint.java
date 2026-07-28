package io.github.greytaiwolf.botplayer.action.interaction;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.Map.Entry;
import java.util.regex.Pattern;

public record BlockStateFingerprint(ResourceId blockId, Map<String, String> properties) {
   public static final int MAX_PROPERTIES = 64;
   public static final int MAX_PROPERTY_NAME_LENGTH = 64;
   public static final int MAX_PROPERTY_VALUE_LENGTH = 128;
   public static final int MAX_TOTAL_PROPERTY_CHARACTERS = 2048;
   private static final Pattern PROPERTY_NAME = Pattern.compile("[a-z0-9_.-]{1,64}");
   private static final Pattern PROPERTY_VALUE = Pattern.compile("[a-z0-9_.:/-]{1,128}");

   public BlockStateFingerprint(ResourceId blockId, Map<String, String> properties) {
      Objects.requireNonNull(blockId, "blockId");
      Objects.requireNonNull(properties, "properties");
      if (properties.size() > 64) {
         throw new IllegalArgumentException("block state exceeds 64 properties");
      } else {
         TreeMap<String, String> var3 = new TreeMap<>();
         int var4 = 0;

         for (Entry<String, String> var6 : properties.entrySet()) {
            String var7 = Objects.requireNonNull(var6.getKey(), "property name");
            String var8 = Objects.requireNonNull(var6.getValue(), "property value");
            if (!PROPERTY_NAME.matcher(var7).matches()) {
               throw new IllegalArgumentException("invalid block-state property name");
            }

            if (!PROPERTY_VALUE.matcher(var8).matches()) {
               throw new IllegalArgumentException("invalid block-state property value");
            }

            var4 = Math.addExact(var4, var7.length());
            var4 = Math.addExact(var4, var8.length());
            if (var4 > 2048) {
               throw new IllegalArgumentException("block-state properties exceed 2048 characters");
            }

            var3.put(var7, var8);
         }

         SortedMap<String, String> var9 = Collections.unmodifiableSortedMap(var3);
         this.blockId = blockId;
         this.properties = var9;
      }
   }
}
