package io.github.greytaiwolf.botplayer.skill.builtin.defense;

/**
 * P5A 有限自卫唯一可以交给动作层的动作类别。
 *
 * <p>故意不包含盾牌、远程、药水、范围攻击或第二目标的操作。
 */
public enum DefenseActionKind {
    MELEE_ATTACK,
    RETREAT
}
