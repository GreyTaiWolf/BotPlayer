package io.github.greytaiwolf.botplayer.worldmodel;

/**
 * 权威侧内部失效判断使用的全局、维度和精确目标 revision 水位。
 */
public record RevisionStamp(long global, long dimension, long target) {
    public RevisionStamp {
        if (global < 0 || dimension < 0 || target < 0) {
            throw new IllegalArgumentException("revision values must not be negative");
        }
    }
}
