package io.github.greytaiwolf.botplayer.skill.builtin;

import io.github.greytaiwolf.botplayer.skill.core.SkillId;
import io.github.greytaiwolf.botplayer.skill.core.SkillVersion;

/** P5A 内建节点的稳定身份；外部包只能引用这些精确 ID/版本。 */
public final class P5ABuiltinSkillIds {
    public static final SkillVersion VERSION = new SkillVersion(1, 0, 0);
    public static final SkillId EQUIP_BASIC_TOOL =
            new SkillId("botplayer", "equip_basic_tool");
    /**
     * 仅将 P5A 编译期白名单中的精确物品切入当前主手热栏位。这个节点不是任意
     * item-id 选择器；运行参数必须由
     * {@code MinecraftBasicEquipmentPlanner.ExactMainHandItem} 认可。
     */
    public static final SkillId EQUIP_EXACT_MAIN_HAND =
            new SkillId("botplayer", "equip_exact_main_hand");
    public static final SkillId EQUIP_REQUESTED_OFFHAND =
            new SkillId("botplayer", "equip_requested_offhand");
    public static final SkillId SELF_DEFEND =
            new SkillId("botplayer", "self_defend");
    public static final SkillId STORE_ITEMS =
            new SkillId("botplayer", "store_items");
    public static final SkillId BOOTSTRAP_IRON =
            new SkillId("botplayer", "bootstrap_iron");
    /**
     * P5A 固定生产 DAG 在每个资源物理采集 fragment 前使用的有界局部导航节点。它不是任意
     * 坐标导航入口：参数只能表达编译器审核过的精确资源方块类型，目标坐标始终在运行时由
     * TaskSensor 当前 tick 的局部候选重新选择。
     */
    public static final SkillId NAVIGATE_TO_RESOURCE =
            new SkillId("botplayer", "navigate_to_resource");
    /**
     * {@link #BOOTSTRAP_IRON} 节点唯一允许的运行参数。值必须是编译进来的生产 operation id，
     * 不是坐标、命令、菜单会话或活动 Minecraft 对象。
     */
    public static final String BOOTSTRAP_IRON_OPERATION_ID_PARAMETER =
            "operation.id";
    /**
     * {@link #NAVIGATE_TO_RESOURCE} 唯一允许的参数。值是四种 P5A 审核资源方块中的精确
     * block id；绝不接受坐标、标签、半径或导航策略。
     */
    public static final String RESOURCE_NAVIGATION_BLOCK_ID_PARAMETER =
            "resource.block";
    /**
     * {@link #EQUIP_EXACT_MAIN_HAND} 唯一允许的参数。值是编译进 P5A 模板的精确
     * Minecraft item id；handler 会再次映射到封闭白名单，不能借此请求任意物品。
     */
    public static final String EXACT_MAIN_HAND_ITEM_ID_PARAMETER = "item.id";

    private P5ABuiltinSkillIds() {
    }
}
