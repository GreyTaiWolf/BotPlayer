package io.github.greytaiwolf.botplayer.perception.event;

/**
 * 不持有 Minecraft 活动对象的不可变世界坐标。
 */
public record SpatialPoint(double x, double y, double z) {
    public SpatialPoint {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("spatial coordinates must be finite");
        }
    }

    public double distanceSquared(SpatialPoint other) {
        double deltaX = x - other.x;
        double deltaY = y - other.y;
        double deltaZ = z - other.z;
        return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
    }
}
