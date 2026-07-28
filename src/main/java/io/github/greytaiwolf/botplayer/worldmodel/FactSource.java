package io.github.greytaiwolf.botplayer.worldmodel;

/**
 * 事实来源只表示证据等级，不表示事实永远正确。
 */
public enum FactSource {
    SELF,
    VISUAL,
    AUDIBLE,
    ACTION_RESULT,
    PLAYER_ASSERTED,
    INFERRED,
    ADMIN
}
