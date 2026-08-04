package io.github.greytaiwolf.botplayer.skill.builtin.survival;

import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class BasicEquipmentPolicyTest {
    private static final String DIGEST = "a".repeat(64);

    @Test
    void armorUpgradeUsesArmorThenToughnessThenDurability() {
        EquipmentCandidate current = armor(
                39, "minecraft:iron_helmet", 2.0D, 0.0D, 100,
                true, false);
        EquipmentCandidate moreDurable = armor(
                12, "minecraft:iron_helmet", 2.0D, 0.0D, 140,
                true, false);
        EquipmentCandidate tougher = armor(
                13, "minecraft:custom_helmet", 2.0D, 1.0D, 20,
                true, false);
        EquipmentCandidate moreArmor = armor(
                14, "minecraft:diamond_helmet", 3.0D, 0.0D, 1,
                true, false);

        Assertions.assertEquals(
                moreDurable,
                BasicEquipmentPolicy.chooseArmorUpgrade(
                                EquipmentSlotKind.HEAD,
                                Optional.of(current),
                                List.of(moreDurable))
                        .orElseThrow());
        Assertions.assertEquals(
                tougher,
                BasicEquipmentPolicy.chooseArmorUpgrade(
                                EquipmentSlotKind.HEAD,
                                Optional.of(current),
                                List.of(moreDurable, tougher))
                        .orElseThrow());

        EquipmentCandidate chosen =
                BasicEquipmentPolicy.chooseArmorUpgrade(
                                EquipmentSlotKind.HEAD,
                                Optional.of(current),
                                List.of(
                                        moreDurable,
                                        tougher,
                                        moreArmor))
                        .orElseThrow();

        Assertions.assertEquals(moreArmor, chosen);
    }

    @Test
    void equalArmorQualityDoesNotCauseReplacement() {
        EquipmentCandidate current = armor(
                39, "minecraft:iron_helmet", 2.0D, 0.0D, 100,
                true, false);
        EquipmentCandidate equalAtLowerSlot = armor(
                1, "minecraft:second_iron_helmet", 2.0D, 0.0D,
                100, true, false);

        Assertions.assertTrue(
                BasicEquipmentPolicy.chooseArmorUpgrade(
                                EquipmentSlotKind.HEAD,
                                Optional.of(current),
                                List.of(equalAtLowerSlot))
                        .isEmpty());
    }

    @Test
    void armorSelectionRejectsBindingUnusableBrokenAndNonFiniteCandidates() {
        EquipmentCandidate current = armor(
                39, "minecraft:leather_helmet", 1.0D, 0.0D, 40,
                true, false);
        EquipmentCandidate blocked = armor(
                1, "minecraft:blocked_helmet", 8.0D, 8.0D, 500,
                true, true);
        EquipmentCandidate cannotEquip = armor(
                2, "minecraft:unusable_helmet", 7.0D, 7.0D, 500,
                false, false);
        EquipmentCandidate broken = armor(
                3, "minecraft:broken_helmet", 6.0D, 6.0D, 0,
                true, false);
        EquipmentCandidate nonFinite = candidate(
                4,
                "minecraft:invalid_helmet",
                EquipmentSlotKind.HEAD,
                Optional.empty(),
                0,
                Double.NaN,
                5.0D,
                0.0D,
                500,
                true,
                true,
                false);

        Assertions.assertTrue(
                BasicEquipmentPolicy.chooseArmorUpgrade(
                                EquipmentSlotKind.HEAD,
                                Optional.of(current),
                                List.of(
                                        blocked,
                                        cannotEquip,
                                        broken,
                                        nonFinite))
                        .isEmpty());
    }

    @Test
    void boundCurrentArmorPreventsReplacement() {
        EquipmentCandidate boundCurrent = armor(
                39, "minecraft:binding_helmet", 1.0D, 0.0D, 40,
                true, true);
        EquipmentCandidate upgrade = armor(
                1, "minecraft:diamond_helmet", 3.0D, 2.0D, 300,
                true, false);

        Assertions.assertTrue(
                BasicEquipmentPolicy.chooseArmorUpgrade(
                                EquipmentSlotKind.HEAD,
                                Optional.of(boundCurrent),
                                List.of(upgrade))
                        .isEmpty());
    }

    @Test
    void bindingCandidateIsRejectedBeforeItCanLockTheTargetSlot() {
        EquipmentCandidate bindingCandidate =
                new EquipmentCandidate(
                        1,
                        ItemStackFingerprint.of(
                                new ResourceId(
                                        "minecraft:binding_helmet"),
                                1,
                                0,
                                DIGEST),
                        EquipmentSlotKind.HEAD,
                        Optional.empty(),
                        0,
                        3.0D,
                        2.0D,
                        0.0D,
                        100,
                        true,
                        true,
                        false,
                        true);

        Assertions.assertTrue(
                BasicEquipmentPolicy.chooseArmorUpgrade(
                                EquipmentSlotKind.HEAD,
                                Optional.empty(),
                                List.of(bindingCandidate))
                        .isEmpty());
    }

    @Test
    void emptyArmorSlotChoosesBestEligibleCandidate() {
        EquipmentCandidate weaker = armor(
                9, "minecraft:leather_helmet", 1.0D, 0.0D, 50,
                true, false);
        EquipmentCandidate stronger = armor(
                10, "minecraft:iron_helmet", 2.0D, 0.0D, 20,
                true, false);

        Assertions.assertEquals(
                stronger,
                BasicEquipmentPolicy.chooseArmorUpgrade(
                                EquipmentSlotKind.HEAD,
                                Optional.empty(),
                                List.of(weaker, stronger))
                        .orElseThrow());
    }

    @Test
    void toolSelectionRequiresExplicitKindAndUsesTierFirst() {
        EquipmentCandidate fastWoodenPickaxe = tool(
                3, "minecraft:wooden_pickaxe", ToolKind.PICKAXE,
                0, 20.0D, 50, true, false);
        EquipmentCandidate slowerIronPickaxe = tool(
                4, "minecraft:iron_pickaxe", ToolKind.PICKAXE,
                2, 6.0D, 10, true, false);
        EquipmentCandidate diamondAxe = tool(
                5, "minecraft:diamond_axe", ToolKind.AXE,
                3, 9.0D, 1000, true, false);

        Assertions.assertEquals(
                slowerIronPickaxe,
                BasicEquipmentPolicy.chooseTool(
                                ToolKind.PICKAXE,
                                List.of(
                                        fastWoodenPickaxe,
                                        diamondAxe,
                                        slowerIronPickaxe))
                        .orElseThrow());
    }

    @Test
    void toolSelectionUsesEfficiencyThenDurabilityThenSlot() {
        EquipmentCandidate inefficient = tool(
                8, "minecraft:pickaxe_a", ToolKind.PICKAXE,
                2, 5.0D, 1000, true, false);
        EquipmentCandidate fragile = tool(
                7, "minecraft:pickaxe_b", ToolKind.PICKAXE,
                2, 6.0D, 10, true, false);
        EquipmentCandidate durableHighSlot = tool(
                6, "minecraft:pickaxe_c", ToolKind.PICKAXE,
                2, 6.0D, 200, true, false);
        EquipmentCandidate durableLowSlot = tool(
                2, "minecraft:pickaxe_d", ToolKind.PICKAXE,
                2, 6.0D, 200, true, false);

        Assertions.assertEquals(
                durableLowSlot,
                BasicEquipmentPolicy.chooseTool(
                                ToolKind.PICKAXE,
                                List.of(
                                        inefficient,
                                        fragile,
                                        durableHighSlot,
                                        durableLowSlot))
                        .orElseThrow());
    }

    @Test
    void toolTieBreakIsStableAcrossInputOrder() {
        EquipmentCandidate higherSlot = tool(
                9, "minecraft:pickaxe_a", ToolKind.PICKAXE,
                2, 6.0D, 200, true, false);
        EquipmentCandidate lowerSlot = tool(
                1, "minecraft:pickaxe_b", ToolKind.PICKAXE,
                2, 6.0D, 200, true, false);

        EquipmentCandidate forward = BasicEquipmentPolicy.chooseTool(
                        ToolKind.PICKAXE,
                        List.of(higherSlot, lowerSlot))
                .orElseThrow();
        EquipmentCandidate reversed = BasicEquipmentPolicy.chooseTool(
                        ToolKind.PICKAXE,
                        List.of(lowerSlot, higherSlot))
                .orElseThrow();

        Assertions.assertEquals(lowerSlot, forward);
        Assertions.assertEquals(lowerSlot, reversed);
    }

    @Test
    void toolSelectionRejectsBrokenBlockedUnusableAndInfiniteCandidates() {
        EquipmentCandidate broken = tool(
                1, "minecraft:broken_pickaxe", ToolKind.PICKAXE,
                4, 20.0D, 0, true, false);
        EquipmentCandidate blocked = tool(
                2, "minecraft:blocked_pickaxe", ToolKind.PICKAXE,
                4, 20.0D, 500, true, true);
        EquipmentCandidate cannotEquip = tool(
                3, "minecraft:unusable_pickaxe", ToolKind.PICKAXE,
                4, 20.0D, 500, false, false);
        EquipmentCandidate infinite = candidate(
                4,
                "minecraft:invalid_pickaxe",
                EquipmentSlotKind.MAIN_HAND_TOOL,
                Optional.of(ToolKind.PICKAXE),
                4,
                0.0D,
                0.0D,
                Double.POSITIVE_INFINITY,
                500,
                true,
                true,
                false);

        Assertions.assertTrue(
                BasicEquipmentPolicy.chooseTool(
                                ToolKind.PICKAXE,
                                List.of(
                                        broken,
                                        blocked,
                                        cannotEquip,
                                        infinite))
                        .isEmpty());
    }

    @Test
    void offhandSelectionOnlyAcceptsExactExplicitRequest() {
        EquipmentCandidate requested = offhand(
                12, "minecraft:torch", false, 0, true, false);
        EquipmentCandidate other = offhand(
                13, "minecraft:shield", true, 200, true, false);

        Assertions.assertTrue(
                BasicEquipmentPolicy.chooseRequestedOffhand(
                                12,
                                other.itemFingerprint(),
                                List.of(requested, other))
                        .isEmpty());
        Assertions.assertEquals(
                requested,
                BasicEquipmentPolicy.chooseRequestedOffhand(
                                12,
                                requested.itemFingerprint(),
                                List.of(other, requested))
                        .orElseThrow());
    }

    @Test
    void dtoRejectsInvalidStructuralBoundaries() {
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> candidate(
                        -1,
                        "minecraft:helmet",
                        EquipmentSlotKind.HEAD,
                        Optional.empty(),
                        0,
                        1.0D,
                        0.0D,
                        0.0D,
                        20,
                        true,
                        true,
                        false));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new EquipmentCandidate(
                        1,
                        ItemStackFingerprint.empty(),
                        EquipmentSlotKind.HEAD,
                        Optional.empty(),
                        0,
                        1.0D,
                        0.0D,
                        0.0D,
                        20,
                        true,
                        true,
                        false,
                        false));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> candidate(
                        1,
                        "minecraft:torch",
                        EquipmentSlotKind.OFFHAND,
                        Optional.empty(),
                        0,
                        0.0D,
                        0.0D,
                        0.0D,
                        1,
                        false,
                        true,
                        false));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> candidate(
                        1,
                        "minecraft:pickaxe",
                        EquipmentSlotKind.MAIN_HAND_TOOL,
                        Optional.empty(),
                        0,
                        0.0D,
                        0.0D,
                        1.0D,
                        20,
                        true,
                        true,
                        false));
    }

    @Test
    void rejectsNonArmorTargetForArmorUpgrade() {
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> BasicEquipmentPolicy.chooseArmorUpgrade(
                        EquipmentSlotKind.OFFHAND,
                        Optional.empty(),
                        List.of()));
    }

    private static EquipmentCandidate armor(
            int inventorySlot,
            String itemId,
            double armor,
            double toughness,
            int remainingDurability,
            boolean canEquip,
            boolean targetBlockedByBinding) {
        return candidate(
                inventorySlot,
                itemId,
                EquipmentSlotKind.HEAD,
                Optional.empty(),
                0,
                armor,
                toughness,
                0.0D,
                remainingDurability,
                true,
                canEquip,
                targetBlockedByBinding);
    }

    private static EquipmentCandidate tool(
            int inventorySlot,
            String itemId,
            ToolKind toolKind,
            int tier,
            double efficiency,
            int remainingDurability,
            boolean canEquip,
            boolean targetBlockedByBinding) {
        return candidate(
                inventorySlot,
                itemId,
                EquipmentSlotKind.MAIN_HAND_TOOL,
                Optional.of(toolKind),
                tier,
                0.0D,
                0.0D,
                efficiency,
                remainingDurability,
                true,
                canEquip,
                targetBlockedByBinding);
    }

    private static EquipmentCandidate offhand(
            int inventorySlot,
            String itemId,
            boolean damageable,
            int remainingDurability,
            boolean canEquip,
            boolean targetBlockedByBinding) {
        return candidate(
                inventorySlot,
                itemId,
                EquipmentSlotKind.OFFHAND,
                Optional.empty(),
                0,
                0.0D,
                0.0D,
                0.0D,
                remainingDurability,
                damageable,
                canEquip,
                targetBlockedByBinding);
    }

    private static EquipmentCandidate candidate(
            int inventorySlot,
            String itemId,
            EquipmentSlotKind targetSlot,
            Optional<ToolKind> toolKind,
            int tier,
            double armor,
            double toughness,
            double efficiency,
            int remainingDurability,
            boolean damageable,
            boolean canEquip,
            boolean targetBlockedByBinding) {
        return new EquipmentCandidate(
                inventorySlot,
                ItemStackFingerprint.of(
                        new ResourceId(itemId),
                        1,
                        0,
                        DIGEST),
                targetSlot,
                toolKind,
                tier,
                armor,
                toughness,
                efficiency,
                remainingDurability,
                damageable,
                canEquip,
                targetBlockedByBinding,
                false);
    }
}
