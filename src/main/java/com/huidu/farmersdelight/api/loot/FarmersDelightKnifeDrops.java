package com.huidu.farmersdelight.api.loot;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.loot.KnifeDropHandler;
import com.huidu.farmersdelight.loot.KnifeDropRule;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Registration of extra mob drops harvested with a knife — the rule set behind FarmersDelight's ham,
 * leather, feather and string drops. Server owners configure these in config.yml; this facade is for
 * plugins that need to add rules at runtime (an addon shipping its own butchering items, a quest
 * plugin adding a conditional drop).
 *
 * A rule fires when a player kills an adult entity of the matching type while holding a matching
 * tool, and a chance roll passes. The rolled item is contributed to the death event's drop list
 * rather than spawned directly, so other plugins' loot handling sees it like any vanilla drop.
 *
 * Rules registered here persist across /fd reload: the reload rebuilds the rule set from
 * config.yml and then re-applies everything registered through this facade on top, so a registration
 * outlives an admin's reload without the addon having to listen for
 * FarmersDelightReloadEvent. Unregister on your plugin's disable.
 */
@ApiStatus.NonExtendable
public final class FarmersDelightKnifeDrops {

    private FarmersDelightKnifeDrops() {
    }

    /**
     * Registers (or replaces) the knife drop rule for entityType. Replaces the built-in rule
     * for that type if one exists — there is one rule per entity type, not a list.
     *
     * entityType Bukkit EntityType name, case-insensitive ("pig", "COW")
     * normalItemId item id to drop, "ns:id"; "minecraft:air" or null disables the drop
     * burningItemId item id to drop instead when the entity dies on fire, or null to always
     *                          use normalItemId
     * chance base drop probability, 0..1
     * lootingMultiplier added to chance per Looting level on the killing tool; 0 to ignore Looting
     * toolItems item ids that count as the harvesting tool; empty or null falls back to
     *                          the globally configured knife item list
     * toolTags item tag ids ("#ns:tag" style ids without the hash) that count as the
     *                          harvesting tool; empty or null falls back to the global knife tag list
     * false when FarmersDelight is unavailable or entityType is null/blank
     */
    public static boolean register(String entityType, String normalItemId, String burningItemId,
                                   double chance, double lootingMultiplier,
                                   List<String> toolItems, List<String> toolTags) {
        KnifeDropHandler handler = handler();
        if (handler == null || entityType == null || entityType.isBlank()) {
            return false;
        }
        String type = entityType.trim().toLowerCase(Locale.ROOT);
        handler.registerExternalDropRule(type, new KnifeDropRule(
                type, normalItemId, burningItemId, chance, lootingMultiplier,
                toolItems == null ? List.of() : List.copyOf(toolItems),
                toolTags == null ? List.of() : List.copyOf(toolTags)));
        return true;
    }

    /** #register without per-rule tool matchers, so the rule uses the globally configured
     *  knife items and tags. */
    public static boolean register(String entityType, String normalItemId, String burningItemId,
                                   double chance, double lootingMultiplier) {
        return register(entityType, normalItemId, burningItemId, chance, lootingMultiplier, null, null);
    }

    /**
     * Removes a rule previously added by register. Returns true when one was registered by
     * this facade for that entity type. Does not touch rules that came from config.yml or the
     * built-in defaults — those come back on the next reload anyway.
     */
    public static boolean unregister(String entityType) {
        KnifeDropHandler handler = handler();
        return handler != null && entityType != null
                && handler.unregisterExternalDropRule(entityType.trim().toLowerCase(Locale.ROOT));
    }

    /** Entity type names (lowercase) that currently have a knife drop rule, from any source. */
    public static Set<String> entityTypesWithRules() {
        KnifeDropHandler handler = handler();
        return handler == null ? Set.of() : Set.copyOf(handler.getDropRules().keySet());
    }

    /** True when a knife drop rule exists for entityType, from any source. */
    public static boolean hasRule(String entityType) {
        KnifeDropHandler handler = handler();
        return handler != null && entityType != null
                && handler.getDropRules().containsKey(entityType.trim().toLowerCase(Locale.ROOT));
    }

    private static KnifeDropHandler handler() {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin == null || !FarmersDelightPlugin.isEnabled0()) {
            return null;
        }
        return plugin.getKnifeDrops();
    }
}
