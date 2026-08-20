package io.github.greytaiwolf.botplayer.action;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record ActionOrigin(
        Optional<UUID> planId,
        Optional<UUID> skillRunId,
        Optional<ControllerOrigin> controller,
        Optional<TechniqueChildOrigin> techniqueChild) {
    private static final UUID ZERO_UUID = new UUID(0L, 0L);
    private static final ActionOrigin NONE = new ActionOrigin(
            Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty());

    public ActionOrigin(
            Optional<UUID> planId,
            Optional<UUID> skillRunId,
            Optional<ControllerOrigin> controller,
            Optional<TechniqueChildOrigin> techniqueChild) {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(skillRunId, "skillRunId");
        Objects.requireNonNull(controller, "controller");
        Objects.requireNonNull(techniqueChild, "techniqueChild");
        planId.ifPresent(var0 -> requireNonZero(var0, "planId"));
        skillRunId.ifPresent(var0 -> requireNonZero(var0, "skillRunId"));
        if (techniqueChild.isPresent() && skillRunId.isEmpty()) {
            throw new IllegalArgumentException(
                    "a technique child origin requires a skillRunId");
        }
        if (techniqueChild.isPresent() && controller.isPresent()) {
            throw new IllegalArgumentException(
                    "an Action cannot be both controller-owned and a technique child");
        }
        this.planId = planId;
        this.skillRunId = skillRunId;
        this.controller = controller;
        this.techniqueChild = techniqueChild;
    }

    public ActionOrigin(
            Optional<UUID> planId,
            Optional<UUID> skillRunId,
            Optional<ControllerOrigin> controller) {
        this(planId, skillRunId, controller, Optional.empty());
    }

    public ActionOrigin(
            Optional<UUID> planId, Optional<UUID> skillRunId) {
        this(planId, skillRunId, Optional.empty(), Optional.empty());
   }

   public static ActionOrigin none() {
      return NONE;
   }

    public static ActionOrigin fromPlan(UUID var0) {
        return new ActionOrigin(
                Optional.of(Objects.requireNonNull(var0, "planId")),
                Optional.empty(),
                Optional.empty(), Optional.empty());
   }

    public static ActionOrigin fromSkillRun(UUID var0) {
        return new ActionOrigin(
                Optional.empty(),
                Optional.of(Objects.requireNonNull(var0, "skillRunId")),
                Optional.empty(), Optional.empty());
   }

    public static ActionOrigin fromPlanAndSkillRun(UUID var0, UUID var1) {
        return new ActionOrigin(
                Optional.of(Objects.requireNonNull(var0, "planId")),
                Optional.of(Objects.requireNonNull(var1, "skillRunId")),
                Optional.empty(), Optional.empty());
   }

    public static ActionOrigin fromController(
            ControllerKind kind, UUID controllerRunId) {
        return new ActionOrigin(
                Optional.empty(),
                Optional.empty(),
                Optional.of(new ControllerOrigin(kind, controllerRunId)),
                Optional.empty());
    }

    /**
     * Creates provenance for one Action prebound to an exact Technique child.
     *
     * <p>The caller still has to prove the bot/generation, ticket channels,
     * deadline and idempotency key at the Technique Action port.  Keeping this
     * helper scalar prevents an Action from carrying a mutable Technique
     * runtime reference.
     */
    public static ActionOrigin fromTechniqueChild(UUID skillRunId,
            TechniqueChildOrigin techniqueChild) {
        return new ActionOrigin(Optional.empty(),
                Optional.of(Objects.requireNonNull(skillRunId, "skillRunId")),
                Optional.empty(),
                Optional.of(Objects.requireNonNull(techniqueChild,
                        "techniqueChild")));
    }

    public boolean isUntracked() {
        return this.planId.isEmpty()
                && this.skillRunId.isEmpty()
                && this.controller.isEmpty()
                && this.techniqueChild.isEmpty();
   }

   private static void requireNonZero(UUID var0, String var1) {
      if (ZERO_UUID.equals(var0)) {
         throw new IllegalArgumentException(var1 + " must not be the zero UUID");
      }
   }
}
