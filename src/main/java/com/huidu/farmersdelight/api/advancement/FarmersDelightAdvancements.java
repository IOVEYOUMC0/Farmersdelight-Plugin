package com.huidu.farmersdelight.api.advancement;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.advancement.AddonAdvancementRegistry;
import com.huidu.farmersdelight.advancement.AddonAdvancementTab;
import com.huidu.farmersdelight.advancement.AdvancementDef;
import com.huidu.farmersdelight.advancement.AdvancementManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;

/**
 * Stable, addon-facing access to FarmersDelight's UltimateAdvancementAPI integration. Two uses:
 * (1) grant/revoke/check FarmersDelight's own built-in advancements by id (award/awardCriteria/revoke/has/
 * showFarmersDelightTab); (2) register a new addon advancement tab from plain data via #tree(String),
 * then grant/check it with the tabId-prefixed overloads — FarmersDelight builds and rebuilds the UAA
 * tab (including across /fd reload), and no UAA types cross this boundary.
 *
 * Everything requires the UltimateAdvancementAPI plugin installed and FarmersDelight's advancement system
 * enabled (guard with #isAvailable()); all methods are null/absence-safe no-ops otherwise.
 */
@ApiStatus.NonExtendable
public final class FarmersDelightAdvancements {

    private FarmersDelightAdvancements() {
    }

    /** True when UltimateAdvancementAPI is installed and FarmersDelight's advancement system is enabled. */
    public static boolean isAvailable() {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        return plugin != null && plugin.isAdvancementsEnabled()
                && Bukkit.getPluginManager().isPluginEnabled("UltimateAdvancementAPI");
    }

    // ------------------------------------------------------------------ FarmersDelight's own tab

    /** Grants a FarmersDelight advancement (by its id, e.g. "master_chef") to player. */
    public static void award(Player player, String advancementId) {
        AdvancementManager manager = fdManager();
        if (manager != null) {
            manager.award(player, advancementId);
        }
    }

    /** Grants one criterion of a FarmersDelight multi-task advancement to player. */
    public static void awardCriteria(Player player, String advancementId, String criterion) {
        AdvancementManager manager = fdManager();
        if (manager != null) {
            manager.awardCriteria(player, advancementId, criterion);
        }
    }

    /** Revokes a FarmersDelight advancement from player. */
    public static void revoke(Player player, String advancementId) {
        AdvancementManager manager = fdManager();
        if (manager != null) {
            manager.revoke(player, advancementId);
        }
    }

    /** True if player has been granted the FarmersDelight advancement advancementId. */
    public static boolean has(Player player, String advancementId) {
        AdvancementManager manager = fdManager();
        return manager != null && manager.hasAdvancement(player, advancementId);
    }

    /** Opens/reveals FarmersDelight's advancement tab to player. */
    public static void showFarmersDelightTab(Player player) {
        AdvancementManager manager = fdManager();
        if (manager != null) {
            manager.showTo(player);
        }
    }

    // ------------------------------------------------------------------ addon tabs

    /**
     * Begins building an addon advancement tab named tabId. Add a root and children, then call
     * AdvancementTree#register(). Re-registering the same tabId replaces the previous tree.
     */
    public static AdvancementTree tree(String tabId) {
        return new AdvancementTree(tabId);
    }

    /** Unregisters an addon tab registered via #tree(String) (e.g. on addon disable). */
    public static void unregister(String tabId) {
        AddonAdvancementRegistry registry = registry();
        if (registry != null) {
            registry.unregister(tabId);
        }
    }

    /** Grants an advancement on the addon tab tabId to player. */
    public static void award(String tabId, Player player, String advancementId) {
        AddonAdvancementTab tab = addonTab(tabId);
        if (tab != null) {
            tab.award(player, advancementId);
        }
    }

    /** Grants one criterion of a multi-task advancement on the addon tab tabId to player. */
    public static void awardCriteria(String tabId, Player player, String advancementId, String criterion) {
        AddonAdvancementTab tab = addonTab(tabId);
        if (tab != null) {
            tab.awardCriteria(player, advancementId, criterion);
        }
    }

    /** Revokes an advancement on the addon tab tabId from player. */
    public static void revoke(String tabId, Player player, String advancementId) {
        AddonAdvancementTab tab = addonTab(tabId);
        if (tab != null) {
            tab.revoke(player, advancementId);
        }
    }

    /** True if player has the advancement advancementId on the addon tab tabId. */
    public static boolean has(String tabId, Player player, String advancementId) {
        AddonAdvancementTab tab = addonTab(tabId);
        return tab != null && tab.hasAdvancement(player, advancementId);
    }

    /** Opens/reveals the addon tab tabId to player. */
    public static void showTab(String tabId, Player player) {
        AddonAdvancementTab tab = addonTab(tabId);
        if (tab != null) {
            tab.showTo(player);
        }
    }

    // ------------------------------------------------------------------ internals

    /** Called by AdvancementTree#register(). */
    static boolean registerTree(String tabId, List<AdvancementDef> defs) {
        AddonAdvancementRegistry registry = registry();
        return registry != null && registry.register(tabId, defs);
    }

    // Return null when the advancement system is unavailable, so callers never reach the UAA-referencing
    // internals (AdvancementManager / AddonAdvancementTab) while UAA is not loaded.
    private static AdvancementManager fdManager() {
        if (!isAvailable()) {
            return null;
        }
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        return plugin == null ? null : plugin.getAdvancementManager();
    }

    private static AddonAdvancementRegistry registry() {
        if (!isAvailable()) {
            return null;
        }
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        return plugin == null ? null : plugin.getAddonAdvancementRegistry();
    }

    private static AddonAdvancementTab addonTab(String tabId) {
        AddonAdvancementRegistry registry = registry();
        return registry == null ? null : registry.tab(tabId);
    }
}
