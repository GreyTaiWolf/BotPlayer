package io.github.greytaiwolf.botplayer.skill.builtin.production;

/**
 * 采集意图，供后续导航/挖掘技能选择对应实现；它本身没有世界副作用。
 */
public enum AcquisitionMethod {
    HARVEST_LOG,
    MINE_COBBLESTONE,
    MINE_RAW_IRON,
    MINE_COAL
}
