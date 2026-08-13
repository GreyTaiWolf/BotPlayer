package io.github.greytaiwolf.botplayer.skill.builtin.defense;

/**
 * 由上游感知器显式给出的目标归类。
 *
 * <p>有限自卫不会根据实体类型、名字或距离自行推断敌意；只有
 * {@link #EXPLICIT_HOSTILE} 才允许作为攻击目标。
 */
public enum DefenseTargetClass {
    EXPLICIT_HOSTILE,
    PLAYER,
    FRIENDLY,
    NEUTRAL,
    UNKNOWN;

    public boolean isExplicitHostile() {
        return this == EXPLICIT_HOSTILE;
    }

    public boolean isPvpOrFriendly() {
        return this == PLAYER || this == FRIENDLY;
    }
}
