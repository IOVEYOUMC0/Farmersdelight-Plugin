package com.huidu.farmersdelight.util;

import net.momirealms.craftengine.bukkit.util.LocationUtils;
import net.momirealms.craftengine.core.world.BlockPos;
import org.bukkit.World;

/**
 * Small bridge helpers for CraftEngine callbacks.
 * CE sometimes passes internal level/position objects instead of Bukkit types.
 */
public final class CraftEngineAdapter {

    private static final String FAST_NMS_CLASS_NAME = "net.momirealms.craftengine.bukkit.nms.FastNMS";
    private static final Object FAST_NMS_INSTANCE = resolveFastNmsInstance();
    private static final java.lang.reflect.Method FAST_NMS_GET_CRAFT_WORLD = resolveFastNmsGetCraftWorldMethod();

    private CraftEngineAdapter() {
    }

    public static World toWorld(Object levelObj) {
        if (levelObj instanceof World world) {
            return world;
        }
        try {
            if (FAST_NMS_INSTANCE != null && FAST_NMS_GET_CRAFT_WORLD != null) {
                Object craftWorld = FAST_NMS_GET_CRAFT_WORLD.invoke(FAST_NMS_INSTANCE, levelObj);
                if (craftWorld instanceof World world) {
                    return world;
                }
            }
        } catch (Exception ignored) {
        }
        try {
            Object craftWorld = levelObj.getClass().getMethod("getWorld").invoke(levelObj);
            if (craftWorld instanceof World world) {
                return world;
            }
            return null;
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

    private static Object resolveFastNmsInstance() {
        try {
            Class<?> clazz = Class.forName(FAST_NMS_CLASS_NAME);
            return clazz.getField("INSTANCE").get(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static java.lang.reflect.Method resolveFastNmsGetCraftWorldMethod() {
        try {
            Class<?> clazz = Class.forName(FAST_NMS_CLASS_NAME);
            return clazz.getMethod("method$Level$getCraftWorld", Object.class);
        } catch (Exception ignored) {
            return null;
        }
    }
}
