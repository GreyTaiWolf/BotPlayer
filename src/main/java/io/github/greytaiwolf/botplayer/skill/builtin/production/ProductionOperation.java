package io.github.greytaiwolf.botplayer.skill.builtin.production;

/**
 * 不可变、无副作用的生产步骤输入。实现类不含玩家、菜单、方块或世界对象。
 */
public sealed interface ProductionOperation permits ResourceAcquisition,
        RecipeExecution, SingleChestTransfer {
    ProductionOperationKind kind();
}
