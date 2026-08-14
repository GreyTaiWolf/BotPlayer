package io.github.greytaiwolf.botplayer.command;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.greytaiwolf.botplayer.ai.review.AiReviewOnlyDispatchReceipt;
import io.github.greytaiwolf.botplayer.ai.review.AiReviewOnlyDispatchStatus;
import io.github.greytaiwolf.botplayer.config.BotPlayerConfig;
import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
import io.github.greytaiwolf.botplayer.lifecycle.BotPlayerManagers;
import io.github.greytaiwolf.botplayer.lifecycle.BotSnapshot;
import io.github.greytaiwolf.botplayer.navigation.GridPoint;
import io.github.greytaiwolf.botplayer.navigation.NavigationSessionView;
import io.github.greytaiwolf.botplayer.navigation.NavigationSubmission;
import io.github.greytaiwolf.botplayer.perception.ObservationSnapshot;
import io.github.greytaiwolf.botplayer.safety.SafetyFrame;
import io.github.greytaiwolf.botplayer.safety.SafetyIncidentView;
import io.github.greytaiwolf.botplayer.skill.core.SkillVersion;
import io.github.greytaiwolf.botplayer.skill.pack.SkillPackHash;
import io.github.greytaiwolf.botplayer.skill.pack.SkillPackId;
import io.github.greytaiwolf.botplayer.skill.pack.SkillPackRecord;
import io.github.greytaiwolf.botplayer.skill.pack.SkillPackRevision;
import io.github.greytaiwolf.botplayer.skill.pack.SkillPackState;
import io.github.greytaiwolf.botplayer.skill.pack.SkillPackTransition;
import io.github.greytaiwolf.botplayer.skill.runtime.SurvivalSkillRunView;
import io.github.greytaiwolf.botplayer.skill.runtime.SurvivalSkillSubmission;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillRunSubmission;
import io.github.greytaiwolf.botplayer.worldmodel.ActivityReportFormatter;
import io.github.greytaiwolf.botplayer.worldmodel.WorldFact;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public final class BotPlayerCommands {
    private static final int ROOT_ADMIN_PERMISSION_LEVEL = 4;
    private static final int MAX_SKILL_PACK_LIST_LINES = 32;
    private static final int MAX_SKILL_PACK_VIOLATION_LINES = 8;
    private static final UUID ZERO_UUID = new UUID(0L, 0L);
    private static final Pattern CANONICAL_UUID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern VERSION_COMPONENT = Pattern.compile(
            "0|[1-9][0-9]{0,4}");

    private BotPlayerCommands() {}

    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(literal("botplayer")
                .then(literal("spawn")
                        .requires(source -> source.hasPermission(
                                BotPlayerConfig.COMMAND_PERMISSION_LEVEL.get()))
                        .then(argument("name", StringArgumentType.word())
                                .executes(context -> spawn(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "name")))))
                .then(literal("remove")
                        .requires(source -> source.hasPermission(
                                BotPlayerConfig.COMMAND_PERMISSION_LEVEL.get()))
                        .then(argument("name", StringArgumentType.word())
                                .executes(context -> remove(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "name")))))
                .then(literal("settings")
                        .then(argument("name", StringArgumentType.word())
                                .executes(context -> settings(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "name")))))
                .then(literal("ai")
                        .then(literal("review")
                                .then(argument("name", StringArgumentType.word())
                                        .executes(context -> reviewAi(
                                                context.getSource(),
                                                StringArgumentType.getString(
                                                        context, "name"))))))
                .then(literal("list")
                        .requires(source -> source.hasPermission(
                                BotPlayerConfig.COMMAND_PERMISSION_LEVEL.get()))
                        .executes(context -> list(context.getSource())))
                .then(literal("navigation")
                        .requires(source -> source.hasPermission(2))
                        .then(literal("go")
                                .then(argument(
                                                "name",
                                                StringArgumentType.word())
                                        .then(argument(
                                                        "x",
                                                        IntegerArgumentType
                                                                .integer(
                                                                        -30_000_000,
                                                                        30_000_000))
                                                .then(argument(
                                                                "y",
                                                                IntegerArgumentType
                                                                        .integer(
                                                                                -2_048,
                                                                                2_048))
                                                        .then(argument(
                                                                        "z",
                                                                        IntegerArgumentType
                                                                                .integer(
                                                                                        -30_000_000,
                                                                                        30_000_000))
                                                                .executes(context ->
                                                                        startNavigation(
                                                                                context
                                                                                        .getSource(),
                                                                                StringArgumentType
                                                                                        .getString(
                                                                                                context,
                                                                                                "name"),
                                                                                IntegerArgumentType
                                                                                        .getInteger(
                                                                                                context,
                                                                                                "x"),
                                                                                IntegerArgumentType
                                                                                        .getInteger(
                                                                                                context,
                                                                                                "y"),
                                                                                IntegerArgumentType
                                                                                        .getInteger(
                                                                                                context,
                                                                                                "z"))))))))
                        .then(literal("stop")
                                .then(argument(
                                                "name",
                                                StringArgumentType.word())
                                        .executes(context ->
                                                stopNavigation(
                                                        context.getSource(),
                                                        StringArgumentType
                                                                .getString(
                                                                        context,
                                                                        "name")))))
                        .then(literal("inspect")
                                .then(argument(
                                                "name",
                                                StringArgumentType.word())
                                        .executes(context ->
                                                inspectNavigation(
                                                        context.getSource(),
                                                        StringArgumentType
                                                                .getString(
                                                                        context,
                                                                        "name"))))))
                .then(literal("safety")
                        .requires(source -> source.hasPermission(2))
                        .then(literal("inspect")
                                .then(argument(
                                                "name",
                                                StringArgumentType.word())
                                        .executes(context ->
                                                inspectSafety(
                                                        context.getSource(),
                                                        StringArgumentType
                                                                .getString(
                                                                        context,
                                                                        "name"))))))
                .then(literal("skill")
                        .requires(source -> source.hasPermission(2))
                        .then(literal("equip-armor")
                                .then(argument(
                                                "name",
                                                StringArgumentType.word())
                                        .executes(context ->
                                                startBasicArmor(
                                                        context.getSource(),
                                                        StringArgumentType
                                                                .getString(
                                                                        context,
                                                                        "name")))))
                        .then(literal("bootstrap-iron")
                                .then(argument(
                                                "name",
                                                StringArgumentType.word())
                                        .executes(context ->
                                                startBootstrapIron(
                                                        context.getSource(),
                                                        StringArgumentType
                                                                .getString(
                                                                        context,
                                                                        "name")))))
                        .then(literal("inspect")
                                .then(argument(
                                                "name",
                                                StringArgumentType.word())
                                        .executes(context ->
                                                inspectSkill(
                                                        context.getSource(),
                                                        StringArgumentType
                                                                .getString(
                                                                        context,
                                                                        "name"))))))
                .then(skillPackCommands())
                .then(literal("perception")
                        /*
                         * 感知快照属于 bot 本地知识，调试入口固定为管理权限，
                         * 不跟随可降到 0 的普通生命周期命令权限。
                         */
                        .requires(source -> source.hasPermission(2))
                        .then(literal("inspect")
                                .then(argument("name", StringArgumentType.word())
                                        .executes(context -> inspectPerception(
                                                context.getSource(),
                                                StringArgumentType.getString(
                                                        context,
                                                        "name")))))
                        .then(literal("correct")
                                .then(argument("bot", StringArgumentType.word())
                                        .then(argument(
                                                        "actor",
                                                        StringArgumentType.word())
                                                .then(argument(
                                                                "activity",
                                                                StringArgumentType
                                                                        .word())
                                                        .executes(context ->
                                                                correctActivity(
                                                                        context
                                                                                .getSource(),
                                                                        StringArgumentType
                                                                                .getString(
                                                                                        context,
                                                                                        "bot"),
                                                                        StringArgumentType
                                                                                .getString(
                                                                                        context,
                                                                                        "actor"),
                                                                        StringArgumentType
                                                                                .getString(
                                                                                        context,
                                                                                        "activity")))))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack>
            skillPackCommands() {
        return LiteralArgumentBuilder.<CommandSourceStack>literal("skill-pack")
                /*
                 * 外部声明内容可以影响 bot 的物理动作，不能跟随普通 /botplayer
                 * 生命周期命令的可配置权限降级。
                 */
                .requires(BotPlayerCommands::hasRootAdministrator)
                .then(literal("reload")
                        .executes(context -> reloadSkillPacks(
                                context.getSource())))
                .then(literal("list")
                        .executes(context -> listSkillPacks(
                                context.getSource())))
                .then(literal("show")
                        .then(argument("pack-id", StringArgumentType.word())
                                .executes(context -> showSkillPack(
                                        context.getSource(),
                                        StringArgumentType.getString(
                                                context, "pack-id")))))
                .then(literal("approve")
                        .then(argument("pack-id", StringArgumentType.word())
                                .then(argument(
                                                "version",
                                                StringArgumentType.word())
                                        .then(argument(
                                                        "sha256",
                                                        StringArgumentType.word())
                                                .executes(context ->
                                                        approveSkillPack(
                                                                context
                                                                        .getSource(),
                                                                StringArgumentType
                                                                        .getString(
                                                                                context,
                                                                                "pack-id"),
                                                                StringArgumentType
                                                                        .getString(
                                                                                context,
                                                                                "version"),
                                                                StringArgumentType
                                                                        .getString(
                                                                                context,
                                                                                "sha256")))))))
                .then(literal("reject")
                        .then(argument("pack-id", StringArgumentType.word())
                                .then(argument(
                                                "version",
                                                StringArgumentType.word())
                                        .then(argument(
                                                        "sha256",
                                                        StringArgumentType.word())
                                                .executes(context ->
                                                        rejectSkillPack(
                                                                context
                                                                        .getSource(),
                                                                StringArgumentType
                                                                        .getString(
                                                                                context,
                                                                                "pack-id"),
                                                                StringArgumentType
                                                                        .getString(
                                                                                context,
                                                                                "version"),
                                                                StringArgumentType
                                                                        .getString(
                                                                                context,
                                                                                "sha256")))))))
                .then(literal("run")
                        .then(argument("name", StringArgumentType.word())
                                .then(argument(
                                                "bot-id",
                                                StringArgumentType.word())
                                        .then(argument(
                                                        "pack-id",
                                                        StringArgumentType.word())
                                                .then(argument(
                                                                "version",
                                                                StringArgumentType
                                                                        .word())
                                                        .then(argument(
                                                                        "sha256",
                                                                        StringArgumentType
                                                                                .word())
                                                                .executes(context ->
                                                                        runApprovedSkillPack(
                                                                                context
                                                                                        .getSource(),
                                                                                StringArgumentType
                                                                                        .getString(
                                                                                                context,
                                                                                                "name"),
                                                                                StringArgumentType
                                                                                        .getString(
                                                                                                context,
                                                                                                "bot-id"),
                                                                                StringArgumentType
                                                                                        .getString(
                                                                                                context,
                                                                                                "pack-id"),
                                                                                StringArgumentType
                                                                                        .getString(
                                                                                                context,
                                                                                                "version"),
                                                                                StringArgumentType
                                                                                        .getString(
                                                                                                context,
                                                                                                "sha256")))))))));
    }

    private static boolean hasRootAdministrator(CommandSourceStack source) {
        return source.hasPermission(ROOT_ADMIN_PERMISSION_LEVEL);
    }

    private static int reloadSkillPacks(CommandSourceStack source) {
        try {
            var result = BotPlayerManagers.get(source.getServer())
                    .reloadSkillPacks();
            source.sendSuccess(
                    () -> Component.literal(
                            "P5A 技能包重载完成：审核记录="
                                    + result.stagedOrKnownCount()
                                    + "，解析拒绝="
                                    + result.rejectedCount()
                                    + "；只恢复既有精确审批，不会批准新内容或执行"),
                    true);
            return 1;
        } catch (IllegalStateException exception) {
            source.sendFailure(Component.literal(
                    "P5A 技能包重载失败；未执行任何技能包"));
            return 0;
        }
    }

    private static int listSkillPacks(CommandSourceStack source) {
        List<SkillPackRecord> records = BotPlayerManagers
                .get(source.getServer())
                .skillPacks();
        if (records.isEmpty()) {
            source.sendSuccess(
                    () -> Component.literal("P5A 没有已发现的技能包审核记录"),
                    false);
            return 0;
        }
        source.sendSuccess(
                () -> Component.literal(
                        "P5A 技能包审核记录："
                                + records.size()
                                + (records.size() > MAX_SKILL_PACK_LIST_LINES
                                        ? "（以下仅显示前 "
                                                + MAX_SKILL_PACK_LIST_LINES
                                                + " 条）"
                                        : "")),
                false);
        records.stream()
                .limit(MAX_SKILL_PACK_LIST_LINES)
                .forEach(record -> source.sendSuccess(
                        () -> Component.literal(
                                "P5A pack "
                                        + formatRevision(record.revision())
                                        + " state="
                                        + record.state().name()
                                        + " valid="
                                        + record.validation().valid()
                                        + " bot="
                                        + record.candidate()
                                                .definition()
                                                .plan()
                                                .botId()),
                        false));
        return 1;
    }

    private static int showSkillPack(
            CommandSourceStack source, String packIdText) {
        SkillPackId packId;
        try {
            packId = parseSkillPackId(packIdText);
        } catch (IllegalArgumentException exception) {
            source.sendFailure(Component.literal(
                    "P5A 技能包 ID 格式无效"));
            return 0;
        }
        SkillPackRecord record = BotPlayerManagers
                .get(source.getServer())
                .skillPack(packId)
                .orElse(null);
        if (record == null) {
            source.sendFailure(Component.literal(
                    "没有该 P5A 技能包的审核记录"));
            return 0;
        }
        source.sendSuccess(
                () -> Component.literal(
                        "P5A pack "
                                + formatRevision(record.revision())
                                + " state="
                                + record.state().name()
                                + " schema="
                                + record.candidate().definition()
                                        .schemaVersion()
                                + " sourceBytes="
                                + record.candidate().source().byteCount()
                                + " descriptors="
                                + record.candidate().definition()
                                        .descriptorReferences().size()
                                + " plan="
                                + record.candidate().definition().plan()
                                        .planId()
                                + " bot="
                                + record.candidate().definition().plan()
                                        .botId()
                                + " nodes="
                                + record.candidate().definition().plan()
                                        .nodes().size()
                                + " edges="
                                + record.candidate().definition().plan()
                                        .edges().size()
                                + " valid="
                                + record.validation().valid()
                                + " reviewedBy="
                                + record.reviewedBy()
                                        .map(UUID::toString)
                                        .orElse("none")),
                false);
        record.validation().violations().stream()
                .limit(MAX_SKILL_PACK_VIOLATION_LINES)
                .forEach(violation -> source.sendSuccess(
                        () -> Component.literal(
                                "P5A pack violation="
                                        + violation.code().name()
                                        + " node="
                                        + violation.nodeId()
                                                .map(UUID::toString)
                                                .orElse("none")),
                        false));
        if (record.validation().violations().size()
                > MAX_SKILL_PACK_VIOLATION_LINES) {
            source.sendSuccess(
                    () -> Component.literal(
                            "P5A 违规明细已截断为 "
                                    + MAX_SKILL_PACK_VIOLATION_LINES
                                    + " 条"),
                    false);
        }
        return 1;
    }

    private static int approveSkillPack(
            CommandSourceStack source,
            String packIdText,
            String versionText,
            String hashText) {
        SkillPackRevision revision;
        UUID administratorId;
        try {
            revision = parseSkillPackRevision(
                    packIdText, versionText, hashText);
            administratorId = requireHumanAdministrator(source);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            source.sendFailure(Component.literal(
                    "P5A 技能包批准被拒绝：必须由实体根管理员提供精确 revision"));
            return 0;
        }
        SkillPackTransition transition = BotPlayerManagers
                .get(source.getServer())
                .approveSkillPack(revision, administratorId);
        if (transition.status() != SkillPackTransition.Status.APPROVED
                && transition.status()
                        != SkillPackTransition.Status.ALREADY_APPROVED) {
            source.sendFailure(Component.literal(
                    "P5A 技能包批准被拒绝："
                            + transition.status().name()));
            return 0;
        }
        source.sendSuccess(
                () -> Component.literal(
                        "P5A 技能包已批准："
                                + formatRevision(revision)
                                + " reviewer="
                                + administratorId),
                true);
        return 1;
    }

    private static int rejectSkillPack(
            CommandSourceStack source,
            String packIdText,
            String versionText,
            String hashText) {
        SkillPackRevision revision;
        UUID administratorId;
        try {
            revision = parseSkillPackRevision(
                    packIdText, versionText, hashText);
            administratorId = requireHumanAdministrator(source);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            source.sendFailure(Component.literal(
                    "P5A 技能包拒绝操作失败：必须由实体根管理员提供精确 revision"));
            return 0;
        }
        SkillPackTransition transition = BotPlayerManagers
                .get(source.getServer())
                .rejectSkillPack(revision, administratorId);
        if (transition.status() != SkillPackTransition.Status.REJECTED
                && transition.status()
                        != SkillPackTransition.Status.ALREADY_REJECTED) {
            source.sendFailure(Component.literal(
                    "P5A 技能包拒绝操作失败："
                            + transition.status().name()));
            return 0;
        }
        source.sendSuccess(
                () -> Component.literal(
                        "P5A 技能包已拒绝："
                                + formatRevision(revision)
                                + " reviewer="
                                + administratorId),
                true);
        return 1;
    }

    private static int runApprovedSkillPack(
            CommandSourceStack source,
            String name,
            String expectedBotIdText,
            String packIdText,
            String versionText,
            String hashText) {
        SkillPackRevision revision;
        UUID expectedBotId;
        try {
            revision = parseSkillPackRevision(
                    packIdText, versionText, hashText);
            expectedBotId = parseCanonicalNonZeroUuid(
                    expectedBotIdText);
        } catch (IllegalArgumentException exception) {
            source.sendFailure(Component.literal(
                    "P5A 技能包运行被拒绝：Bot UUID 或 revision 格式无效"));
            return 0;
        }
        SkillPackRecord record = BotPlayerManagers
                .get(source.getServer())
                .skillPack(revision.id())
                .orElse(null);
        if (record == null
                || !record.revision().equals(revision)
                || record.state() != SkillPackState.APPROVED) {
            source.sendFailure(Component.literal(
                    "P5A 技能包运行被拒绝：精确 revision 尚未批准"));
            return 0;
        }
        if (!record.candidate().definition().plan().botId()
                .equals(expectedBotId)) {
            source.sendFailure(Component.literal(
                    "P5A 技能包运行被拒绝：输入 Bot UUID 与已批准计划不一致"));
            return 0;
        }
        SkillRunSubmission submission = BotPlayerManagers
                .get(source.getServer())
                .submitApprovedSkillPack(name, revision);
        if (submission.status() != SkillRunSubmission.Status.ACCEPTED) {
            source.sendFailure(Component.literal(
                    "P5A 技能包运行被拒绝："
                            + submission.status().name()
                            + " "
                            + submission.safeSummary()));
            return 0;
        }
        source.sendSuccess(
                () -> Component.literal(
                        "P5A 技能包已启动："
                                + formatRevision(revision)
                                + " run="
                                + submission.runId().orElseThrow()),
                false);
        return 1;
    }

    static SkillPackRevision parseSkillPackRevision(
            String packIdText, String versionText, String hashText) {
        try {
            return new SkillPackRevision(
                    parseSkillPackId(packIdText),
                    parseSkillVersion(versionText),
                    new SkillPackHash(requireNonBlank(hashText)));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "skill pack revision syntax is invalid", exception);
        }
    }

    static UUID parseCanonicalNonZeroUuid(String value) {
        String candidate = requireNonBlank(value);
        if (!CANONICAL_UUID.matcher(candidate).matches()) {
            throw new IllegalArgumentException(
                    "UUID must use lower-case canonical form");
        }
        try {
            UUID parsed = UUID.fromString(candidate);
            if (ZERO_UUID.equals(parsed)
                    || !parsed.toString().equals(candidate)) {
                throw new IllegalArgumentException(
                        "UUID must be non-zero canonical form");
            }
            return parsed;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "UUID must use lower-case canonical form", exception);
        }
    }

    private static SkillPackId parseSkillPackId(String value) {
        return SkillPackId.parse(requireNonBlank(value));
    }

    private static SkillVersion parseSkillVersion(String value) {
        String[] components = requireNonBlank(value).split("\\.", -1);
        if (components.length != 3
                || !VERSION_COMPONENT.matcher(components[0]).matches()
                || !VERSION_COMPONENT.matcher(components[1]).matches()
                || !VERSION_COMPONENT.matcher(components[2]).matches()) {
            throw new IllegalArgumentException(
                    "version must use bounded major.minor.patch syntax");
        }
        try {
            return new SkillVersion(
                    Integer.parseInt(components[0]),
                    Integer.parseInt(components[1]),
                    Integer.parseInt(components[2]));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "version components are outside the allowed range", exception);
        }
    }

    private static UUID requireHumanAdministrator(
            CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer administrator)
                || administrator instanceof BotServerPlayer
                || ZERO_UUID.equals(administrator.getUUID())) {
            throw new IllegalStateException(
                    "approval requires a non-bot ServerPlayer administrator");
        }
        return administrator.getUUID();
    }

    private static String requireNonBlank(String value) {
        if (value == null
                || value.isBlank()
                || !value.equals(value.strip())) {
            throw new IllegalArgumentException(
                    "argument must be a trimmed non-blank token");
        }
        return value;
    }

    private static String formatRevision(SkillPackRevision revision) {
        return revision.id()
                + "@"
                + revision.version()
                + "#"
                + revision.contentHash().value();
    }

    private static int startNavigation(
            CommandSourceStack source,
            String name,
            int x,
            int y,
            int z) {
        var manager = BotPlayerManagers.get(source.getServer());
        NavigationSubmission submission = manager.startNavigation(
                name, new GridPoint(x, y, z));
        if (submission.status()
                != NavigationSubmission.Status.ENQUEUED) {
            source.sendFailure(Component.literal(
                    "P4 导航拒绝："
                            + submission.status().name()
                            + " "
                            + submission.safeSummary()));
            return 0;
        }
        NavigationSessionView view =
                manager.navigationSession(name).orElseThrow();
        source.sendSuccess(
                () -> Component.literal(
                        "P4 导航已启动："
                                + name
                                + " id="
                                + view.navigationId()
                                + " target="
                                + x
                                + ","
                                + y
                                + ","
                                + z),
                true);
        return 1;
    }

    private static int stopNavigation(
            CommandSourceStack source, String name) {
        if (!BotPlayerManagers.get(source.getServer())
                .stopNavigation(name)) {
            source.sendFailure(Component.literal(
                    "没有可取消的 P4 导航：" + name));
            return 0;
        }
        source.sendSuccess(
                () -> Component.literal(
                        "已取消 P4 导航：" + name),
                true);
        return 1;
    }

    private static int inspectNavigation(
            CommandSourceStack source, String name) {
        NavigationSessionView view = BotPlayerManagers
                .get(source.getServer())
                .navigationSession(name)
                .orElse(null);
        if (view == null) {
            source.sendFailure(Component.literal(
                    "没有 P4 导航记录：" + name));
            return 0;
        }
        source.sendSuccess(
                () -> Component.literal(
                        "P4 导航 "
                                + name
                                + " id="
                                + view.navigationId()
                                + " state="
                                + view.state().name()
                                + " route="
                                + view.routeIndex()
                                + "/"
                                + view.routeSize()
                                + " segments="
                                + view.segmentsCompleted()
                                + " replans="
                                + view.replans()
                                + " recoveries="
                                + view.recoveryAttempts()
                                + " break="
                                + view.blocksBroken()
                                + " place="
                                + view.blocksPlaced()
                                + " summary="
                                + view.safeSummary()),
                false);
        return 1;
    }

    private static int inspectSafety(
            CommandSourceStack source, String name) {
        var manager = BotPlayerManagers.get(source.getServer());
        SafetyIncidentView incident =
                manager.safetyIncident(name).orElse(null);
        SafetyFrame frame =
                manager.latestSafetyFrame(name).orElse(null);
        if (incident == null && frame == null) {
            source.sendFailure(Component.literal(
                    "没有 P4 安全帧：" + name));
            return 0;
        }
        if (incident != null) {
            source.sendSuccess(
                    () -> Component.literal(
                            "P4 安全 incident="
                                    + incident.incidentId()
                                    + " state="
                                    + incident.state().name()
                                    + " hazard="
                                    + incident.hazardType().name()
                                    + "/"
                                    + incident.severity().name()
                                    + " attempts="
                                    + incident.interventions()
                                    + " stable="
                                    + incident.clearStableTicks()
                                    + " evidence="
                                    + incident.evidence()),
                    false);
        }
        if (frame != null) {
            source.sendSuccess(
                    () -> Component.literal(
                            "P4 安全帧 tick="
                                    + frame.gameTick()
                                    + " hp="
                                    + frame.health()
                                    + "+"
                                    + frame.absorption()
                                    + " food="
                                    + frame.food()
                                    + " air="
                                    + frame.air()
                                    + " effects="
                                    + frame.effects().size()
                                    + (frame.effectsTruncated()
                                            ? "+"
                                            : "")
                                    + " threats="
                                    + frame.threats().size()
                                    + (frame.threatCoverageIncomplete()
                                            ? "+"
                                            : "")
                                    + " lastDamage="
                                    + frame.recentDamage()
                                            .map(value ->
                                                    value.damageTypeId())
                                            .orElse("none")),
                    false);
        }
        return 1;
    }

    private static int inspectSkill(
            CommandSourceStack source, String name) {
        SurvivalSkillRunView view = BotPlayerManagers
                .get(source.getServer())
                .survivalSkillRun(name)
                .orElse(null);
        if (view == null) {
            source.sendFailure(Component.literal(
                    "没有 P5 生存技能记录：" + name));
            return 0;
        }
        source.sendSuccess(
                () -> Component.literal(
                        "P5 技能 "
                                + name
                                + " run="
                                + view.runId()
                                + " kind="
                                + view.kind().name()
                                + " state="
                                + view.state().name()
                                + " revision="
                                + view.stateRevision()
                                + " generation="
                                + view.botGeneration()
                                + " operations="
                                + view.operationSequence()
                                + " failure="
                                + view.failureCode()
                                        .map(Enum::name)
                                        .orElse("none")
                                + " summary="
                                + view.safeSummary()),
                false);
        return 1;
    }

    private static int startBasicArmor(
            CommandSourceStack source, String name) {
        SurvivalSkillSubmission submission =
                BotPlayerManagers
                        .get(source.getServer())
                        .startBasicArmor(name);
        if (!submission.accepted()) {
            source.sendFailure(Component.literal(
                    "P5 基础盔甲技能未启动："
                            + submission.status().name()
                            + " "
                            + submission.safeSummary()));
            return 0;
        }
        source.sendSuccess(
                () -> Component.literal(
                        "P5 基础盔甲技能已启动："
                                + name
                                + " run="
                                + submission.runId()
                                        .orElseThrow()
                                + " "
                                + submission.safeSummary()),
                false);
        return 1;
    }

    private static int startBootstrapIron(
            CommandSourceStack source, String name) {
        SkillRunSubmission submission = BotPlayerManagers
                .get(source.getServer())
                .startBootstrapIron(name);
        if (submission.status() != SkillRunSubmission.Status.ACCEPTED) {
            source.sendFailure(Component.literal(
                    "P5A 木头到铁镐生产计划未启动："
                            + submission.status().name()
                            + " "
                            + submission.safeSummary()));
            return 0;
        }
        source.sendSuccess(
                () -> Component.literal(
                        "P5A 木头到铁镐生产计划已启动："
                                + name
                                + " run="
                                + submission.runId().orElseThrow()
                                + " "
                                + submission.safeSummary()),
                false);
        return 1;
    }

    private static int spawn(CommandSourceStack source, String name) {
        try {
            var player = BotPlayerManagers.get(source.getServer()).spawn(source, name);
            source.sendSuccess(
                    () -> Component.literal("Spawned BotPlayer " + player.getScoreboardName()),
                    true);
            return 1;
        } catch (IllegalArgumentException | IllegalStateException exception) {
            source.sendFailure(Component.literal(exception.getMessage()));
            return 0;
        }
    }

    private static int remove(CommandSourceStack source, String name) {
        boolean removed = BotPlayerManagers.get(source.getServer())
                .removeByName(name, Component.literal("Removed by /botplayer"));
        if (!removed) {
            source.sendFailure(Component.literal("No online BotPlayer named " + name));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Removed BotPlayer " + name), true);
        return 1;
    }

    private static int list(CommandSourceStack source) {
        List<BotSnapshot> bots = BotPlayerManagers.get(source.getServer()).snapshots();
        if (bots.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No BotPlayers are online"), false);
            return 0;
        }

        String summary = bots.stream()
                .map(bot -> bot.name() + " [" + bot.state().name().toLowerCase() + "]")
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
        source.sendSuccess(
                () -> Component.literal("BotPlayers (" + bots.size() + "): " + summary), false);
        return bots.size();
    }

    private static int settings(CommandSourceStack source, String name) {
        if (!(source.getEntity() instanceof ServerPlayer requester)) {
            source.sendFailure(Component.literal(
                    "Only a real player can configure BotPlayer credentials"));
            return 0;
        }

        try {
            BotPlayerManagers.get(source.getServer()).openCredentialScreen(requester, name);
            source.sendSuccess(
                    () -> Component.literal(
                            "Opened local credential settings for BotPlayer " + name),
                    false);
            return 1;
        } catch (IllegalArgumentException | IllegalStateException exception) {
            source.sendFailure(Component.literal(exception.getMessage()));
            return 0;
        }
    }

    /** Starts the only P6-R1 manual flow; it never accepts a user prompt or action request. */
    private static int reviewAi(CommandSourceStack source, String name) {
        if (!(source.getEntity() instanceof ServerPlayer requester)
                || requester instanceof BotServerPlayer) {
            source.sendFailure(Component.literal(
                    "Only the real persistent owner can request a BotPlayer AI review"));
            return 0;
        }
        AiReviewOnlyDispatchReceipt receipt;
        try {
            receipt = BotPlayerManagers.get(source.getServer())
                    .requestAiReview(requester, name);
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal(
                    "The P6-R1 review request could not be prepared"));
            return 0;
        }
        if (receipt.status() == AiReviewOnlyDispatchStatus.DISPATCHED) {
            source.sendSuccess(
                    () -> Component.literal(
                            "P6-R1 review was sent to your locally enabled client provider; "
                                    + "it receives only a fixed read-only snapshot and cannot act."),
                    false);
            return 1;
        }
        source.sendFailure(Component.literal(reviewFailureMessage(receipt.status())));
        return 0;
    }

    private static String reviewFailureMessage(AiReviewOnlyDispatchStatus status) {
        return switch (status) {
            case NOT_OWNER -> "Only the persistent BotPlayer owner can request this review";
            case AGENT_NOT_BOUND -> "No active local AI agent is bound for that BotPlayer";
            case SNAPSHOT_UNAVAILABLE, SNAPSHOT_NOT_CURRENT ->
                    "No current review-safe perception snapshot is available; try again next tick";
            case OWNER_OFFLINE -> "The persistent owner is not available on this server";
            case BOT_NOT_ACTIVE -> "That BotPlayer is not active";
            case INTERNAL_ERROR -> "The P6-R1 review request could not be prepared";
            case DISPATCHED -> throw new IllegalArgumentException("dispatched is not a failure");
        };
    }

    private static int inspectPerception(
            CommandSourceStack source, String name) {
        var manager = BotPlayerManagers.get(source.getServer());
        ObservationSnapshot snapshot =
                manager.latestPerception(name).orElse(null);
        if (snapshot == null) {
            source.sendFailure(Component.literal(
                    "没有活动感知快照：" + name));
            return 0;
        }

        source.sendSuccess(
                () -> Component.literal(
                        "P3 快照 "
                                + name
                                + " #"
                                + snapshot.snapshotId()
                                + " tick="
                                + snapshot.gameTick()
                                + " dim="
                                + snapshot.dimension()
                                + " pressure="
                                + snapshot.limits().pressure().name()
                                + " self="
                                + String.format(
                                        java.util.Locale.ROOT,
                                        "%.1f/%.1f",
                                        snapshot.self().health(),
                                        snapshot.self().maximumHealth())
                                + " food="
                                + snapshot.self().food()
                                + " vision="
                                + snapshot.vision().kind().name()
                                + " entities="
                                + snapshot.entities().size()
                                + " threats="
                                + snapshot.threats().size()
                                + " blocks="
                                + snapshot.blocks().size()
                                + " events="
                                + snapshot.recentEvents().size()
                                + " internalCoverageGaps="
                                + BotPlayerManagers.get(
                                                source.getServer())
                                        .perceptionCoverageGaps(name)
                                + " localWork="
                                + snapshot.limits()
                                        .budget()
                                        .used()
                                        .values()
                                        .stream()
                                        .mapToInt(Integer::intValue)
                                        .sum()
                                + "/"
                                + snapshot.limits()
                                        .budget()
                                        .limits()
                                        .values()
                                        .stream()
                                        .mapToInt(Integer::intValue)
                                        .sum()),
                false);
        snapshot.activities().stream()
                .limit(4)
                .forEach(activity -> source.sendSuccess(
                        () -> Component.literal(
                                "活动 actor="
                                        + activity.actorId()
                                        + " "
                                        + ActivityReportFormatter
                                                .formatChinese(activity)
                                        + " evidenceSeq="
                                        + activity.evidence().stream()
                                                .map(evidence -> Long.toString(
                                                        evidence.sequence()))
                                                .collect(Collectors.joining(","))),
                        false));
        List<WorldFact> facts =
                manager.recentPerceptionFacts(name, 5);
        facts.forEach(fact -> source.sendSuccess(
                () -> Component.literal(
                        "事实 "
                                + fact.status().name()
                                + " "
                                + fact.key().namespace()
                                + ":"
                                + fact.key().subject()
                                + " tick="
                                + fact.lastConfirmedTick()),
                false));
        return 1;
    }

    private static int correctActivity(
            CommandSourceStack source,
            String botName,
            String actorName,
            String activity) {
        try {
            BotPlayerManagers.get(source.getServer())
                    .correctPerceivedActivity(
                            source,
                            botName,
                            actorName,
                            activity);
            source.sendSuccess(
                    () -> Component.literal(
                            "已记录活动纠正："
                                    + botName
                                    + " 对 "
                                    + actorName
                                    + " = "
                                    + activity),
                    true);
            return 1;
        } catch (IllegalArgumentException exception) {
            source.sendFailure(
                    Component.literal(exception.getMessage()));
            return 0;
        }
    }
}
