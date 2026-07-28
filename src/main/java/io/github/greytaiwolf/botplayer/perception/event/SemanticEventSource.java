package io.github.greytaiwolf.botplayer.perception.event;

/**
 * 语义事件的权威来源。来源只描述证据边界，不授予额外权限。
 */
public enum SemanticEventSource {
    ACTION_RESULT,
    NEOFORGE_EVENT,
    VANILLA_CLIENTBOUND,
    SENSOR_OBSERVATION,
    PLAYER_CORRECTION,
    ADMIN_OMNISCIENT
}
