package io.github.greytaiwolf.botplayer.perception.event;

import java.util.List;
import java.util.Objects;

/**
 * 有界事件读取结果。{@code truncatedBefore} 表示调用方的游标已经落后于保留窗口。
 */
public record EventReadWindow<T>(
        List<T> events,
        boolean truncatedBefore,
        long oldestAvailableSeq,
        long newestAvailableSeq) {
    public EventReadWindow {
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        if (oldestAvailableSeq < 0 || newestAvailableSeq < 0) {
            throw new IllegalArgumentException("sequence bounds must not be negative");
        }
        if (oldestAvailableSeq > newestAvailableSeq && newestAvailableSeq != 0) {
            throw new IllegalArgumentException("oldest sequence cannot exceed newest sequence");
        }
    }
}
