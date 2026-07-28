package io.github.greytaiwolf.botplayer.perception.sensor;

import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
import io.github.greytaiwolf.botplayer.perception.BudgetKind;
import io.github.greytaiwolf.botplayer.perception.InventoryObservation;
import io.github.greytaiwolf.botplayer.perception.ItemObservation;
import io.github.greytaiwolf.botplayer.perception.PerceptionBudget;
import io.github.greytaiwolf.botplayer.perception.SensorId;
import io.github.greytaiwolf.botplayer.perception.SensorSchedule;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * 按槽位顺序读取 bot 自身背包，并生成不携带 ItemStack 的稳定摘要。
 */
public final class InventorySensor implements BotSensor {
    private static final int MAX_PLAYER_INVENTORY_SLOTS =
            Inventory.INVENTORY_SIZE + 5;

    public static final SensorSchedule DEFAULT_SCHEDULE =
            new SensorSchedule(1, 5, 20);

    private final SensorSchedule schedule;

    public InventorySensor() {
        this(DEFAULT_SCHEDULE);
    }

    public InventorySensor(SensorSchedule schedule) {
        this.schedule = Objects.requireNonNull(schedule, "schedule");
    }

    @Override
    public SensorId id() {
        return SensorId.INVENTORY;
    }

    @Override
    public SensorSchedule schedule() {
        return schedule;
    }

    @Override
    public SensorCost estimatedCost() {
        return SensorCost.of(
                BudgetKind.INVENTORY_SLOT,
                MAX_PLAYER_INVENTORY_SLOTS);
    }

    @Override
    public SensorResult sample(
            SensorContext context, PerceptionBudget budget) {
        Objects.requireNonNull(context, "context").assertMainThread();
        Objects.requireNonNull(budget, "budget");
        BotServerPlayer player = context.player();
        Inventory inventory = player.getInventory();
        int totalSlots = inventory.getContainerSize();
        int occupiedSlots = 0;
        boolean truncated = false;
        List<ItemObservation> items = new ArrayList<>();
        MessageDigest digest = SensorSupport.newDigest();
        SensorSupport.updateString(digest, "botplayer:inventory:v1");
        SensorSupport.updateInt(digest, totalSlots);
        SensorSupport.updateInt(digest, inventory.selected);

        int scannedSlots =
                Math.min(totalSlots, MAX_PLAYER_INVENTORY_SLOTS);
        if (scannedSlots < totalSlots) {
            return new SensorResult.Unavailable(
                    id(),
                    SensorResult.Reason.SENSOR_FAILURE,
                    true);
        }
        for (int slot = 0; slot < scannedSlots; slot++) {
            if (!budget.tryConsume(BudgetKind.INVENTORY_SLOT)) {
                return new SensorResult.Unavailable(
                        id(),
                        SensorResult.Reason.BUDGET_EXHAUSTED,
                        true);
            }
            SensorSupport.updateInt(digest, slot);
            try {
                ItemStack stack = inventory.getItem(slot);
                if (stack.isEmpty()) {
                    SensorSupport.updateString(digest, "empty");
                    continue;
                }
                String itemId = BuiltInRegistries.ITEM
                        .getKey(stack.getItem())
                        .toString();
                ItemObservation item = new ItemObservation(
                        slot,
                        itemId,
                        stack.getCount(),
                        stack.getDamageValue(),
                        stack.getMaxDamage());
                /*
                 * P3 只摘要有界的槽位表面字段。任意 data component/NBT 可能是
                 * 超深或超大的模组载荷，不能用“一槽一个 work”递归序列化。
                 */
                SensorSupport.updateString(digest, itemId);
                SensorSupport.updateInt(digest, stack.getCount());
                SensorSupport.updateInt(
                        digest, stack.getDamageValue());
                SensorSupport.updateInt(
                        digest, stack.getMaxDamage());
                occupiedSlots++;
                items.add(item);
            } catch (RuntimeException exception) {
                // 第三方物品异常时保留上一份完整背包，不发布半成品摘要。
                return new SensorResult.Unavailable(
                        id(),
                        SensorResult.Reason.SENSOR_FAILURE,
                        true);
            }
        }
        SensorSupport.updateString(digest, "complete");
        InventoryObservation observation = new InventoryObservation(
                HexFormat.of().formatHex(digest.digest()),
                inventory.selected,
                occupiedSlots,
                totalSlots,
                items,
                false);
        return new SensorResult.Inventory(observation);
    }
}
