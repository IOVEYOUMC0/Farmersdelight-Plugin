package com.huidu.farmersdelight.util;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;

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

    // Single source of truth for the world+chunk -> long index used by the multidimensional-entity
    // registries. The 64-bit key packs the 32-bit chunk column and the unsigned low 32-bit Z; OR is enough
    // because the two words never overlap, so consumers must not vary the formula or keys stop matching.
    public static long chunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) | (chunkZ & 0xffffffffL);
    }

    public static long chunkKey(Location location) {
        return chunkKey(location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    public static <T> void saveAllData(Map<Location, T> entries, BiConsumer<Location, T> saver) {
        for (Map.Entry<Location, T> entry : entries.entrySet()) {
            saver.accept(entry.getKey(), entry.getValue());
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

    public static Particle resolveParticle(String configured, Particle defaultParticle) {
        if (configured == null || configured.isBlank()) {
            return defaultParticle;
        }

        String normalized = configured.trim();
        int namespaceSeparator = normalized.indexOf(':');
        if (namespaceSeparator >= 0 && namespaceSeparator < normalized.length() - 1) {
            normalized = normalized.substring(namespaceSeparator + 1);
        }

        try {
            return Particle.valueOf(normalized.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return defaultParticle;
        }
    }

    public static double clampChance(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    public static List<Player> collectNearbyPlayers(World world, Location center, double distanceSquared, List<Player> out) {
        out.clear();
        if (world == null || center == null) {
            return out;
        }
        int chunkX = center.getBlockX() >> 4;
        int chunkZ = center.getBlockZ() >> 4;
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            return out;
        }
        // Compute squared distance by hand to avoid allocating a Location per candidate. Same world is
        // already guaranteed below, so this is equivalent to distanceSquared.
        double cx = center.getX();
        double cy = center.getY();
        double cz = center.getZ();
        for (Player p : world.getChunkAt(chunkX, chunkZ).getPlayersSeeingChunk()) {
            if (p.getWorld() != world) {
                continue;
            }
            double dx = p.getX() - cx;
            double dy = p.getY() - cy;
            double dz = p.getZ() - cz;
            if (dx * dx + dy * dy + dz * dz <= distanceSquared) {
                out.add(p);
            }
        }
        return out;
    }

    public static void spawnParticleFor(List<Player> viewers, Particle particle, double x, double y, double z,
                                        int count, double offsetX, double offsetY, double offsetZ, double extra) {
        for (Player viewer : viewers) {
            viewer.spawnParticle(particle, x, y, z, count, offsetX, offsetY, offsetZ, extra, null, false);
        }
    }
}
