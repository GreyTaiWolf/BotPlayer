package io.github.greytaiwolf.botplayer.skill.menu;

import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import java.util.Optional;

/**
 * 解析一个计划中 crafting 输入格变更后的原版 result preview。
 *
 * <p>返回 {@link Optional#empty()} 表示解析器无法在权威菜单语义下作出判断，调用方必须
 * 拒绝构造计划；{@code Optional.of(ItemStackFingerprint.empty())} 才表示原版确实没有结果。
 * 这一区分避免把未知的中间配方 preview 当成空格位继续执行。
 */
@FunctionalInterface
public interface CraftingPreviewResolver {
    Optional<ItemStackFingerprint> resolve(MenuSnapshot postInput);
}
