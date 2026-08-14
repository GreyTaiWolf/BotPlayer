package io.github.greytaiwolf.botplayer.ai;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 有界退避抖动的可注入来源。
 *
 * <p>生产默认使用线程本地随机源；测试可用 {@link #fixed(long)} 精确控制偏移。调用方给出的
 * 非法值会被策略归一化到合法范围，不能扩大退避预算。
 */
@FunctionalInterface
public interface AiRetryJitter {
    /**
     * 返回介于零和 {@code maximumOffsetMillis}（含）之间的候选毫秒偏移。
     */
    long nextOffsetMillis(long maximumOffsetMillis);

    static AiRetryJitter defaults() {
        return maximumOffsetMillis -> maximumOffsetMillis == 0L
                ? 0L
                : ThreadLocalRandom.current().nextLong(
                        maximumOffsetMillis + 1L);
    }

    static AiRetryJitter none() {
        return maximumOffsetMillis -> 0L;
    }

    static AiRetryJitter fixed(long offsetMillis) {
        if (offsetMillis < 0L) {
            throw new IllegalArgumentException(
                    "offsetMillis must not be negative");
        }
        return maximumOffsetMillis -> Math.min(
                offsetMillis,
                Math.max(0L, maximumOffsetMillis));
    }

    static AiRetryJitter checked(AiRetryJitter jitter) {
        return Objects.requireNonNull(jitter, "jitter");
    }
}
