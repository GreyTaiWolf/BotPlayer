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
}
