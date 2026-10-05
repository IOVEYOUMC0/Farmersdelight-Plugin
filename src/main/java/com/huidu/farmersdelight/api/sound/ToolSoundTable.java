package com.huidu.farmersdelight.api.sound;

import com.huidu.farmersdelight.api.item.FarmersDelightItems;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * An ordered "which tool made this sound" table.
 *
 *
 * Keys are item ids (minecraft:shears, farmersdelight:flint_knife) or tags
 * ("#minecraft:axes", "#farmersdelight:tools/knives"). The first key that matches the
 * stack wins, so listing a specific item above the tag it belongs to overrides that tag. Values are
 * either a bare sound key or a map:
 *
 * tool-sounds:
 *   minecraft:shears: minecraft:entity.sheep.shear
 *   "#farmersdelight:tools/knives":
 *     sound: farmersdelight:block.cutting_board.knife_cut
 *     volume: 0.8
 *     pitch-min: 0.9
 *     pitch-max: 1.1
 *
 *
 * Addon stations can build one of these for their own tools instead of hardcoding a sound per tool.
 * Matching uses the shared item/tag resolution, so CraftEngine custom items and tags work as keys.
 */
public final class ToolSoundTable {

    /** One resolved row. pitch() rolls inside the configured range on each call. */
    public record Entry(String soundKey, float volume, float pitchMin, float pitchMax) {

        public float pitch() {
            return pitchMin >= pitchMax
                    ? pitchMin
                    : pitchMin + ThreadLocalRandom.current().nextFloat() * (pitchMax - pitchMin);
        }
    }

    private record Row(String key, boolean tag, Entry entry) {
    }

    private static final ToolSoundTable EMPTY = new ToolSoundTable(List.of());

    private final List<Row> rows;

    private ToolSoundTable(List<Row> rows) {
        this.rows = rows;
    }

    public static ToolSoundTable empty() {
        return EMPTY;
    }

    /**
     * Reads a table from a config section. Row order follows the file, because the first match wins.
     * A row whose value has no sound key is skipped rather than silently playing nothing.
     */
    public static ToolSoundTable from(ConfigurationSection section, float defaultVolume, float defaultPitch) {
        if (section == null) {
            return EMPTY;
        }
        List<Row> rows = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            Entry entry = readEntry(section, key, defaultVolume, defaultPitch);
            if (entry == null) {
                continue;
            }
            String trimmed = key.trim();
            boolean tag = trimmed.startsWith("#");
            rows.add(new Row(tag ? trimmed.substring(1) : trimmed, tag, entry));
        }
        return rows.isEmpty() ? EMPTY : new ToolSoundTable(List.copyOf(rows));
    }

    private static Entry readEntry(ConfigurationSection section, String key,
                                   float defaultVolume, float defaultPitch) {
        ConfigurationSection child = section.getConfigurationSection(key);
        if (child == null) {
            String soundKey = section.getString(key);
            return soundKey == null || soundKey.isBlank()
                    ? null
                    : new Entry(soundKey.trim(), defaultVolume, defaultPitch, defaultPitch);
        }
        String soundKey = child.getString("sound");
        if (soundKey == null || soundKey.isBlank()) {
            return null;
        }
        float volume = (float) child.getDouble("volume", defaultVolume);
        float pitch = (float) child.getDouble("pitch", defaultPitch);
        float pitchMin = (float) child.getDouble("pitch-min", pitch);
        float pitchMax = (float) child.getDouble("pitch-max", pitch);
        return new Entry(soundKey.trim(), volume, Math.min(pitchMin, pitchMax), Math.max(pitchMin, pitchMax));
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }

    /** The first row matching this stack, or null when the table is empty or nothing matches. */
    public Entry resolve(ItemStack tool) {
        if (rows.isEmpty() || tool == null || tool.getType().isAir()) {
            return null;
        }
        for (Row row : rows) {
            boolean matched = row.tag()
                    ? FarmersDelightItems.matchesTag(tool, row.key())
                    : FarmersDelightItems.matchesId(tool, row.key());
            if (matched) {
                return row.entry();
            }
        }
        return null;
    }
}
