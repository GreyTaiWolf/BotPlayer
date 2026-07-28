package io.github.greytaiwolf.botplayer.action.interaction;

import java.util.Objects;
import java.util.UUID;

public record EntityTargetFingerprint(ResourceId dimension, UUID entityId, ResourceId entityType) {
   public EntityTargetFingerprint(ResourceId dimension, UUID entityId, ResourceId entityType) {
      Objects.requireNonNull(dimension, "dimension");
      entityId = InteractionChecks.requireNonZeroUuid(entityId, "entityId");
      Objects.requireNonNull(entityType, "entityType");
      this.dimension = dimension;
      this.entityId = entityId;
      this.entityType = entityType;
   }
}
