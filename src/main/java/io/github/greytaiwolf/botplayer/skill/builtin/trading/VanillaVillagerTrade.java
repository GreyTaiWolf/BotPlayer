package io.github.greytaiwolf.botplayer.skill.builtin.trading;

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
 * P5B 的受限原版村民单笔交易计划合同。
 *
 * <p>它只编译一笔已经由可信服务器逻辑选中的村民/offer/库存槽提示。handler 和 action
 * backend 会在服务端主线程重新观察 UUID、实体种类、offer 的完整价格状态、原生菜单和库存，
 * 因此这里的四个标量不是世界事实，也不能表达任意商店、双支付或批量交易。</p>
 *
 * <p>该 descriptor 只由固定生命周期注册；它不被加入外部 Skill Pack 白名单，也不自动成为
 * AI 工具。这样模型或磁盘 pack 不会凭这个 compiler 获得交易世界写入权。</p>
 */
public final class VanillaVillagerTrade {
    public static final SkillId ID = new SkillId(
            "botplayer", "trade_vanilla_villager");
    public static final SkillVersion VERSION = new SkillVersion(1, 0, 0);

    public static final String VILLAGER_ID_PARAMETER = "villager.id";
    public static final String OFFER_INDEX_PARAMETER = "offer.index";
    public static final String SOURCE_INVENTORY_SLOT_PARAMETER =
            "source.inventory.slot";
    public static final String OUTPUT_INVENTORY_SLOT_PARAMETER =
            "output.inventory.slot";

    /** Vanilla villager offer lists are small; this also bounds untrusted plan work. */
    public static final int MAXIMUM_OFFER_INDEX = 127;
    public static final int FIRST_PLAYER_INVENTORY_SLOT = 0;
    public static final int LAST_PLAYER_INVENTORY_SLOT = 35;
    /** One bounded native entity/menu transaction. */
    public static final int MAXIMUM_NODE_TICKS = 240;

    private static final UUID ZERO_UUID = new UUID(0L, 0L);
    private static final String PLAN_ID_DOMAIN =
            "botplayer:vanilla-villager-trade-plan:v1";
    private static final String NODE_ID_DOMAIN =
            "botplayer:vanilla-villager-trade-node:v1";
    private static final Set<String> REQUIRED_PARAMETER_NAMES = Set.of(
            VILLAGER_ID_PARAMETER,
            OFFER_INDEX_PARAMETER,
            SOURCE_INVENTORY_SLOT_PARAMETER,
            OUTPUT_INVENTORY_SLOT_PARAMETER);
    private static final SkillDescriptor DESCRIPTOR = new SkillDescriptor(
            ID,
            VERSION,
            SkillCategory.RESOURCE,
            new SkillParameterSchema(Map.of(
                    VILLAGER_ID_PARAMETER,
                    new SkillParameterRule.StringRule(true, 36, 36, Set.of()),
                    OFFER_INDEX_PARAMETER,
                    new SkillParameterRule.IntegerRule(
                            true, 0, MAXIMUM_OFFER_INDEX),
                    SOURCE_INVENTORY_SLOT_PARAMETER,
                    new SkillParameterRule.IntegerRule(
                            true,
                            FIRST_PLAYER_INVENTORY_SLOT,
                            LAST_PLAYER_INVENTORY_SLOT),
                    OUTPUT_INVENTORY_SLOT_PARAMETER,
                    new SkillParameterRule.IntegerRule(
                            true,
                            FIRST_PLAYER_INVENTORY_SLOT,
                            LAST_PLAYER_INVENTORY_SLOT))),
            SkillRiskLevel.MODERATE,
            Set.of(),
            MAXIMUM_NODE_TICKS,
            0,
            false);

    private VanillaVillagerTrade() {
    }

    public static SkillDescriptor descriptor() {
        return DESCRIPTOR;
    }

