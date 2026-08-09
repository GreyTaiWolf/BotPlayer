package io.github.greytaiwolf.botplayer.skill.menu;

import java.util.List;
import java.util.Objects;

/**
 * 有界、不可变的通用菜单点击计划。
 *
 * <p>计划只保存完整快照链，不保存 Minecraft 菜单、ItemStack 或打开窗口的对象引用，
 * 因此可以安全用于运行时恢复前的纯逻辑验证。
 */
public record MenuTransactionPlan(
        MenuFamily family,
        MenuSnapshot initialSnapshot,
        List<MenuClickStep> orderedSteps,
        MenuSnapshot finalSnapshot) {

    public MenuTransactionPlan {
        Objects.requireNonNull(family, "family");
        Objects.requireNonNull(initialSnapshot, "initialSnapshot");
        Objects.requireNonNull(orderedSteps, "orderedSteps");
        Objects.requireNonNull(finalSnapshot, "finalSnapshot");
        if (initialSnapshot.family() != family
                || finalSnapshot.family() != family) {
            throw new IllegalArgumentException(
                    "plan snapshots must use the declared menu family");
        }
        if (orderedSteps.isEmpty()) {
            throw new IllegalArgumentException(
                    "menu plan must contain at least one click");
        }
        orderedSteps = List.copyOf(orderedSteps);

        MenuSnapshot expectedBefore = initialSnapshot;
        for (MenuClickStep step : orderedSteps) {
            Objects.requireNonNull(step, "ordered click step");
            if (step.expectedBefore().family() != family
                    || !step.expectedBefore()
                            .layoutEqualsIgnoringState(expectedBefore)) {
                throw new IllegalArgumentException(
                        "menu plan steps must form one exact layout chain");
            }
            expectedBefore = step.expectedAfter();
        }
        if (!finalSnapshot.layoutEqualsIgnoringState(expectedBefore)) {
            throw new IllegalArgumentException(
                    "final snapshot must match the final planned click");
        }
        validateUniquePrefixes(initialSnapshot, orderedSteps);
    }

    public MenuSnapshot snapshotAtPrefix(int confirmedClicks) {
        if (confirmedClicks < 0 || confirmedClicks > orderedSteps.size()) {
            throw new IllegalArgumentException(
                    "confirmed click count is outside the plan");
        }
        return confirmedClicks == 0
                ? initialSnapshot
                : orderedSteps.get(confirmedClicks - 1).expectedAfter();
    }

    private static void validateUniquePrefixes(
            MenuSnapshot initialSnapshot,
            List<MenuClickStep> orderedSteps) {
        for (int first = 0; first <= orderedSteps.size(); first++) {
            MenuSnapshot firstSnapshot = first == 0
                    ? initialSnapshot
                    : orderedSteps.get(first - 1).expectedAfter();
            for (int second = first + 1;
                    second <= orderedSteps.size(); second++) {
                MenuSnapshot secondSnapshot = orderedSteps
                        .get(second - 1).expectedAfter();
                if (firstSnapshot.layoutEqualsIgnoringState(secondSnapshot)) {
                    throw new IllegalArgumentException(
                            "menu plan prefixes must have unique layouts");
                }
            }
        }
    }
}
