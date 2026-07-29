package io.github.greytaiwolf.botplayer.navigation;

public sealed interface NavigationGoal
        permits NavigationGoal.ExactPosition, NavigationGoal.NearPosition {
    String dimension();

    GridPoint center();

    boolean reached(GridPoint point);

    int horizontalTolerance();

    int verticalTolerance();

    record ExactPosition(
            String dimension,
            GridPoint center,
            int horizontalTolerance,
            int verticalTolerance)
            implements NavigationGoal {
        public ExactPosition {
            dimension = requireDimension(dimension);
            if (center == null) {
                throw new NullPointerException("center");
            }
            requireTolerance(horizontalTolerance, "horizontalTolerance");
            requireTolerance(verticalTolerance, "verticalTolerance");
        }

        @Override
        public boolean reached(GridPoint point) {
            if (point == null) {
                return false;
            }
            long horizontal = point.horizontalDistanceSquared(center);
            long tolerance = (long) horizontalTolerance * horizontalTolerance;
            return horizontal <= tolerance
                    && Math.abs((long) point.y() - center.y())
                            <= verticalTolerance;
        }
    }

    record NearPosition(
            String dimension, GridPoint center, int radius)
            implements NavigationGoal {
        public NearPosition {
            dimension = requireDimension(dimension);
            if (center == null) {
                throw new NullPointerException("center");
            }
            requireTolerance(radius, "radius");
        }

        @Override
        public boolean reached(GridPoint point) {
            return point != null
                    && point.horizontalDistanceSquared(center)
                            <= (long) radius * radius
                    && Math.abs((long) point.y() - center.y()) <= radius;
        }

        @Override
        public int horizontalTolerance() {
            return radius;
        }

        @Override
        public int verticalTolerance() {
            return radius;
        }
    }

    private static String requireDimension(String dimension) {
        if (dimension == null || dimension.isBlank() || dimension.length() > 128) {
            throw new IllegalArgumentException(
                    "dimension must contain 1-128 visible characters");
        }
        return dimension;
    }

    private static void requireTolerance(int value, String name) {
        if (value < 0 || value > 64) {
            throw new IllegalArgumentException(
                    name + " must be between 0 and 64");
        }
    }
}