    /**
     * Compiles one deterministic, non-resumable transaction node. The caller must have obtained
     * the scalar request through a server-side observation/approval path; this method performs no
     * entity lookup and intentionally cannot create a trade from user text alone.
     */
    public static SkillPlan compile(
            UUID botId, long revision, TradeRequest request) {
        UUID checkedBotId = requireNonZero(
                Objects.requireNonNull(botId, "botId"), "botId");
        if (revision <= 0L) {
            throw new IllegalArgumentException("revision must be positive");
        }
        TradeRequest checkedRequest = Objects.requireNonNull(request, "request");
        UUID planId = stableId(PLAN_ID_DOMAIN, checkedBotId, revision,
                checkedRequest, "plan");
        UUID nodeId = stableId(NODE_ID_DOMAIN, checkedBotId, revision,
                checkedRequest, "trade");
        return new SkillPlan(
                planId,
                checkedBotId,
                revision,
                List.of(new SkillPlanNode(
                        nodeId,
                        ID,
                        VERSION,
                        parameters(checkedRequest))),
                List.of());
    }

    public static SkillParameters parameters(TradeRequest request) {
        TradeRequest checked = Objects.requireNonNull(request, "request");
        return new SkillParameters(Map.of(
                VILLAGER_ID_PARAMETER, checked.villagerId().toString(),
                OFFER_INDEX_PARAMETER, checked.offerIndex(),
                SOURCE_INVENTORY_SLOT_PARAMETER, checked.sourceInventorySlot(),
                OUTPUT_INVENTORY_SLOT_PARAMETER, checked.outputInventorySlot()));
    }

    /**
     * Re-parses a node's scalar input exactly. Schema validation alone cannot prove canonical
     * lower-case UUID text or reject an unknown key that a future handler might accidentally
     * ignore.
     */
    public static Optional<TradeRequest> parse(SkillParameters parameters) {
        Objects.requireNonNull(parameters, "parameters");
        Map<String, Object> values = parameters.values();
        if (!values.keySet().equals(REQUIRED_PARAMETER_NAMES)
                || !(values.get(VILLAGER_ID_PARAMETER) instanceof String text)
                || !(values.get(OFFER_INDEX_PARAMETER) instanceof Integer offerIndex)
                || !(values.get(SOURCE_INVENTORY_SLOT_PARAMETER)
                        instanceof Integer sourceSlot)
                || !(values.get(OUTPUT_INVENTORY_SLOT_PARAMETER)
                        instanceof Integer outputSlot)) {
            return Optional.empty();
        }
        try {
            UUID villagerId = UUID.fromString(text);
            if (!villagerId.toString().equals(text) || ZERO_UUID.equals(villagerId)) {
                return Optional.empty();
            }
            return Optional.of(new TradeRequest(
                    villagerId, offerIndex, sourceSlot, outputSlot));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static UUID stableId(
            String domain,
            UUID botId,
            long revision,
            TradeRequest request,
            String purpose) {
        String material = domain
                + "|" + botId
                + "|" + revision
                + "|" + request.villagerId()
                + "|" + request.offerIndex()
                + "|" + request.sourceInventorySlot()
                + "|" + request.outputInventorySlot()
                + "|" + purpose;
        UUID value = UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8));
        if (ZERO_UUID.equals(value)) {
            throw new IllegalStateException(
                    "deterministic villager trade identifier resolved to zero UUID");
        }
        return value;
    }

    private static UUID requireNonZero(UUID value, String field) {
        if (ZERO_UUID.equals(value)) {
            throw new IllegalArgumentException(field + " must not be zero UUID");
        }
        return value;
    }

    /** Scalar identity only; the live {@code Villager} and offer are always re-observed. */
    public record TradeRequest(
            UUID villagerId,
            int offerIndex,
            int sourceInventorySlot,
            int outputInventorySlot) {
        public TradeRequest {
            requireNonZero(Objects.requireNonNull(villagerId, "villagerId"),
                    "villagerId");
            if (offerIndex < 0 || offerIndex > MAXIMUM_OFFER_INDEX
                    || sourceInventorySlot < FIRST_PLAYER_INVENTORY_SLOT
                    || sourceInventorySlot > LAST_PLAYER_INVENTORY_SLOT
                    || outputInventorySlot < FIRST_PLAYER_INVENTORY_SLOT
                    || outputInventorySlot > LAST_PLAYER_INVENTORY_SLOT
                    || sourceInventorySlot == outputInventorySlot) {
                throw new IllegalArgumentException(
                        "villager trade request fields are outside the narrow contract");
            }
        }
    }
}
