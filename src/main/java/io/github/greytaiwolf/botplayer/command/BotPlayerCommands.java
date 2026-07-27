package io.github.greytaiwolf.botplayer.command;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.github.greytaiwolf.botplayer.config.BotPlayerConfig;
import io.github.greytaiwolf.botplayer.lifecycle.BotPlayerManagers;
import io.github.greytaiwolf.botplayer.lifecycle.BotSnapshot;
import java.util.List;
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
                        .executes(context -> list(context.getSource()))));
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
}
