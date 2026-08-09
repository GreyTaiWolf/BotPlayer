package io.github.greytaiwolf.botplayer.skill.menu;

/**
 * 通用菜单事务允许的最小原版点击子集。
 *
 * <p>拖拽、克隆、丢弃与 pickup-all 都可能跨越多个槽位或损失物品，因此 P5A 模型不
 * 表示它们。适配器若收到这些请求必须失败关闭。
 */
public enum MenuClickType {
    PICKUP,
    QUICK_MOVE,
    SWAP;

    public boolean acceptsButton(int button) {
        return switch (this) {
            case PICKUP -> button == 0 || button == 1;
            case QUICK_MOVE -> button == 0;
            case SWAP -> button >= 0 && button <= 8;
        };
    }
}
