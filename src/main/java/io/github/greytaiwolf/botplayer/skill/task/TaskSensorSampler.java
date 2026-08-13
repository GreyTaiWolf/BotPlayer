package io.github.greytaiwolf.botplayer.skill.task;

/**
 * 主线程适配器的窄接口。实现只能从已有合法 scope 中构造不可变快照。
 */
@FunctionalInterface
public interface TaskSensorSampler {
    TaskSensorSnapshot sample(TaskSensorQuery query, long currentTick);
}
