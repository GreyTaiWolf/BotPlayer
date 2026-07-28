package io.github.greytaiwolf.botplayer.worldmodel;

/**
 * 世界事实的当前可信状态。
 */
public enum FactStatus {
    ACTIVE,
    STALE,
    STALE_UNKNOWN,
    SUPERSEDED,
    RETRACTED,
    UNVERIFIED
}
