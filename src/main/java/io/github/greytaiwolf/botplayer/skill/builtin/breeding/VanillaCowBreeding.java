package io.github.greytaiwolf.botplayer.skill.builtin.breeding;

import io.github.greytaiwolf.botplayer.skill.core.SkillCategory;
import io.github.greytaiwolf.botplayer.skill.core.SkillDescriptor;
import io.github.greytaiwolf.botplayer.skill.core.SkillId;
import io.github.greytaiwolf.botplayer.skill.core.SkillParameterRule;
import io.github.greytaiwolf.botplayer.skill.core.SkillParameterSchema;
import io.github.greytaiwolf.botplayer.skill.core.SkillParameters;
import io.github.greytaiwolf.botplayer.skill.core.SkillRiskLevel;
import io.github.greytaiwolf.botplayer.skill.core.SkillVersion;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlan;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanEdge;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanNode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * P5B 最小原版牛繁殖合同。
 *
 * <p>这个类只定义可审核的两步 DAG 和参数边界；它不会自行向生产生命周期注册 descriptor 或
 * handler。这样一个未经明确 lifecycle 接线的实验性 P5B 切片不能意外获得执行权。真正的
 * 世界副作用由 {@code MinecraftCowBreedingSkillNodeHandler} 通过现有 action mailbox 完成，
 * 绝不直接设置年龄、love 状态、实体或背包。
 */
public final class VanillaCowBreeding {
    public static final SkillId ID = new SkillId(
            "botplayer", "breed_vanilla_cows");
    public static final SkillVersion VERSION = new SkillVersion(1, 0, 0);

    public static final String FIRST_COW_UUID_PARAMETER =
            "first.cow.uuid";
    public static final String SECOND_COW_UUID_PARAMETER =
            "second.cow.uuid";
    public static final String FOOD_SLOT_PARAMETER = "food.slot";
    public static final String PHASE_PARAMETER = "breed.phase";

    /** 两次实体交互和有界幼体观察都必须在单节点预算内完成。 */
    public static final int MAXIMUM_NODE_TICKS = 180;

    private static final String PLAN_ID_DOMAIN =
            "botplayer:vanilla-cow-breeding-plan:v1";
    private static final String NODE_ID_DOMAIN =
            "botplayer:vanilla-cow-breeding-node:v1";
    private static final Set<String> REQUIRED_PARAMETER_NAMES = Set.of(
            FIRST_COW_UUID_PARAMETER,
            SECOND_COW_UUID_PARAMETER,
            FOOD_SLOT_PARAMETER,
            PHASE_PARAMETER);
    private static final SkillDescriptor DESCRIPTOR = new SkillDescriptor(
            ID,
            VERSION,
            SkillCategory.SURVIVAL,
            new SkillParameterSchema(Map.of(
                    FIRST_COW_UUID_PARAMETER,
                    new SkillParameterRule.StringRule(
                            true, 36, 36, Set.of()),
                    SECOND_COW_UUID_PARAMETER,
                    new SkillParameterRule.StringRule(
                            true, 36, 36, Set.of()),
                    FOOD_SLOT_PARAMETER,
                    new SkillParameterRule.IntegerRule(true, 0, 8),
                    PHASE_PARAMETER,
                    new SkillParameterRule.StringRule(
                            true, 5, 6, Set.of(
                                    Phase.FIRST.parameterValue(),
                                    Phase.SECOND.parameterValue())))),
            SkillRiskLevel.LOW,
            Set.of(),
            MAXIMUM_NODE_TICKS,
            0,
            false);

    private VanillaCowBreeding() {
    }

    /** 返回必须被显式注册到 {@code SkillRegistry} 的精确 descriptor。 */
    public static SkillDescriptor descriptor() {
        return DESCRIPTOR;
    }

    /**
     * 将一份已由服务器观察到的两头牛身份编译成严格顺序的两节点计划。
     *
     * <p>plan 不携带 world、dimension、坐标、实体对象或 item id；handler 会在每个节点开始时
     * 重新从服务器线程观察这些事实，并只接受原版默认组件的主手小麦。
     */
    public static SkillPlan compile(
            UUID botId, long revision, CowPairRequest request) {
        requireNonZero(Objects.requireNonNull(botId, "botId"), "botId");
        if (revision <= 0L) {
            throw new IllegalArgumentException("revision must be positive");
        }
        CowPairRequest pair = Objects.requireNonNull(request, "request");
        UUID planId = stableId(
                PLAN_ID_DOMAIN,
                botId,
                revision,
                pair.firstCowId(),
                pair.secondCowId(),
                pair.foodSlot(),
                "plan");
        UUID firstNodeId = stableId(
                NODE_ID_DOMAIN,
                botId,
                revision,
                pair.firstCowId(),
                pair.secondCowId(),
                pair.foodSlot(),
                Phase.FIRST.parameterValue());
        UUID secondNodeId = stableId(
                NODE_ID_DOMAIN,
                botId,
                revision,
                pair.firstCowId(),
                pair.secondCowId(),
                pair.foodSlot(),
                Phase.SECOND.parameterValue());
        return new SkillPlan(
                planId,
                botId,
                revision,
                List.of(
                        node(firstNodeId, pair, Phase.FIRST),
                        node(secondNodeId, pair, Phase.SECOND)),
                List.of(new SkillPlanEdge(firstNodeId, secondNodeId)));
    }

