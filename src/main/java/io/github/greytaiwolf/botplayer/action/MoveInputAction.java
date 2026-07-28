package io.github.greytaiwolf.botplayer.action;

import io.github.greytaiwolf.botplayer.action.input.PlayerInputState;
import java.util.Set;

/**
 * Holds bounded forward/strafe player input for a short deterministic interval.
 *
 * <p>This action deliberately has no target coordinate and cannot teleport. Long-distance waypoint
 * following belongs to P4 navigation; P2-B uses this primitive for short physical movement and
 * collision/stuck verification.
 */
public record MoveInputAction(
        float forward,
        float strafe,
        boolean sprint,
        boolean sneak,
        boolean swim,
        int ticks,
        int stuckWindowTicks)
        implements ActionRequest {
    public static final int MAX_INPUT_TICKS = 200;
    public static final int MAX_STUCK_WINDOW_TICKS = 40;
    public static final double MAX_EXPECTED_BLOCKS_PER_TICK = 1.25D;

    private static final Set<ActionChannel> CHANNELS = Set.of(ActionChannel.MOVE);

    public MoveInputAction {
        new PlayerInputState(forward, strafe, false, sprint, sneak, swim);
        if (ticks < 1 || ticks > MAX_INPUT_TICKS) {
            throw new IllegalArgumentException(
                    "ticks must be between 1 and " + MAX_INPUT_TICKS);
        }
        if (stuckWindowTicks < 1
                || stuckWindowTicks > MAX_STUCK_WINDOW_TICKS
                || stuckWindowTicks > ticks) {
            throw new IllegalArgumentException(
                    "stuckWindowTicks must be between 1 and min(ticks, "
                            + MAX_STUCK_WINDOW_TICKS
                            + ")");
        }
        if (forward == 0.0F
                && strafe == 0.0F
                && !sneak
                && !swim) {
            throw new IllegalArgumentException(
                    "move input must contain a movement, sneak, or swim intent");
        }
    }

    @Override
    public ActionKind kind() {
        return ActionKind.MOVE_INPUT;
    }

    @Override
    public Set<ActionChannel> channels() {
        return CHANNELS;
    }

    public PlayerInputState inputState() {
        return new PlayerInputState(
                forward, strafe, false, sprint, sneak, swim);
    }

    /**
     * Conservative safety envelope for detecting impossible displacement, not an arrival claim.
     */
    public double expectedMaximumHorizontalDistance() {
        return ticks * MAX_EXPECTED_BLOCKS_PER_TICK;
    }
}
