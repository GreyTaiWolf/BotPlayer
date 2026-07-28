package io.github.greytaiwolf.botplayer.worldmodel;

import java.util.Locale;
import java.util.Objects;

/**
 * P3 诊断命令使用的确定性中文置信表达。
 */
public final class ActivityReportFormatter {
    private ActivityReportFormatter() {}

    public static String formatChinese(ActivityHypothesis hypothesis) {
        Objects.requireNonNull(hypothesis, "hypothesis");
        String activity = activityName(hypothesis.type());
        String prefix = switch (hypothesis.band()) {
            case CONFIRMED -> "正在";
            case LIKELY -> "看起来正在";
            case POSSIBLE -> "可能正在";
            case UNCERTAIN -> "我不确定是否正在";
        };
        return String.format(
                Locale.ROOT,
                "%s%s（置信度 %.2f，证据 %d 条）",
                prefix,
                activity,
                hypothesis.confidence(),
                hypothesis.evidence().size());
    }

    private static String activityName(ActivityType type) {
        return switch (type) {
            case UNKNOWN -> "进行可识别活动";
            case IDLE -> "休息";
            case MOVING -> "移动";
            case EXPLORING -> "探索";
            case MINING -> "挖矿";
            case BUILDING -> "建造";
            case COMBAT -> "战斗";
            case FARMING -> "农耕";
            case CRAFTING -> "合成";
            case SMELTING -> "冶炼";
        };
    }
}
