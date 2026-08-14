package io.github.greytaiwolf.botplayer.skill.builtin.farming;

import io.github.greytaiwolf.botplayer.action.interaction.InventoryContentsSnapshot;
import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 小麦补种的纯库存守恒规则。
 *
 * <p>原版播种只允许消耗当前主手中精确一枚 {@code minecraft:wheat_seeds}。调用方仍须独立
 * 检查 cursor、选中槽和完整原生菜单；本类故意只处理槽位顺序无关的物品多重集。
 */
public final class WheatFarmingInventoryConservation {
    private WheatFarmingInventoryConservation() {
    }

    /**
     * 从完整背包总量中精确移除一枚与当前主手相同 item/component identity 的种子。
     * 找不到该 identity 时返回空，而不是猜测其他同类堆叠可以被消耗。
     */
    public static Optional<InventoryContentsSnapshot> afterOneSeedConsumed(
            InventoryContentsSnapshot before,
            ItemStackFingerprint heldSeed) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(heldSeed, "heldSeed");
        if (heldSeed.isEmpty()) {
            return Optional.empty();
        }
        List<ItemStackFingerprint> after = new ArrayList<>(
                before.itemTotals().size());
        boolean consumed = false;
        for (ItemStackFingerprint stack : before.itemTotals()) {
            if (!consumed && stack.sameItemAndComponents(heldSeed)) {
                if (stack.count() < 1) {
                    return Optional.empty();
                }
                consumed = true;
                if (stack.count() > 1) {
                    after.add(new ItemStackFingerprint(
                            stack.itemId(),
                            stack.count() - 1,
                            stack.damage(),
                            stack.componentsDigest()));
                }
            } else {
                after.add(stack);
            }
        }
        return consumed
                ? Optional.of(new InventoryContentsSnapshot(after))
                : Optional.empty();
    }

    /** 当前选中主手在播种后应有的精确堆叠；一枚种子会变为空手。 */
    public static ItemStackFingerprint selectedAfterOneSeedConsumed(
            ItemStackFingerprint heldSeed) {
        Objects.requireNonNull(heldSeed, "heldSeed");
        if (heldSeed.isEmpty()) {
            throw new IllegalArgumentException(
                    "seed consumption requires a non-empty held stack");
        }
        if (heldSeed.count() == 1) {
            return ItemStackFingerprint.empty();
        }
        return new ItemStackFingerprint(
                heldSeed.itemId(),
                heldSeed.count() - 1,
                heldSeed.damage(),
                heldSeed.componentsDigest());
    }

    /**
     * Adds only the exact, already-proven native crop drops to a complete player
     * inventory multiset. The input is folded one stack at a time so the bounded
     * {@link InventoryContentsSnapshot} constructor is never asked to represent an
     * impossible 42nd distinct player-inventory identity.
     */
    public static Optional<InventoryContentsSnapshot> afterCollectedDrops(
            InventoryContentsSnapshot before,
            List<ItemStackFingerprint> drops) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(drops, "drops");
        InventoryContentsSnapshot result = before;
        for (ItemStackFingerprint drop : drops) {
            ItemStackFingerprint required = Objects.requireNonNull(
                    drop, "drop");
            if (required.isEmpty()) {
                return Optional.empty();
            }
            List<ItemStackFingerprint> combined = new ArrayList<>(
                    result.itemTotals());
            int matching = -1;
            for (int index = 0; index < combined.size(); index++) {
                if (combined.get(index).sameItemAndComponents(required)) {
                    matching = index;
                    break;
                }
            }
            try {
                if (matching >= 0) {
                    ItemStackFingerprint existing = combined.get(matching);
                    combined.set(matching, new ItemStackFingerprint(
                            existing.itemId(),
                            Math.addExact(existing.count(),
                                    required.count()),
                            existing.damage(),
                            existing.componentsDigest()));
                } else {
                    if (combined.size()
                            >= InventoryContentsSnapshot.MAX_INPUT_STACKS) {
                        return Optional.empty();
                    }
                    combined.add(required);
                }
                result = new InventoryContentsSnapshot(combined);
            } catch (ArithmeticException | IllegalArgumentException exception) {
                return Optional.empty();
            }
        }
        return Optional.of(result);
    }
}
