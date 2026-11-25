package com.huidu.farmersdelight.util;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import com.huidu.farmersdelight.util.scheduler.SchedulerAdapter;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

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
            try {
                saver.accept(entry.getKey(), entry.getValue());
            } catch (RuntimeException | LinkageError failure) {
                // Shutdown is best-effort: one bad block must not skip the remaining entries.
                Logger.getLogger("FarmersDelight").log(Level.WARNING,
                        "Failed to save FarmersDelight block data at " + formatLocation(entry.getKey()), failure);
            }
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

    /**
     * Copies the players tracking the center block's chunk into out, without reading any player
     * coordinate. The caller must pass this list to runForViewerWithin so the distance gate runs on each
     * viewer's own scheduler instead of the emitting block's region thread.
     */
    public static List<Player> playersSeeingChunk(World world, Location center, List<Player> out) {
        out.clear();
        if (world == null || center == null) {
            return out;
        }
        int chunkX = center.getBlockX() >> 4;
        int chunkZ = center.getBlockZ() >> 4;
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            return out;
        }
        out.addAll(world.getChunkAt(chunkX, chunkZ).getPlayersSeeingChunk());
        return out;
    }

    /**
     * Runs one viewer effect on that viewer's own scheduler, after re-checking there that the viewer still
     * stands within the broadcast radius. The emitting block region therefore never reads another region's
     * player coordinates, while the per-viewer distance gate is kept.
     */
    public static void runForViewerWithin(SchedulerAdapter scheduler, World world, List<Player> viewers,
                                          Location rangeCenter, double distanceSquared, Consumer<Player> effect) {
        if (effect == null || rangeCenter == null || viewers.isEmpty()) {
            return;
        }
        double x = rangeCenter.getX();
        double y = rangeCenter.getY();
        double z = rangeCenter.getZ();
        for (Player viewer : viewers) {
            // The distance read is only safe on the viewer's own scheduler; a null scheduler means the
            // single-threaded call path.
            if (scheduler == null) {
                if (withinViewerRange(viewer, world, x, y, z, distanceSquared)) {
                    effect.accept(viewer);
                }
            } else {
                scheduler.runForEntity(viewer, () -> {
                    if (withinViewerRange(viewer, world, x, y, z, distanceSquared)) {
                        effect.accept(viewer);
                    }
                });
            }
        }
    }

    /** True when the viewer is in the broadcast world and within the squared radius of the point. */
    static boolean withinViewerRange(Player viewer, World world, double x, double y, double z,
                                     double distanceSquared) {
        if (viewer.getWorld() != world) {
            return false;
        }
        double dx = viewer.getX() - x;
        double dy = viewer.getY() - y;
        double dz = viewer.getZ() - z;
        return dx * dx + dy * dy + dz * dz <= distanceSquared;
    }
}
