package io.github.greytaiwolf.botplayer.navigation;

import net.minecraft.core.BlockPos;

public record GridPoint(int x, int y, int z) {
    public static GridPoint from(BlockPos position) {
        if (position == null) {
            throw new NullPointerException("position");
        }
        return new GridPoint(position.getX(), position.getY(), position.getZ());
    }

    public BlockPos toBlockPos() {
        return new BlockPos(x, y, z);
    }

    public long horizontalDistanceSquared(GridPoint other) {
        long dx = (long) x - other.x;
        long dz = (long) z - other.z;
        return Math.addExact(Math.multiplyExact(dx, dx), Math.multiplyExact(dz, dz));
    }
}
