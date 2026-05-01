package com.huidu.farmersdelight.util;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.storage.BlockStorageManager;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class ManagerSupport {

    private ManagerSupport() {
    }

    public static Location normalize(Location location) {
        if (location == null || location.getWorld() == null) {
            return location;
        }
        return new Location(location.getWorld(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public static Location toLocation(World world, BlockPosKey posKey) {
        if (world == null || posKey == null) {
            return null;
        }
        return normalize(posKey.toLocation(world));
    }

    public static boolean isInWorld(Location location, World world) {
        return location != null && location.getWorld() != null && location.getWorld().equals(world);
    }

    public static boolean isInWorld(Location location, UUID worldId) {
        return location != null && location.getWorld() != null && location.getWorld().getUID().equals(worldId);
    }

    public static void removeStoredData(FarmersDelightPlugin plugin, Location location) {
        if (plugin == null || location == null || location.getWorld() == null) {
            return;
        }

        BlockStorageManager storage = plugin.getBlockStorageManager();
        if (storage != null) {
            storage.removeBlockData(normalize(location));
        }
    }

    public static <T> void saveAllData(Map<Location, T> entries, BiConsumer<Location, T> saver) {
        for (Map.Entry<Location, T> entry : entries.entrySet()) {
            saver.accept(entry.getKey(), entry.getValue());
        }
    }

    public static <T> void saveWorldData(Map<Location, T> entries, World world, BiConsumer<Location, T> saver) {
        if (world == null) {
            return;
        }

        for (Map.Entry<Location, T> entry : entries.entrySet()) {
            Location location = entry.getKey();
            if (isInWorld(location, world)) {
                saver.accept(location, entry.getValue());
            }
        }
    }

    public static <T> void cleanupWorld(Map<Location, T> entries, UUID worldId, Consumer<T> cleaner) {
        Iterator<Map.Entry<Location, T>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Location, T> entry = it.next();
            if (isInWorld(entry.getKey(), worldId)) {
                cleaner.accept(entry.getValue());
                it.remove();
            }
        }
    }

    public static <T> void saveAndUnloadChunk(Map<Location, T> entries, World world, int minX, int maxX, int minZ, int maxZ,
                                              BiConsumer<Location, T> saver, Consumer<Location> remover) {
        List<Location> toRemove = new ArrayList<>();
        for (Map.Entry<Location, T> entry : entries.entrySet()) {
            Location location = entry.getKey();
            if (location.getWorld() == null || !location.getWorld().equals(world)) {
                continue;
            }
            if (location.getBlockX() >= minX && location.getBlockX() <= maxX
                    && location.getBlockZ() >= minZ && location.getBlockZ() <= maxZ) {
                saver.accept(location, entry.getValue());
                toRemove.add(location);
            }
        }
        for (Location location : toRemove) {
            remover.accept(location);
        }
    }

    public static String formatItem(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return "air";
        }
        return item.getType() + "x" + item.getAmount();
    }

    public static String formatLocation(Location location) {
        if (location == null || location.getWorld() == null) {
            return "unknown";
        }
        return location.getWorld().getName() + "@" + location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ();
    }
}
