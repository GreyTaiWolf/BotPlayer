package io.github.greytaiwolf.botplayer.skill.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 可在真实菜单打开后绑定的完整点击计划。
 *
 * <p>该模板不保存活菜单或预估 window id。{@link #bind(MenuSnapshot)} 只在初始布局
 * 与原版权威快照逐槽一致时产生 {@link MenuTransactionPlan}，其他情况一律返回空。
 */
public record MenuTransactionTemplate(
        MenuFamily family,
        MenuLayout initialLayout,
        List<MenuTemplateStep> orderedSteps,
        MenuLayout finalLayout) {
    public MenuTransactionTemplate {
        Objects.requireNonNull(family, "family");
        Objects.requireNonNull(initialLayout, "initialLayout");
        Objects.requireNonNull(orderedSteps, "orderedSteps");
        Objects.requireNonNull(finalLayout, "finalLayout");
        if (initialLayout.family() != family
                || finalLayout.family() != family
                || orderedSteps.isEmpty()) {
            throw new IllegalArgumentException(
                    "template must use one family and contain at least one click");
        }
        orderedSteps = List.copyOf(orderedSteps);
        MenuLayout previous = initialLayout;
        for (MenuTemplateStep step : orderedSteps) {
            Objects.requireNonNull(step, "template step");
            if (step.expectedBefore().family() != family
                    || !step.expectedBefore().equals(previous)) {
                throw new IllegalArgumentException(
                        "template steps must form one exact layout chain");
            }
            previous = step.expectedAfter();
        }
        if (!finalLayout.equals(previous)) {
            throw new IllegalArgumentException(
                    "template final layout must match its final click");
        }
        validateUniquePrefixes(initialLayout, orderedSteps);
    }

    /**
     * 以原版实际 containerId/stateId 绑定模板；调用方应把空结果视为外部世界变化。
     */
    public java.util.Optional<MenuTransactionPlan> bind(
            MenuSnapshot openedSnapshot) {
        if (openedSnapshot == null
                || openedSnapshot.family() != family
                || !initialLayout.matches(openedSnapshot)) {
            return java.util.Optional.empty();
        }
        try {
            List<MenuClickStep> bound = new ArrayList<>(
                    orderedSteps.size());
            MenuSnapshot before = openedSnapshot;
            for (int index = 0; index < orderedSteps.size(); index++) {
                MenuTemplateStep step = orderedSteps.get(index);
                MenuSnapshot after = step.expectedAfter().bind(
                        openedSnapshot.containerId(),
                        Math.addExact(
                                openedSnapshot.stateId(), index + 1));
                bound.add(new MenuClickStep(
                        step.click(), before, after, step.conservation()));
                before = after;
            }
            return java.util.Optional.of(new MenuTransactionPlan(
                    family, openedSnapshot, bound, before));
        } catch (ArithmeticException | IllegalArgumentException exception) {
            return java.util.Optional.empty();
        }
    }

    private static void validateUniquePrefixes(
            MenuLayout initial, List<MenuTemplateStep> steps) {
        List<MenuLayout> prefixes = new ArrayList<>(steps.size() + 1);
        prefixes.add(initial);
        for (MenuTemplateStep step : steps) {
            MenuLayout after = step.expectedAfter();
            if (prefixes.contains(after)) {
                throw new IllegalArgumentException(
                        "template prefixes must have unique layouts");
            }
            prefixes.add(after);
        }
    }
}
