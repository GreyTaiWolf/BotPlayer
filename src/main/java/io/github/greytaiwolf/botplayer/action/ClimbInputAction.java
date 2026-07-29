package io.github.greytaiwolf.botplayer.action;

import io.github.greytaiwolf.botplayer.action.input.PlayerInputState;
import java.util.Set;

public record ClimbInputAction(
        float forward,
        float strafe,
        boolean ascend,
        boolean descend,
        int ticks)
        implements ActionRequest {
    public static final int MAX_TICKS = 40;
    public static final double MINIMUM_VERTICAL_PROGRESS = 0.05D;
    private static final Set<ActionChannel> CHANNELS =
            Set.of(ActionChannel.MOVE);

    public ClimbInputAction {
        if (ascend == descend) {
            throw new IllegalArgumentException(
                    "exactly one of ascend or descend must be true");
        }
        new PlayerInputState(
                forward, strafe, ascend, false, descend, false);
        if (ticks < 1 || ticks > MAX_TICKS) {
            throw new IllegalArgumentException(
                    "ticks must be between 1 and " + MAX_TICKS);
        }
    }

    @Override
    public ActionKind kind() {
        return ActionKind.CLIMB_INPUT;
    }

    @Override
    public Set<ActionChannel> channels() {
        return CHANNELS;
    }

    public PlayerInputState inputState() {
        return new PlayerInputState(
                forward, strafe, ascend, false, descend, false);
    }

    public boolean hasVerticalProgress(double startY, double endY) {
        if (!Double.isFinite(startY) || !Double.isFinite(endY)) {
            return false;
        }
        double delta = endY - startY;
        return ascend
                ? delta >= MINIMUM_VERTICAL_PROGRESS
                : delta <= -MINIMUM_VERTICAL_PROGRESS;
    }
}
