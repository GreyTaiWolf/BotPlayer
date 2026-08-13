package io.github.greytaiwolf.botplayer.skill.pack;

import io.github.greytaiwolf.botplayer.skill.core.SkillDescriptor;
import io.github.greytaiwolf.botplayer.skill.core.SkillRegistry;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanNode;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanValidation;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanValidator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 对已解析的声明式包执行白名单、精确版本、风险和 DAG 的静态审核。
 *
 * <p>该层从未接收可执行对象、类名、反射句柄、命令或动作对象；正文只能引用注册 descriptor。
 */
public final class SkillPackValidator {
    private final SkillRegistry registry;
    private final SkillPackPolicy policy;
    private final SkillPlanValidator planValidator;

    public SkillPackValidator(
            SkillRegistry registry, SkillPackPolicy policy) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.policy = Objects.requireNonNull(policy, "policy");
        planValidator = new SkillPlanValidator(
                registry, policy.limits().planLimits());
    }

    public SkillPackValidation validate(SkillPackCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        SkillPackDefinition definition = candidate.definition();
        List<SkillPackViolation> violations = new ArrayList<>();
        validateHeader(candidate, violations);
        validateDescriptors(definition, violations);
        validateDeclaredReferences(definition, violations);
        SkillPlanValidation planValidation = planValidator.validate(
                definition.plan());
        if (!planValidation.valid()) {
            add(
                    violations,
                    SkillPackViolation.Code.PLAN_INVALID,
                    null,
                    "技能包计划没有通过注册表和 DAG 静态检查");
        }
        return new SkillPackValidation(
                candidate.revision(), violations, planValidation);
    }

    private void validateHeader(
            SkillPackCandidate candidate,
            List<SkillPackViolation> violations) {
        SkillPackDefinition definition = candidate.definition();
        if (!policy.supportsSchema(definition.schemaVersion())) {
            add(
                    violations,
                    SkillPackViolation.Code.UNSUPPORTED_SCHEMA,
                    null,
                    "技能包 schema 版本不在服务器白名单中");
        }
        if (candidate.source().byteCount()
                > policy.limits().maximumSourceBytes()) {
            add(
                    violations,
                    SkillPackViolation.Code.SOURCE_TOO_LARGE,
                    null,
                    "技能包字节数超过服务器上限");
        }
        if (!candidate.source().matches(definition.id())) {
            add(
                    violations,
                    SkillPackViolation.Code.SOURCE_PATH_MISMATCH,
                    null,
                    "技能包路径与声明 id 不匹配");
        }
        if (definition.descriptorReferences().size()
                > policy.limits().maximumDescriptorReferences()) {
            add(
                    violations,
                    SkillPackViolation.Code
                            .DESCRIPTOR_REFERENCE_LIMIT_EXCEEDED,
                    null,
                    "技能包 descriptor 引用数超过服务器上限");
        }
    }

    private void validateDescriptors(
            SkillPackDefinition definition,
            List<SkillPackViolation> violations) {
        for (SkillPackDescriptorReference reference :
                definition.descriptorReferences()) {
            if (!policy.allows(reference)) {
                add(
                        violations,
                        SkillPackViolation.Code
                                .DESCRIPTOR_NOT_ALLOWLISTED,
                        null,
                        "技能包引用了未获准的 descriptor "
                                + reference.skillId());
            }
            SkillDescriptor descriptor = registry.find(
                    reference.skillId(), reference.skillVersion())
                    .orElse(null);
            if (descriptor == null) {
                add(
                        violations,
                        SkillPackViolation.Code
                                .DESCRIPTOR_NOT_REGISTERED,
                        null,
                        "技能包引用了未注册 descriptor "
                                + reference.skillId());
                continue;
            }
            if (descriptor.risk().ordinal()
                    > policy.limits().maximumRisk().ordinal()) {
                add(
                        violations,
                        SkillPackViolation.Code
                                .DESCRIPTOR_RISK_EXCEEDED,
                        null,
                        "技能包 descriptor 风险超过服务器上限");
            }
        }
    }

    private static void validateDeclaredReferences(
            SkillPackDefinition definition,
            List<SkillPackViolation> violations) {
        Set<SkillPackDescriptorReference> used = new HashSet<>();
        for (SkillPlanNode node : definition.plan().nodes()) {
            SkillPackDescriptorReference reference =
                    new SkillPackDescriptorReference(
                            node.skillId(), node.skillVersion());
            used.add(reference);
            if (!definition.descriptorReferences().contains(reference)) {
                add(
                        violations,
                        SkillPackViolation.Code
                                .PLAN_NODE_NOT_DECLARED,
                        node.nodeId(),
                        "计划节点没有对应的 descriptor 声明");
            }
        }
        for (SkillPackDescriptorReference reference :
                definition.descriptorReferences()) {
            if (!used.contains(reference)) {
                add(
                        violations,
                        SkillPackViolation.Code
                                .DECLARED_DESCRIPTOR_UNUSED,
                        null,
                        "descriptor 声明没有被计划节点使用");
            }
        }
    }

    private static void add(
            List<SkillPackViolation> violations,
            SkillPackViolation.Code code,
            UUID nodeId,
            String summary) {
        if (violations.size() < SkillPackValidation.MAX_VIOLATIONS) {
            violations.add(new SkillPackViolation(
                    code, java.util.Optional.ofNullable(nodeId), summary));
        }
    }
}
