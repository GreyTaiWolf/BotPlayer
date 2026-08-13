package io.github.greytaiwolf.botplayer.skill.checkpoint;

/** 恢复前的瞬态状态证明；任一否定项都禁止续跑。 */
public record SkillCheckpointRecoverySafety(
        boolean menuClosed,
        boolean actionQuiescent,
        boolean carriedStateEmpty,
        boolean oldGenerationTokensCleared) {
    public boolean safe() {
        return menuClosed
                && actionQuiescent
                && carriedStateEmpty
                && oldGenerationTokensCleared;
    }
}
