package io.github.greytaiwolf.botplayer.skill.builtin.farming;

import io.github.greytaiwolf.botplayer.skill.core.SkillId;
import io.github.greytaiwolf.botplayer.skill.core.SkillVersion;

/** P5B 单格原版小麦收获与补种节点的稳定身份。 */
public final class WheatFarmingSkillIds {
    public static final SkillVersion VERSION = new SkillVersion(1, 0, 0);
    public static final SkillId HARVEST_MATURE_WHEAT =
            new SkillId("botplayer", "harvest_mature_wheat");
    public static final SkillId PLANT_WHEAT =
            new SkillId("botplayer", "plant_wheat");

    /** 两个节点只接受同一格的三个严格整数坐标；不接受半径、标签或方块 id。 */
    public static final String TARGET_X_PARAMETER = "target.x";
    public static final String TARGET_Y_PARAMETER = "target.y";
    public static final String TARGET_Z_PARAMETER = "target.z";

    private WheatFarmingSkillIds() {
    }
}
