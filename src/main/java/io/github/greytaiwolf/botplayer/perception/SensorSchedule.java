package io.github.greytaiwolf.botplayer.perception;

/**
 * 不同压力档位下的确定性采样间隔；0 表示该档位暂停此传感器。
 */
public record SensorSchedule(
        int normalInterval,
        int degradedInterval,
        int criticalInterval) {
    public SensorSchedule {
        requireInterval(normalInterval, "normalInterval", false);
        requireInterval(degradedInterval, "degradedInterval", true);
        requireInterval(criticalInterval, "criticalInterval", true);
    }

    public boolean due(
            long lastSampleTick, long currentTick, PerceptionPressure pressure) {
        if (lastSampleTick < -1 || currentTick < 0 || currentTick < lastSampleTick) {
            throw new IllegalArgumentException("sensor tick range is invalid");
        }
        int interval = switch (pressure) {
            case NORMAL -> normalInterval;
            case DEGRADED -> degradedInterval;
            case CRITICAL -> criticalInterval;
        };
        return interval > 0
                && (lastSampleTick < 0 || currentTick - lastSampleTick >= interval);
    }

    private static void requireInterval(
            int value, String field, boolean zeroAllowed) {
        int minimum = zeroAllowed ? 0 : 1;
        if (value < minimum || value > 20 * 60) {
            throw new IllegalArgumentException(
                    field + " must be between " + minimum + " and 1200");
        }
    }
}
