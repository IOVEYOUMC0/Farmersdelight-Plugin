package com.huidu.farmersdelight.api.advancement;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.advancement.AddonAdvancementRegistry;
import com.huidu.farmersdelight.advancement.AddonAdvancementTab;
import com.huidu.farmersdelight.advancement.AdvancementDef;
import com.huidu.farmersdelight.advancement.AdvancementManager;
import com.huidu.farmersdelight.api.FarmersDelightApi;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;

@ApiStatus.NonExtendable
public final class FarmersDelightAdvancements {

    private FarmersDelightAdvancements() {
    }

    // Returns true only when the advancement system is usable: FarmersDelight loaded, its advancement
    // feature enabled, and UltimateAdvancementAPI present & enabled.
    public static boolean isAvailable() {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        return plugin != null && plugin.isAdvancementsEnabled()
                && Bukkit.getPluginManager().isPluginEnabled("UltimateAdvancementAPI");
    }

    // ------------------------------------------------------------------ FarmersDelight's own tab

    public static void award(Player player, String advancementId) {
        AdvancementManager manager = fdManager();
        if (manager != null) {
            manager.award(player, advancementId);
        }
    }

    public static void awardCriteria(Player player, String advancementId, String criterion) {
        AdvancementManager manager = fdManager();
        if (manager != null) {
            manager.awardCriteria(player, advancementId, criterion);
        }
    }

    public static void revoke(Player player, String advancementId) {
        AdvancementManager manager = fdManager();
        if (manager != null) {
            manager.revoke(player, advancementId);
        }
    }

    public static boolean has(Player player, String advancementId) {
        AdvancementManager manager = fdManager();
        return manager != null && manager.hasAdvancement(player, advancementId);
    }

    public static void showFarmersDelightTab(Player player) {
        AdvancementManager manager = fdManager();
        if (manager != null) {
            manager.showTo(player);
        }
    }

    // ------------------------------------------------------------------ addon tabs

    public static AdvancementTree tree(String tabId) {
        return new AdvancementTree(tabId);
    }

    public static void unregister(String tabId) {
        AddonAdvancementRegistry registry = registry();
        if (registry != null) {
            registry.unregister(tabId);
        }
    }

    public static void award(String tabId, Player player, String advancementId) {
        AddonAdvancementTab tab = addonTab(tabId);
        if (tab != null) {
            tab.award(player, advancementId);
        }
    }

    public static void awardCriteria(String tabId, Player player, String advancementId, String criterion) {
        AddonAdvancementTab tab = addonTab(tabId);
        if (tab != null) {
            tab.awardCriteria(player, advancementId, criterion);
        }
    }

    public static void revoke(String tabId, Player player, String advancementId) {
        AddonAdvancementTab tab = addonTab(tabId);
        if (tab != null) {
            tab.revoke(player, advancementId);
        }
    }

    public static boolean has(String tabId, Player player, String advancementId) {
        AddonAdvancementTab tab = addonTab(tabId);
        return tab != null && tab.hasAdvancement(player, advancementId);
    }

    public static void showTab(String tabId, Player player) {
        AddonAdvancementTab tab = addonTab(tabId);
        if (tab != null) {
            if (FarmersDelightApi.get().isFolia() && player != null) {
                FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
                player.getScheduler().run(plugin, task -> tab.showTo(player), null);
            } else {
                tab.showTo(player);
            }
        }
    }

    // ------------------------------------------------------------------ internals

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
