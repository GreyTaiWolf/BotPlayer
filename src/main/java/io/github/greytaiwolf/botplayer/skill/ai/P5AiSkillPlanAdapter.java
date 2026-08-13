package io.github.greytaiwolf.botplayer.skill.ai;

import io.github.greytaiwolf.botplayer.ai.plan.AiPlanBinding;
import io.github.greytaiwolf.botplayer.ai.plan.AiPlanSubmission;
import io.github.greytaiwolf.botplayer.ai.plan.AiPlanSubmissionStatus;
import io.github.greytaiwolf.botplayer.ai.plan.AiPlanValidation;
import io.github.greytaiwolf.botplayer.ai.plan.AiPlanValidationStatus;
import io.github.greytaiwolf.botplayer.ai.plan.AiSkillCatalog;
import io.github.greytaiwolf.botplayer.ai.plan.AiSkillCatalogPort;
import io.github.greytaiwolf.botplayer.ai.plan.AiSkillCatalogQuery;
import io.github.greytaiwolf.botplayer.ai.plan.AiSkillPlanPort;
import io.github.greytaiwolf.botplayer.ai.plan.AiValidatedSkillPlan;
import io.github.greytaiwolf.botplayer.ai.plan.AiVisibleSkillDescriptor;
import io.github.greytaiwolf.botplayer.ai.tool.ProposedSkillPlan;
import io.github.greytaiwolf.botplayer.ai.tool.ProposedToolCall;
import io.github.greytaiwolf.botplayer.ai.tool.ToolDefinition;
import io.github.greytaiwolf.botplayer.ai.tool.ToolFirewall;
import io.github.greytaiwolf.botplayer.ai.tool.ToolValue;
import io.github.greytaiwolf.botplayer.skill.core.SkillCategory;
import io.github.greytaiwolf.botplayer.skill.core.SkillDescriptor;
import io.github.greytaiwolf.botplayer.skill.core.SkillParameterRule;
import io.github.greytaiwolf.botplayer.skill.core.SkillParameters;
import io.github.greytaiwolf.botplayer.skill.core.SkillRegistry;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlan;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanEdge;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanNode;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanValidation;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanValidator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * P5-owned implementation of the narrow P6 {@link AiSkillPlanPort} boundary.
 *
 * <p>It accepts only a firewall-approved batch of pre-registered tools, converts each call to a
 * scalar-only {@link SkillParameters} instance, and builds a deterministic serial DAG.  P6 never
 * receives that DAG: it receives an opaque, single-use token and can submit it only through this
 * adapter.  Every validation and submission rechecks server-derived freshness through
 * {@link P5AiPlanBindingAuthority}; this class intentionally owns no Minecraft object and has no
 * model/network callback entry point.
 */
