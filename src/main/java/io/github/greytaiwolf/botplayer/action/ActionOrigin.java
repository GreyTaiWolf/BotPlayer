package io.github.greytaiwolf.botplayer.action;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record ActionOrigin(Optional<UUID> planId, Optional<UUID> skillRunId) {
   private static final UUID ZERO_UUID = new UUID(0L, 0L);
   private static final ActionOrigin NONE = new ActionOrigin(Optional.empty(), Optional.empty());

   public ActionOrigin(Optional<UUID> planId, Optional<UUID> skillRunId) {
      Objects.requireNonNull(planId, "planId");
      Objects.requireNonNull(skillRunId, "skillRunId");
      planId.ifPresent(var0 -> requireNonZero(var0, "planId"));
      skillRunId.ifPresent(var0 -> requireNonZero(var0, "skillRunId"));
      this.planId = planId;
      this.skillRunId = skillRunId;
   }

   public static ActionOrigin none() {
      return NONE;
   }

   public static ActionOrigin fromPlan(UUID var0) {
      return new ActionOrigin(Optional.of(Objects.requireNonNull(var0, "planId")), Optional.empty());
   }

   public static ActionOrigin fromSkillRun(UUID var0) {
      return new ActionOrigin(Optional.empty(), Optional.of(Objects.requireNonNull(var0, "skillRunId")));
   }

   public static ActionOrigin fromPlanAndSkillRun(UUID var0, UUID var1) {
      return new ActionOrigin(Optional.of(Objects.requireNonNull(var0, "planId")), Optional.of(Objects.requireNonNull(var1, "skillRunId")));
   }

   public boolean isUntracked() {
      return this.planId.isEmpty() && this.skillRunId.isEmpty();
   }

   private static void requireNonZero(UUID var0, String var1) {
      if (ZERO_UUID.equals(var0)) {
         throw new IllegalArgumentException(var1 + " must not be the zero UUID");
      }
   }
}
