package io.github.greytaiwolf.botplayer.navigation;

public record GridPoint(int x, int y, int z) {
    public long horizontalDistanceSquared(GridPoint other) {
        long dx = (long) x - other.x;
        long dz = (long) z - other.z;
        return Math.addExact(Math.multiplyExact(dx, dx), Math.multiplyExact(dz, dz));
    }
}
