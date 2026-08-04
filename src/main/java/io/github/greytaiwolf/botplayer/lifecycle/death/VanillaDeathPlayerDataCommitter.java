package io.github.greytaiwolf.botplayer.lifecycle.death;

import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
import io.github.greytaiwolf.botplayer.mixin.PlayerListAccessor;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * 使用原版精确保存入口提交死亡 playerdata，并用主副本回读证明结果。
 *
 * <p>PlayerList.save 与底层 PlayerDataStorage.save 都返回 void 且吞掉部分 I/O
 * 异常，因此“调用返回”不是成功凭据。这里严格执行 save×2、文件/目录刷盘和
 * 有界双回读；任一步失败都由生命周期重试，绝不能提前清除 tombstone。
 */
public final class VanillaDeathPlayerDataCommitter {
    private static final long MAX_PLAYER_DATA_BYTES =
            4L * 1024L * 1024L;
    private final MinecraftServer server;
    private final Path playerDataDirectory;

    public VanillaDeathPlayerDataCommitter(
            MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server");
        this.playerDataDirectory = server
                .getWorldPath(LevelResource.PLAYER_DATA_DIR)
                .toAbsolutePath()
                .normalize();
    }

    public boolean commitDead(
            BotServerPlayer player,
            VanillaDeathTicket ticket,
            BooleanSupplier exactAuthority)
            throws IOException {
        return commit(
                player,
                ticket,
                false,
                exactAuthority,
                data -> VanillaDeathPlayerDataContract
                        .isCanonicalDead(data, ticket));
    }

    public boolean commitSuccessor(
            BotServerPlayer player,
            VanillaDeathTicket ticket,
            BooleanSupplier exactAuthority)
            throws IOException {
        return commit(
                player,
                ticket,
                true,
                exactAuthority,
                data -> VanillaDeathPlayerDataContract
                        .isCanonicalAlive(data, ticket));
    }

    /**
     * 在登录前同时检查主副本中的未完成 handoff。任一文件损坏或两份票据
     * 冲突都失败关闭，不能依赖原版只优先加载主文件的回退顺序。
     */
    public Optional<VanillaDeathTicket> discoverHandoff(
            UUID botId) throws IOException {
        Objects.requireNonNull(botId, "botId");
        VanillaDeathTicket discovered = null;
        Path[] candidates = {
            playerDataDirectory.resolve(botId + ".dat"),
            playerDataDirectory.resolve(botId + ".dat_old")
        };
        for (Path candidate : candidates) {
            if (!Files.exists(
                    candidate, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            VanillaDeathTicket ticket;
            try {
                ticket = VanillaDeathPlayerDataContract
                        .readHandoff(readBounded(candidate))
                        .orElse(null);
            } catch (IllegalArgumentException exception) {
                throw new IOException(
                        "Playerdata contains an invalid death handoff",
                        exception);
            }
            if (ticket == null) {
                continue;
            }
            if (!ticket.botId().equals(botId)) {
                throw new IOException(
                        "Playerdata death handoff identity mismatch");
            }
            if (discovered != null
                    && !discovered.equals(ticket)) {
                throw new IOException(
                        "Playerdata primary and backup contain conflicting death handoffs");
            }
            discovered = ticket;
        }
        return Optional.ofNullable(discovered);
    }

    private boolean commit(
            BotServerPlayer player,
            VanillaDeathTicket ticket,
            boolean omitHandoff,
            BooleanSupplier exactAuthority,
            java.util.function.Predicate<CompoundTag> verifier)
            throws IOException {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(ticket, "ticket");
        Objects.requireNonNull(exactAuthority, "exactAuthority");
        Objects.requireNonNull(verifier, "verifier");
        if (!player.getUUID().equals(ticket.botId())
                || !exactAuthority.getAsBoolean()) {
            return false;
        }
        saveExact(player, omitHandoff);
        if (!exactAuthority.getAsBoolean()) {
            return false;
        }
        saveExact(player, omitHandoff);
        if (!exactAuthority.getAsBoolean()) {
            return false;
        }

        Path primary = playerDataDirectory.resolve(
                ticket.botId() + ".dat");
        Path backup = playerDataDirectory.resolve(
                ticket.botId() + ".dat_old");
        forceRegularFile(primary);
        forceRegularFile(backup);
        forceDirectory(playerDataDirectory);
        if (!exactAuthority.getAsBoolean()) {
            return false;
        }
        CompoundTag primaryData = readBounded(primary);
        CompoundTag backupData = readBounded(backup);
        return verifier.test(primaryData)
                && verifier.test(backupData)
                && exactAuthority.getAsBoolean();
    }

    private void saveExact(
            BotServerPlayer player, boolean omitHandoff)
            throws IOException {
        if (!(server.getPlayerList()
                instanceof PlayerListAccessor accessor)) {
            throw new IOException(
                    "PlayerList exact-save accessor is unavailable");
        }
        player.armDeathDataSave(omitHandoff);
        boolean serialized;
        try {
            accessor.botplayer$saveExactPlayer(player);
        } catch (RuntimeException exception) {
            throw new IOException(
                    "Exact playerdata save threw", exception);
        } finally {
            serialized = player.finishDeathDataSave();
        }
        if (!serialized) {
            throw new IOException(
                    "Exact playerdata save never reached PlayerList.save HEAD");
        }
    }

    private static CompoundTag readBounded(Path file)
            throws IOException {
        if (!Files.isRegularFile(
                        file, LinkOption.NOFOLLOW_LINKS)
                || Files.size(file) <= 0L
                || Files.size(file) > MAX_PLAYER_DATA_BYTES) {
            throw new IOException(
                    "Playerdata file is missing, unsafe, or oversized: "
                            + file.getFileName());
        }
        return NbtIo.readCompressed(
                file,
                NbtAccounter.create(MAX_PLAYER_DATA_BYTES));
    }

    private static void forceRegularFile(Path file)
            throws IOException {
        if (!Files.isRegularFile(
                file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(
                    "Playerdata durability target is not a regular file: "
                            + file.getFileName());
        }
        try (FileChannel channel = FileChannel.open(
                file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static void forceDirectory(Path directory)
            throws IOException {
        if (!Files.isDirectory(
                directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(
                    "Playerdata durability directory is missing");
        }
        try (FileChannel channel = FileChannel.open(
                directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }
}
