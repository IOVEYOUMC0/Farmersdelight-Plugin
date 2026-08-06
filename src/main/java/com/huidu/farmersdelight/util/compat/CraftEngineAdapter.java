package com.huidu.farmersdelight.util.compat;

import net.momirealms.craftengine.bukkit.util.LocationUtils;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.proxy.minecraft.world.level.LevelProxy;
import org.bukkit.World;

public final class CraftEngineAdapter {

    private CraftEngineAdapter() {
    }

    public static World toWorld(Object levelObj) {
        if (levelObj instanceof World world) {
            return world;
        }
        try {
            return LevelProxy.INSTANCE.getWorld(levelObj);
        } catch (Exception ignored) {
            return null;
        }
    }

    public static BlockPos toBlockPos(Object posObj) {
        if (posObj instanceof BlockPos pos) {
            return pos;
        }
        try {
            return LocationUtils.fromBlockPos(posObj);
        } catch (Exception ignored) {
            return null;
        }
    }

}
