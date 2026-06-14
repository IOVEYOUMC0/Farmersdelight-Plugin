package com.huidu.farmersdelight.util;

import net.momirealms.craftengine.bukkit.util.LocationUtils;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.proxy.minecraft.world.level.LevelProxy;
import org.bukkit.World;

/**
 * 用于 CraftEngine 回调的小型桥接辅助方法。
 */
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

