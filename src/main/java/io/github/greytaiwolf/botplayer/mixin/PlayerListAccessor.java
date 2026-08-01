package io.github.greytaiwolf.botplayer.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 为精确玩家数据基线提供窄入口，避免 GameTest 调用 saveAll 污染并行 Bot。
 */
@Mixin(PlayerList.class)
public interface PlayerListAccessor {
    @Invoker("save")
    void botplayer$saveExactPlayer(ServerPlayer player);
}
