package io.github.greytaiwolf.botplayer.perception.sensor;

import io.github.greytaiwolf.botplayer.perception.PerceptionBudget;
import io.github.greytaiwolf.botplayer.perception.SensorId;
import io.github.greytaiwolf.botplayer.perception.SensorSchedule;

/**
 * 在服务器主线程上把短生命周期 Minecraft 对象转换为不可变观察 DTO。
 */
public interface BotSensor {
    SensorId id();

    SensorSchedule schedule();

    SensorCost estimatedCost();

    SensorResult sample(SensorContext context, PerceptionBudget budget);
}
