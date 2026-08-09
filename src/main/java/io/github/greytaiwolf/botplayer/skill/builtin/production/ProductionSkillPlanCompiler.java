package io.github.greytaiwolf.botplayer.skill.builtin.production;

import io.github.greytaiwolf.botplayer.skill.builtin.P5ABuiltinSkillIds;
import io.github.greytaiwolf.botplayer.skill.builtin.survival.MinecraftBasicEquipmentPlanner.ExactMainHandItem;
import io.github.greytaiwolf.botplayer.skill.core.SkillCategory;
import io.github.greytaiwolf.botplayer.skill.core.SkillDescriptor;
import io.github.greytaiwolf.botplayer.skill.core.SkillParameterRule;
import io.github.greytaiwolf.botplayer.skill.core.SkillParameterSchema;
import io.github.greytaiwolf.botplayer.skill.core.SkillParameters;
import io.github.greytaiwolf.botplayer.skill.core.SkillRiskLevel;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlan;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanEdge;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanNode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 把已审核的 P5A 木头到铁镐生产模板编译为通用 {@link SkillPlan}。
 *
 * <p>这个编译器不是动态脚本入口：它只接受 {@link WoodToIronPickTemplate} 的精确结构，并且
 * 每次都先通过 {@link ProductionPlanValidator}。生产 fragment 只携带一个编译进来的 operation
 * id；一个逻辑生产节点会下沉为一个或多个可由原版实际完成的物理 fragment（例如四根原木必须是
 * 四次 {@code BREAK_BLOCK}）。固定的精确主手门只携带封闭白名单中的 item id。坐标、命令、容器
 * session、玩家对象和 run token 都不属于参数模型。真实生产 handler 只能用
 * {@link #approvedOperation(String)} 回查同一份物理生产合同，再绑定当时观察到的世界/menu 状态。
 */
public final class ProductionSkillPlanCompiler {
    private static final String PLAN_ID_DOMAIN =
            "botplayer:production-skill-plan:v1";
    private static final String NODE_ID_DOMAIN =
            "botplayer:production-skill-node:v1";
    private static final String OPERATION_ID_PREFIX = "p5a.";
    private static final int MAX_OPERATION_ID_LENGTH = 128;
    private static final int MAXIMUM_STEP_TICKS = 2_400;

    private static final ProductionPlanTemplate CANONICAL_TEMPLATE =
            WoodToIronPickTemplate.create();
    private static final ProductionPlanValidation CANONICAL_VALIDATION =
            validateCanonical();
    private static final List<LoweredOperation> LOWERED_OPERATIONS =
            lowerCanonicalOperations();
    private static final Map<String, List<LoweredOperation>>
            LOWERED_OPERATIONS_BY_NODE = loweredOperationsByNode();
    private static final Map<String, List<String>> OPERATION_IDS_BY_NODE =
            operationIdsByNode();
    private static final Map<String, UUID> SKILL_NODE_IDS_BY_OPERATION =
            skillNodeIdsByOperation();
    /*
     * 这些不是 ProductionOperation：它们是把已经产出的精确物品切入原版主手热栏的
     * 独立 P5A 节点。必须保留在模板之外，避免把“背包装备”错误写成直接的生产/世界
     * 副作用，也让 checkpoint/restart 继续只以最终的 SkillPlan 为权威。
     */
    private static final List<ExactMainHandStep> EXACT_MAIN_HAND_STEPS =
            canonicalExactMainHandSteps();
    private static final Map<ProductionPlanEdge, ExactMainHandStep>
            EXACT_MAIN_HAND_STEPS_BY_REPLACED_EDGE =
                    exactMainHandStepsByReplacedEdge();
    private static final Map<String, UUID> EXACT_MAIN_HAND_NODE_IDS =
            exactMainHandNodeIds();
    private static final Map<String, ProductionResolvedNode>
            APPROVED_OPERATIONS = approvedOperations();
    private static final Set<String> APPROVED_OPERATION_IDS =
            Set.copyOf(APPROVED_OPERATIONS.keySet());
    private static final UUID STABLE_PLAN_ID = stablePlanId();
    private static final SkillDescriptor HANDLER_DESCRIPTOR =
            new SkillDescriptor(
                    P5ABuiltinSkillIds.BOOTSTRAP_IRON,
                    P5ABuiltinSkillIds.VERSION,
                    SkillCategory.CRAFTING,
                    new SkillParameterSchema(Map.of(
                            P5ABuiltinSkillIds
                                    .BOOTSTRAP_IRON_OPERATION_ID_PARAMETER,
                            new SkillParameterRule.StringRule(
                                    true,
                                    1,
                                    MAX_OPERATION_ID_LENGTH,
                                    APPROVED_OPERATION_IDS))),
                    SkillRiskLevel.MODERATE,
                    Set.of(),
                    MAXIMUM_STEP_TICKS,
                    ProductionStepLifecycle.MAX_ATTEMPTS,
                    true);

    private final ProductionPlanValidator validator;

    public ProductionSkillPlanCompiler(ProductionPlanValidator validator) {
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    public static ProductionSkillPlanCompiler p5aDefault() {
        return new ProductionSkillPlanCompiler(
                ProductionPlanValidator.p5aDefault());
    }

    /**
     * 供 lifecycle 在未来注册 handler 前使用的精确 descriptor。该方法不注册任何全局状态。
     */
    public static SkillDescriptor handlerDescriptor() {
        return HANDLER_DESCRIPTOR;
    }

    /**
     * handler 可接收的唯一 operation id 集合。
     */
    public static Set<String> approvedOperationIds() {
        return APPROVED_OPERATION_IDS;
    }

    /**
     * 返回一个受审核 operation id 所代表的纯生产合同；未知 id 一律不存在。
     */
    public static Optional<ProductionResolvedNode> approvedOperation(
            String operationId) {
        return Optional.ofNullable(APPROVED_OPERATIONS.get(
                Objects.requireNonNull(operationId, "operationId")));
    }

    /**
     * 返回模板逻辑节点首个物理 fragment 的稳定 operation id。这个兼容入口只适合需要一个
     * 代表性操作的只读诊断；真正的 compiler 和 handler 白名单必须使用
     * {@link #operationIdsForProductionNode(String)}，不能据此假定一个逻辑节点只对应一次
     * 原版动作。
     */
    public static Optional<String> operationIdForProductionNode(
            String productionNodeId) {
        List<String> operationIds = OPERATION_IDS_BY_NODE.get(
                Objects.requireNonNull(productionNodeId, "productionNodeId"));
        return operationIds == null
                ? Optional.empty()
                : Optional.of(operationIds.get(0));
    }

    /**
     * 返回一个模板逻辑节点的全部、按实际原版动作顺序排列的白名单 fragment operation id。
     * 未知节点返回空列表。返回值不含坐标、menu session 或运行时 token。
     */
    public static List<String> operationIdsForProductionNode(
            String productionNodeId) {
        List<String> operationIds = OPERATION_IDS_BY_NODE.get(
                Objects.requireNonNull(productionNodeId, "productionNodeId"));
        return operationIds == null ? List.of() : operationIds;
    }

    /**
     * 编译固定的木头到铁镐 DAG。planId、nodeId 都由模板语义派生，不读取或接受 run token。
     */
    public SkillPlan compileWoodToIronPick(UUID botId, long revision) {
        return compile(botId, revision, CANONICAL_TEMPLATE);
    }

    /**
     * 编译一个生产模板，但仅允许当前版本完全审核的木头到铁镐模板进入通用运行时。
     *
     * <p>先验证、后匹配精确模板：被生产 validator 拒绝的输入不会暴露任何通用计划；即使某个
     * 变体在生产账本层合法，也不能绕过本 handler 的 operation-id 白名单。
     */
    public SkillPlan compile(
            UUID botId,
            long revision,
            ProductionPlanTemplate template) {
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(template, "template");

        ProductionPlanValidation validation = validator.validate(template);
        if (!validation.accepted()) {
            throw new IllegalArgumentException(
                    "production template was rejected before skill-plan compilation: "
                            + validation.violations());
        }
        if (!CANONICAL_TEMPLATE.equals(template)) {
            throw new IllegalArgumentException(
                    "bootstrap iron compiler only accepts the canonical reviewed template");
        }

        verifyResolvedNodes(validation);
        Map<String, UUID> skillNodeIds = SKILL_NODE_IDS_BY_OPERATION;
        List<SkillPlanNode> nodes = new ArrayList<>(LOWERED_OPERATIONS.size()
                + EXACT_MAIN_HAND_STEPS.size());
        for (LoweredOperation lowered : LOWERED_OPERATIONS) {
            String operationId = lowered.operationId();
            UUID nodeId = skillNodeIds.get(operationId);
            if (nodeId == null) {
                throw new IllegalStateException(
                        "approved operation has no stable SkillPlan node id");
            }
            nodes.add(new SkillPlanNode(
                    nodeId,
                    P5ABuiltinSkillIds.BOOTSTRAP_IRON,
                    P5ABuiltinSkillIds.VERSION,
                    new SkillParameters(Map.of(
                            P5ABuiltinSkillIds
                                    .BOOTSTRAP_IRON_OPERATION_ID_PARAMETER,
                                    operationId))));
        }
        for (ExactMainHandStep step : EXACT_MAIN_HAND_STEPS) {
            UUID nodeId = EXACT_MAIN_HAND_NODE_IDS.get(step.nodeId());
            if (nodeId == null) {
                throw new IllegalStateException(
                        "canonical exact main-hand step has no stable SkillPlan node id");
            }
            nodes.add(new SkillPlanNode(
                    nodeId,
                    P5ABuiltinSkillIds.EQUIP_EXACT_MAIN_HAND,
                    P5ABuiltinSkillIds.VERSION,
                    new SkillParameters(Map.of(
                            P5ABuiltinSkillIds
                                    .EXACT_MAIN_HAND_ITEM_ID_PARAMETER,
                            step.item().itemId().value()))));
        }

        List<SkillPlanEdge> edges = new ArrayList<>(
                template.edges().size() + LOWERED_OPERATIONS.size()
                        - template.nodes().size()
                        + EXACT_MAIN_HAND_STEPS.size());
        Set<SkillPlanEdge> uniqueEdges = new LinkedHashSet<>();
        for (ProductionPlanNode productionNode : template.nodes()) {
            List<LoweredOperation> fragments = fragmentsFor(
                    productionNode.nodeId());
            for (int index = 1; index < fragments.size(); index++) {
                appendEdge(edges, uniqueEdges, skillNodeIds,
                        fragments.get(index - 1).operationId(),
                        fragments.get(index).operationId());
            }
        }
        for (ProductionPlanEdge productionEdge : template.edges()) {
            if (EXACT_MAIN_HAND_STEPS_BY_REPLACED_EDGE.containsKey(
                    productionEdge)) {
                continue;
            }
            List<LoweredOperation> before = fragmentsFor(
                    productionEdge.beforeNodeId());
            List<LoweredOperation> after = fragmentsFor(
                    productionEdge.afterNodeId());
            appendEdge(edges, uniqueEdges, skillNodeIds,
                    before.get(before.size() - 1).operationId(),
                    after.get(0).operationId());
        }
        for (ExactMainHandStep step : EXACT_MAIN_HAND_STEPS) {
            UUID equipNodeId = EXACT_MAIN_HAND_NODE_IDS.get(step.nodeId());
            if (equipNodeId == null) {
                throw new IllegalStateException(
                        "canonical exact main-hand step has no stable SkillPlan node id");
            }
            List<LoweredOperation> produced = fragmentsFor(
                    step.sourceProductionNodeId());
            appendNodeEdge(edges, uniqueEdges, skillNodeIds.get(
                    produced.get(produced.size() - 1).operationId()),
                    equipNodeId);
            for (String dependentProductionNodeId :
                    step.dependentProductionNodeIds()) {
                List<LoweredOperation> dependent = fragmentsFor(
                        dependentProductionNodeId);
                appendNodeEdge(edges, uniqueEdges, equipNodeId,
                        skillNodeIds.get(dependent.get(0).operationId()));
            }
        }
        return new SkillPlan(
                STABLE_PLAN_ID,
                botId,
                revision,
                nodes,
                edges);
    }

    private static ProductionPlanValidation validateCanonical() {
        ProductionPlanValidation validation = ProductionPlanValidator
                .p5aDefault()
                .validate(CANONICAL_TEMPLATE);
        if (!validation.accepted()) {
            throw new IllegalStateException(
                    "canonical wood-to-iron production template is invalid: "
                            + validation.violations());
        }
        return validation;
    }

    /**
     * 把逻辑 production DAG 下沉为一次真实原版动作可完成的 fragment。这个转换只在已验证的
     * canonical template 上运行；它不是第二个开放的生产计划解析器。
     */
    private static List<LoweredOperation> lowerCanonicalOperations() {
        Map<String, ProductionResolvedNode> resolvedByNode =
                resolvedByLogicalNode(CANONICAL_VALIDATION);
        List<LoweredOperation> lowered = new ArrayList<>();
        Set<String> uniqueOperationIds = new LinkedHashSet<>();
        for (ProductionPlanNode source : CANONICAL_TEMPLATE.nodes()) {
            ProductionResolvedNode resolved = resolvedByNode.get(
                    source.nodeId());
            if (resolved == null || !resolved.node().equals(source)) {
                throw new IllegalStateException(
                        "canonical validation does not match its source node");
            }
            int fragmentCount = fragmentCount(resolved);
            for (int fragmentIndex = 1;
                    fragmentIndex <= fragmentCount;
                    fragmentIndex++) {
                String operationId = operationId(
                        source.nodeId(), fragmentIndex, fragmentCount);
                if (!uniqueOperationIds.add(operationId)) {
                    throw new IllegalStateException(
                            "canonical production lowering produced a duplicate operation id");
                }
                lowered.add(new LoweredOperation(
                        source.nodeId(),
                        fragmentIndex,
                        fragmentCount,
                        operationId,
                        lowerResolvedNode(resolved, fragmentIndex,
                                fragmentCount)));
            }
        }
        if (lowered.isEmpty()) {
            throw new IllegalStateException(
                    "canonical production lowering must not be empty");
        }
        return List.copyOf(lowered);
    }

    private static Map<String, List<LoweredOperation>>
            loweredOperationsByNode() {
        Map<String, List<LoweredOperation>> grouped = new LinkedHashMap<>();
        for (LoweredOperation lowered : LOWERED_OPERATIONS) {
            grouped.computeIfAbsent(lowered.sourceNodeId(),
                    ignored -> new ArrayList<>()).add(lowered);
        }
        if (!grouped.keySet().equals(CANONICAL_TEMPLATE.nodes().stream()
                .map(ProductionPlanNode::nodeId)
                .collect(java.util.stream.Collectors.toCollection(
                        LinkedHashSet::new)))) {
            throw new IllegalStateException(
                    "canonical production lowering omitted a logical node");
        }
        Map<String, List<LoweredOperation>> copied = new LinkedHashMap<>();
        grouped.forEach((nodeId, fragments) -> {
            if (fragments.isEmpty()) {
                throw new IllegalStateException(
                        "canonical production lowering produced an empty fragment list");
            }
            copied.put(nodeId, List.copyOf(fragments));
        });
        return Map.copyOf(copied);
    }

    private static Map<String, List<String>> operationIdsByNode() {
        Map<String, List<String>> ids = new LinkedHashMap<>();
        for (ProductionPlanNode node : CANONICAL_TEMPLATE.nodes()) {
            List<LoweredOperation> fragments = fragmentsFor(node.nodeId());
            List<String> operationIds = fragments.stream()
                    .map(LoweredOperation::operationId)
                    .toList();
            if (ids.putIfAbsent(node.nodeId(), operationIds) != null) {
                throw new IllegalStateException(
                        "canonical production lowering duplicated a logical node");
            }
        }
        return Map.copyOf(ids);
    }

    private static Map<String, UUID> skillNodeIdsByOperation() {
        Map<String, UUID> nodeIds = new LinkedHashMap<>();
        Set<UUID> unique = new LinkedHashSet<>();
        for (LoweredOperation lowered : LOWERED_OPERATIONS) {
            String operationId = lowered.operationId();
            UUID nodeId = deterministicUuid(
                    NODE_ID_DOMAIN, List.of(operationId));
            if (!unique.add(nodeId)
                    || nodeIds.putIfAbsent(operationId, nodeId) != null) {
                throw new IllegalStateException(
                        "canonical production operation ids collided in SkillPlan node ids");
            }
        }
        return Map.copyOf(nodeIds);
    }

    /**
     * 生产模板本身只描述物料和工作站合同；工具装入主手必须是独立的、可由原版
     * InventoryMenu 事务审计的节点。下列三道门重新接管 template 中相应源节点的全部
     * 下游边：木镐在圆石前、石镐在煤和原铁前，铁镐作为最后一个确认节点。
     */
    private static List<ExactMainHandStep> canonicalExactMainHandSteps() {
        List<ExactMainHandStep> steps = List.of(
                new ExactMainHandStep(
                        "equip_wooden_pickaxe",
                        ExactMainHandItem.WOODEN_PICKAXE,
                        "wooden_pickaxe",
                        List.of("mine_cobblestone")),
                new ExactMainHandStep(
                        "equip_stone_pickaxe",
                        ExactMainHandItem.STONE_PICKAXE,
                        "stone_pickaxe",
                        List.of("mine_raw_iron", "mine_coal")),
                new ExactMainHandStep(
                        "equip_iron_pickaxe",
                        ExactMainHandItem.IRON_PICKAXE,
                        "iron_pickaxe",
                        List.of()));
        Set<String> canonicalNodeIds = new LinkedHashSet<>();
        for (ProductionPlanNode node : CANONICAL_TEMPLATE.nodes()) {
            canonicalNodeIds.add(node.nodeId());
        }
        Set<ProductionPlanEdge> canonicalEdges = new LinkedHashSet<>(
                CANONICAL_TEMPLATE.edges());
        Set<String> uniqueStepIds = new LinkedHashSet<>();
        Set<ExactMainHandItem> uniqueItems = new LinkedHashSet<>();
        Set<ProductionPlanEdge> reroutedEdges = new LinkedHashSet<>();
        for (ExactMainHandStep step : steps) {
            if (!uniqueStepIds.add(step.nodeId())
                    || !uniqueItems.add(step.item())
                    || !canonicalNodeIds.contains(
                            step.sourceProductionNodeId())) {
                throw new IllegalStateException(
                        "canonical exact main-hand steps are not unique or reference an unknown source");
            }
            Set<String> expectedDependents = new LinkedHashSet<>();
            for (ProductionPlanEdge edge : CANONICAL_TEMPLATE.edges()) {
                if (edge.beforeNodeId().equals(
                        step.sourceProductionNodeId())) {
                    expectedDependents.add(edge.afterNodeId());
                }
            }
            if (!expectedDependents.equals(new LinkedHashSet<>(
                    step.dependentProductionNodeIds()))) {
                throw new IllegalStateException(
                        "canonical exact main-hand step must reroute every direct downstream production edge");
            }
            for (String dependent : step.dependentProductionNodeIds()) {
                ProductionPlanEdge edge = new ProductionPlanEdge(
                        step.sourceProductionNodeId(), dependent);
                if (!canonicalEdges.contains(edge)
                        || !reroutedEdges.add(edge)) {
                    throw new IllegalStateException(
                            "canonical exact main-hand step reroutes an invalid production edge");
                }
            }
        }
        return steps;
    }

    private static Map<ProductionPlanEdge, ExactMainHandStep>
            exactMainHandStepsByReplacedEdge() {
        Map<ProductionPlanEdge, ExactMainHandStep> steps =
                new LinkedHashMap<>();
        for (ExactMainHandStep step : EXACT_MAIN_HAND_STEPS) {
            for (String dependent : step.dependentProductionNodeIds()) {
                ProductionPlanEdge edge = new ProductionPlanEdge(
                        step.sourceProductionNodeId(), dependent);
                if (steps.putIfAbsent(edge, step) != null) {
                    throw new IllegalStateException(
                            "canonical exact main-hand steps duplicate a rerouted production edge");
                }
            }
        }
        return Map.copyOf(steps);
    }

    private static Map<String, UUID> exactMainHandNodeIds() {
        Map<String, UUID> nodeIds = new LinkedHashMap<>();
        Set<UUID> unique = new LinkedHashSet<>(
                SKILL_NODE_IDS_BY_OPERATION.values());
        for (ExactMainHandStep step : EXACT_MAIN_HAND_STEPS) {
            List<String> semantics = new ArrayList<>();
            semantics.add("exact-main-hand");
            semantics.add(step.nodeId());
            semantics.add(step.item().itemId().value());
            semantics.add(step.sourceProductionNodeId());
            semantics.addAll(step.dependentProductionNodeIds());
            UUID nodeId = deterministicUuid(NODE_ID_DOMAIN, semantics);
            if (!unique.add(nodeId)
                    || nodeIds.putIfAbsent(step.nodeId(), nodeId) != null) {
                throw new IllegalStateException(
                        "canonical exact main-hand steps collided in SkillPlan node ids");
            }
        }
        return Map.copyOf(nodeIds);
    }

    private static Map<String, ProductionResolvedNode> approvedOperations() {
        Map<String, ProductionResolvedNode> operations = new LinkedHashMap<>();
        for (LoweredOperation lowered : LOWERED_OPERATIONS) {
            if (operations.putIfAbsent(lowered.operationId(),
                    lowered.resolved()) != null) {
                throw new IllegalStateException(
                        "canonical production lowering duplicated an approved operation");
            }
        }
        if (operations.size() != LOWERED_OPERATIONS.size()) {
            throw new IllegalStateException(
                    "canonical production lowering omitted an approved operation");
        }
        return Map.copyOf(operations);
    }

    private static Map<String, ProductionResolvedNode> resolvedByLogicalNode(
            ProductionPlanValidation validation) {
        Map<String, ProductionResolvedNode> resolved = new LinkedHashMap<>();
        for (ProductionResolvedNode node : validation.resolvedNodes()) {
            if (resolved.putIfAbsent(node.node().nodeId(), node) != null) {
                throw new IllegalStateException(
                        "accepted production validation exposed a duplicate node");
            }
        }
        Set<String> expected = new LinkedHashSet<>();
        for (ProductionPlanNode node : CANONICAL_TEMPLATE.nodes()) {
            expected.add(node.nodeId());
        }
        if (!resolved.keySet().equals(expected)) {
            throw new IllegalStateException(
                    "accepted production validation does not match the canonical node set");
        }
        return Map.copyOf(resolved);
    }

    /**
     * 一次 {@code BREAK_BLOCK} 只能对账一个目标方块，因此采集账本按物品逐个下沉；普通
     * crafting 也必须每批单独走一次真实菜单。炉子仍可用单个已审核炉次处理其配方内的三份
     * raw iron，因为那是同一次 {@code FURNACE} 菜单合同而不是三个独立 recipe batch。
     */
    private static int fragmentCount(ProductionResolvedNode resolved) {
        ProductionOperation operation = resolved.node().operation();
        if (operation instanceof ResourceAcquisition acquisition) {
            if (acquisition.expectedGain().quantities().size() != 1) {
                throw new IllegalStateException(
                        "canonical acquisition cannot be lowered to one block per fragment");
            }
            return acquisition.expectedGain().quantities().values()
                    .iterator().next();
        }
        if (operation instanceof RecipeExecution execution) {
            return execution.batches();
        }
        if (operation instanceof SingleChestTransfer) {
            return 1;
        }
        throw new IllegalStateException(
                "unrecognized sealed production operation type");
    }

    private static ProductionResolvedNode lowerResolvedNode(
            ProductionResolvedNode source,
            int fragmentIndex,
            int fragmentCount) {
        ProductionPlanNode sourceNode = source.node();
        ProductionOperation operation = sourceNode.operation();
        ProductionPlanNode fragmentNode = new ProductionPlanNode(
                fragmentNodeId(sourceNode.nodeId(), fragmentIndex,
                        fragmentCount),
                lowerOperation(operation, fragmentIndex, fragmentCount),
                sourceNode.checkpoint() && fragmentIndex == fragmentCount);

        if (operation instanceof ResourceAcquisition acquisition) {
            if (!source.expectedPlayerDelta().equals(
                    acquisition.expectedDelta())
                    || source.menuContract().isPresent()
                    || source.furnaceRequirement().isPresent()) {
                throw new IllegalStateException(
                        "canonical acquisition resolved contract drifted before lowering");
            }
            ResourceAcquisition fragment = (ResourceAcquisition) fragmentNode
                    .operation();
            return new ProductionResolvedNode(
                    fragmentNode,
                    fragment.expectedDelta(),
                    Optional.empty(),
                    Optional.empty());
        }
        if (operation instanceof RecipeExecution execution) {
            ProductionRecipe recipe = ProductionRecipeCatalog.p5aDefault()
                    .find(execution.recipeId())
                    .orElseThrow(() -> new IllegalStateException(
                            "canonical recipe disappeared before lowering"));
            ProductionMenuContract fullContract = recipe.menuContract(
                    execution.batches());
            Optional<FurnaceBatchRequirement> fullFurnace = recipe
                    .furnaceRequirement(execution.batches());
            if (!source.expectedPlayerDelta().equals(
                    fullContract.expectedPlayerDelta())
                    || !source.menuContract().equals(Optional.of(fullContract))
                    || !source.furnaceRequirement().equals(fullFurnace)) {
                throw new IllegalStateException(
                        "canonical recipe resolved contract drifted before lowering");
            }
            ProductionMenuContract fragmentContract = recipe.menuContract(1);
            return new ProductionResolvedNode(
                    fragmentNode,
                    fragmentContract.expectedPlayerDelta(),
                    Optional.of(fragmentContract),
                    recipe.furnaceRequirement(1));
        }
        if (operation instanceof SingleChestTransfer) {
            if (fragmentCount != 1 || fragmentIndex != 1) {
                throw new IllegalStateException(
                        "single chest transfer must remain one physical fragment");
            }
            return new ProductionResolvedNode(
                    fragmentNode,
                    source.expectedPlayerDelta(),
                    source.menuContract(),
                    source.furnaceRequirement());
        }
        throw new IllegalStateException(
                "unrecognized sealed production operation type");
    }

    private static ProductionOperation lowerOperation(
            ProductionOperation source,
            int fragmentIndex,
            int fragmentCount) {
        if (fragmentIndex < 1 || fragmentIndex > fragmentCount) {
            throw new IllegalArgumentException(
                    "production fragment index is outside its count");
        }
        if (source instanceof ResourceAcquisition acquisition) {
            Map.Entry<ProductionMaterial, Integer> entry = acquisition
                    .expectedGain().quantities().entrySet().stream()
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "canonical acquisition has no material"));
            if (acquisition.expectedGain().quantities().size() != 1
                    || entry.getValue() != fragmentCount) {
                throw new IllegalStateException(
                        "canonical acquisition fragments do not match its ledger");
            }
            return new ResourceAcquisition(acquisition.method(),
                    ProductionLedger.of(entry.getKey(), 1));
        }
        if (source instanceof RecipeExecution execution) {
            if (execution.batches() != fragmentCount) {
                throw new IllegalStateException(
                        "canonical recipe fragments do not match its batch count");
            }
            return new RecipeExecution(execution.recipeId(), 1);
        }
        if (source instanceof SingleChestTransfer) {
            if (fragmentCount != 1) {
                throw new IllegalStateException(
                        "single chest transfer must have one physical fragment");
            }
            return source;
        }
        throw new IllegalStateException(
                "unrecognized sealed production operation type");
    }

    private static String operationId(
            String sourceNodeId, int fragmentIndex, int fragmentCount) {
        String operationId = OPERATION_ID_PREFIX
                + CANONICAL_TEMPLATE.schema().schemaId()
                + ".v"
                + CANONICAL_TEMPLATE.schema().version()
                + "."
                + fragmentNodeId(sourceNodeId, fragmentIndex, fragmentCount);
        if (operationId.length() > MAX_OPERATION_ID_LENGTH) {
            throw new IllegalStateException(
                    "canonical production fragment operation id exceeds its bound");
        }
        return operationId;
    }

    private static String fragmentNodeId(
            String sourceNodeId, int fragmentIndex, int fragmentCount) {
        if (fragmentIndex < 1 || fragmentIndex > fragmentCount) {
            throw new IllegalArgumentException(
                    "production fragment index is outside its count");
        }
        String nodeId = sourceNodeId
                + ".fragment-"
                + fragmentIndex
                + "-of-"
                + fragmentCount;
        if (nodeId.length() > ProductionPlanNode.MAX_NODE_ID_LENGTH) {
            throw new IllegalStateException(
                    "canonical production fragment node id exceeds its bound");
        }
        return nodeId;
    }

    private static List<LoweredOperation> fragmentsFor(String nodeId) {
        List<LoweredOperation> fragments = LOWERED_OPERATIONS_BY_NODE.get(
                Objects.requireNonNull(nodeId, "nodeId"));
        if (fragments == null || fragments.isEmpty()) {
            throw new IllegalStateException(
                    "canonical production node has no physical fragments");
        }
        return fragments;
    }

    private static void appendEdge(
            List<SkillPlanEdge> edges,
            Set<SkillPlanEdge> uniqueEdges,
            Map<String, UUID> skillNodeIds,
            String beforeOperation,
            String afterOperation) {
        UUID before = skillNodeIds.get(beforeOperation);
        UUID after = skillNodeIds.get(afterOperation);
        if (before == null || after == null) {
            throw new IllegalStateException(
                    "canonical production edge has no stable SkillPlan endpoint");
        }
        appendNodeEdge(edges, uniqueEdges, before, after);
    }

    private static void appendNodeEdge(
            List<SkillPlanEdge> edges,
            Set<SkillPlanEdge> uniqueEdges,
            UUID before,
            UUID after) {
        if (before == null || after == null) {
            throw new IllegalStateException(
                    "canonical production edge has no stable SkillPlan endpoint");
        }
        SkillPlanEdge edge = new SkillPlanEdge(before, after);
        if (!uniqueEdges.add(edge)) {
            throw new IllegalStateException(
                    "canonical production lowering produced a duplicate edge");
        }
        edges.add(edge);
    }

    private static UUID stablePlanId() {
        List<String> signature = new ArrayList<>();
        signature.add(P5ABuiltinSkillIds.BOOTSTRAP_IRON.toString());
        signature.add(P5ABuiltinSkillIds.EQUIP_EXACT_MAIN_HAND.toString());
        signature.add(P5ABuiltinSkillIds.VERSION.toString());
        signature.add(CANONICAL_TEMPLATE.schema().schemaId());
        signature.add(Integer.toString(CANONICAL_TEMPLATE.schema().version()));
        for (ProductionPlanNode node : CANONICAL_TEMPLATE.nodes()) {
            signature.add(node.nodeId());
            signature.add(Boolean.toString(node.checkpoint()));
            signature.add(operationSignature(node.operation()));
            signature.addAll(OPERATION_IDS_BY_NODE.get(node.nodeId()));
        }
        for (LoweredOperation lowered : LOWERED_OPERATIONS) {
            signature.add("fragment");
            signature.add(lowered.sourceNodeId());
            signature.add(Integer.toString(lowered.fragmentIndex()));
            signature.add(Integer.toString(lowered.fragmentCount()));
            signature.add(lowered.operationId());
            signature.add(lowered.resolved().node().nodeId());
            signature.add(Boolean.toString(
                    lowered.resolved().node().checkpoint()));
            signature.add(operationSignature(
                    lowered.resolved().node().operation()));
        }
        for (ProductionPlanEdge edge : CANONICAL_TEMPLATE.edges()) {
            signature.add(edge.beforeNodeId());
            signature.add(edge.afterNodeId());
        }
        for (ExactMainHandStep step : EXACT_MAIN_HAND_STEPS) {
            signature.add("exact-main-hand");
            signature.add(step.nodeId());
            signature.add(step.item().itemId().value());
            signature.add(step.sourceProductionNodeId());
            signature.addAll(step.dependentProductionNodeIds());
            UUID nodeId = EXACT_MAIN_HAND_NODE_IDS.get(step.nodeId());
            if (nodeId == null) {
                throw new IllegalStateException(
                        "canonical exact main-hand step has no stable SkillPlan node id");
            }
            signature.add(nodeId.toString());
        }
        UUID planId = deterministicUuid(PLAN_ID_DOMAIN, signature);
        if (SKILL_NODE_IDS_BY_OPERATION.containsValue(planId)
                || EXACT_MAIN_HAND_NODE_IDS.containsValue(planId)) {
            throw new IllegalStateException(
                    "stable production SkillPlan id collided with a node id");
        }
        return planId;
    }

    private static void verifyResolvedNodes(
            ProductionPlanValidation validation) {
        if (!resolvedByLogicalNode(validation).keySet().equals(
                OPERATION_IDS_BY_NODE.keySet())) {
            throw new IllegalStateException(
                    "accepted production validation does not match the canonical node set");
        }
    }

    private static String operationSignature(ProductionOperation operation) {
        if (operation instanceof ResourceAcquisition acquisition) {
            return "acquire\u0000"
                    + acquisition.method().name()
                    + "\u0000"
                    + ledgerSignature(acquisition.expectedGain());
        }
        if (operation instanceof RecipeExecution execution) {
            return "recipe\u0000"
                    + execution.recipeId().value()
                    + "\u0000"
                    + execution.batches();
        }
        if (operation instanceof SingleChestTransfer transfer) {
            return "chest\u0000"
                    + ledgerSignature(transfer.depositToChest())
                    + "\u0000"
                    + ledgerSignature(transfer.withdrawFromChest())
                    + "\u0000"
                    + transfer.maximumClicks();
        }
        throw new IllegalStateException(
                "unrecognized sealed production operation type");
    }

    private static String ledgerSignature(ProductionLedger ledger) {
        StringBuilder signature = new StringBuilder();
        ledger.quantities().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> signature.append(entry.getKey().id().value())
                        .append('=')
                        .append(entry.getValue())
                        .append(';'));
        return signature.toString();
    }

    /**
     * 逻辑节点的一个已审核物理 fragment。该记录刻意不包含 bot、run、坐标、容器 session
     * 或活动 Minecraft 对象，因此可安全地作为 compile-time 白名单的一部分。
     */
    private record LoweredOperation(
            String sourceNodeId,
            int fragmentIndex,
            int fragmentCount,
            String operationId,
            ProductionResolvedNode resolved) {
        private LoweredOperation {
            Objects.requireNonNull(sourceNodeId, "sourceNodeId");
            if (fragmentIndex < 1 || fragmentCount < fragmentIndex) {
                throw new IllegalArgumentException(
                        "production fragment bounds are invalid");
            }
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(resolved, "resolved");
            if (resolved.node().checkpoint()
                    && fragmentIndex != fragmentCount) {
                throw new IllegalArgumentException(
                        "only a logical node's final fragment may be a checkpoint");
            }
        }
    }

    /**
     * 一个不属于 {@link ProductionOperation} 的固定装备门。它只引用已完成的生产逻辑节点和
     * 其全部直接后继，因此无法通过遗漏一条原 template 边绕开所需工具。
     */
    private record ExactMainHandStep(
            String nodeId,
            ExactMainHandItem item,
            String sourceProductionNodeId,
            List<String> dependentProductionNodeIds) {
        private ExactMainHandStep {
            Objects.requireNonNull(nodeId, "nodeId");
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(sourceProductionNodeId,
                    "sourceProductionNodeId");
            dependentProductionNodeIds = List.copyOf(Objects.requireNonNull(
                    dependentProductionNodeIds,
                    "dependentProductionNodeIds"));
            if (nodeId.isBlank() || sourceProductionNodeId.isBlank()
                    || dependentProductionNodeIds.stream().anyMatch(
                            value -> value == null || value.isBlank())
                    || new LinkedHashSet<>(dependentProductionNodeIds).size()
                            != dependentProductionNodeIds.size()) {
                throw new IllegalArgumentException(
                        "exact main-hand step identifiers must be unique, nonblank production ids");
            }
        }
    }

    private static UUID deterministicUuid(
            String domain, List<String> components) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, domain);
            for (String component : components) {
                update(digest, component);
            }
            ByteBuffer bytes = ByteBuffer.wrap(digest.digest());
            long most = bytes.getLong();
            long least = bytes.getLong();
            most = (most & 0xffffffffffff0fffL)
                    | 0x0000000000005000L;
            least = (least & 0x3fffffffffffffffL)
                    | 0x8000000000000000L;
            UUID value = new UUID(most, least);
            if (value.getMostSignificantBits() == 0L
                    && value.getLeastSignificantBits() == 0L) {
                throw new IllegalStateException(
                        "deterministic production UUID must not be zero");
            }
            return value;
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = Objects.requireNonNull(value, "digest component")
                .getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }
}
