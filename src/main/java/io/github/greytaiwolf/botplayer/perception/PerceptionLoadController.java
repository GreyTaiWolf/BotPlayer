package io.github.greytaiwolf.botplayer.perception;

/**
 * 使用 EWMA 与恢复滞回控制感知降级，避免单个慢 Tick 造成频繁档位抖动。
 */
public final class PerceptionLoadController {
    private static final double EWMA_ALPHA = 0.20D;

    private final double degradeMspt;
    private final double criticalMspt;
    private final double recoverMspt;
    private final double criticalRecoverMspt;
    private PerceptionPressure pressure = PerceptionPressure.NORMAL;
    private double smoothedMspt;
    private boolean initialized;

    public PerceptionLoadController(
            double degradeMspt,
            double criticalMspt,
            double recoverMspt,
            double criticalRecoverMspt) {
        requireFinitePositive(degradeMspt, "degradeMspt");
        requireFinitePositive(criticalMspt, "criticalMspt");
        requireFinitePositive(recoverMspt, "recoverMspt");
        requireFinitePositive(criticalRecoverMspt, "criticalRecoverMspt");
        if (recoverMspt >= degradeMspt) {
            throw new IllegalArgumentException("recoverMspt must be below degradeMspt");
        }
        if (degradeMspt >= criticalMspt) {
            throw new IllegalArgumentException("degradeMspt must be below criticalMspt");
        }
        if (criticalRecoverMspt >= criticalMspt
                || criticalRecoverMspt < recoverMspt) {
            throw new IllegalArgumentException(
                    "criticalRecoverMspt must be between recoverMspt and criticalMspt");
        }
        this.degradeMspt = degradeMspt;
        this.criticalMspt = criticalMspt;
        this.recoverMspt = recoverMspt;
        this.criticalRecoverMspt = criticalRecoverMspt;
    }

    public PerceptionPressure recordTickDurationNanos(long durationNanos) {
        if (durationNanos < 0) {
            throw new IllegalArgumentException("durationNanos must not be negative");
        }
        double mspt = durationNanos / 1_000_000.0D;
        smoothedMspt = initialized
                ? smoothedMspt + EWMA_ALPHA * (mspt - smoothedMspt)
                : mspt;
        initialized = true;
        pressure = switch (pressure) {
            case NORMAL -> smoothedMspt >= criticalMspt
                    ? PerceptionPressure.CRITICAL
                    : smoothedMspt >= degradeMspt
                            ? PerceptionPressure.DEGRADED
                            : PerceptionPressure.NORMAL;
            case DEGRADED -> smoothedMspt >= criticalMspt
                    ? PerceptionPressure.CRITICAL
                    : smoothedMspt <= recoverMspt
                            ? PerceptionPressure.NORMAL
                            : PerceptionPressure.DEGRADED;
            case CRITICAL -> smoothedMspt <= recoverMspt
                    ? PerceptionPressure.NORMAL
                    : smoothedMspt <= criticalRecoverMspt
                            ? PerceptionPressure.DEGRADED
                            : PerceptionPressure.CRITICAL;
        };
        return pressure;
    }

    public PerceptionPressure pressure() {
        return pressure;
    }

    public double smoothedMspt() {
        return smoothedMspt;
    }

    private static void requireFinitePositive(double value, String field) {
        if (!Double.isFinite(value) || value <= 0.0D) {
            throw new IllegalArgumentException(field + " must be finite and positive");
        }
    }
}
