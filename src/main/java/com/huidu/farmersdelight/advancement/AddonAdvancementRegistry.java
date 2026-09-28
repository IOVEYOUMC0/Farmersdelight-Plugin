package com.huidu.farmersdelight.advancement;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import com.huidu.farmersdelight.api.FarmersDelightApi;

import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class AddonAdvancementRegistry {

    private final Plugin plugin;
    private record TreeDefinition(List<AdvancementDef> advancements) {}

    private final Map<String, TreeDefinition> definitions = new ConcurrentHashMap<>();
    private final Map<String, AddonAdvancementTab> tabs = new ConcurrentHashMap<>();
    private final Set<String> packTabs = ConcurrentHashMap.newKeySet();
    private final AtomicLong loadGeneration = new AtomicLong();
    private volatile boolean ready = false;

    public AddonAdvancementRegistry(Plugin plugin) {
        this.plugin = plugin;
    }

    public boolean register(String tabName, List<AdvancementDef> defs) {
        if (tabName == null || defs == null || defs.isEmpty()) {
            return false;
        }
        definitions.put(tabName, new TreeDefinition(List.copyOf(defs)));
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
        long generation = loadGeneration.incrementAndGet();
        ready = false;
        AddonAdvancementPackLoader.load(FarmersDelightPlugin.getInstance(), configs -> {
            if (generation != loadGeneration.get()) {
                return;
            }
            applyPackDefinitions(configs);
        });
    }

    private void applyPackDefinitions(List<AddonAdvancementPackLoader.Config> configs) {
        Set<String> loadedPackTabs = new HashSet<>();
        for (AddonAdvancementPackLoader.Config config : configs) {
            List<AdvancementDef> defs = AddonAdvancementPackLoader.parse(
                    FarmersDelightPlugin.getInstance(), config.namespace(), config.source(), config.yaml());
            if (!defs.isEmpty()) {
                String tab = config.namespace();
                register(tab, defs);
                loadedPackTabs.add(tab);
            }
        }
        // A removed CE package must not leave its old virtual tab and definitions behind after reload.
        for (String oldTab : packTabs) {
            if (!loadedPackTabs.contains(oldTab)) {
                definitions.remove(oldTab);
                AddonAdvancementTab stale = tabs.remove(oldTab);
                if (stale != null) {
                    stale.dispose();
                }
            }
        }
        packTabs.clear();
        packTabs.addAll(loadedPackTabs);
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
                runForPlayer(player, () -> tab.resyncPlayer(player));
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
                runForPlayer(player, () -> tab.forceResend(player));
            }
        }
    }

    public void awardForItem(Player player, String itemId) {
        if (!ready || player == null || itemId == null) return;
        for (Map.Entry<String, TreeDefinition> entry : definitions.entrySet()) {
            AddonAdvancementTab tab = tabs.get(entry.getKey());
            if (tab == null) continue;
            for (AdvancementDef def : entry.getValue().advancements()) {
                if (!def.criteria().isEmpty()) {
                    for (Map.Entry<String, List<String>> c : def.criteriaRequirements().entrySet()) {
                        if (c.getValue().contains(itemId)) tab.awardCriteria(player, def.id(), c.getKey());
                    }
                } else if (def.requiredIds().contains(itemId)) {
                    tab.award(player, def.id());
                }
            }
        }
    }

    private void runForPlayer(Player player, Runnable action) {
        if (FarmersDelightApi.get().isFolia()) {
            player.getScheduler().run(plugin, task -> action.run(), null);
        } else {
            action.run();
        }
    }

    public void onSystemDown() {
        loadGeneration.incrementAndGet();
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
        TreeDefinition definition = definitions.get(tabName);
        if (definition == null) {
            return false;
        }
        // Build the replacement first; load() unregisters any existing UAA tab of this name before creating it.
        AddonAdvancementTab fresh = new AddonAdvancementTab(plugin, tabName, definition.advancements());
        if (!fresh.load()) {
            AddonAdvancementTab stale = tabs.remove(tabName);
            if (stale != null) {
                stale.dispose();
            }
            return false;
        }
        // Swap in the new wrapper without disposing the replaced wrapper because dispose() unregisters by name.
        tabs.put(tabName, fresh);
        return true;
    }
}
