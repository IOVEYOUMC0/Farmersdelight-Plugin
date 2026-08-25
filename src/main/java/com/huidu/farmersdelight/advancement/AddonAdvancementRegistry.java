package com.huidu.farmersdelight.advancement;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AddonAdvancementRegistry {

    private final Plugin plugin;
    private final Map<String, List<AdvancementDef>> definitions = new ConcurrentHashMap<>();
    private final Map<String, AddonAdvancementTab> tabs = new ConcurrentHashMap<>();
    private volatile boolean ready = false;

    public AddonAdvancementRegistry(Plugin plugin) {
        this.plugin = plugin;
    }

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

    public AddonAdvancementTab tab(String tabName) {
        return tabName == null ? null : tabs.get(tabName);
    }

    public boolean isReady() {
        return ready;
    }

    public int getLoadedAdvancementCount() {
        int total = 0;
        for (AddonAdvancementTab tab : tabs.values()) {
            total += tab.getLoadedCount();
        }
        return total;
    }

    public void onSystemReady() {
        ready = true;
        for (String tabName : definitions.keySet()) {
            buildOne(tabName);
        }
        // A rebuild (e.g. /ce reload) recreates each UAA tab, which drops it from online clients. Re-show
        // every rebuilt tab to players already online so they don't lose it until they rejoin or re-trigger
        // an award.
        resyncOnline();
    }

    // Re-shows every registered addon tab to all players currently online. Safe to call repeatedly; each
    // showTab re-pushes the advancement tree (a fresh tab instance re-sends even to already-shown players).
    public void resyncOnline() {
        var online = Bukkit.getOnlinePlayers();
        if (online.isEmpty()) {
            return;
        }
        for (AddonAdvancementTab tab : tabs.values()) {
            for (Player player : online) {
                tab.resyncPlayer(player);
            }
        }
    }

    // Forces every tab to re-send its full tree to online players, bypassing UAA's already-shown guard. Used a
    // short time after a reload/rebuild when the immediate resync's packet may have been dropped by clients
    // still re-applying CraftEngine resources.
    public void forceResyncOnline() {
        var online = Bukkit.getOnlinePlayers();
        if (online.isEmpty()) {
            return;
        }
        for (AddonAdvancementTab tab : tabs.values()) {
            for (Player player : online) {
                tab.forceResend(player);
            }
        }
    }

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
