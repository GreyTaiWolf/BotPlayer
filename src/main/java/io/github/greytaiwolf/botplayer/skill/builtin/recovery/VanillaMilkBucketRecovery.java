package io.github.greytaiwolf.botplayer.skill.builtin.recovery;

import io.github.greytaiwolf.botplayer.skill.core.SkillCategory;
import io.github.greytaiwolf.botplayer.skill.core.SkillDescriptor;
import io.github.greytaiwolf.botplayer.skill.core.SkillId;
import io.github.greytaiwolf.botplayer.skill.core.SkillParameterRule;
import io.github.greytaiwolf.botplayer.skill.core.SkillParameterSchema;
import io.github.greytaiwolf.botplayer.skill.core.SkillParameters;
import io.github.greytaiwolf.botplayer.skill.core.SkillRiskLevel;
import io.github.greytaiwolf.botplayer.skill.core.SkillVersion;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlan;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanNode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * One narrowly approved P5B recovery plan: drink one vanilla milk bucket to
 * cure a sustained poison effect.
 *
 * <p>The compiler deliberately has no generic item id, effect id, health target
 * or inventory-routing parameter. It emits one closed-schema node only; the
 * runtime handler re-observes the live bot, requires exactly vanilla poison and
 * delegates the consumption to the normal {@code USE_ITEM} mailbox action.
 * Neither this compiler nor its descriptor auto-registers with a lifecycle.
 */
public final class VanillaMilkBucketRecovery {
    public static final SkillId ID = new SkillId(
            "botplayer", "drink_milk_for_poison");
    public static final SkillVersion VERSION = new SkillVersion(1, 0, 0);
    public static final String HOTBAR_SLOT_PARAMETER = "milk.slot";

    /** Includes the finite vanilla drinking action and one verification tick. */
    public static final int MAXIMUM_NODE_TICKS = 160;

    private static final UUID ZERO_UUID = new UUID(0L, 0L);
    private static final String PLAN_ID_DOMAIN =
            "botplayer:milk-poison-recovery-plan:v1";
    private static final String NODE_ID_DOMAIN =
            "botplayer:milk-poison-recovery-node:v1";
    private static final Set<String> REQUIRED_PARAMETERS = Set.of(
            HOTBAR_SLOT_PARAMETER);
    private static final SkillDescriptor DESCRIPTOR = new SkillDescriptor(
            ID,
            VERSION,
            SkillCategory.SURVIVAL,
            new SkillParameterSchema(Map.of(
                    HOTBAR_SLOT_PARAMETER,
                    new SkillParameterRule.IntegerRule(true, 0, 8))),
            SkillRiskLevel.LOW,
            Set.of(),
            MAXIMUM_NODE_TICKS,
            0,
            false);

    private VanillaMilkBucketRecovery() {
    }

    /** The descriptor must be explicitly registered by the caller. */
    public static SkillDescriptor descriptor() {
        return DESCRIPTOR;
    }

    /**
     * Compiles a single deterministic node without carrying any mutable world,
     * player, effect or item object across the runtime boundary.
     */
    public static SkillPlan compile(
            UUID botId, long revision, MilkRequest request) {
        UUID owner = requireNonZero(Objects.requireNonNull(botId, "botId"),
                "botId");
        if (revision <= 0L) {
            throw new IllegalArgumentException("revision must be positive");
        }
        MilkRequest normalized = Objects.requireNonNull(request, "request");
        UUID planId = stableId(PLAN_ID_DOMAIN, owner, revision,
                normalized.hotbarSlot());
        UUID nodeId = stableId(NODE_ID_DOMAIN, owner, revision,
                normalized.hotbarSlot());
        return new SkillPlan(
                planId,
                owner,
                revision,
                List.of(new SkillPlanNode(
                        nodeId,
                        ID,
                        VERSION,
                        parameters(normalized))),
                List.of());
    }

    public static SkillParameters parameters(MilkRequest request) {
        MilkRequest normalized = Objects.requireNonNull(request, "request");
        return new SkillParameters(Map.of(
                HOTBAR_SLOT_PARAMETER, normalized.hotbarSlot()));
    }

    /**
     * A second strict parser for the handler. It rejects every unknown field and
     * every scalar coercion even if an upstream plan validator was bypassed.
     */
    public static Optional<MilkRequest> parse(SkillParameters parameters) {
        SkillParameters supplied = Objects.requireNonNull(parameters,
                "parameters");
        if (!supplied.values().keySet().equals(REQUIRED_PARAMETERS)) {
            return Optional.empty();
        }
        Object slot = supplied.values().get(HOTBAR_SLOT_PARAMETER);
        if (!(slot instanceof Integer hotbarSlot)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new MilkRequest(hotbarSlot));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static UUID stableId(
            String domain, UUID botId, long revision, int hotbarSlot) {
        UUID result = UUID.nameUUIDFromBytes((domain
                + "|"
                + botId
                + "|"
                + revision
                + "|"
                + hotbarSlot).getBytes(StandardCharsets.UTF_8));
        if (ZERO_UUID.equals(result)) {
            throw new IllegalStateException(
                    "deterministic milk recovery identifier resolved to zero UUID");
        }
        return result;
    }

    private static UUID requireNonZero(UUID value, String name) {
        if (ZERO_UUID.equals(value)) {
            throw new IllegalArgumentException(name + " must not be zero UUID");
        }
        return value;
    }

    /** A fixed hotbar position; the live stack is never a planner parameter. */
    public record MilkRequest(int hotbarSlot) {
        public MilkRequest {
            if (hotbarSlot < 0 || hotbarSlot > 8) {
                throw new IllegalArgumentException(
                        "hotbarSlot must be between 0 and 8");
            }
        }
    }
}
