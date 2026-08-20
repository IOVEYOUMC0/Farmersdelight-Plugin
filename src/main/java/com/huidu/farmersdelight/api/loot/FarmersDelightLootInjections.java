package com.huidu.farmersdelight.api.loot;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.loot.LootInjectionRegistry;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;

// Lets addons append their own pools to vanilla chest loot tables through the same datapack pipeline
// as the bundled FD injections: /fd reload loot (or the next world load / server start) merges the
// registered pools with the current vanilla table and the deleted-CE-item filter applies to them too.
@ApiStatus.NonExtendable
public final class FarmersDelightLootInjections {

    private FarmersDelightLootInjections() {
    }

    // chestTable is the table path relative to data/minecraft/loot_table/chests/ without extension,
    // e.g. "simple_dungeon" or "village/village_butcher"; "chests/..." and "minecraft:chests/..."
    // prefixes and a trailing ".json" are accepted and stripped. poolsJson is either a JSON pool
    // array or a {"pools": [...]} object (the same shape the bundled append files use). Returns
    // false when FD is not enabled or the JSON is malformed.
    public static boolean registerChestLoot(String chestTable, String poolsJson) {
        LootInjectionRegistry registry = registry();
        if (registry == null || chestTable == null || chestTable.isBlank()) {
            return false;
        }
        return registry.register(chestTable, poolsJson);
    }

    public static boolean unregisterChestLoot(String chestTable) {
        LootInjectionRegistry registry = registry();
        return registry != null && chestTable != null && registry.unregister(chestTable);
    }

    public static List<String> injectedTables() {
        LootInjectionRegistry registry = registry();
        return registry == null ? List.of() : registry.tables();
    }

    private static LootInjectionRegistry registry() {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin == null || !FarmersDelightPlugin.isEnabled0()) {
            return null;
        }
        return plugin.getLootInjections();
    }
}
