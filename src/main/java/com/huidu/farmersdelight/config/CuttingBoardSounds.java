package com.huidu.farmersdelight.config;

import com.huidu.farmersdelight.api.sound.ToolSoundTable;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

/**
 * Cutting-board cut sounds chosen by the tool in hand, for recipes that do not name a sound of their
 * own. Rebuilt on reload; the resolved table is immutable so the tick threads read it without locking.
 */
public final class CuttingBoardSounds {

    private static final String DEFAULT_FALLBACK_SOUND = "minecraft:block.wood.break";
    private static final float DEFAULT_VOLUME = 1.0f;
    private static final float DEFAULT_PITCH = 0.8f;

    private final ToolSoundTable table;
    private final ToolSoundTable.Entry fallback;

    private CuttingBoardSounds(ToolSoundTable table, ToolSoundTable.Entry fallback) {
        this.table = table;
        this.fallback = fallback;
    }

    public static CuttingBoardSounds defaults() {
        return new CuttingBoardSounds(ToolSoundTable.empty(), defaultFallback());
    }

    public static CuttingBoardSounds from(ConfigurationSection soundsSection) {
        if (soundsSection == null) {
            return defaults();
        }
        ToolSoundTable table = ToolSoundTable.from(soundsSection.getConfigurationSection("tool-sounds"),
                DEFAULT_VOLUME, DEFAULT_PITCH);
        ConfigurationSection fallbackSection = soundsSection.getConfigurationSection("fallback");
        ToolSoundTable.Entry fallback = defaultFallback();
        if (fallbackSection != null) {
            String sound = fallbackSection.getString("sound", DEFAULT_FALLBACK_SOUND);
            float volume = (float) fallbackSection.getDouble("volume", DEFAULT_VOLUME);
            float pitch = (float) fallbackSection.getDouble("pitch", DEFAULT_PITCH);
            float pitchMin = (float) fallbackSection.getDouble("pitch-min", pitch);
            float pitchMax = (float) fallbackSection.getDouble("pitch-max", pitch);
            fallback = new ToolSoundTable.Entry(sound, volume,
                    Math.min(pitchMin, pitchMax), Math.max(pitchMin, pitchMax));
        }
        return new CuttingBoardSounds(table, fallback);
    }

    private static ToolSoundTable.Entry defaultFallback() {
        return new ToolSoundTable.Entry(DEFAULT_FALLBACK_SOUND, DEFAULT_VOLUME, DEFAULT_PITCH, DEFAULT_PITCH);
    }

    /** Never null: an unmatched tool falls back to the configured default. */
    public ToolSoundTable.Entry resolve(ItemStack tool) {
        ToolSoundTable.Entry entry = table.resolve(tool);
        return entry != null ? entry : fallback;
    }
}
