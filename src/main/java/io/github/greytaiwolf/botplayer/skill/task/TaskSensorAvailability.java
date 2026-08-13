package io.github.greytaiwolf.botplayer.skill.task;

/**
 * 不可用不等于空结果；调用者必须重新观察或失败，不能推断世界中没有目标。
 */
public enum TaskSensorAvailability {
    AVAILABLE,
    UNAVAILABLE
}
