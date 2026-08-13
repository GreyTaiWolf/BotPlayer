package io.github.greytaiwolf.botplayer.skill.runtime;

import io.github.greytaiwolf.botplayer.skill.builtin.P5ABuiltinSkillIds;
import io.github.greytaiwolf.botplayer.skill.builtin.survival.MinecraftBasicEquipmentPlanner.ExactMainHandItem;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class MinecraftEquipmentSkillNodeHandlerTest {
    @Test
    void exactMainHandParameterOnlyAcceptsTheClosedPlannerWhitelist() {
        Assertions.assertEquals(Optional.of(ExactMainHandItem.WOODEN_PICKAXE),
                MinecraftEquipmentSkillNodeHandler.parseExactMainHandItem(
                        "minecraft:wooden_pickaxe"));
        Assertions.assertEquals(Optional.of(ExactMainHandItem.STONE_PICKAXE),
                MinecraftEquipmentSkillNodeHandler.parseExactMainHandItem(
                        "minecraft:stone_pickaxe"));
        Assertions.assertEquals(Optional.of(ExactMainHandItem.IRON_PICKAXE),
                MinecraftEquipmentSkillNodeHandler.parseExactMainHandItem(
                        "minecraft:iron_pickaxe"));
        Assertions.assertEquals(Optional.of(ExactMainHandItem.CRAFTING_TABLE),
                MinecraftEquipmentSkillNodeHandler.parseExactMainHandItem(
                        "minecraft:crafting_table"));
        Assertions.assertEquals(Optional.of(ExactMainHandItem.FURNACE),
                MinecraftEquipmentSkillNodeHandler.parseExactMainHandItem(
                        "minecraft:furnace"));
        Assertions.assertTrue(MinecraftEquipmentSkillNodeHandler
                .parseExactMainHandItem("minecraft:diamond_pickaxe")
                .isEmpty());
        Assertions.assertTrue(MinecraftEquipmentSkillNodeHandler
                .parseExactMainHandItem("Minecraft:furnace").isEmpty());
        Assertions.assertTrue(MinecraftEquipmentSkillNodeHandler
                .parseExactMainHandItem(12).isEmpty());
        Assertions.assertTrue(MinecraftEquipmentSkillNodeHandler
                .parseExactMainHandItem(null).isEmpty());
    }

    @Test
    void exactMainHandSkillIdentityAndParameterNameAreStable() {
        Assertions.assertEquals("botplayer:equip_exact_main_hand",
                P5ABuiltinSkillIds.EQUIP_EXACT_MAIN_HAND.toString());
        Assertions.assertEquals("item.id",
                P5ABuiltinSkillIds.EXACT_MAIN_HAND_ITEM_ID_PARAMETER);
    }

    @Test
    void exactMainHandOperationKeysRemainValidActionBridgeIdentifiers() {
        Assertions.assertAll(
                () -> Assertions.assertEquals("equip-exact-crafting-table",
                        MinecraftEquipmentSkillNodeHandler
                                .exactMainHandOperationKey(
                                        ExactMainHandItem.CRAFTING_TABLE)),
                () -> Assertions.assertEquals("equip-exact-furnace",
                        MinecraftEquipmentSkillNodeHandler
                                .exactMainHandOperationKey(
                                        ExactMainHandItem.FURNACE)),
                () -> Assertions.assertEquals("equip-exact-wooden-pickaxe",
                        MinecraftEquipmentSkillNodeHandler
                                .exactMainHandOperationKey(
                                        ExactMainHandItem.WOODEN_PICKAXE)),
                () -> Assertions.assertEquals("equip-exact-stone-pickaxe",
                        MinecraftEquipmentSkillNodeHandler
                                .exactMainHandOperationKey(
                                        ExactMainHandItem.STONE_PICKAXE)),
                () -> Assertions.assertEquals("equip-exact-iron-pickaxe",
                        MinecraftEquipmentSkillNodeHandler
                                .exactMainHandOperationKey(
                                        ExactMainHandItem.IRON_PICKAXE)));
        for (ExactMainHandItem item : ExactMainHandItem.values()) {
            String key = MinecraftEquipmentSkillNodeHandler
                    .exactMainHandOperationKey(item);
            Assertions.assertTrue(key.matches("[a-z][a-z0-9_-]{0,31}"),
                    () -> "action bridge rejected exact-main-hand key: "
                            + key);
        }
    }
}
