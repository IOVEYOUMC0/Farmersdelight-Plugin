package com.huidu.farmersdelight.api.sound;

import com.huidu.farmersdelight.util.SoundUtils;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;

/**
 * Public sound-resolution entry point for addons. A sound key is either a registered Sound
 * (resolved through the vanilla registry) or a raw sound name; resolution results are cached internally,
 * matching how FarmersDelight's own stations resolve their configured sounds. Methods that take
 * null/blank keys fall back to the supplied Sound fallback.
 */
@ApiStatus.NonExtendable
public final class FarmersDelightSounds {

    private FarmersDelightSounds() {
    }

    public static void play(org.bukkit.World world, Location location, String soundKey,
                            Sound fallback, float volume, float pitch) {
        SoundUtils.play(world, location, soundKey, fallback, volume, pitch);
    }

    public static void play(org.bukkit.entity.Player player, Location location, String soundKey,
                                    Sound fallback, float volume, float pitch) {
                SoundUtils.play(player, location, soundKey, fallback, SoundCategory.BLOCKS, volume, pitch);
            }

    public static void play(List<org.bukkit.entity.Player> viewers, Location location, String soundKey,
                            Sound fallback, float volume, float pitch) {
        SoundUtils.play(viewers, location, soundKey, fallback, volume, pitch);
    }

    public static void clearCache() {
        SoundUtils.clearCache();
    }
}