    /**
     * 对运行时节点参数做第二次、严格的解析。schema 的类型/长度校验不足以证明 UUID 是
     * canonical，且此处必须拒绝未知字段，避免随后的 handler 忽略不可信输入。
     */
    public static Optional<FeedRequest> parse(SkillParameters parameters) {
        Objects.requireNonNull(parameters, "parameters");
        if (!parameters.values().keySet().equals(
                REQUIRED_PARAMETER_NAMES)) {
            return Optional.empty();
        }
        Object firstValue = parameters.values().get(
                FIRST_COW_UUID_PARAMETER);
        Object secondValue = parameters.values().get(
                SECOND_COW_UUID_PARAMETER);
        Object foodSlotValue = parameters.values().get(
                FOOD_SLOT_PARAMETER);
        Object phaseValue = parameters.values().get(PHASE_PARAMETER);
        if (!(firstValue instanceof String firstText)
                || !(secondValue instanceof String secondText)
                || !(foodSlotValue instanceof Integer foodSlot)
                || !(phaseValue instanceof String phaseText)) {
            return Optional.empty();
        }
        Optional<UUID> first = parseCanonicalUuid(firstText);
        Optional<UUID> second = parseCanonicalUuid(secondText);
        Optional<Phase> phase = Phase.parse(phaseText);
        if (first.isEmpty()
                || second.isEmpty()
                || phase.isEmpty()
                || foodSlot < 0
                || foodSlot > 8
                || first.equals(second)) {
            return Optional.empty();
        }
        return Optional.of(new FeedRequest(
                first.orElseThrow(),
                second.orElseThrow(),
                foodSlot,
                phase.orElseThrow()));
    }

    private static SkillPlanNode node(
            UUID nodeId, CowPairRequest request, Phase phase) {
        return new SkillPlanNode(
                nodeId,
                ID,
                VERSION,
                new SkillParameters(Map.of(
                        FIRST_COW_UUID_PARAMETER,
                        request.firstCowId().toString(),
                        SECOND_COW_UUID_PARAMETER,
                        request.secondCowId().toString(),
                        FOOD_SLOT_PARAMETER,
                        request.foodSlot(),
                        PHASE_PARAMETER,
                        phase.parameterValue())));
    }

    private static Optional<UUID> parseCanonicalUuid(String value) {
        Objects.requireNonNull(value, "value");
        try {
            UUID parsed = UUID.fromString(value);
            return ZERO_UUID.equals(parsed)
                    || !parsed.toString().equals(value)
                    ? Optional.empty()
                    : Optional.of(parsed);
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private static UUID stableId(
            String domain,
            UUID botId,
            long revision,
            UUID firstCowId,
            UUID secondCowId,
            int foodSlot,
            String phase) {
        String material = domain
                + "|" + botId
                + "|" + revision
                + "|" + firstCowId
                + "|" + secondCowId
                + "|" + foodSlot
                + "|" + phase;
        UUID result = UUID.nameUUIDFromBytes(
                material.getBytes(StandardCharsets.UTF_8));
        if (ZERO_UUID.equals(result)) {
            throw new IllegalStateException(
                    "deterministic breeding identifier resolved to zero UUID");
        }
        return result;
    }

    private static void requireNonZero(UUID value, String name) {
        if (ZERO_UUID.equals(value)) {
            throw new IllegalArgumentException(name + " must not be zero UUID");
        }
    }

    /** 经过服务器观察/批准后才能进入 compiler 的两头不同成年牛身份。 */
    public record CowPairRequest(
            UUID firstCowId, UUID secondCowId, int foodSlot) {
        public CowPairRequest {
            firstCowId = Objects.requireNonNull(firstCowId, "firstCowId");
            secondCowId = Objects.requireNonNull(
                    secondCowId, "secondCowId");
            requireNonZero(firstCowId, "firstCowId");
            requireNonZero(secondCowId, "secondCowId");
            if (firstCowId.equals(secondCowId)) {
                throw new IllegalArgumentException(
                        "cow breeding requires two different cows");
            }
            if (foodSlot < 0 || foodSlot > 8) {
                throw new IllegalArgumentException(
                        "foodSlot must be a hotbar inventory slot");
            }
        }
    }

    /** 单节点用的纯标量请求；实际 entity/item/world binding 一律在服务器重新捕获。 */
    public record FeedRequest(
            UUID firstCowId,
            UUID secondCowId,
            int foodSlot,
            Phase phase) {
        public FeedRequest {
            firstCowId = Objects.requireNonNull(firstCowId, "firstCowId");
            secondCowId = Objects.requireNonNull(
                    secondCowId, "secondCowId");
            phase = Objects.requireNonNull(phase, "phase");
            requireNonZero(firstCowId, "firstCowId");
            requireNonZero(secondCowId, "secondCowId");
            if (firstCowId.equals(secondCowId)) {
                throw new IllegalArgumentException(
                        "cow breeding requires two different cows");
            }
            if (foodSlot < 0 || foodSlot > 8) {
                throw new IllegalArgumentException(
                        "foodSlot must be a hotbar inventory slot");
            }
        }

        public UUID targetCowId() {
            return phase == Phase.FIRST ? firstCowId : secondCowId;
        }

        public UUID partnerCowId() {
            return phase == Phase.FIRST ? secondCowId : firstCowId;
        }
    }

    public enum Phase {
        FIRST("first"),
        SECOND("second");

        private final String parameterValue;

        Phase(String parameterValue) {
            this.parameterValue = parameterValue;
        }

        public String parameterValue() {
            return parameterValue;
        }

        private static Optional<Phase> parse(String value) {
            for (Phase phase : values()) {
                if (phase.parameterValue.equals(value)) {
                    return Optional.of(phase);
                }
            }
            return Optional.empty();
        }
    }

    private static final UUID ZERO_UUID = new UUID(0L, 0L);
}
