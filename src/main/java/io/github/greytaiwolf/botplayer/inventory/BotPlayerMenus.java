package io.github.greytaiwolf.botplayer.inventory;

import io.github.greytaiwolf.botplayer.BotPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class BotPlayerMenus {
    private static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(BuiltInRegistries.MENU, BotPlayer.MOD_ID);

    public static final DeferredHolder<MenuType<?>, MenuType<BotInventoryMenu>> BOT_INVENTORY =
            MENUS.register(
                    "bot_inventory",
                    () -> IMenuTypeExtension.create(BotInventoryMenu::new));

    private BotPlayerMenus() {}

    public static void register(IEventBus modBus) {
        MENUS.register(modBus);
    }
}
