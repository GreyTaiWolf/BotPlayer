package io.github.greytaiwolf.botplayer.worldmodel;

/**
 * 置信等级是解释性阈值，不宣称为统计学概率。
 */
public enum ActivityConfidenceBand {
    UNCERTAIN,
    POSSIBLE,
    LIKELY,
    CONFIRMED;

    public static ActivityConfidenceBand fromConfidence(float confidence) {
        if (!Float.isFinite(confidence) || confidence < 0.0F || confidence > 1.0F) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        if (confidence >= 0.85F) {
            return CONFIRMED;
        }
        if (confidence >= 0.65F) {
            return LIKELY;
        }
        if (confidence >= 0.40F) {
            return POSSIBLE;
        }
        return UNCERTAIN;
    }
}
