package io.github.greytaiwolf.botplayer.skill.pack;

import io.github.greytaiwolf.botplayer.skill.core.SkillCategory;
import io.github.greytaiwolf.botplayer.skill.core.SkillDescriptor;
import io.github.greytaiwolf.botplayer.skill.core.SkillId;
import io.github.greytaiwolf.botplayer.skill.core.SkillParameterSchema;
import io.github.greytaiwolf.botplayer.skill.core.SkillParameters;
import io.github.greytaiwolf.botplayer.skill.core.SkillRegistry;
import io.github.greytaiwolf.botplayer.skill.core.SkillRiskLevel;
import io.github.greytaiwolf.botplayer.skill.core.SkillVersion;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlan;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanLimits;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanNode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SkillPackApprovalServiceTest {
    private static final UUID PLAN_ID = new UUID(1L, 1L);
    private static final UUID BOT_ID = new UUID(1L, 2L);
    private static final UUID NODE_ID = new UUID(1L, 3L);
    private static final UUID ADMIN_ID = new UUID(2L, 1L);
    private static final SkillPackId PACK_ID =
            new SkillPackId("botplayer", "wood_to_stone");
    private static final SkillId COLLECT =
            new SkillId("botplayer", "resource/collect");
    private static final SkillVersion VERSION =
            new SkillVersion(1, 0, 0);
    private static final SkillPackDescriptorReference COLLECT_REF =
            new SkillPackDescriptorReference(COLLECT, VERSION);

    @Test
    void stagesOnlyStaticValidPackAndBindsApprovalToHash() {
        SkillPackApprovalService service = service(
                SkillRiskLevel.MODERATE, Set.of(COLLECT_REF));
        SkillPackCandidate first = candidate(
                1, Set.of(COLLECT_REF), source("{\"nodes\":1}"));

        SkillPackTransition staged = service.stage(first, 10L);

        Assertions.assertEquals(
                SkillPackTransition.Status.STAGED, staged.status());
        Assertions.assertEquals(
                SkillPackState.STAGED,
                staged.record().orElseThrow().state());
        SkillPackTransition approved = service.approve(
                first.revision(), ADMIN_ID, 11L);
        Assertions.assertEquals(
                SkillPackTransition.Status.APPROVED, approved.status());
        Assertions.assertEquals(
                ADMIN_ID,
                approved.record().orElseThrow().reviewedBy().orElseThrow());
        Assertions.assertTrue(service.approved(first.revision()).isPresent());

        SkillPackCandidate changedBytes = candidate(
                1, Set.of(COLLECT_REF), source("{\"nodes\":2}"));
        SkillPackTransition restaged = service.stage(changedBytes, 12L);

        Assertions.assertEquals(
                SkillPackTransition.Status.STAGED, restaged.status());
        Assertions.assertNotEquals(
                first.revision().contentHash(),
                changedBytes.revision().contentHash());
        Assertions.assertTrue(service.approved(first.revision()).isEmpty());
        Assertions.assertEquals(
                SkillPackTransition.Status.REVISION_MISMATCH,
                service.approve(first.revision(), ADMIN_ID, 13L).status());
        Assertions.assertEquals(
                SkillPackTransition.Status.APPROVED,
                service.approve(changedBytes.revision(), ADMIN_ID, 13L)
                        .status());
    }

    @Test
    void rejectsUnapprovedReferencesAndManifestPlanMismatch() {
        SkillPackApprovalService service = service(
                SkillRiskLevel.MODERATE, Set.of());
        SkillPackCandidate candidate = candidate(
                1, Set.of(), source("{}"));

        SkillPackTransition transition = service.stage(candidate, 1L);

        Assertions.assertEquals(
                SkillPackTransition.Status.REJECTED_BY_VALIDATION,
                transition.status());
        Assertions.assertEquals(
                SkillPackState.REJECTED,
                transition.record().orElseThrow().state());
        Assertions.assertEquals(
                Set.of(
                        SkillPackViolation.Code
                                .PLAN_NODE_NOT_DECLARED),
                transition.record().orElseThrow().validation()
                        .violations().stream()
                        .map(SkillPackViolation::code)
                        .collect(java.util.stream.Collectors.toSet()));
        Assertions.assertEquals(
                SkillPackTransition.Status.NOT_STAGED,
                service.approve(candidate.revision(), ADMIN_ID, 2L).status());
    }

    @Test
    void rejectsUnsupportedSchemaPathSizeAndDescriptorRisk() {
        SkillPackApprovalService schemaService = service(
                SkillRiskLevel.MODERATE, Set.of(COLLECT_REF));
        SkillPackCandidate unsupportedSchema = candidate(
                2, Set.of(COLLECT_REF), source("{}"));
        Assertions.assertTrue(schemaService.stage(unsupportedSchema, 1L)
                .record().orElseThrow().validation().violations().stream()
                .anyMatch(value -> value.code()
                        == SkillPackViolation.Code.UNSUPPORTED_SCHEMA));

        SkillPackCandidate wrongPath = new SkillPackCandidate(
                definition(1, Set.of(COLLECT_REF)),
                new SkillPackSource(
                        "data/botplayer/botplayer/skills/other.json",
                        "{\"different\":true}".getBytes(
                                StandardCharsets.UTF_8)));
        Assertions.assertTrue(schemaService.stage(wrongPath, 2L)
                .record().orElseThrow().validation().violations().stream()
                .anyMatch(value -> value.code()
                        == SkillPackViolation.Code.SOURCE_PATH_MISMATCH));

        SkillPackPolicy bytePolicy = new SkillPackPolicy(
                new SkillPackLimits(
                        1,
                        8,
                        8,
                        new SkillPlanLimits(8, 8, 8),
                        SkillRiskLevel.MODERATE),
                Set.of(1),
                Set.of(COLLECT_REF));
        SkillPackApprovalService byteService = new SkillPackApprovalService(
                new SkillPackValidator(registry(SkillRiskLevel.LOW), bytePolicy),
                8);
        Assertions.assertTrue(byteService.stage(candidate(
                1, Set.of(COLLECT_REF), source("{}")), 1L)
                .record().orElseThrow().validation().violations().stream()
                .anyMatch(value -> value.code()
                        == SkillPackViolation.Code.SOURCE_TOO_LARGE));

        SkillPackApprovalService riskService = service(
                SkillRiskLevel.MODERATE, Set.of(COLLECT_REF),
                SkillRiskLevel.HIGH);
        Assertions.assertTrue(riskService.stage(candidate(
                1, Set.of(COLLECT_REF), source("{}")), 1L)
                .record().orElseThrow().validation().violations().stream()
                .anyMatch(value -> value.code()
                        == SkillPackViolation.Code.DESCRIPTOR_RISK_EXCEEDED));
    }

    @Test
    void administratorIdentityAndSourceBytesAreDefensivelyBounded() {
        SkillPackApprovalService service = service(
                SkillRiskLevel.MODERATE, Set.of(COLLECT_REF));
        byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
        SkillPackSource source = new SkillPackSource(
                PACK_ID.expectedSourcePath(), bytes);
        SkillPackHash hash = source.contentHash();
        bytes[0] = '[';
        Assertions.assertEquals(hash, source.contentHash());
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> service.approve(
                        candidate(1, Set.of(COLLECT_REF), source)
                                .revision(),
                        new UUID(0L, 0L),
                        1L));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new SkillPackSource(
                        "../bad.json", new byte[0]));
    }

    private static SkillPackApprovalService service(
            SkillRiskLevel maximumRisk,
            Set<SkillPackDescriptorReference> allowedDescriptors) {
        return service(maximumRisk, allowedDescriptors, SkillRiskLevel.LOW);
    }

    private static SkillPackApprovalService service(
            SkillRiskLevel maximumRisk,
            Set<SkillPackDescriptorReference> allowedDescriptors,
            SkillRiskLevel descriptorRisk) {
        SkillPackPolicy policy = new SkillPackPolicy(
                new SkillPackLimits(
                        1_024,
                        8,
                        8,
                        new SkillPlanLimits(8, 8, 8),
                        maximumRisk),
                Set.of(1),
                allowedDescriptors);
        return new SkillPackApprovalService(
                new SkillPackValidator(registry(descriptorRisk), policy), 8);
    }

    private static SkillRegistry registry(SkillRiskLevel risk) {
        SkillRegistry registry = new SkillRegistry();
        Assertions.assertEquals(
                SkillRegistry.RegisterStatus.REGISTERED,
                registry.register(new SkillDescriptor(
                        COLLECT,
                        VERSION,
                        SkillCategory.RESOURCE,
                        SkillParameterSchema.empty(),
                        risk,
                        Set.of(),
                        100,
                        0,
                        true)));
        return registry;
    }

    private static SkillPackCandidate candidate(
            int schemaVersion,
            Set<SkillPackDescriptorReference> references,
            SkillPackSource source) {
        return new SkillPackCandidate(
                definition(schemaVersion, references), source);
    }

    private static SkillPackDefinition definition(
            int schemaVersion,
            Set<SkillPackDescriptorReference> references) {
        return new SkillPackDefinition(
                schemaVersion,
                PACK_ID,
                VERSION,
                references,
                new SkillPlan(
                        PLAN_ID,
                        BOT_ID,
                        1L,
                        List.of(new SkillPlanNode(
                                NODE_ID,
                                COLLECT,
                                VERSION,
                                SkillParameters.empty())),
                        List.of()));
    }

    private static SkillPackSource source(String text) {
        return new SkillPackSource(
                PACK_ID.expectedSourcePath(),
                text.getBytes(StandardCharsets.UTF_8));
    }
}
