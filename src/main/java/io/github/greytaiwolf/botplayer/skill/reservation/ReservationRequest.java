package io.github.greytaiwolf.botplayer.skill.reservation;

import java.util.Objects;

/**
 * 一次原子租约申请中的单个纯数据资源请求。
 */
public record ReservationRequest(
        ReservationKey key, ReservationMode mode) {
    public ReservationRequest {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(mode, "mode");
    }
}
