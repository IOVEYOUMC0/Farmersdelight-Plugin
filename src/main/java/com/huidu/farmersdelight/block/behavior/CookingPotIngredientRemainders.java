package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.util.ItemUtils;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Ingredient remainder overrides for the cooking pot.
 *
 * Some ingredients carry a container that vanilla does not report as a crafting remainder: a bucket of fish is
 * not craftable, so the item has no remainder, yet consuming it in a pot should still hand the bucket back.
 * The mod keeps a fixed table for these; this mirrors it and lets servers extend or clear entries from
 * config.yml under cooking-pot.ingredient-remainders (or the top-level ingredient-remainders alias), in the
 * same key/value shape as container-returns.
 *
 * Lookup order in the pot matches the mod: a real crafting remainder wins, and this table only fills the gap
 * for items that have none.
 */
public final class CookingPotIngredientRemainders {

    private static final Map<String, String> DEFAULTS = Map.ofEntries(
            Map.entry("minecraft:powder_snow_bucket", "minecraft:bucket"),
            Map.entry("minecraft:axolotl_bucket", "minecraft:bucket"),
            Map.entry("minecraft:cod_bucket", "minecraft:bucket"),
            Map.entry("minecraft:pufferfish_bucket", "minecraft:bucket"),
            Map.entry("minecraft:salmon_bucket", "minecraft:bucket"),
            Map.entry("minecraft:tropical_fish_bucket", "minecraft:bucket"),
            Map.entry("minecraft:suspicious_stew", "minecraft:bowl"),
            Map.entry("minecraft:mushroom_stew", "minecraft:bowl"),
            Map.entry("minecraft:rabbit_stew", "minecraft:bowl"),
            Map.entry("minecraft:beetroot_soup", "minecraft:bowl"),
            Map.entry("minecraft:potion", "minecraft:glass_bottle"),
            Map.entry("minecraft:splash_potion", "minecraft:glass_bottle"),
            Map.entry("minecraft:lingering_potion", "minecraft:glass_bottle"),
            Map.entry("minecraft:experience_bottle", "minecraft:glass_bottle")
    );

    /** Built table plus the FileConfiguration instance it was built from. JavaPlugin.reloadConfig replaces that
     *  instance, so an identity mismatch is a reliable, allocation-free "config changed" signal without this
     *  class having to be registered on the plugin's reload path. */
    private record Snapshot(FileConfiguration source, Map<String, ItemStack> table) {
    }

    // Written by whichever thread first observes a new configuration, read by region threads during a cook.
    private static volatile Snapshot snapshot;

    private CookingPotIngredientRemainders() {
    }

    /**
     * Returns the override remainder for the given item, or null when the item has no override. The returned
     * stack is a fresh copy the caller owns.
     */
    public static ItemStack getRemainder(ItemStack item, int amount) {
        String itemId = ItemUtils.getVanillaMaterialItemId(item);
        if (itemId == null) {
            return null;
        }
        ItemStack remainder = table().get(itemId.toLowerCase(Locale.ROOT));
        if (remainder == null) {
            return null;
        }
        ItemStack result = remainder.clone();
        result.setAmount(Math.max(1, amount));
        return result;
    }

    private static Map<String, ItemStack> table() {
        FileConfiguration current = currentConfig();
        Snapshot cached = snapshot;
        if (cached != null && cached.source() == current) {
            return cached.table();
        }
        Map<String, ItemStack> built = build();
        snapshot = new Snapshot(current, built);
        return built;
    }

    private static FileConfiguration currentConfig() {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin == null) {
            return null;
        }
        try {
            return plugin.getConfig();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Map<String, ItemStack> build() {
        Map<String, ItemStack> table = new HashMap<>();
        for (Map.Entry<String, String> entry : DEFAULTS.entrySet()) {
            put(table, entry.getKey(), entry.getValue());
        }

        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin == null) {
            return Map.copyOf(table);
        }
        ConfigurationSection section;
        try {
            section = plugin.getFirstConfigSection("cooking-pot.ingredient-remainders", "ingredient-remainders");
        } catch (Exception ignored) {
            section = null;
        }
        if (section == null) {
            return Map.copyOf(table);
        }
        for (String itemId : section.getKeys(false)) {
            String remainderId = section.getString(itemId);
            if (ItemUtils.isEmptyItemId(remainderId)) {
                table.remove(itemId.toLowerCase(Locale.ROOT));
                continue;
            }
            put(table, itemId, remainderId);
        }
        return Map.copyOf(table);
    }

    private static void put(Map<String, ItemStack> table, String itemId, String remainderId) {
        ItemStack remainder = ItemUtils.createItem(remainderId);
        if (remainder != null && !remainder.getType().isAir()) {
            table.put(itemId.toLowerCase(Locale.ROOT), remainder);
        }
    }
}
