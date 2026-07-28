package io.github.greytaiwolf.botplayer.event;

import io.github.greytaiwolf.botplayer.BotPlayer;
import io.github.greytaiwolf.botplayer.lifecycle.BotPlayerManagers;
import io.github.greytaiwolf.botplayer.perception.AuthorityEventCollector;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * 在逻辑服务端把平台事件立即压缩为不可变候选，成功语义由 Tick 末世界状态复核。
 */
@EventBusSubscriber(modid = BotPlayer.MOD_ID)
public final class BotPerceptionEvents {
    private static final int MAX_MULTI_PLACE_CANDIDATES = 2_048;
    private static final AtomicLong ISOLATED_FAILURES =
            new AtomicLong();

    private BotPerceptionEvents() {}

    @SubscribeEvent(
            priority = EventPriority.LOWEST,
            receiveCanceled = false)
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        guard("方块破坏", () -> {
            if (!(event.getLevel() instanceof ServerLevel level)
                    || !level.getServer().isSameThread()) {
                return;
            }
            collector(level).ifPresent(value -> value.recordBlockBreak(
                    level,
                    event.getPos(),
                    event.getState(),
                    event.getPlayer()));
        });
    }

    @SubscribeEvent(
            priority = EventPriority.LOWEST,
            receiveCanceled = false)
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        guard("方块放置", () -> {
            if (!(event.getLevel() instanceof ServerLevel level)
                    || !level.getServer().isSameThread()) {
                return;
            }
            Entity actor = event.getEntity();
            collector(level).ifPresent(value -> {
                if (event
                        instanceof BlockEvent.EntityMultiPlaceEvent multiPlace) {
                    int accepted = 0;
                    for (BlockSnapshot snapshot :
                            multiPlace.getReplacedBlockSnapshots()) {
                        if (accepted++ >= MAX_MULTI_PLACE_CANDIDATES) {
                            break;
                        }
                        value.recordBlockPlace(
                                level,
                                snapshot.getPos(),
                                snapshot.getState(),
                                snapshot.getCurrentState(),
                                actor);
                    }
                } else {
                    BlockSnapshot snapshot =
                            event.getBlockSnapshot();
                    value.recordBlockPlace(
                            level,
                            event.getPos(),
                            snapshot.getState(),
                            event.getPlacedBlock(),
                            actor);
                }
            });
        });
    }

    @SubscribeEvent
    public static void onLivingDamage(
            LivingDamageEvent.Post event) {
        guard("实体受伤", () -> {
            if (!(event.getEntity().level()
                            instanceof ServerLevel level)
                    || !level.getServer().isSameThread()
                    || !Float.isFinite(event.getNewDamage())
                    || event.getNewDamage() <= 0.0F) {
                return;
            }
            collector(level).ifPresent(value -> value.recordDamage(
                    event.getEntity(),
                    event.getSource().getEntity(),
                    event.getNewDamage(),
                    level.getServer().getTickCount()));
        });
    }

    @SubscribeEvent
    public static void onItemPickup(
            ItemEntityPickupEvent.Post event) {
        guard("物品拾取", () -> {
            if (!(event.getPlayer().level()
                            instanceof ServerLevel level)
                    || !level.getServer().isSameThread()) {
                return;
            }
            ItemStack original = event.getOriginalStack();
            int pickedCount = original.getCount()
                    - event.getCurrentStack().getCount();
            if (pickedCount <= 0) {
                return;
            }
            ItemStack picked = original.copy();
            picked.setCount(pickedCount);
            collector(level).ifPresent(value -> value.recordPickup(
                    event.getPlayer(),
                    picked,
                    level.getServer().getTickCount()));
        });
    }

    @SubscribeEvent(
            priority = EventPriority.LOWEST,
            receiveCanceled = false)
    public static void onItemToss(ItemTossEvent event) {
        guard("物品丢弃", () -> {
            if (!(event.getPlayer().level()
                            instanceof ServerLevel level)
                    || !level.getServer().isSameThread()) {
                return;
            }
            collector(level).ifPresent(value -> value.recordToss(
                    event.getPlayer(),
                    event.getEntity(),
                    level.getServer().getTickCount()));
        });
    }

    private static java.util.Optional<AuthorityEventCollector> collector(
            ServerLevel level) {
        MinecraftServer server = level.getServer();
        return BotPlayerManagers.find(server)
                .map(manager ->
                        manager.authorityEventCollector());
    }

    private static void guard(
            String stage, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            long count = ISOLATED_FAILURES.incrementAndGet();
            /*
             * 平台事件可能由第三方模组构造。边界默认关闭，并低频记录诊断，
             * 不能让异常迭代器或异步事件中断 NeoForge 事件总线。
             */
            if ((count & 63L) == 1L) {
                BotPlayer.LOGGER.warn(
                        "BotPlayer P3 已隔离平台事件异常：{}（累计 {} 次）",
                        stage,
                        count,
                        exception);
            }
        }
    }
}
