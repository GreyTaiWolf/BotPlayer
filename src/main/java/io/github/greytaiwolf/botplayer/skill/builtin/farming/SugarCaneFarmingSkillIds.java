package io.github.greytaiwolf.botplayer.skill.builtin.farming;

import io.github.greytaiwolf.botplayer.skill.core.SkillId;
import io.github.greytaiwolf.botplayer.skill.core.SkillVersion;

/** P5B 最小原版甘蔗上节收获节点的稳定身份。 */
public final class SugarCaneFarmingSkillIds {
    public static final SkillVersion VERSION = new SkillVersion(1, 0, 0);
    public static final SkillId HARVEST_UPPER_SUGAR_CANE =
            new SkillId("botplayer", "harvest_upper_sugar_cane");

    /** 只接受一个已知候选方块的三个严格整数坐标。 */
    public static final String TARGET_X_PARAMETER = "target.x";
    public static final String TARGET_Y_PARAMETER = "target.y";
    public static final String TARGET_Z_PARAMETER = "target.z";

    private SugarCaneFarmingSkillIds() {
    }
}
