package com.huidu.farmersdelight.util;

import net.momirealms.craftengine.core.world.BlockPos;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.Optional;

public record BlockPosKey(int x, int y, int z) {

    public BlockPosKey(BlockPos pos) {
        this(pos.x(), pos.y(), pos.z());
    }

    public BlockPosKey(Location location) {
        this(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public static Optional<BlockPosKey> fromString(String str) {
        if (str == null || str.isEmpty()) return Optional.empty();
        try {
            String[] parts = str.split(",");
            if (parts.length == 3) {
                return Optional.of(new BlockPosKey(
                        Integer.parseInt(parts[0].trim()),
                        Integer.parseInt(parts[1].trim()),
                        Integer.parseInt(parts[2].trim())
                ));
            }
        } catch (NumberFormatException ignored) {
        }
        return Optional.empty();
    }

    public BlockPos toBlockPos() {
        return new BlockPos(x, y, z);
    }

    public Location toLocation(World world) {
        return new Location(world, x, y, z);
    }

    @Override
    public String toString() {
        return x + "," + y + "," + z;
    }
}