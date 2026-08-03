package com.huidu.farmersdelight.util;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class SoundUtils {

    /**
     * Memo of sound-key resolution results, keyed on the raw config string. A hit is either a
     * registry-backed Sound or the normalized string for the string-play fallback (which
     * doubles as the negative cache for keys absent from the registry — e.g. CraftEngine
     * resourcepack sounds like the stove crackle, which the Bukkit registry can never contain).
     * The registry is frozen at server bootstrap, so entries cannot go stale; the reload-time
     * #clearCache() hook exists for pattern uniformity with the other config caches.
     * The size cap guards against a caller passing unbounded dynamic strings.
     */
    private static final Map<String, Object> RESOLUTION_CACHE = new ConcurrentHashMap<>();
    private static final int RESOLUTION_CACHE_MAX = 512;

    private SoundUtils() {
    }

    public static void play(World world, Location location, String soundKey, Sound fallback, float volume, float pitch) {
        play(world, location, soundKey, fallback, SoundCategory.BLOCKS, volume, pitch);
    }

    public static void play(World world, Location location, String soundKey, Sound fallback, SoundCategory category, float volume, float pitch) {
        if (world == null || location == null) {
            return;
        }
        if (soundKey == null || soundKey.isBlank()) {
            world.playSound(location, fallback, category, volume, pitch);
            return;
        }

        Object resolved = RESOLUTION_CACHE.get(soundKey);
        if (resolved == null) {
            resolved = resolve(soundKey);
            if (RESOLUTION_CACHE.size() < RESOLUTION_CACHE_MAX) {
                RESOLUTION_CACHE.put(soundKey, resolved);
            }
        }
        if (resolved instanceof Sound sound) {
            world.playSound(location, sound, category, volume, pitch);
        } else {
            world.playSound(location, (String) resolved, category, volume, pitch);
        }
    }

    /** Plays a sound only to the given viewers (already distance-filtered), one packet per viewer — no
     *  world.playSound full-world recipient walk. Shares the resolution cache with #play. */
    public static void play(List<Player> viewers, Location location, String soundKey, Sound fallback, float volume, float pitch) {
        if (viewers.isEmpty() || location == null) {
            return;
        }
        if (soundKey == null || soundKey.isBlank()) {
            for (Player viewer : viewers) {
                viewer.playSound(location, fallback, volume, pitch);
            }
            return;
        }
        Object resolved = RESOLUTION_CACHE.get(soundKey);
        if (resolved == null) {
            resolved = resolve(soundKey);
            if (RESOLUTION_CACHE.size() < RESOLUTION_CACHE_MAX) {
                RESOLUTION_CACHE.put(soundKey, resolved);
            }
        }
        if (resolved instanceof Sound sound) {
            for (Player viewer : viewers) {
                viewer.playSound(location, sound, volume, pitch);
            }
        } else {
            String soundName = (String) resolved;
            for (Player viewer : viewers) {
                viewer.playSound(location, soundName, SoundCategory.BLOCKS, volume, pitch);
            }
        }
    }

    /** Plays a sound to a single player at the given location, resolving the config sound key against the
     *  Bukkit registry with a resourcepack-sound string fallback. Shares the resolution cache with #play. */
    public static void play(Player player, Location location, String soundKey, Sound fallback, SoundCategory category, float volume, float pitch) {
        if (player == null || location == null) {
            return;
        }
        if (soundKey == null || soundKey.isBlank()) {
            player.playSound(location, fallback, category, volume, pitch);
            return;
        }
        Object resolved = RESOLUTION_CACHE.get(soundKey);
        if (resolved == null) {
            resolved = resolve(soundKey);
            if (RESOLUTION_CACHE.size() < RESOLUTION_CACHE_MAX) {
                RESOLUTION_CACHE.put(soundKey, resolved);
            }
        }
        if (resolved instanceof Sound sound) {
            player.playSound(location, sound, category, volume, pitch);
        } else {
            player.playSound(location, (String) resolved, category, volume, pitch);
        }
    }

    private static Object resolve(String soundKey) {
        String normalized = soundKey.trim().toLowerCase(java.util.Locale.ROOT);
        NamespacedKey key = normalized.contains(":")
                ? NamespacedKey.fromString(normalized)
                : NamespacedKey.minecraft(normalized);
        if (key != null) {
            Sound registered = Registry.SOUNDS.get(key);
            if (registered != null) {
                return registered;
            }
        }
        return normalized;
    }

    public static void clearCache() {
        RESOLUTION_CACHE.clear();
    }
}
