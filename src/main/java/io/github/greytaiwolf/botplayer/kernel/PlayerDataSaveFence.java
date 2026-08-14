package io.github.greytaiwolf.botplayer.kernel;

/**
 * 玩家数据保存围栏的有界状态。一次性门闩只属于 {@code PlayerList.remove} 内的原版保存；
 * 其余围栏在显式释放前持续生效。
 */
final class PlayerDataSaveFence {
    private boolean suppressNextRemovalSave;
    private boolean suppressUntilReleased;
    private boolean disconnectPreSave;
    private boolean deathRetirementSave;
    private boolean deathAttemptSave;

    void armNextRemovalSave() {
        suppressNextRemovalSave = true;
    }

    void armPersistentSaveFence() {
        suppressUntilReleased = true;
    }

    boolean hasPersistentSaveFence() {
        return suppressUntilReleased;
    }

    void armDisconnectPreSaveFence() {
        disconnectPreSave = true;
    }

    boolean hasDisconnectPreSaveFence() {
        return disconnectPreSave;
    }

    boolean releaseDisconnectPreSaveFence() {
        disconnectPreSave = false;
        return !hasAnySaveFence();
    }

    void armDeathRetirementSaveFence() {
        deathRetirementSave = true;
    }

    boolean hasDeathRetirementSaveFence() {
        return deathRetirementSave;
    }

    boolean releaseDeathRetirementSaveFence() {
        deathRetirementSave = false;
        return !hasAnySaveFence();
    }

    void transferDeathAttemptToRetirement() {
        deathRetirementSave = true;
        deathAttemptSave = false;
    }

    void armDeathAttemptSaveFence() {
        deathAttemptSave = true;
    }

    void releaseDeathAttemptSaveFence() {
        deathAttemptSave = false;
    }

    /** 只检查持久围栏，绝不读取或消费 remove 专属的一次性门闩。 */
    boolean shouldSuppressOrdinarySave() {
        return suppressUntilReleased
                || disconnectPreSave
                || deathRetirementSave
                || deathAttemptSave;
    }

    /** 仅供 {@code PlayerList.remove} 内被精确包装的原版保存消费。 */
    boolean consumeRemovalSaveSuppression() {
        if (shouldSuppressOrdinarySave()) {
            return true;
        }
        boolean suppressed = suppressNextRemovalSave;
        suppressNextRemovalSave = false;
        return suppressed;
    }

    void clearRemovalSaveSuppression() {
        suppressNextRemovalSave = false;
    }

    void releaseAll() {
        suppressNextRemovalSave = false;
        suppressUntilReleased = false;
        disconnectPreSave = false;
        deathRetirementSave = false;
        deathAttemptSave = false;
    }

    void releaseInheritedPersistentSaveFence() {
        suppressUntilReleased = false;
    }

    void retainPersistentPoisonAfterRemoval() {
        suppressNextRemovalSave = false;
        suppressUntilReleased = true;
        disconnectPreSave = false;
        deathRetirementSave = false;
        deathAttemptSave = false;
    }

    boolean hasDeathRetirementOrAttemptFence() {
        return deathRetirementSave || deathAttemptSave;
    }

    private boolean hasAnySaveFence() {
        return suppressNextRemovalSave
                || suppressUntilReleased
                || disconnectPreSave
                || deathRetirementSave
                || deathAttemptSave;
    }
}
