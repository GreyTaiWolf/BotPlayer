package io.github.greytaiwolf.botplayer.perception.event;

/**
 * P3 使用的有界语义事件类型。
 */
public enum SemanticEventType {
    ACTION_COMPLETED,
    ACTION_FAILED,
    BLOCK_BROKEN,
    BLOCK_PLACED,
    BLOCK_CHANGED,
    CONTAINER_CHANGED,
    ITEM_PICKED_UP,
    ITEM_DROPPED,
    ENTITY_DAMAGED,
    ENTITY_DIED,
    SOUND_PLAYED,
    PLAYER_CHAT,
    PLAYER_CORRECTION,
    REGION_ENTERED,
    DIMENSION_CHANGED,
    WEATHER_CHANGED,
    PERMISSION_DENIED
}
