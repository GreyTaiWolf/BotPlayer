package io.github.greytaiwolf.botplayer.navigation.path;

public final class MovementCostModel {
    public static final long CARDINAL = 1_000L;
    public static final long DIAGONAL = 1_414L;
    public static final long STEP_SURCHARGE = 250L;
    public static final long JUMP_SURCHARGE = 700L;
    public static final long SWIM_SURCHARGE = 1_800L;
    public static final long CLIMB_SURCHARGE = 1_400L;
    public static final long DOOR_SURCHARGE = 750L;
    public static final long DROP_SURCHARGE_PER_BLOCK = 350L;

    public long edgeCost(TraversalKind kind, int verticalBlocks) {
        if (verticalBlocks < 0 || verticalBlocks > 4) {
            throw new IllegalArgumentException(
                    "verticalBlocks must be between 0 and 4");
        }
        return switch (kind) {
            case START -> 0L;
            case WALK_CARDINAL -> CARDINAL;
            case WALK_DIAGONAL -> DIAGONAL;
            case STEP_UP -> saturatedAdd(CARDINAL, STEP_SURCHARGE);
            case JUMP_UP_ONE -> saturatedAdd(CARDINAL, JUMP_SURCHARGE);
            case DROP_SAFE -> saturatedAdd(
                    CARDINAL,
                    saturatedMultiply(DROP_SURCHARGE_PER_BLOCK, verticalBlocks));
            case SWIM_HORIZONTAL, SWIM_UP, SWIM_DOWN ->
                    saturatedAdd(CARDINAL, SWIM_SURCHARGE);
            case CLIMB_UP, CLIMB_DOWN ->
                    saturatedAdd(CARDINAL, CLIMB_SURCHARGE);
            case OPEN_DOOR -> saturatedAdd(CARDINAL, DOOR_SURCHARGE);
            case WAIT_FOR_OBSTACLE -> 5_000L;
        };
    }

    public static long saturatedAdd(long left, long right) {
        if (left < 0L || right < 0L) {
            throw new IllegalArgumentException("costs must not be negative");
        }
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    public static long saturatedMultiply(long value, int multiplier) {
        if (value < 0L || multiplier < 0) {
            throw new IllegalArgumentException(
                    "cost and multiplier must not be negative");
        }
        return multiplier != 0 && value > Long.MAX_VALUE / multiplier
                ? Long.MAX_VALUE
                : value * multiplier;
    }
}
