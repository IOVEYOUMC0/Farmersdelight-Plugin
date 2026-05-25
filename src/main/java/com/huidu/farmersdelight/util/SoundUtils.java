package com.huidu.farmersdelight.util;

import org.bukkit.*;

public final class SoundUtils {

    private SoundUtils() {
    }

    public static void play(World world, Location location, String soundKey, Sound fallback, float volume, float pitch) {
        if (world == null || location == null) {
            return;
        }
        if (soundKey == null || soundKey.isBlank()) {
            world.playSound(location, fallback, volume, pitch);
            return;
        }

        String normalized = soundKey.trim().toLowerCase();
        NamespacedKey key = normalized.contains(":")
                ? NamespacedKey.fromString(normalized)
                : NamespacedKey.minecraft(normalized);
        if (key != null) {
            Sound registered = Registry.SOUNDS.get(key);
            if (registered != null) {
                world.playSound(location, registered, volume, pitch);
                return;
            }
        }

        world.playSound(location, normalized, SoundCategory.BLOCKS, volume, pitch);
    }
}

