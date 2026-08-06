package com.huidu.farmersdelight.recipe;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import net.momirealms.craftengine.core.util.Key;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// Per-tag cache of CraftEngine's vanillaItemIdsByTag result so recipe matching does not re-stream the
// full vanilla tag membership on every lookup. One instance per recipe manager. Concurrent: read on
// Folia region/entity threads, cleared on reload.
final class VanillaTagItemIdCache {

    private final FarmersDelightPlugin plugin;
    private final Map<Key, Set<String>> cache = new ConcurrentHashMap<>();

    VanillaTagItemIdCache(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    Set<String> getIds(Key tagKey) {
        if (tagKey == null) {
            return Set.of();
        }
        return cache.computeIfAbsent(tagKey, key -> {
            var craftEngine = plugin.getCraftEngine();
            if (craftEngine == null || craftEngine.itemManager() == null) {
                return Set.of();
            }
            Set<String> itemIds = new HashSet<>();
            for (var itemId : craftEngine.itemManager().vanillaItemIdsByTag(key)) {
                itemIds.add(itemId.toString());
            }
            return itemIds.isEmpty() ? Set.of() : Collections.unmodifiableSet(itemIds);
        });
    }

    void clear() {
        cache.clear();
    }
}
