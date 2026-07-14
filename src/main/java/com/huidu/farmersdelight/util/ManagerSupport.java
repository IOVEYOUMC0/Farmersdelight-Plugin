package com.huidu.farmersdelight.util;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;
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

    /**
     * Collects the chunk-tracked players within distanceSquared of center into
     * out (cleared first) and returns it. Candidates come from Paper's chunk-tracked player
     * set (maintained O(1) off the chunk holder) rather than a full-world scan; a non-empty result
     * means at least one player is close. One walk of
     * the chunk-tracked set (typically &lt;10) serves both the gate and the subsequent targeted sends,
     * so a hot broadcast can emit via player.spawnParticle/playSound to this list instead of
     * world.spawnParticle/playSound, which re-walks the whole world player list per call (R-PERF-006).
     * Effect-only path: obeys CORRECTNESS-CAVEAT (never gate correctness on this).
     */
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
        for (Player p : world.getChunkAt(chunkX, chunkZ).getPlayersSeeingChunk()) {
            if (p.getWorld() == world && p.getLocation().distanceSquared(center) <= distanceSquared) {
                out.add(p);
            }
        }
        return out;
    }

    /** Emits a particle only to the given viewers (already distance-filtered), one packet per viewer —
     *  no world.spawnParticle full-world recipient walk. Passes force = false explicitly so the
     *  packet matches world.spawnParticle's default on this server (verified force=false → 32-block
     *  range in CraftWorld/NMS): particles respect a viewer's reduced-particle client setting exactly as
     *  before. Data is null — these hot-path particles (SMOKE/FLAME/configured smoke) carry none. */
    public static void spawnParticleFor(List<Player> viewers, Particle particle, double x, double y, double z,
                                        int count, double offsetX, double offsetY, double offsetZ, double extra) {
        for (int i = 0; i < viewers.size(); i++) {
            viewers.get(i).spawnParticle(particle, x, y, z, count, offsetX, offsetY, offsetZ, extra, null, false);
        }
    }
}
