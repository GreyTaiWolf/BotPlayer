package io.github.greytaiwolf.botplayer.kernel;

/**
 * 保存 fence 内只允许一次外层 PlayerList.save 的一次性门。
 */
final class DeathDataSavePermit {
    enum HandoffMode {
        INCLUDE,
        OMIT
    }

    private State state = State.IDLE;
    private HandoffMode handoffMode = HandoffMode.INCLUDE;

    void arm(HandoffMode requestedMode) {
        if (state != State.IDLE) {
            throw new IllegalStateException(
                    "a controlled player-data save is already armed");
        }
        handoffMode = requestedMode;
        state = State.ARMED;
    }

    boolean shouldSuppress(boolean ordinaryFenceArmed) {
        if (state == State.ARMED) {
            state = State.SERIALIZING;
            return false;
        }
        if (state == State.SERIALIZING) {
            return true;
        }
        return ordinaryFenceArmed;
    }

    boolean omitHandoffWhileSerializing() {
        return state == State.SERIALIZING
                && handoffMode == HandoffMode.OMIT;
    }

    boolean finish() {
        boolean serialized = state == State.SERIALIZING;
        state = State.IDLE;
        handoffMode = HandoffMode.INCLUDE;
        return serialized;
    }

    boolean armedOrSerializing() {
        return state != State.IDLE;
    }

    private enum State {
        IDLE,
        ARMED,
        SERIALIZING
    }
}