public final class P5AiSkillPlanAdapter
        implements AiSkillCatalogPort, AiSkillPlanPort {
    private static final int MAX_ID_ATTEMPTS = 16;
    private static final Pattern SKILL_PARAMETER_NAME = Pattern.compile(
            "[a-z][a-z0-9_.-]{0,63}");

    private final Thread ownerThread;
    private final SkillRegistry registry;
    private final SkillPlanValidator planValidator;
    private final P5AiSkillPlanAdapterPolicy policy;
    private final ToolFirewall firewall;
    private final Map<String, P5AiToolSkillBinding> bindingsByTool;
    private final P5AiPlanBindingAuthority authority;
    private final P5AiPlanSubmitter submitter;
    private final LongSupplier tickSupplier;
    private final Supplier<UUID> idSupplier;
    private final Map<UUID, IssuedPlan> issuedByValidationId = new LinkedHashMap<>();
    private final Map<UUID, IssuedPlan> issuedByRequestId = new LinkedHashMap<>();
    private final Map<UUID, Long> consumedRequestExpiryTicks = new LinkedHashMap<>();

    public P5AiSkillPlanAdapter(
            SkillRegistry registry,
            SkillPlanValidator planValidator,
            P5AiSkillPlanAdapterPolicy policy,
            Map<String, P5AiToolSkillBinding> bindings,
            P5AiPlanBindingAuthority authority,
            P5AiPlanSubmitter submitter,
            LongSupplier tickSupplier) {
        this(
                registry,
                planValidator,
                policy,
                bindings,
                authority,
                submitter,
                tickSupplier,
                UUID::randomUUID);
    }

    /** Visible for deterministic pure-Java tests; production uses random non-zero identities. */
    P5AiSkillPlanAdapter(
            SkillRegistry registry,
            SkillPlanValidator planValidator,
            P5AiSkillPlanAdapterPolicy policy,
            Map<String, P5AiToolSkillBinding> bindings,
            P5AiPlanBindingAuthority authority,
            P5AiPlanSubmitter submitter,
            LongSupplier tickSupplier,
            Supplier<UUID> idSupplier) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.planValidator = Objects.requireNonNull(planValidator, "planValidator");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.firewall = new ToolFirewall(policy.firewallPolicy());
        this.bindingsByTool = checkedBindings(bindings, policy);
        this.authority = Objects.requireNonNull(authority, "authority");
        this.submitter = Objects.requireNonNull(submitter, "submitter");
        this.tickSupplier = Objects.requireNonNull(tickSupplier, "tickSupplier");
        this.idSupplier = Objects.requireNonNull(idSupplier, "idSupplier");
        this.ownerThread = Thread.currentThread();
    }

    /**
     * Returns only the static bindings which are currently registered, permitted and fresh.
     * A stale query intentionally receives an empty catalog rather than an error that could leak
     * server authority details.
     */
    @Override
    public AiSkillCatalog visibleSkills(AiSkillCatalogQuery query) {
        requireOwnerThread();
        AiSkillCatalogQuery checkedQuery = Objects.requireNonNull(query, "query");
        long currentTick = currentTick();
        purgeExpiredTokens(currentTick);
        if (!isCurrent(checkedQuery.binding(), currentTick)) {
            return new AiSkillCatalog(checkedQuery, List.of());
        }
        List<AiVisibleSkillDescriptor> visible = new ArrayList<>();
        for (P5AiToolSkillBinding binding : bindingsByTool.values()) {
            if (visible.size() >= checkedQuery.maximumSkills()) {
                break;
            }
            ToolDefinition definition = policy.firewallPolicy().knownTools().get(
                    binding.toolName());
            SkillDescriptor descriptor = descriptorFor(binding);
            if (definition == null || !policy.firewallPolicy().requestWhitelist().contains(
                    binding.toolName())
                    || !definition.riskLevel().isAtMost(policy.firewallPolicy().maximumRisk())
                    || descriptor == null || !isDescriptorAllowed(descriptor)) {
                continue;
            }
            visible.add(new AiVisibleSkillDescriptor(
                    binding.toolName(),
                    descriptor.id(),
                    descriptor.version(),
                    descriptor.category(),
                    descriptor.risk(),
                    definition,
                    binding.summary()));
        }
        return new AiSkillCatalog(checkedQuery, visible);
    }

    /**
     * Re-checks static Firewall rules and creates an opaque one-time capability only after the
     * canonical P5 plan validates.  No caller can supply node ids, skill ids, plan revision or
     * execution order.
     */
    @Override
    public AiPlanValidation validate(
            AiPlanBinding binding, ProposedSkillPlan proposal) {
        requireOwnerThread();
        AiPlanBinding checkedBinding = Objects.requireNonNull(binding, "binding");
        ProposedSkillPlan checkedProposal = Objects.requireNonNull(proposal, "proposal");
        long currentTick = currentTick();
        purgeExpiredTokens(currentTick);
        if (!checkedBinding.matches(checkedProposal)) {
            return AiPlanValidation.rejected(AiPlanValidationStatus.REQUEST_MISMATCH);
        }
        if (!isCurrent(checkedBinding, currentTick)) {
            return AiPlanValidation.rejected(AiPlanValidationStatus.BINDING_STALE);
        }
        if (issuedByRequestId.containsKey(checkedBinding.requestId())
                || consumedRequestExpiryTicks.containsKey(checkedBinding.requestId())) {
            return AiPlanValidation.rejected(
                    AiPlanValidationStatus.REQUEST_ALREADY_CONSUMED);
        }
        if (checkedProposal.toolCalls().size() > policy.maximumToolCalls()
                || !firewall.review(checkedProposal).acceptedByStaticRules()) {
            return AiPlanValidation.rejected(AiPlanValidationStatus.POLICY_REJECTED);
        }
        if (issuedByValidationId.size() + consumedRequestExpiryTicks.size()
                >= policy.maximumIssuedTokens()) {
            return AiPlanValidation.rejected(AiPlanValidationStatus.PORT_NOT_READY);
        }

        final SkillPlan plan;
        try {
            plan = mapCanonicalPlan(checkedBinding, checkedProposal);
        } catch (MappingRejectedException exception) {
            return AiPlanValidation.rejected(AiPlanValidationStatus.TOOL_MAPPING_REJECTED);
        } catch (RuntimeException exception) {
            return AiPlanValidation.rejected(AiPlanValidationStatus.INTERNAL_FAILURE);
        }
        SkillPlanValidation validation = planValidator.validate(plan);
        if (!validation.valid()) {
            return AiPlanValidation.rejected(AiPlanValidationStatus.PLAN_INVALID);
        }

        UUID validationId = nextId(issuedByValidationId.keySet());
        if (validationId == null) {
            return AiPlanValidation.rejected(AiPlanValidationStatus.PORT_NOT_READY);
        }
        IssuedPlan issued = new IssuedPlan(
                validationId,
                checkedBinding,
                plan,
                checkedProposal.toolCalls().size());
        issuedByValidationId.put(validationId, issued);
        issuedByRequestId.put(checkedBinding.requestId(), issued);
        return AiPlanValidation.accepted(checkedBinding, checkedProposal, issued);
    }

    /**
     * Consumes a token before rechecking the live binding and runtime submission.  A network retry,
     * stale revision or failing downstream runtime can never reuse a previously model-authored plan.
     */
    @Override
    public AiPlanSubmission submit(AiValidatedSkillPlan validatedPlan) {
        requireOwnerThread();
        if (!(Objects.requireNonNull(validatedPlan, "validatedPlan") instanceof IssuedPlan issued)) {
            return AiPlanSubmission.rejected(
                    AiPlanSubmissionStatus.TOKEN_NOT_ISSUED_BY_PORT);
        }
        IssuedPlan stored = issuedByValidationId.remove(issued.validationId());
        if (stored != issued) {
            return AiPlanSubmission.rejected(
                    AiPlanSubmissionStatus.TOKEN_NOT_ISSUED_BY_PORT);
        }
        issuedByRequestId.remove(issued.binding().requestId(), issued);
        consumedRequestExpiryTicks.put(
                issued.binding().requestId(), issued.binding().expiresAtTick());

        long currentTick = currentTick();
        if (!isCurrent(issued.binding(), currentTick)) {
            return AiPlanSubmission.rejected(AiPlanSubmissionStatus.BINDING_STALE);
        }
        if (!planValidator.validate(issued.plan()).valid()) {
            return AiPlanSubmission.rejected(AiPlanSubmissionStatus.EXECUTION_REJECTED);
        }
        try {
            AiPlanSubmission result = submitter.submit(
                    issued.binding(), issued.plan(), currentTick);
            if (result == null) {
                return AiPlanSubmission.rejected(AiPlanSubmissionStatus.INTERNAL_FAILURE);
            }
            return result;
        } catch (RuntimeException exception) {
            return AiPlanSubmission.rejected(AiPlanSubmissionStatus.INTERNAL_FAILURE);
        }
    }

    /** Returns the bounded number of unsubmitted opaque tokens; suitable for server diagnostics. */
    public int issuedTokenCount() {
        requireOwnerThread();
        purgeExpiredTokens(currentTick());
        return issuedByValidationId.size();
    }

    private SkillPlan mapCanonicalPlan(
            AiPlanBinding binding, ProposedSkillPlan proposal) {
        UUID planId = nextId(Set.of());
        if (planId == null) {
            throw new MappingRejectedException();
        }
        List<SkillPlanNode> nodes = new ArrayList<>(proposal.toolCalls().size());
        List<SkillPlanEdge> edges = new ArrayList<>(Math.max(
                0, proposal.toolCalls().size() - 1));
        Set<UUID> usedNodeIds = new HashSet<>();
        UUID previousNode = null;
        for (ProposedToolCall call : proposal.toolCalls()) {
            P5AiToolSkillBinding toolBinding = bindingsByTool.get(call.toolName());
            if (toolBinding == null) {
                throw new MappingRejectedException();
            }
            SkillDescriptor descriptor = descriptorFor(toolBinding);
            if (descriptor == null || !isDescriptorAllowed(descriptor)) {
                throw new MappingRejectedException();
            }
            SkillParameters parameters = mapParameters(call, toolBinding, descriptor);
            UUID nodeId = nextId(usedNodeIds);
            if (nodeId == null) {
                throw new MappingRejectedException();
            }
            usedNodeIds.add(nodeId);
            nodes.add(new SkillPlanNode(
                    nodeId,
                    descriptor.id(),
                    descriptor.version(),
                    parameters));
            if (previousNode != null) {
                /* Tool batches have no model-provided dependency graph: preserve arrival order serially. */
                edges.add(new SkillPlanEdge(previousNode, nodeId));
            }
            previousNode = nodeId;
        }
        return new SkillPlan(planId, binding.botId(), binding.revision(), nodes, edges);
    }

    private SkillParameters mapParameters(
            ProposedToolCall call,
            P5AiToolSkillBinding toolBinding,
            SkillDescriptor descriptor) {
        ToolDefinition definition = policy.firewallPolicy().knownTools().get(call.toolName());
        if (definition == null) {
            throw new MappingRejectedException();
        }
        Map<String, Object> values = new HashMap<>();
        for (Map.Entry<String, ToolValue> entry : call.arguments().entrySet()) {
            io.github.greytaiwolf.botplayer.ai.tool.ToolParameterRule toolRule =
                    definition.parameters().get(entry.getKey());
            String targetParameter = toolBinding.parameterMappings().getOrDefault(
                    entry.getKey(), entry.getKey());
            SkillParameterRule skillRule;
            try {
                skillRule = descriptor.parameterSchema()
                        .rule(targetParameter)
                        .orElseThrow(MappingRejectedException::new);
            } catch (IllegalArgumentException exception) {
                throw new MappingRejectedException();
            }
            if (toolRule == null) {
                throw new MappingRejectedException();
            }
            Object mapped = mapScalar(entry.getValue(), toolRule, skillRule);
            if (values.put(targetParameter, mapped) != null) {
                throw new MappingRejectedException();
            }
        }
        SkillParameters parameters = new SkillParameters(values);
        if (!descriptor.parameterSchema().validate(parameters).valid()) {
            throw new MappingRejectedException();
        }
        return parameters;
    }

    private static Object mapScalar(
            ToolValue value,
            io.github.greytaiwolf.botplayer.ai.tool.ToolParameterRule toolRule,
            SkillParameterRule skillRule) {
        if (value.type() != toolRule.type()) {
            throw new MappingRejectedException();
        }
        return switch (skillRule.type()) {
            case STRING -> {
                if (!(value instanceof ToolValue.StringValue stringValue)) {
                    throw new MappingRejectedException();
                }
                yield stringValue.value();
            }
            case BOOLEAN -> {
                if (!(value instanceof ToolValue.BooleanValue booleanValue)) {
                    throw new MappingRejectedException();
                }
                yield booleanValue.value();
            }
            case INTEGER -> {
                if (!(value instanceof ToolValue.IntegerValue integerValue)) {
                    throw new MappingRejectedException();
                }
                try {
                    yield Math.toIntExact(integerValue.value());
                } catch (ArithmeticException exception) {
                    throw new MappingRejectedException();
                }
            }
            case LONG -> {
                if (!(value instanceof ToolValue.IntegerValue integerValue)) {
                    throw new MappingRejectedException();
                }
                yield integerValue.value();
            }
            case DECIMAL -> {
                if (!(value instanceof ToolValue.IntegerValue integerValue)
                        || integerValue.value() < -(1L << 53)
                        || integerValue.value() > (1L << 53)) {
                    throw new MappingRejectedException();
                }
                yield (double) integerValue.value();
            }
        };
    }

    private boolean isCurrent(AiPlanBinding binding, long currentTick) {
        if (currentTick < binding.issuedAtTick() || binding.isExpiredAt(currentTick)) {
            return false;
        }
        try {
            return authority.isCurrent(binding, currentTick);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean isDescriptorAllowed(SkillDescriptor descriptor) {
        return policy.allowedCategories().contains(descriptor.category())
                && descriptor.risk().ordinal() <= policy.maximumSkillRisk().ordinal();
    }

    private SkillDescriptor descriptorFor(P5AiToolSkillBinding binding) {
        return registry.find(binding.skillId(), binding.skillVersion()).orElse(null);
    }

    private void purgeExpiredTokens(long currentTick) {
        List<IssuedPlan> expired = issuedByValidationId.values().stream()
                .filter(token -> token.binding().isExpiredAt(currentTick))
                .toList();
        for (IssuedPlan token : expired) {
            issuedByValidationId.remove(token.validationId(), token);
            issuedByRequestId.remove(token.binding().requestId(), token);
        }
        consumedRequestExpiryTicks.entrySet().removeIf(entry ->
                currentTick >= entry.getValue());
    }

    private long currentTick() {
        long currentTick = tickSupplier.getAsLong();
        if (currentTick < 0L) {
            throw new IllegalStateException("current tick must not be negative");
        }
        return currentTick;
    }

    private UUID nextId(Set<UUID> forbidden) {
        for (int attempt = 0; attempt < MAX_ID_ATTEMPTS; attempt++) {
            UUID candidate = idSupplier.get();
            if (candidate != null
                    && (candidate.getMostSignificantBits() != 0L
                            || candidate.getLeastSignificantBits() != 0L)
                    && !forbidden.contains(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static Map<String, P5AiToolSkillBinding> checkedBindings(
            Map<String, P5AiToolSkillBinding> bindings,
            P5AiSkillPlanAdapterPolicy policy) {
        Objects.requireNonNull(bindings, "bindings");
        if (bindings.isEmpty() || bindings.size() > AiSkillCatalogQuery.MAXIMUM_SKILLS) {
            throw new IllegalArgumentException("binding count is outside the catalog bound");
        }
        Map<String, P5AiToolSkillBinding> copied = new LinkedHashMap<>();
        bindings.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .forEach(entry -> {
                    String key = Objects.requireNonNull(entry.getKey(), "binding tool name");
                    P5AiToolSkillBinding binding = Objects.requireNonNull(
                            entry.getValue(), "tool skill binding");
                    ToolDefinition definition = policy.firewallPolicy().knownTools().get(key);
                    if (!key.equals(binding.toolName()) || definition == null
                            || !policy.firewallPolicy().requestWhitelist().contains(key)) {
                        throw new IllegalArgumentException(
                                "each P5 AI binding must name a whitelisted firewall tool");
                    }
                    for (String sourceParameter : binding.parameterMappings().keySet()) {
                        if (!definition.parameters().containsKey(sourceParameter)) {
                            throw new IllegalArgumentException(
                                    "parameter mapping must name a schema parameter");
                        }
                    }
                    Set<String> targetParameters = new HashSet<>();
                    for (Map.Entry<String, io.github.greytaiwolf.botplayer.ai.tool.ToolParameterRule>
                            parameter : definition.parameters().entrySet()) {
                        String sourceParameter = parameter.getKey();
                        String targetParameter = binding.parameterMappings().getOrDefault(
                                sourceParameter, sourceParameter);
                        if (!SKILL_PARAMETER_NAME.matcher(targetParameter).matches()
                                || !targetParameters.add(targetParameter)
                                || !isScalarToolRule(parameter.getValue())) {
                            throw new IllegalArgumentException(
                                    "P5 AI tools need unique scalar parameter mappings");
                        }
                    }
                    if (copied.put(key, binding) != null) {
                        throw new IllegalArgumentException("duplicate P5 AI tool binding");
                    }
                });
        return Collections.unmodifiableMap(new LinkedHashMap<>(copied));
    }

    private static boolean isScalarToolRule(
            io.github.greytaiwolf.botplayer.ai.tool.ToolParameterRule rule) {
        return switch (rule.type()) {
            case STRING, INTEGER, BOOLEAN -> true;
            case NULL, ARRAY, OBJECT -> false;
        };
    }

    private void requireOwnerThread() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException(
                    "P5 AI plan adapter must run on its owner server thread");
        }
    }

    private static final class MappingRejectedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private MappingRejectedException() {
            super(null, null, false, false);
        }
    }

    /** Private identity token: neither P6 nor another adapter can manufacture one. */
    private static final class IssuedPlan implements AiValidatedSkillPlan {
        private final UUID validationId;
        private final AiPlanBinding binding;
        private final SkillPlan plan;
        private final int sourceToolCallCount;

        private IssuedPlan(
                UUID validationId,
                AiPlanBinding binding,
                SkillPlan plan,
                int sourceToolCallCount) {
            this.validationId = Objects.requireNonNull(validationId, "validationId");
            this.binding = Objects.requireNonNull(binding, "binding");
            this.plan = Objects.requireNonNull(plan, "plan");
            this.sourceToolCallCount = sourceToolCallCount;
        }

        @Override
        public UUID validationId() {
            return validationId;
        }

        @Override
        public AiPlanBinding binding() {
            return binding;
        }

        @Override
        public int sourceToolCallCount() {
            return sourceToolCallCount;
        }

        @Override
        public int nodeCount() {
            return plan.nodes().size();
        }

        @Override
        public int edgeCount() {
            return plan.edges().size();
        }

        private SkillPlan plan() {
            return plan;
        }

        @Override
        public String toString() {
            return "IssuedPlan[nodeCount=" + plan.nodes().size()
                    + ", edgeCount=" + plan.edges().size() + "]";
        }
    }
}
