package com.huidu.farmersdelight.gui;

import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The static state the recipe-view GUI keeps between opens: the parsed config, the built list display items
 * and the two warn-once sets. It lives here rather than on the GUI class so that "what is cached" and
 * "when it is dropped" are one place, which GuiCacheInvalidator and the recipe managers drive.
 */
final class RecipeViewCache {

    private static volatile RecipeViewGuiConfig config;
    private static final Map<String, ItemStack> recipeListDisplayCache = new ConcurrentHashMap<>();
    private static final Set<String> warnedMissingCustomCookingPotDetailConfigs = ConcurrentHashMap.newKeySet();
    private static final Set<String> warnedCookingPotDetailCapacityConfigs = ConcurrentHashMap.newKeySet();

    private RecipeViewCache() {
    }

    /** Returns the parsed config, building it through the loader on first use. */
    static RecipeViewGuiConfig config(Supplier<RecipeViewGuiConfig> loader) {
        RecipeViewGuiConfig current = config;
        if (current != null) {
            return current;
        }
        synchronized (RecipeViewCache.class) {
            if (config == null) {
                config = loader.get();
            }
            return config;
        }
    }

    /** Built list display items, keyed by recipe type, group, preview count, recipe id and locale. */
    static Map<String, ItemStack> displayCache() {
        return recipeListDisplayCache;
    }

    /** True the first time this custom id is reported missing its own detail config. */
    static boolean warnMissingCustomDetailOnce(String customId) {
        return warnedMissingCustomCookingPotDetailConfigs.add(customId);
    }

    /** True the first time this key is reported as too small for the recipes it must show. */
    static boolean warnCapacityOnce(String warningKey) {
        return warnedCookingPotDetailCapacityConfigs.add(warningKey);
    }

    /** Drops the parsed config and the warn-once sets; the display items are dropped separately. */
    static void clearConfig() {
        config = null;
        warnedMissingCustomCookingPotDetailConfigs.clear();
        warnedCookingPotDetailCapacityConfigs.clear();
    }

    /** Drops the built display items, which embed resolved names and lore from the language files. */
    static void clearDisplay() {
        recipeListDisplayCache.clear();
    }
}
