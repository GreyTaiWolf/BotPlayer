package io.github.greytaiwolf.botplayer;

import com.mojang.logging.LogUtils;
import io.github.greytaiwolf.botplayer.config.BotPlayerConfig;
import io.github.greytaiwolf.botplayer.network.BotPlayerNetwork;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;

@Mod(BotPlayer.MOD_ID)
public final class BotPlayer {
    public static final String MOD_ID = "botplayer";
    public static final Logger LOGGER = LogUtils.getLogger();

    public BotPlayer(IEventBus modBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.SERVER, BotPlayerConfig.SPEC);
        modBus.addListener(BotPlayerNetwork::register);
    }
}
