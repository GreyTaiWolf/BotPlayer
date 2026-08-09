package io.github.greytaiwolf.botplayer.skill.builtin;

import io.github.greytaiwolf.botplayer.skill.core.SkillId;
import io.github.greytaiwolf.botplayer.skill.core.SkillVersion;

/** P5A 内建节点的稳定身份；外部包只能引用这些精确 ID/版本。 */
public final class P5ABuiltinSkillIds {
    public static final SkillVersion VERSION = new SkillVersion(1, 0, 0);
    public static final SkillId EQUIP_BASIC_TOOL =
            new SkillId("botplayer", "equip_basic_tool");
    public static final SkillId EQUIP_REQUESTED_OFFHAND =
            new SkillId("botplayer", "equip_requested_offhand");
    public static final SkillId SELF_DEFEND =
            new SkillId("botplayer", "self_defend");
    public static final SkillId STORE_ITEMS =
            new SkillId("botplayer", "store_items");
    public static final SkillId BOOTSTRAP_IRON =
            new SkillId("botplayer", "bootstrap_iron");
    /**
     * {@link #BOOTSTRAP_IRON} 节点唯一允许的运行参数。值必须是编译进来的生产 operation id，
     * 不是坐标、命令、菜单会话或活动 Minecraft 对象。
     */
    public static final String BOOTSTRAP_IRON_OPERATION_ID_PARAMETER =
            "operation.id";

    private P5ABuiltinSkillIds() {
    }
}
