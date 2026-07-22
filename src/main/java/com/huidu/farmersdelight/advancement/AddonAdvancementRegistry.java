package com.huidu.farmersdelight.advancement;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds addon advancement-tab definitions and manages their UltimateAdvancementAPI lifecycle alongside
 * FarmersDelight's own tab. Definitions persist (so tabs survive /fd reload); actual UAA tabs are
 * (re)built only while the advancement system is ready — after CraftEngine items load and UAA is enabled.
 *
 * The FarmersDelight plugin drives this: onSystemReady() after its own tab loads, onSystemDown()
 * when the advancement system is disabled. Addons register through FarmersDelightAdvancements.
 */
public final class AddonAdvancementRegistry {

    private final Plugin plugin;
    private final Map<String, List<AdvancementDef>> definitions = new ConcurrentHashMap<>();
    private final Map<String, AddonAdvancementTab> tabs = new ConcurrentHashMap<>();
    private volatile boolean ready = false;

    public AddonAdvancementRegistry(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Stores (or replaces) a tab's definitions and builds it immediately when the system is already up. */
    public boolean register(String tabName, List<AdvancementDef> defs) {
        if (tabName == null || defs == null || defs.isEmpty()) {
            return false;
        }
        definitions.put(tabName, List.copyOf(defs));
        if (ready) {
            return buildOne(tabName);
        }
        return true;
    }

    /** Removes a tab's definitions and disposes its UAA tab. */
    public void unregister(String tabName) {
        if (tabName == null) {
            return;
        }
        definitions.remove(tabName);
        AddonAdvancementTab tab = tabs.remove(tabName);
        if (tab != null) {
            tab.dispose();
        }
    }

    /** The built tab for grant/check operations, or null when not currently loaded. */
    public AddonAdvancementTab tab(String tabName) {
        return tabName == null ? null : tabs.get(tabName);
    }

    public boolean isReady() {
        return ready;
    }

    /** Total advancements across every currently built addon tab, for the consolidated startup summary.
     *  Counts what is live rather than what is defined, so a tab whose build failed contributes zero and
     *  a tab that appears or disappears moves the number the summary dedupes on. */
    public int getLoadedAdvancementCount() {
        int total = 0;
        for (AddonAdvancementTab tab : tabs.values()) {
            total += tab.getLoadedCount();
        }
        return total;
    }

    /** Called when FarmersDelight's advancement system becomes ready: (re)build every registered tab. */
    public void onSystemReady() {
        ready = true;
        for (String tabName : definitions.keySet()) {
            buildOne(tabName);
        }
        // A rebuild (e.g. /ce reload) recreates each UAA tab, which drops it from online clients. Re-show
        // every rebuilt tab to players already online so they don't lose it until they rejoin or re-trigger
        // an award.
        var online = Bukkit.getOnlinePlayers();
        if (!online.isEmpty()) {
            for (AddonAdvancementTab tab : tabs.values()) {
                for (Player player : online) {
                    tab.resyncPlayer(player);
                }
            }
        }
    }

    /** Called when the advancement system is disabled/unloaded: dispose every built tab (definitions kept). */
    public void onSystemDown() {
        ready = false;
        for (AddonAdvancementTab tab : tabs.values()) {
            tab.dispose();
        }
        tabs.clear();
    }

    public void forgetPlayer(UUID playerId) {
        if (playerId == null) {
            return;
        }
        for (AddonAdvancementTab tab : tabs.values()) {
            tab.forgetPlayer(playerId);
        }
    }

    private boolean buildOne(String tabName) {
        List<AdvancementDef> defs = definitions.get(tabName);
        if (defs == null) {
            return false;
        }
        // Build the replacement first; load() unregisters any existing UAA tab of this name before creating the
        // new one, so the old tab stays live until the new one is built.
        AddonAdvancementTab fresh = new AddonAdvancementTab(plugin, tabName, defs);
        if (!fresh.load()) {
            AddonAdvancementTab stale = tabs.remove(tabName);
            if (stale != null) {
                stale.dispose();
            }
            return false;
        }
        // Swap in the new wrapper without disposing the old one (dispose() unregisters by name and would tear
        // down the freshly-built tab).
        tabs.put(tabName, fresh);
        return true;
    }
}
