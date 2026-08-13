package io.github.greytaiwolf.botplayer.skill.builtin.trading;

import io.github.greytaiwolf.botplayer.action.interaction.InventoryContentsSnapshot;
import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 单次受限村民交易的全背包守恒判定。
 *
 * <p>结果只允许使一个精确支付物 identity 减少、一个精确结果 identity 增加；所有其他
 * identity（包括盔甲和副手）都必须保持原数。位置变化由原版 {@code MerchantMenu} 的完整
 * 菜单快照验证，这里刻意使用 totals 来确认取消关闭时原版返还 payment 后没有物品遗失。
 */
public final class VillagerTradeInventoryConservation {
    private VillagerTradeInventoryConservation() {
    }

    public static boolean matchesCompletedTrade(
            InventoryContentsSnapshot before,
            InventoryContentsSnapshot after,
            ItemStackFingerprint cost,
            ItemStackFingerprint result) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        requireDistinctNonEmpty(cost, result);
        try {
            Map<ItemIdentity, Long> beforeTotals = totals(before);
            Map<ItemIdentity, Long> afterTotals = totals(after);
            ItemIdentity costIdentity = ItemIdentity.from(cost);
            ItemIdentity resultIdentity = ItemIdentity.from(result);
            Set<ItemIdentity> identities = new HashSet<>(beforeTotals.keySet());
            identities.addAll(afterTotals.keySet());
            for (ItemIdentity identity : identities) {
                long expected = beforeTotals.getOrDefault(identity, 0L);
                if (identity.equals(costIdentity)) {
                    expected = Math.subtractExact(expected, cost.count());
                }
                if (identity.equals(resultIdentity)) {
                    expected = Math.addExact(expected, result.count());
                }
                if (expected < 0L
                        || afterTotals.getOrDefault(identity, 0L)
                                != expected) {
                    return false;
                }
            }
            return true;
        } catch (ArithmeticException exception) {
            return false;
        }
    }

    /**
     * 取消清理只可收敛为“完全未交易”或“恰好一笔已完成交易”。其他任何 totals 都说明
     * 原版关闭/外部漂移无法证明，调用方必须以不安全状态失败，而不是重试。
     */
    public static CancellationSettlement cancellationSettlement(
            InventoryContentsSnapshot before,
            InventoryContentsSnapshot after,
            ItemStackFingerprint cost,
            ItemStackFingerprint result) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        requireDistinctNonEmpty(cost, result);
        if (before.equals(after)) {
            return CancellationSettlement.UNCHANGED;
        }
        return matchesCompletedTrade(before, after, cost, result)
                ? CancellationSettlement.COMPLETED
                : CancellationSettlement.UNSAFE;
    }

    private static void requireDistinctNonEmpty(
            ItemStackFingerprint cost, ItemStackFingerprint result) {
        Objects.requireNonNull(cost, "cost");
        Objects.requireNonNull(result, "result");
        if (cost.isEmpty()
                || result.isEmpty()
                || cost.sameItemAndComponents(result)) {
            throw new IllegalArgumentException(
                    "trade cost and result must be distinct non-empty identities");
        }
    }

    private static Map<ItemIdentity, Long> totals(
            InventoryContentsSnapshot snapshot) {
        Map<ItemIdentity, Long> totals = new HashMap<>();
        for (ItemStackFingerprint item : snapshot.itemTotals()) {
            ItemIdentity identity = ItemIdentity.from(item);
            totals.merge(identity, (long) item.count(), Math::addExact);
        }
        return totals;
    }

    public enum CancellationSettlement {
        UNCHANGED,
        COMPLETED,
        UNSAFE
    }

    private record ItemIdentity(
            ResourceId itemId, int damage, String componentsDigest) {
        private ItemIdentity {
            Objects.requireNonNull(itemId, "itemId");
            if (damage < 0) {
                throw new IllegalArgumentException("damage must not be negative");
            }
            Objects.requireNonNull(componentsDigest, "componentsDigest");
        }

        private static ItemIdentity from(ItemStackFingerprint item) {
            Objects.requireNonNull(item, "item");
            if (item.isEmpty()) {
                throw new IllegalArgumentException(
                        "inventory totals must not include empty stacks");
            }
            return new ItemIdentity(item.itemId().orElseThrow(),
                    item.damage(), item.componentsDigest().orElseThrow());
        }
    }
}
