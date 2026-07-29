package io.github.greytaiwolf.botplayer.gametest.fixture;

import io.github.greytaiwolf.botplayer.BotPlayer;
import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * 仅在 GameTest source set 中加载的标准模组扩展 fixture。
 *
 * <p>它模拟另一个模组通过 PlayerTickEvent 修改真实玩家属性；生产 JAR 不包含此类。
 */
@EventBusSubscriber(modid = BotPlayer.MOD_ID)
public final class P4CompatibilityFixtureEvents {
    public static final String ATTRIBUTE_BUFF_TAG =
            "botplayer_p4_fixture_attribute_buff";
    private static final ResourceLocation ATTRIBUTE_BUFF_ID =
            ResourceLocation.fromNamespaceAndPath(
                    BotPlayer.MOD_ID,
                    "gametest.player_tick_movement_buff");
    private static final AttributeModifier ATTRIBUTE_BUFF =
            new AttributeModifier(
                    ATTRIBUTE_BUFF_ID,
                    0.05D,
                    AttributeModifier.Operation.ADD_VALUE);

    private P4CompatibilityFixtureEvents() {}

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof BotServerPlayer bot)
                || bot.level().isClientSide()) {
            return;
        }
        AttributeInstance movementSpeed =
                bot.getAttribute(Attributes.MOVEMENT_SPEED);
        if (movementSpeed == null) {
            return;
        }
        if (bot.getTags().contains(ATTRIBUTE_BUFF_TAG)) {
            if (!movementSpeed.hasModifier(ATTRIBUTE_BUFF_ID)) {
                movementSpeed.addTransientModifier(ATTRIBUTE_BUFF);
            }
        } else {
            movementSpeed.removeModifier(ATTRIBUTE_BUFF_ID);
        }
    }
}
