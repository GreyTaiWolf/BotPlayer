package io.github.greytaiwolf.botplayer.action.interaction;

import java.util.Objects;

public record BlockTargetFingerprint(ResourceId dimension, BlockCoordinates position, BlockStateFingerprint state) {
   public BlockTargetFingerprint(ResourceId dimension, BlockCoordinates position, BlockStateFingerprint state) {
      Objects.requireNonNull(dimension, "dimension");
      Objects.requireNonNull(position, "position");
      Objects.requireNonNull(state, "state");
      this.dimension = dimension;
      this.position = position;
      this.state = state;
   }
}
