package com.huidu.farmersdelight.util;

import net.momirealms.craftengine.core.world.BlockPos;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.Objects;

public final class BlockPosKey {

    private final int x;
    private final int y;
    private final int z;
    private final int hashCode;

    public BlockPosKey(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.hashCode = Objects.hash(x, y, z);
    }

    public BlockPosKey(BlockPos pos) {
        this(pos.x(), pos.y(), pos.z());
    }

    public BlockPosKey(Location location) {
        this(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public static BlockPosKey fromString(String str) {
        if (str == null || str.isEmpty()) return null;
        try {
            String[] parts = str.split(",");
            if (parts.length == 3) {
                return new BlockPosKey(
                        Integer.parseInt(parts[0].trim()),
                        Integer.parseInt(parts[1].trim()),
                        Integer.parseInt(parts[2].trim())
                );
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return null;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int z() {
        return z;
    }

    public BlockPos toBlockPos() {
        return new BlockPos(x, y, z);
    }

    public Location toLocation(World world) {
        return new Location(world, x, y, z);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BlockPosKey that = (BlockPosKey) o;
        return x == that.x && y == that.y && z == that.z;
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public String toString() {
        return x + "," + y + "," + z;
    }
}
