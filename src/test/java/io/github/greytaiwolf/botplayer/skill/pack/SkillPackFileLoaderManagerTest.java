package io.github.greytaiwolf.botplayer.skill.pack;

import io.github.greytaiwolf.botplayer.skill.core.SkillCategory;
import io.github.greytaiwolf.botplayer.skill.core.SkillDescriptor;
import io.github.greytaiwolf.botplayer.skill.core.SkillId;
import io.github.greytaiwolf.botplayer.skill.core.SkillParameterRule;
import io.github.greytaiwolf.botplayer.skill.core.SkillParameterSchema;
import io.github.greytaiwolf.botplayer.skill.core.SkillRegistry;
import io.github.greytaiwolf.botplayer.skill.core.SkillRiskLevel;
import io.github.greytaiwolf.botplayer.skill.core.SkillVersion;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanLimits;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SkillPackFileLoaderManagerTest {
    private static final SkillPackId PACK_ID =
            new SkillPackId("botplayer", "wood_to_stone");
    private static final SkillId COLLECT =
            new SkillId("botplayer", "resource/collect");
    private static final SkillVersion VERSION =
            new SkillVersion(1, 0, 0);
    private static final SkillPackDescriptorReference COLLECT_REF =
            new SkillPackDescriptorReference(COLLECT, VERSION);
    private static final UUID ADMIN = new UUID(5L, 1L);

    @Test
    void loaderAcceptsOnlyExactSafePathsAndBoundsSourceBytes()
            throws IOException {
        Path root = Files.createTempDirectory("skill-pack-loader");
        try {
            Path accepted = skillPath(root);
            Files.createDirectories(accepted.getParent());
            Files.writeString(accepted, SkillPackJsonParserTest.validJson("1"));
            Path nested = accepted.getParent().resolve("nested/ignored.json");
            Files.createDirectories(nested.getParent());
            Files.writeString(nested, "{}");
            Path wrongSuffix = accepted.getParent().resolve("ignored.txt");
            Files.writeString(wrongSuffix, "{}");
            Path unsafeNamespace = root.resolve(
                    "data/Bad/botplayer/skills/ignored.json");
            Files.createDirectories(unsafeNamespace.getParent());
            Files.writeString(unsafeNamespace, "{}");

            List<SkillPackSource> sources = new SkillPackFileLoader().load(root);

            Assertions.assertEquals(1, sources.size());
            Assertions.assertEquals(PACK_ID.expectedSourcePath(),
                    sources.get(0).relativePath());

            Files.write(accepted,
                    new byte[SkillPackSource.ABSOLUTE_MAX_BYTES + 1]);
            Assertions.assertThrows(
                    IllegalStateException.class,
                    () -> new SkillPackFileLoader().load(root));
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void reloadStagesOnlyAndContentHashChangeRevokesApproval()
            throws IOException {
        Path root = Files.createTempDirectory("skill-pack-manager");
        try {
            Path source = skillPath(root);
            Files.createDirectories(source.getParent());
            Files.writeString(source, SkillPackJsonParserTest.validJson("1"));
            SkillPackManager manager = manager(root);

            SkillPackManager.ReloadResult first = manager.reload(10L);

            Assertions.assertEquals(1, first.stagedOrKnownCount());
            Assertions.assertEquals(0, first.rejectedCount());
            SkillPackRecord staged = manager.find(PACK_ID).orElseThrow();
            Assertions.assertEquals(SkillPackState.STAGED, staged.state());
            Assertions.assertTrue(manager.approved(staged.revision()).isEmpty());
            Assertions.assertEquals(SkillPackTransition.Status.APPROVED,
                    manager.approve(staged.revision(), ADMIN, 11L).status());
            Assertions.assertTrue(manager.approved(staged.revision()).isPresent());

            Files.writeString(source, SkillPackJsonParserTest.validJson("2"));
            SkillPackManager.ReloadResult changed = manager.reload(12L);

            Assertions.assertEquals(1, changed.stagedOrKnownCount());
            SkillPackRecord restaged = manager.find(PACK_ID).orElseThrow();
            Assertions.assertEquals(SkillPackState.STAGED, restaged.state());
            Assertions.assertNotEquals(staged.revision().contentHash(),
                    restaged.revision().contentHash());
            Assertions.assertTrue(manager.approved(staged.revision()).isEmpty());
            Assertions.assertTrue(manager.approved(restaged.revision()).isEmpty());
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void restoresOnlyTheExactPersistedApprovalAfterCurrentReload()
            throws IOException {
        Path root = Files.createTempDirectory("skill-pack-restore");
        try {
            Path source = skillPath(root);
            Files.createDirectories(source.getParent());
            Files.writeString(source, SkillPackJsonParserTest.validJson("1"));
            SkillPackManager manager = manager(root);

            manager.reload(10L);
            SkillPackRecord staged = manager.find(PACK_ID).orElseThrow();
            Assertions.assertEquals(SkillPackTransition.Status.APPROVED,
                    manager.restoreApproved(
                            staged.revision(), ADMIN, 11L).status());
            Assertions.assertTrue(manager.approved(staged.revision()).isPresent());

            Files.writeString(source, SkillPackJsonParserTest.validJson("2"));
            manager.reload(12L);
            SkillPackRecord changed = manager.find(PACK_ID).orElseThrow();
            Assertions.assertNotEquals(staged.revision(), changed.revision());
            Assertions.assertEquals(SkillPackTransition.Status.NOT_STAGED,
                    manager.restoreApproved(
                            staged.revision(), ADMIN, 13L).status());
            Assertions.assertEquals(SkillPackState.STAGED, changed.state());
            Assertions.assertTrue(manager.approved(changed.revision()).isEmpty());
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void reloadDropsDeletedPackBodyBeforeAnyApprovalCanBeReused()
            throws IOException {
        Path root = Files.createTempDirectory("skill-pack-deleted");
        try {
            Path source = skillPath(root);
            Files.createDirectories(source.getParent());
            Files.writeString(source, SkillPackJsonParserTest.validJson("1"));
            SkillPackManager manager = manager(root);

            manager.reload(10L);
            SkillPackRecord approved = manager.find(PACK_ID).orElseThrow();
            Assertions.assertEquals(SkillPackTransition.Status.APPROVED,
                    manager.approve(approved.revision(), ADMIN, 11L).status());
            Files.delete(source);

            SkillPackManager.ReloadResult reloaded = manager.reload(12L);

            Assertions.assertEquals(0, reloaded.stagedOrKnownCount());
            Assertions.assertTrue(manager.find(PACK_ID).isEmpty());
            Assertions.assertTrue(manager.approved(approved.revision()).isEmpty());
            Assertions.assertEquals(SkillPackTransition.Status.NOT_STAGED,
                    manager.restoreApproved(
                            approved.revision(), ADMIN, 13L).status());
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void reloadKeepsParseFailuresOutOfTheApprovalLedger()
            throws IOException {
        Path root = Files.createTempDirectory("skill-pack-invalid");
        try {
            Path source = skillPath(root);
            Files.createDirectories(source.getParent());
            Files.writeString(source, "{\"schemaVersion\":1}",
                    StandardCharsets.UTF_8);
            SkillPackManager manager = manager(root);

            SkillPackManager.ReloadResult result = manager.reload(1L);

            Assertions.assertEquals(0, result.stagedOrKnownCount());
            Assertions.assertEquals(List.of(PACK_ID.expectedSourcePath()),
                    result.rejectedPaths());
            Assertions.assertTrue(manager.find(PACK_ID).isEmpty());
        } finally {
            deleteTree(root);
        }
    }

    private static SkillPackManager manager(Path root) {
        SkillRegistry registry = new SkillRegistry();
        Assertions.assertEquals(SkillRegistry.RegisterStatus.REGISTERED,
                registry.register(new SkillDescriptor(
                        COLLECT,
                        VERSION,
                        SkillCategory.RESOURCE,
                        new SkillParameterSchema(java.util.Map.of(
                                "count",
                                new SkillParameterRule.IntegerRule(
                                        true, 1, 64))),
                        SkillRiskLevel.LOW,
                        Set.of(),
                        100,
                        0,
                        true)));
        SkillPackPolicy policy = new SkillPackPolicy(
                new SkillPackLimits(
                        262_144,
                        8,
                        8,
                        new SkillPlanLimits(8, 8, 8),
                        SkillRiskLevel.MODERATE),
                Set.of(1),
                Set.of(COLLECT_REF));
        return new SkillPackManager(
                new SkillPackFileLoader(),
                new SkillPackJsonParser(),
                new SkillPackApprovalService(
                        new SkillPackValidator(registry, policy), 8),
                root);
    }

    private static Path skillPath(Path root) {
        return root.resolve(PACK_ID.expectedSourcePath());
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
