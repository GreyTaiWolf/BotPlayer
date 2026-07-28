package io.github.greytaiwolf.botplayer.action;

import io.github.greytaiwolf.botplayer.action.input.PlayerInputState;
import java.util.Set;

/**
 * Holds ordinary jump input, optionally with short forward/strafe intent.
 *
 * <p>Success is verified from physical position, ground/fluid state, and collision evidence.
 * Constructing this request never changes position directly.
 */
public record JumpAction(
        float forward,
        float strafe,
        boolean sprint,
        int holdTicks)
        implements ActionRequest {
    public static final int MAX_HOLD_TICKS = 10;
    public static final double MIN_GROUND_RISE_BLOCKS = 0.05D;
    public static final double MIN_WATER_DISPLACEMENT_BLOCKS = 0.05D;

    private static final Set<ActionChannel> CHANNELS = Set.of(ActionChannel.MOVE);

    public JumpAction {
        new PlayerInputState(forward, strafe, true, sprint, false, false);
        if (holdTicks < 1 || holdTicks > MAX_HOLD_TICKS) {
            throw new IllegalArgumentException(
                    "holdTicks must be between 1 and " + MAX_HOLD_TICKS);
        }
    }

    @Override
    public ActionKind kind() {
        return ActionKind.JUMP;
    }

    @Override
    public Set<ActionChannel> channels() {
        return CHANNELS;
    }

    public PlayerInputState inputState() {
        return new PlayerInputState(
                forward, strafe, true, sprint, false, false);
    }

    /**
     * Classifies the only two supported physical jump starts.
     *
     * <p>Water takes precedence when the player also touches the bottom. An airborne player
     * outside water cannot start a new jump action, because an unrelated fall must not satisfy it.
     */
    public static StartMode classifyStart(boolean onGround, boolean inWater) {
        if (inWater) {
            return StartMode.WATER;
        }
        return onGround ? StartMode.GROUND : StartMode.REJECTED;
    }

    /**
     * Evaluates causal physical evidence for the classified start mode.
     */
    public static boolean hasPhysicalSuccess(
            StartMode startMode,
            boolean leftGround,
            double maximumRiseBlocks,
            double maximumDisplacementBlocks) {
        if (startMode == null) {
            throw new NullPointerException("startMode");
        }
        requireFiniteNonNegative(maximumRiseBlocks, "maximumRiseBlocks");
        requireFiniteNonNegative(
                maximumDisplacementBlocks, "maximumDisplacementBlocks");
        return switch (startMode) {
            case GROUND -> leftGround
                    && maximumRiseBlocks >= MIN_GROUND_RISE_BLOCKS;
            case WATER -> maximumDisplacementBlocks
                    >= MIN_WATER_DISPLACEMENT_BLOCKS;
            case REJECTED -> false;
        };
    }

    private static void requireFiniteNonNegative(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0D) {
            throw new IllegalArgumentException(
                    name + " must be finite and non-negative");
        }
    }

    public enum StartMode {
        GROUND,
        WATER,
        REJECTED
    }
}
