package io.github.greytaiwolf.botplayer.command;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.github.greytaiwolf.botplayer.config.BotPlayerConfig;
import io.github.greytaiwolf.botplayer.lifecycle.BotPlayerManagers;
import io.github.greytaiwolf.botplayer.lifecycle.BotSnapshot;
import io.github.greytaiwolf.botplayer.perception.ObservationSnapshot;
import io.github.greytaiwolf.botplayer.worldmodel.ActivityReportFormatter;
import io.github.greytaiwolf.botplayer.worldmodel.WorldFact;
import java.util.List;
import java.util.stream.Collectors;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public final class BotPlayerCommands {
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
                .then(literal("list")
                        .requires(source -> source.hasPermission(
                                BotPlayerConfig.COMMAND_PERMISSION_LEVEL.get()))
                        .executes(context -> list(context.getSource())))
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
