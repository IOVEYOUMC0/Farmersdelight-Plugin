package com.huidu.farmersdelight.util;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import net.momirealms.craftengine.core.item.ItemDefinition;
import net.momirealms.craftengine.core.plugin.CraftEngine;
import net.momirealms.craftengine.core.util.Key;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bridges the common (`c:`) tag membership declared by CraftEngine item settings into the family tag
 * registry. Matching already reads CE's own tag index, but icons, `CommonTagResolver.getTagsForItemId`
 * and the datapack/debug snapshots only look at the family registry; without this bridge a `c:` member
 * declared in an addon pack is invisible to those consumers.
 *
 * Runs at warmup and on tag reload, never on a hot path. A missing or not-yet-loaded CraftEngine is answered
 * with "nothing bridged", so the next reload republishes the members.
 */
public final class CraftEngineTagBridge {

    /** Source name used in the registry so an operator can tell CE-declared members from config ones. */
    public static final String SOURCE = "craftengine-tags";
    private static final String COMMON_PREFIX = "c:";

    private CraftEngineTagBridge() {
    }

    /**
     * Recollects CE-declared common-tag members and replaces the bridge source.
     *
     * @return the number of registered item members, 0 when CraftEngine is not available yet
     */
    public static int refresh(FarmersDelightPlugin plugin) {
        Map<String, List<String>> tags = new LinkedHashMap<>();
        try {
            CraftEngine craftEngine = CraftEngine.instance();
            if (craftEngine == null || craftEngine.itemManager() == null) {
                return 0;
            }
            for (Map.Entry<Key, ItemDefinition> entry : craftEngine.itemManager().loadedItems().entrySet()) {
                ItemDefinition definition = entry.getValue();
                if (definition == null || definition.settings() == null) {
                    continue;
                }
                for (Key tag : definition.settings().tags()) {
                    String id = tag.toString();
                    if (!id.startsWith(COMMON_PREFIX)) {
                        continue;
                    }
                    tags.computeIfAbsent(id, ignored -> new ArrayList<>()).add(entry.getKey().toString());
                }
            }
        } catch (Throwable failure) {
            // CraftEngine mid-load, a reloading registry, or an unexpected CE shape: keep the previous source.
            if (plugin != null) {
                plugin.getLogger().fine("CraftEngine common-tag bridge skipped: " + failure.getMessage());
            }
            return 0;
        }
        CommonTagResolver.registerSource(SOURCE, tags);
        int members = tags.values().stream().mapToInt(List::size).sum();
        if (plugin != null && plugin.isDebugEnabled("tags")) {
            plugin.getLogger().info("Bridged " + tags.size() + " CraftEngine common tags (" + members + " members)");
        }
        return members;
    }
}
