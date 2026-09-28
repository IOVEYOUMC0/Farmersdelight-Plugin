package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.util.ItemUtils;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

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

    private record Snapshot(FileConfiguration source, Map<String, ItemStack> table) {
    }

    // Written by whichever thread first observes a new configuration, read by region threads during a cook.
    private static volatile Snapshot snapshot;

    private CookingPotIngredientRemainders() {
    }

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
        // NO SHIPPED CONFIG KEY backs this lookup, and that is deliberate rather than an oversight: the
        // remainders moved into each CraftEngine item's `craft-remainder` setting (see items.yml), so a
        // section in config.yml would be a second source of truth for the same thing. The built-in DEFAULTS
        // table below is therefore the behaviour, and this read only honours a hand-added section.
        // config-path-check: no shipped key, on purpose
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
